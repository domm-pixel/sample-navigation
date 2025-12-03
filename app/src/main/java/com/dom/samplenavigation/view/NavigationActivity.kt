package com.dom.samplenavigation.view

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.location.Location
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dom.samplenavigation.R
import com.dom.samplenavigation.base.BaseActivity
import com.dom.samplenavigation.databinding.ActivityNavigationBinding
import com.dom.samplenavigation.navigation.manager.NavigationManager
import com.dom.samplenavigation.navigation.model.Instruction
import com.dom.samplenavigation.navigation.model.NavigationRoute
import com.dom.samplenavigation.navigation.model.NavigationState
import com.dom.samplenavigation.navigation.voice.VoiceGuideManager
import com.dom.samplenavigation.view.viewmodel.NavigationViewModel
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.MapFragment
import com.naver.maps.map.NaverMap
import com.naver.maps.map.OnMapReadyCallback
import com.naver.maps.map.overlay.PathOverlay
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

/**
 * OMS 스타일 네비게이션 액티비티
 * - Location Snapping: GPS 위치를 경로에 스냅
 * - Camera Follow: 속도/방향 기반 부드러운 카메라 추적
 * - Zoom Control: 속도 기반 자동 줌 조정
 * - Rerouting: 경로 이탈 감지 및 자동 재탐색
 * - Distance/Time: 경로 기반 정확한 거리/시간 계산
 */
@AndroidEntryPoint
class NavigationActivity : BaseActivity<ActivityNavigationBinding>(
    R.layout.activity_navigation
), OnMapReadyCallback {

    private val navigationViewModel: NavigationViewModel by viewModels()
    private lateinit var navigationManager: NavigationManager
    private lateinit var voiceGuideManager: VoiceGuideManager

    // Map
    private var naverMap: NaverMap? = null
    private var pathOverlays: MutableList<PathOverlay> = mutableListOf()
    private var directionArrowOverlay: PathOverlay? = null  // 분기점 화살표 오버레이
    private var endMarker: Marker? = null
    private var currentLocationMarker: Marker? = null
    private var isMapReady = false

    // Navigation State
    private var currentRoute: NavigationRoute? = null
    private var currentPathIndex: Int = 0
    private var snappedLocation: LatLng? = null
    private var isNavigating = false
    private var isRerouting = false
    private var lastRerouteTime: Long = 0

    // Camera State (OMS 스타일)
    private var currentBearing: Float = 0f
    private var currentSpeed: Float = 0f
    private var currentZoom: Double = 17.0
    private var currentTilt: Double = 0.0

    // Location
    private lateinit var fusedClient: FusedLocationProviderClient
    private var fusedCallback: LocationCallback? = null
    private var lastLocation: Location? = null
    private var lastKnownLocation: LatLng? = null

    // Voice Guide
    private var isVoiceGuideEnabled: Boolean = true
    private var suppressVoiceSwitchCallback: Boolean = false

    // Picture-in-Picture
    private var isInPictureInPictureModeCompat: Boolean = false
    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_STOP_NAVIGATION -> {
                    Timber.d("PIP action: stop navigation")
                    stopNavigationAndFinish()
                }
                ACTION_PIP_TOGGLE_VOICE -> {
                    Timber.d("PIP action: toggle voice guide")
                    toggleVoiceGuideFromPip()
                }
            }
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        
        // OMS 스타일 상수
        private const val SNAP_TOLERANCE_M = 50f  // 경로 스냅 허용 거리 (미터)
        private const val OFF_ROUTE_THRESHOLD_M = 50f  // 경로 이탈 임계값 (미터)
        private const val REROUTE_COOLDOWN_MS = 5000L  // 재탐색 쿨다운 (밀리초)
        private const val ARRIVAL_THRESHOLD_M = 30f  // 도착 판정 거리 (미터)
        
        // Camera 상수
        private const val ZOOM_LOW_SPEED = 18.0  // 저속 줌
        private const val ZOOM_DEFAULT = 17.0  // 기본 줌
        private const val ZOOM_HIGH_SPEED = 16.0  // 고속 줌
        private const val SPEED_THRESHOLD_SLOW = 4.2f  // ≈15km/h
        private const val SPEED_THRESHOLD_FAST = 13.9f  // ≈50km/h
        private const val HIGH_SPEED_TILT = 35.0
        private const val DEFAULT_TILT = 0.0
        private const val BEARING_SMOOTH_ALPHA = 0.3f  // 베어링 스무딩 계수
        
        // PIP
        private const val ACTION_PIP_STOP_NAVIGATION = "com.dom.samplenavigation.action.PIP_STOP"
        private const val ACTION_PIP_TOGGLE_VOICE = "com.dom.samplenavigation.action.PIP_TOGGLE_VOICE"
        private const val REQUEST_CODE_PIP_STOP = 2001
        private const val REQUEST_CODE_PIP_TOGGLE_VOICE = 2002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fused client 초기화
        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        // 네비게이션 매니저 초기화
        navigationManager = NavigationManager(this, lifecycleScope)
        voiceGuideManager = VoiceGuideManager(this)
        registerPipActionReceiver()
        updateVoiceGuideState(isVoiceGuideEnabled, fromUser = false)

        // 전달받은 데이터 설정
        val startLat = intent.getDoubleExtra("start_lat", 0.0)
        val startLng = intent.getDoubleExtra("start_lng", 0.0)
        val destination = intent.getStringExtra("destination")

        if (startLat != 0.0 && startLng != 0.0 && !destination.isNullOrEmpty()) {
            val startLocation = LatLng(startLat, startLng)
            navigationViewModel.setRoute(startLocation, destination)
            Timber.d("Navigation data set: $startLocation -> $destination")
        } else {
            Timber.w("Navigation data not available")
        }

        setupMap()
        setupObservers()
        setupClickListeners()

        // 위치 권한 확인
        checkLocationPermission()
    }

    private fun setupMap() {
        val mapFragment =
            supportFragmentManager.findFragmentById(R.id.mapView_navigation) as MapFragment?
                ?: MapFragment.newInstance().also {
                    supportFragmentManager.beginTransaction().replace(R.id.mapView_navigation, it)
                        .commit()
                }
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(naverMap: NaverMap) {
        this.naverMap = naverMap
        isMapReady = true

        // 지도 설정
        naverMap.uiSettings.isCompassEnabled = false
        naverMap.uiSettings.isLocationButtonEnabled = false
        naverMap.uiSettings.isZoomControlEnabled = false
        naverMap.buildingHeight = 0.2f

        // 운전자 시야 확보를 위해 지도 중심을 화면 하단 쪽으로 오프셋
        val density = resources.displayMetrics.density
        val topPaddingPx = (600 * density).toInt()
        naverMap.setContentPadding(0, topPaddingPx, 0, 0)

        // 현재 위치 마커 생성
        createCurrentLocationMarker()

        // 네비게이션 자동 시작
        isNavigating = true
        currentPathIndex = 0
        navigationViewModel.startNavigation()
    }

    @SuppressLint("MissingPermission")
    private fun setupObservers() {
        // 네비게이션 상태 관찰
        navigationManager.navigationState.observe(this) { state ->
            if (state == null) {
                Timber.w("Navigation state is null")
                return@observe
            }

            updateNavigationUI(state)

            // 네비게이션 모드 자동 전환
            if (state.isNavigating) {
                startNavigationMode()
            } else {
                stopNavigationMode()
            }

            // OMS 스타일: Location Snapping 및 Camera Follow
            if (state.isNavigating && isNavigating && state.currentLocation != null && state.currentRoute != null) {
                val gpsLocation = state.currentLocation
                val route = state.currentRoute

                if (isMapReady) {
                    // 1. Location Snapping: GPS 위치를 경로에 스냅
                    val snapResult = snapLocationToRoute(gpsLocation, route.path, currentPathIndex)
                    snappedLocation = snapResult.snappedLocation
                    currentPathIndex = snapResult.pathIndex

                    // 2. 경로 이탈 감지 및 재탐색
                    val distanceToPath = calculateDistance(gpsLocation, snappedLocation!!)
                    if (distanceToPath > OFF_ROUTE_THRESHOLD_M && !isRerouting) {
                        checkAndReroute(gpsLocation, route)
                    }

                    // 3. Camera Follow: 스냅된 위치로 카메라 추적
                    if (snappedLocation != null) {
                        updateCameraFollow(snappedLocation!!, currentBearing, currentSpeed)
                        updateCurrentLocationMarker(snappedLocation!!)
                    }

                    // 4. 도착 확인
                    val distanceToDestination = calculateDistance(
                        snappedLocation!!,
                        route.summary.endLocation
                    )
                    if (distanceToDestination <= ARRIVAL_THRESHOLD_M) {
                        Timber.d("Arrived at destination! (${distanceToDestination}m)")
                        navigationManager.stopNavigation()
                        Toast.makeText(this, "목적지에 도착했습니다!", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            updatePictureInPictureParams()
        }

        // 현재 안내 메시지 관찰
        navigationManager.currentInstruction.observe(this) { instruction ->
            instruction?.let {
                updateInstructionUI(it)
                // 분기점 화살표 업데이트
                currentRoute?.let { route ->
                    updateDirectionArrow(it, route)
                }
                updatePictureInPictureParams()
            }
        }

        // 음성 안내 트리거 관찰
        navigationManager.shouldPlayVoice.observe(this) { shouldPlay ->
            if (shouldPlay == true) {
                navigationManager.currentInstruction.value?.let { instruction ->
                    if (voiceGuideManager.isReady()) {
                        voiceGuideManager.speakInstruction(instruction)
                        Timber.d("Voice instruction spoken: ${instruction.message}")
                    }
                }
            }
        }

        // 안내 시작 알림 관찰
        navigationManager.shouldPlayNavigationStart.observe(this) { shouldPlay ->
            if (shouldPlay == true) {
                navigationManager.currentInstruction.value?.let { instruction ->
                    if (voiceGuideManager.isReady()) {
                        voiceGuideManager.speakNavigationStart(instruction)
                        Timber.d("Navigation start announcement: 경로 안내를 시작합니다 + ${instruction.message}")
                    }
                }
            }
        }

        // 경로 데이터 관찰
        navigationViewModel.navigationRoute.observe(this) { route ->
            route?.let { newRoute ->
                currentRoute = newRoute
                displayRoute(newRoute)

                // 재탐색 후 초기화
                if (isRerouting) {
                    isRerouting = false
                    Toast.makeText(this, "경로를 재검색했습니다", Toast.LENGTH_SHORT).show()
                    
                    // 재탐색 후 스냅 위치 초기화
                    val referenceLocation = lastKnownLocation ?: newRoute.summary.startLocation
                    val snapResult = snapLocationToRoute(referenceLocation, newRoute.path, 0)
                    snappedLocation = snapResult.snappedLocation
                    currentPathIndex = snapResult.pathIndex
                    updateCurrentLocationMarker(snappedLocation!!)
                } else {
                    // 최초 시작 시 출발지로 초기화
                    snappedLocation = newRoute.summary.startLocation
                    currentPathIndex = 0
                    updateCurrentLocationMarker(snappedLocation!!)
                }

                navigationManager.startNavigation(newRoute)

                // 네비게이션 시작 시 즉시 3D 뷰로 전환
                if (isMapReady && snappedLocation != null) {
                    updateCameraFollow(snappedLocation!!, currentBearing, 0f)
                }
            }
        }

        // 로딩 상태 관찰
        navigationViewModel.isLoading.observe(this) { isLoading ->
            binding.progressLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    private fun setupClickListeners() {
        binding.btnStopNavigation.setOnClickListener {
            showStopNavigationDialog()
        }

        binding.switchVoiceGuide.isChecked = isVoiceGuideEnabled
        binding.switchVoiceGuide.setOnCheckedChangeListener { _, isChecked ->
            if (suppressVoiceSwitchCallback) return@setOnCheckedChangeListener
            updateVoiceGuideState(isChecked, fromUser = true)
            Timber.d("Voice guide ${if (isChecked) "enabled" else "disabled"}")
        }

        binding.btnEnterPip.setOnClickListener {
            enterPictureInPictureModeIfSupported()
        }
    }

    // ==================== OMS 스타일: Location Snapping ====================

    /**
     * GPS 위치를 경로에 스냅 (OMS 스타일)
     * 경로의 선분들 중 가장 가까운 선분을 찾아 그 위의 점으로 스냅
     */
    private data class SnapResult(
        val snappedLocation: LatLng,
        val pathIndex: Int
    )

    private fun snapLocationToRoute(
        gpsLocation: LatLng,
        path: List<LatLng>,
        currentIndex: Int
    ): SnapResult {
        if (path.isEmpty()) {
            return SnapResult(gpsLocation, 0)
        }

        var minDistance = Float.MAX_VALUE
        var bestSnappedLocation = gpsLocation
        var bestIndex = currentIndex.coerceIn(0, path.size - 1)

        // 현재 인덱스 주변 검색 (성능 최적화)
        val searchRange = 100
        val startIndex = maxOf(0, currentIndex - searchRange)
        val endIndex = minOf(path.size - 1, currentIndex + searchRange)

        // 선분들에 대해 가장 가까운 점 찾기
        for (i in startIndex until endIndex) {
            if (i >= path.size - 1) break
            val p1 = path[i]
            val p2 = path[i + 1]

            val snapped = snapToLineSegment(gpsLocation, p1, p2)
            val distance = calculateDistance(gpsLocation, snapped)

            if (distance < minDistance) {
                minDistance = distance
                bestSnappedLocation = snapped
                bestIndex = if (calculateDistance(snapped, p1) < calculateDistance(snapped, p2)) i else i + 1
            }
        }

        // 스냅 허용 범위 내에 있으면 스냅, 아니면 GPS 위치 사용
        return if (minDistance <= SNAP_TOLERANCE_M) {
            SnapResult(bestSnappedLocation, bestIndex)
        } else {
            SnapResult(gpsLocation, bestIndex)
        }
    }

    /**
     * 점을 선분에 스냅
     */
    private fun snapToLineSegment(point: LatLng, lineStart: LatLng, lineEnd: LatLng): LatLng {
        val A = point.latitude - lineStart.latitude
        val B = point.longitude - lineStart.longitude
        val C = lineEnd.latitude - lineStart.latitude
        val D = lineEnd.longitude - lineStart.longitude

        val dot = A * C + B * D
        val lenSq = C * C + D * D

        if (lenSq == 0.0) {
            return lineStart
        }

        val param = (dot / lenSq).coerceIn(0.0, 1.0)
        val lat = lineStart.latitude + param * C
        val lng = lineStart.longitude + param * D

        return LatLng(lat, lng)
    }

    // ==================== OMS 스타일: Camera Follow ====================

    /**
     * 카메라를 스냅된 위치로 추적 (OMS 스타일)
     * 속도와 방향에 따라 부드럽게 카메라 업데이트
     */
    private fun updateCameraFollow(location: LatLng, bearing: Float, speed: Float) {
        naverMap?.let { map ->
            // 속도 기반 줌 계산
            val zoom = calculateZoomFromSpeed(speed)
            
            // 속도 기반 틸트 계산
            val tilt = calculateTiltFromSpeed(speed)

            // 베어링 스무딩 (부드러운 회전)
            val smoothedBearing = smoothBearing(currentBearing, bearing)

            // 카메라 위치 업데이트
            val cameraPosition = CameraPosition(
                location,
                zoom,
                tilt,
                smoothedBearing.toDouble()
            )

            val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
                .animate(CameraAnimation.Easing, 200)
            map.moveCamera(cameraUpdate)

            // 상태 저장
            currentZoom = zoom
            currentTilt = tilt
            currentBearing = smoothedBearing

            Timber.d("Camera follow: location=$location, bearing=${smoothedBearing}°, zoom=$zoom, speed=${speed * 3.6f}km/h")
        }
    }

    /**
     * 속도 기반 줌 계산 (OMS 스타일)
     */
    private fun calculateZoomFromSpeed(speedMps: Float): Double {
        val speedKmh = speedMps * 3.6f
        return when {
            speedKmh < 15 -> ZOOM_LOW_SPEED  // 저속: 줌인
            speedKmh > 50 -> ZOOM_HIGH_SPEED  // 고속: 줌아웃
            else -> ZOOM_DEFAULT  // 기본
        }
    }

    /**
     * 속도 기반 틸트 계산 (OMS 스타일)
     */
    private fun calculateTiltFromSpeed(speedMps: Float): Double {
        val speedKmh = speedMps * 3.6f
        return if (speedKmh > 50) HIGH_SPEED_TILT else DEFAULT_TILT
    }

    /**
     * 베어링 스무딩 (부드러운 회전)
     */
    private fun smoothBearing(current: Float, target: Float): Float {
        if (current == 0f) return target

        val diff = shortestAngleDiff(current, target)
        val smoothed = current + diff * BEARING_SMOOTH_ALPHA
        return normalizeBearing(smoothed)
    }

    // ==================== OMS 스타일: Rerouting ====================

    /**
     * 경로 이탈 감지 및 재탐색 (OMS 스타일)
     */
    private fun checkAndReroute(gpsLocation: LatLng, route: NavigationRoute) {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastReroute = currentTime - lastRerouteTime

        // 쿨다운 체크
        if (timeSinceLastReroute < REROUTE_COOLDOWN_MS) {
            return
        }

        // 재탐색 실행
        isRerouting = true
        lastRerouteTime = currentTime
        Timber.d("Rerouting triggered from: $gpsLocation")
        
        if (voiceGuideManager.isReady()) {
            voiceGuideManager.speakPlain("경로를 재탐색합니다")
        }
        
        navigationViewModel.reroute(gpsLocation)
        binding.tvCurrentInstruction.text = "경로를 재검색 중입니다..."
    }

    // ==================== OMS 스타일: Distance/Time Calculation ====================

    /**
     * 경로 기반 거리 계산 (OMS 스타일)
     * 현재 위치에서 목적지까지 경로상의 실제 거리
     */
    private fun calculateRouteDistance(
        fromIndex: Int,
        toIndex: Int,
        path: List<LatLng>
    ): Float {
        if (fromIndex >= toIndex || fromIndex >= path.size - 1) return 0f

        var distance = 0f
        for (i in fromIndex until minOf(toIndex, path.size - 1)) {
            distance += calculateDistance(path[i], path[i + 1])
        }
        return distance
    }

    /**
     * 경로 기반 시간 계산 (OMS 스타일)
     * 현재 속도를 기반으로 예상 시간 계산
     */
    private fun calculateRouteTime(distanceMeters: Float, speedMps: Float): Int {
        if (speedMps <= 0f) return 0
        return (distanceMeters / speedMps).toInt()
    }

    // ==================== Helper Methods ====================

    private fun updateNavigationUI(state: NavigationState) {
        if (!isInPictureInPictureModeCompat) {
            binding.btnStopNavigation.visibility = if (state.isNavigating) View.VISIBLE else View.GONE
        }

        // 진행률 업데이트
        binding.progressNavigation.progress = (state.progress * 100).toInt()

        // 남은 거리 및 시간 업데이트
        val distanceKm = state.remainingDistance / 1000f
        val remainingTimeMinutes = state.currentRoute?.let { route ->
            val totalDurationMs = route.summary.totalDuration
            val progress = state.progress
            val remainingMs = (totalDurationMs * (1.0 - progress)).toInt()
            remainingMs / 1000 / 60
        } ?: if (distanceKm > 0) {
            (distanceKm / 40f * 60f).toInt()
        } else {
            0
        }

        val timeString = if (remainingTimeMinutes >= 60) {
            val hours = remainingTimeMinutes / 60
            val mins = remainingTimeMinutes % 60
            if (mins > 0) "${hours}시간 ${mins}분" else "${hours}시간"
        } else {
            "${remainingTimeMinutes}분"
        }

        binding.tvRemainingDistance.text = "남은 거리: ${String.format("%.1f", distanceKm)}km"
        binding.tvRemainingTime.text = "남은 시간: $timeString"
    }

    private fun updateInstructionUI(instruction: Instruction) {
        val route = currentRoute ?: return
        val currentPos = snappedLocation ?: lastKnownLocation ?: return

        // 경로 기반 거리 계산
        val targetIndex = instruction.pointIndex.coerceIn(0, route.path.size - 1)
        val distance = if (currentPathIndex < targetIndex) {
            calculateRouteDistance(currentPathIndex, targetIndex, route.path)
        } else {
            calculateDistance(currentPos, instruction.location)
        }

        val cleanMessage = instruction.message
            .replace(Regex("\\d+\\s*킬로미터\\s*(후|전방|앞)\\s*"), "")
            .replace(Regex("\\d+\\s*미터\\s*(후|전방|앞)\\s*"), "")
            .replace(Regex("\\d+\\.?\\d*\\s*km\\s*(후|전방|앞)\\s*"), "")
            .replace(Regex("\\d+\\s*m\\s*(후|전방|앞)\\s*"), "")
            .trim()

        val messageWithDistance = if (distance > 0) {
            if (distance >= 1000) {
                val km = distance / 1000.0
                "[${String.format("%.1f", km)}km] $cleanMessage"
            } else {
                "[${distance.toInt()}m] $cleanMessage"
            }
        } else {
            cleanMessage
        }

        binding.tvCurrentInstruction.text = messageWithDistance
    }

    private fun displayRoute(route: NavigationRoute) {
        val nMap = naverMap ?: return

        // 기존 오버레이 제거
        pathOverlays.forEach { it.map = null }
        pathOverlays.clear()
        directionArrowOverlay?.map = null
        directionArrowOverlay = null
        endMarker?.map = null

        // 경로 표시
        pathOverlays.add(PathOverlay().apply {
            coords = route.path
            color = resources.getColor(R.color.skyBlue, null)
            outlineColor = Color.WHITE
            width = 40
            map = nMap
        })

        // 도착지 마커
        endMarker = Marker().apply {
            position = route.summary.endLocation
            captionText = "도착지"
            map = nMap
        }

        Timber.d("Route displayed with ${route.path.size} points")
    }

    private fun createCurrentLocationMarker() {
        val map = naverMap ?: return

        currentLocationMarker = Marker().apply {
            icon = OverlayImage.fromResource(R.drawable.a)
            position = LatLng(37.5665, 126.9780)
            this.map = map
            zIndex = 10000
            width = 150
            height = 150
        }
    }

    private fun updateCurrentLocationMarker(location: LatLng) {
        currentLocationMarker?.let { marker ->
            marker.position = location
            marker.map = naverMap
            marker.zIndex = 10000
        } ?: run {
            createCurrentLocationMarker()
            updateCurrentLocationMarker(location)
        }
    }

    private fun startNavigationMode() {
        naverMap?.let { map ->
            map.locationTrackingMode = LocationTrackingMode.None
            Timber.d("Navigation mode started")
        }
    }

    private fun stopNavigationMode() {
        naverMap?.let { map ->
            map.locationTrackingMode = LocationTrackingMode.Follow
            Timber.d("Navigation mode stopped")
        }
    }

    // ==================== Location Updates ====================

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(1f)
            .setWaitForAccurateLocation(true)
            .build()

        if (fusedCallback == null) {
            fusedCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    val latLng = LatLng(loc.latitude, loc.longitude)

                    lastKnownLocation = latLng
                    lastLocation = loc
                    currentSpeed = loc.speed.coerceAtLeast(0f)

                    // 베어링 업데이트
                    val stableBearing = navigationManager.calculateStableBearing(loc)
                    if (stableBearing > 0f) {
                        currentBearing = stableBearing
                    } else if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.0f) {
                        currentBearing = loc.bearing
                    }

                    // NavigationManager에 위치 업데이트
                    navigationManager.updateCurrentLocation(latLng)

                    Timber.d("Location: $latLng, bearing=${currentBearing}°, speed=${currentSpeed * 3.6f}km/h")
                }
            }
        }

        fusedClient.requestLocationUpdates(request, fusedCallback as LocationCallback, mainLooper)

        // 마지막 알려진 위치 즉시 반영
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            loc?.let {
                val latLng = LatLng(it.latitude, it.longitude)
                lastKnownLocation = latLng
                lastLocation = it
                navigationManager.updateCurrentLocation(latLng)
            }
        }
    }

    // ==================== Direction Arrow (분기점 화살표) ====================

    /**
     * 분기점 기준으로 앞뒤 경로를 이어서 화살표 그리기
     * instruction의 pointIndex를 기준으로 -1, 0(분기점), +1 → 최대 3개의 점 사용
     */
    private fun createDirectionArrow(
        instruction: Instruction,
        route: NavigationRoute
    ): Pair<List<LatLng>, LatLng> {
        val path = route.path
        val pointIndex = instruction.pointIndex

        if (path.isEmpty() || pointIndex !in path.indices) {
            return Pair(emptyList(), path.firstOrNull() ?: LatLng(0.0, 0.0))
        }

        val center = path[pointIndex]

        // 분기점 기준으로 -1, 0(분기점), +1 → 최대 3개의 점 사용
        val startIndex = maxOf(0, pointIndex - 1)
        val endIndexExclusive = minOf(path.size, pointIndex + 2) // +2 (exclusive) → pointIndex+1까지 포함

        val arrowPath = path.subList(startIndex, endIndexExclusive).toList()

        // 최소 2개 이상일 때만 사용
        return if (arrowPath.size >= 2) {
            Pair(arrowPath, center)
        } else {
            Pair(emptyList(), center)
        }
    }

    /**
     * 분기점 화살표 업데이트
     */
    private fun updateDirectionArrow(instruction: Instruction, route: NavigationRoute) {
        val nMap = naverMap ?: return

        // 기존 화살표 제거
        directionArrowOverlay?.map = null
        directionArrowOverlay = null

        // 화살표 경로 생성
        val (arrowPath, center) = createDirectionArrow(instruction, route)

        // 화살표가 유효한 경우에만 표시
        if (arrowPath.size >= 2) {
            directionArrowOverlay = PathOverlay().apply {
                coords = arrowPath
                color = Color.WHITE
                width = 25  // 기존 경로보다 두껍게
                map = nMap
                zIndex = 1000  // 기존 경로 위에 표시
            }
            Timber.d("Direction arrow updated at pointIndex=${instruction.pointIndex}, path size=${arrowPath.size}")
        } else {
            Timber.d("Direction arrow not created: insufficient path points")
        }
    }

    // ==================== Utility Methods ====================

    private fun calculateDistance(latLng1: LatLng, latLng2: LatLng): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            latLng1.latitude, latLng1.longitude,
            latLng2.latitude, latLng2.longitude,
            results
        )
        return results[0]
    }

    private fun normalizeBearing(angle: Float): Float {
        var normalized = angle % 360f
        if (normalized < 0f) normalized += 360f
        return normalized
    }

    private fun shortestAngleDiff(from: Float, to: Float): Float {
        var diff = (to - from) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return diff
    }

    // ==================== Voice Guide ====================

    private fun updateVoiceGuideState(enabled: Boolean, fromUser: Boolean) {
        isVoiceGuideEnabled = enabled
        voiceGuideManager.setEnabled(enabled)
        if (binding.switchVoiceGuide.isChecked != enabled) {
            suppressVoiceSwitchCallback = true
            binding.switchVoiceGuide.isChecked = enabled
            suppressVoiceSwitchCallback = false
        }
        updatePictureInPictureParams()
    }

    private fun toggleVoiceGuideFromPip() {
        runOnUiThread {
            val newState = !isVoiceGuideEnabled
            updateVoiceGuideState(newState, fromUser = false)
            val toastMessage = if (newState) {
                getString(R.string.pip_action_voice_on_description)
            } else {
                getString(R.string.pip_action_voice_off_description)
            }
            Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== Picture-in-Picture ====================

    private fun supportsPictureInPicture(): Boolean {
        return packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun buildPictureInPictureParams(): PictureInPictureParams? {
        if (!supportsPictureInPicture()) return null

        val stopIntent = PendingIntent.getBroadcast(
            this,
            REQUEST_CODE_PIP_STOP,
            Intent(ACTION_PIP_STOP_NAVIGATION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = RemoteAction(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause),
            getString(R.string.pip_action_stop_title),
            getString(R.string.pip_action_stop_description),
            stopIntent
        )

        val voiceIntent = PendingIntent.getBroadcast(
            this,
            REQUEST_CODE_PIP_TOGGLE_VOICE,
            Intent(ACTION_PIP_TOGGLE_VOICE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val voiceIconRes = if (isVoiceGuideEnabled) {
            android.R.drawable.ic_lock_silent_mode_off
        } else {
            android.R.drawable.ic_lock_silent_mode
        }
        val voiceTitle = if (isVoiceGuideEnabled) {
            getString(R.string.pip_action_voice_off_title)
        } else {
            getString(R.string.pip_action_voice_on_title)
        }
        val voiceDescription = if (isVoiceGuideEnabled) {
            getString(R.string.pip_action_voice_off_description)
        } else {
            getString(R.string.pip_action_voice_on_description)
        }
        val voiceAction = RemoteAction(
            Icon.createWithResource(this, voiceIconRes),
            voiceTitle,
            voiceDescription,
            voiceIntent
        )

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(9, 16))
            .setActions(listOf(stopAction, voiceAction))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val instructionText = binding.tvCurrentInstruction.text?.toString().orEmpty()
            if (instructionText.isNotBlank()) {
                builder.setTitle(instructionText)
            }
            val subtitle = buildPipSubtitle()
            if (subtitle.isNotBlank()) {
                builder.setSubtitle(subtitle)
            }
        }

        return builder.build()
    }

    private fun updatePictureInPictureParams() {
        if (!supportsPictureInPicture()) return
        val params = buildPictureInPictureParams() ?: return
        try {
            setPictureInPictureParams(params)
        } catch (e: IllegalStateException) {
            Timber.w("Unable to update PIP params: ${e.message}")
        }
    }

    private fun enterPictureInPictureModeIfSupported() {
        if (!supportsPictureInPicture()) {
            Toast.makeText(this, getString(R.string.pip_not_supported), Toast.LENGTH_SHORT).show()
            return
        }
        if (navigationManager.navigationState.value?.isNavigating != true) {
            Toast.makeText(this, getString(R.string.pip_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        val params = buildPictureInPictureParams()
        try {
            if (params != null) {
                enterPictureInPictureMode(params)
            } else {
                enterPictureInPictureMode()
            }
        } catch (e: IllegalStateException) {
            Timber.e("Failed to enter PIP mode: ${e.message}")
        }
    }

    private fun buildPipSubtitle(): String {
        val parts = mutableListOf<String>()
        binding.tvRemainingDistance.text?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { parts.add(it) }
        val voiceState = if (isVoiceGuideEnabled) {
            getString(R.string.pip_voice_state_on)
        } else {
            getString(R.string.pip_voice_state_off)
        }
        parts.add(voiceState)
        return parts.joinToString(" · ")
    }

    private fun registerPipActionReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PIP_STOP_NAVIGATION)
            addAction(ACTION_PIP_TOGGLE_VOICE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipActionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(pipActionReceiver, filter)
        }
    }

    private fun unregisterPipActionReceiver() {
        try {
            unregisterReceiver(pipActionReceiver)
        } catch (e: IllegalArgumentException) {
            Timber.w("PIP receiver already unregistered: ${e.message}")
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPictureInPictureModeCompat = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            binding.btnEnterPip.visibility = View.GONE
            binding.bottomPanel.visibility = View.GONE
            binding.progressNavigation.visibility = View.GONE
            binding.btnStopNavigation.visibility = View.GONE
            binding.tvCurrentSpeed.visibility = View.GONE
        } else {
            binding.btnEnterPip.visibility = View.VISIBLE
            binding.bottomPanel.visibility = View.VISIBLE
            binding.progressNavigation.visibility = View.VISIBLE
            binding.btnStopNavigation.visibility =
                if (navigationManager.navigationState.value?.isNavigating == true) View.VISIBLE else View.GONE
            binding.tvCurrentSpeed.visibility = View.VISIBLE
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPictureInPictureModeCompat &&
            supportsPictureInPicture() &&
            navigationManager.navigationState.value?.isNavigating == true
        ) {
            enterPictureInPictureModeIfSupported()
        }
    }

    // ==================== Permissions ====================

    private fun checkLocationPermission() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            requestLocationPermission()
        } else {
            startLocationUpdates()
        }
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            } else {
                binding.tvCurrentInstruction.text = "위치 권한이 필요합니다. 설정에서 권한을 허용해주세요."
            }
        }
    }

    // ==================== Navigation Control ====================

    private fun showStopNavigationDialog() {
        AlertDialog.Builder(this)
            .setTitle("안내 종료")
            .setMessage("안내를 종료하시겠어요?")
            .setPositiveButton("확인") { _, _ ->
                stopNavigationAndFinish()
            }
            .setNegativeButton("취소") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    private fun stopNavigationAndFinish() {
        isNavigating = false
        currentPathIndex = 0
        
        // 화살표 제거
        directionArrowOverlay?.map = null
        directionArrowOverlay = null
        
        navigationManager.stopNavigation()
        navigationViewModel.stopNavigation()
        finish()
    }

    override fun onDestroy() {
        unregisterPipActionReceiver()
        super.onDestroy()
        stopNavigationMode()
        navigationManager.stopNavigation()
        voiceGuideManager.release()

        try {
            fusedCallback?.let { cb ->
                fusedClient.removeLocationUpdates(cb)
            }
        } catch (e: Exception) {
            Timber.e("Error stopping location updates: ${e.message}")
        }
    }
}
