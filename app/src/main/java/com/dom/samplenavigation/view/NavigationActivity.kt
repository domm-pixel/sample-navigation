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
import android.graphics.PointF
import android.graphics.drawable.Icon
import android.location.Location
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import android.view.View
import android.view.WindowManager
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
import com.dom.samplenavigation.navigation.model.RouteOptionType
import com.dom.samplenavigation.navigation.voice.VoiceGuideManager
import com.dom.samplenavigation.view.viewmodel.NavigationViewModel
import com.dom.samplenavigation.api.telemetry.model.VehicleLocationPayload
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.MapFragment
import com.naver.maps.map.NaverMap
import com.naver.maps.map.OnMapReadyCallback
import com.naver.maps.map.overlay.ArrowheadPathOverlay
import com.naver.maps.map.overlay.PathOverlay
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.Overlay
import com.naver.maps.map.overlay.OverlayImage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
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
    private var pathOverlays: MutableList<Overlay> = mutableListOf()
    private var directionArrowOverlay: ArrowheadPathOverlay? = null  // 분기점 화살표 오버레이 (별도 관리)
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
    private var selectedRouteOption: RouteOptionType? = null  // 선택된 경로 옵션
    private var isShowingFullRoute = false  // 전체 경로 표시 여부
    private var isGestureMode = false  // 유저 제스처 모드 (지도 조작 중)
    private var lastGestureTime: Long = 0  // 마지막 유저 제스처 시간
    private var gestureTimeoutJob: Job? = null  // 제스처 타임아웃 작업
    private var isRerouteNavigation = false  // 재탐색으로 인한 네비게이션 시작 여부

    // Simulation Mode
    private var isSimulationMode: Boolean = false  // 시뮬레이션 모드 플래그
    private var simulationJob: Job? = null  // 시뮬레이션 코루틴 작업

    // Camera State (OMS 스타일)
    private var currentBearing: Float = 0f
    private var currentSpeed: Float = 0f
    private var currentZoom: Double = 17.0
    private var currentTilt: Double = 0.0

    // 네비게이션 모드의 마지막 카메라 상태 (제스처 모드 복귀 시 사용)
    private var lastNavigationZoom: Double = 17.0
    private var lastNavigationTilt: Double = 10.0
    private var lastNavigationBearing: Float = 0f
    private var lastBearing: Float = 0f  // 일반 베어링 (경로 기반 계산용)
    
    // Content Padding (전체 경로 표시 시 패딩 제거를 위해 저장)
    private var originalTopPadding: Int = 0

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
        private const val OFF_ROUTE_THRESHOLD_M = 35f  // 경로 이탈 임계값 (미터)
        private const val REROUTE_COOLDOWN_MS = 5000L  // 재탐색 쿨다운 (밀리초)
        private const val ARRIVAL_THRESHOLD_M = 30f  // 도착 판정 거리 (미터)

        // Camera 상수
        private const val ZOOM_LOW_SPEED = 18.0  // 저속 줌
        private const val ZOOM_DEFAULT = 17.0  // 기본 줌
        private const val ZOOM_HIGH_SPEED = 16.0  // 고속 줌
        private const val SPEED_THRESHOLD_SLOW = 4.2f  // ≈15km/h
        private const val SPEED_THRESHOLD_FAST = 13.9f  // ≈50km/h
        private const val HIGH_SPEED_TILT = 45.0
        private const val DEFAULT_TILT = 30.0
        private const val BEARING_SMOOTH_ALPHA = 0.3f  // 베어링 스무딩 계수

        // Simulation 상수
        private const val SIMULATION_UPDATE_INTERVAL_MS = 800L  // 시뮬레이션 업데이트 간격 (밀리초)
        private const val SIMULATION_SPEED_KMH = 50f  // 시뮬레이션 속도 (km/h)

        // User Gesture 상수
        private const val GESTURE_TIMEOUT = 10000L  // 유저 제스처 후 자동 복귀 시간 (10초)

        // PIP
        private const val ACTION_PIP_STOP_NAVIGATION = "com.dom.samplenavigation.action.PIP_STOP"
        private const val ACTION_PIP_TOGGLE_VOICE =
            "com.dom.samplenavigation.action.PIP_TOGGLE_VOICE"
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
        // 시뮬레이션 모드 플래그 (기본값: false)
        isSimulationMode = intent.getBooleanExtra("simulation_mode", false)
        // 경로 옵션 (기본값: TRAOPTIMAL)
        val routeOptionOrdinal =
            intent.getIntExtra("route_option", RouteOptionType.TRAOPTIMAL.ordinal)
        selectedRouteOption =
            RouteOptionType.entries.getOrNull(routeOptionOrdinal) ?: RouteOptionType.TRAOPTIMAL

        if (startLat != 0.0 && startLng != 0.0 && !destination.isNullOrEmpty()) {
            val startLocation = LatLng(startLat, startLng)
            navigationViewModel.setRoute(startLocation, destination, selectedRouteOption)
            Timber.d("Navigation data set: $startLocation -> $destination, simulationMode=$isSimulationMode, option=$selectedRouteOption")
        } else {
            Timber.w("Navigation data not available")
        }

        setupMap()
        setupObservers()
        setupClickListeners()

        // 위치 권한 확인 (시뮬레이션 모드가 아닐 때만)
        if (!isSimulationMode) {
            checkLocationPermission()
        }
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
        naverMap.mapType = NaverMap.MapType.Navi

        // 운전자 시야 확보를 위해 지도 중심을 화면 하단 쪽으로 오프셋
        val density = resources.displayMetrics.density
        val topPaddingPx = (600 * density).toInt()
        originalTopPadding = topPaddingPx  // 원래 패딩 값 저장
        naverMap.setContentPadding(0, topPaddingPx, 0, 0)

        // 현재 위치 마커 생성
        createCurrentLocationMarker()

        // 지도 제스처 리스너 설정
        setupMapGestureListeners(naverMap)

        // 네비게이션 자동 시작
        isNavigating = true
        currentPathIndex = 0
        navigationViewModel.startNavigation()
    }

    /**
     * 지도 제스처 리스너 설정
     * 사용자가 지도를 조작할 때 감지하여 자동 추적을 중지하고 혼잡도를 표시
     */
    private fun setupMapGestureListeners(map: NaverMap) {
        // 지도 클릭 감지
        map.setOnMapClickListener { _, _ ->
            handleUserGesture()
        }

        // 지도 롱클릭 감지
        map.setOnMapLongClickListener { _, _ ->
            handleUserGesture()
            true  // 이벤트 소비
        }

        // 카메라 변경 감지 (줌, 팬, 틸트 등)
        map.addOnCameraChangeListener { reason, _ ->
            // 제스처로 인한 카메라 변경 감지
            // NaverMap SDK의 카메라 변경 이유는 정수로 반환됨
            if (reason == CameraUpdate.REASON_GESTURE) {
                handleUserGesture()
            }
        }
    }

    /**
     * 사용자 제스처 처리
     */
    private fun handleUserGesture() {
        if (!isNavigating) return

        val currentTime = System.currentTimeMillis()

        // 제스처 모드 활성화
        if (!isGestureMode) {
            isGestureMode = true
            lastGestureTime = currentTime
            enterGestureMode()
            Timber.d("User gesture detected - entering gesture mode")
        } else {
            // 제스처 모드가 이미 활성화된 경우 시간 갱신
            lastGestureTime = currentTime
        }
    }

    /**
     * 제스처 모드 진입
     */
    private fun enterGestureMode() {
        // 교통량 표시로 전환
        currentRoute?.let { route ->
            displayRouteWithCongestion(route, naverMap ?: return)
        }

        // 자동 추적 비활성화
        naverMap?.let { map ->
            map.locationTrackingMode = LocationTrackingMode.None
        }

        // 버튼 텍스트 변경
        binding.btnReturnToCurrentLocation.text = "현위치로"
        binding.btnReturnToCurrentLocation.visibility = View.VISIBLE

        // 10초 후 자동 복귀 타이머 시작
        startGestureTimeoutTimer()

        Timber.d("Entered gesture mode - congestion display enabled, auto tracking disabled")
    }

    /**
     * 현재 위치 모드로 복귀
     */
    private fun returnToCurrentLocationMode() {
        Timber.d("returnToCurrentLocationMode() called")
        Timber.d("Current state - isGestureMode: $isGestureMode, isNavigating: ${navigationManager.navigationState.value?.isNavigating}, currentLocation: ${navigationManager.navigationState.value?.currentLocation}")

        isGestureMode = false
        isShowingFullRoute = false

        // 원래 패딩 복원 (네비게이션 모드용)
        naverMap?.setContentPadding(0, originalTopPadding, 0, 0)

        // 단색 경로로 복귀
        currentRoute?.let { route ->
            displayRoute(route, showFullRoute = false)
            Timber.d("Route displayed (single color)")
        }

        // 네비게이션 모드 재활성화
        if (navigationManager.navigationState.value?.isNavigating == true) {
            startNavigationMode()
            Timber.d("Navigation mode reactivated")
        }

        // 현재 위치로 카메라 이동 (저장된 줌과 방향 유지)
        val currentLocation = navigationManager.navigationState.value?.currentLocation
        val currentRoute = navigationManager.navigationState.value?.currentRoute

        if (currentLocation != null && naverMap != null) {
            Timber.d("Moving camera to current location: $currentLocation")
            val bearing = if (lastNavigationBearing > 0) {
                Timber.d("Using last navigation bearing: $lastNavigationBearing")
                lastNavigationBearing
            } else {
                // 방향이 없으면 경로 기반으로 계산
                if (currentRoute != null && currentPathIndex < currentRoute.path.size - 1) {
                    val pathBearing = calculateBearingFromPath(currentRoute.path, currentPathIndex)
                    Timber.d("Calculated bearing from path: $pathBearing")
                    pathBearing
                } else {
                    Timber.d("Using last bearing: $lastBearing")
                    lastBearing
                }
            }

            val cameraPosition = CameraPosition(
                currentLocation,
                lastNavigationZoom,
                lastNavigationTilt,
                bearing.toDouble()
            )
            val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
                .animate(CameraAnimation.Easing, 200)
            naverMap?.moveCamera(cameraUpdate)

            Timber.d("Camera moved to current location - zoom=${lastNavigationZoom}, bearing=$bearing°")
        } else {
            // 현재 위치가 없으면 경로의 시작점으로 이동
            if (currentRoute != null && naverMap != null) {
                val startLocation = if (currentPathIndex < currentRoute.path.size) {
                    currentRoute.path[currentPathIndex]
                } else {
                    currentRoute.summary.startLocation
                }

                Timber.w("Current location is null, using route location: $startLocation")
                val bearing = if (lastNavigationBearing > 0) {
                    lastNavigationBearing
                } else if (currentPathIndex < currentRoute.path.size - 1) {
                    calculateBearingFromPath(currentRoute.path, currentPathIndex)
                } else {
                    lastBearing
                }

                val cameraPosition = CameraPosition(
                    startLocation,
                    lastNavigationZoom,
                    lastNavigationTilt,
                    bearing.toDouble()
                )
                val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
                    .animate(CameraAnimation.Easing, 200)
                naverMap?.moveCamera(cameraUpdate)

                Timber.d("Camera moved to route location: $startLocation")
            } else {
                Timber.e("Cannot return to location - currentLocation: null, currentRoute: ${currentRoute != null}, naverMap: ${naverMap != null}")
                Toast.makeText(this, "현재 위치를 가져올 수 없습니다. GPS를 확인해주세요.", Toast.LENGTH_SHORT).show()
            }
        }

        // 버튼 텍스트 변경
        binding.btnReturnToCurrentLocation.text = "전체 경로"

        Timber.d("Returned to current location mode complete")
    }

    /**
     * 제스처 모드 자동 복귀 타이머
     */
    private fun startGestureTimeoutTimer() {
        // 기존 타이머 취소
        gestureTimeoutJob?.cancel()

        // 새 타이머 시작
        gestureTimeoutJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(GESTURE_TIMEOUT)

            // 제스처 모드가 여전히 활성화되어 있고, 최근 제스처가 없으면 복귀
            if (isGestureMode) {
                val timeSinceLastGesture = System.currentTimeMillis() - lastGestureTime
                if (timeSinceLastGesture >= GESTURE_TIMEOUT) {
                    returnToCurrentLocationMode()
                }
            }
        }

        Timber.d("Gesture timeout timer started (${GESTURE_TIMEOUT}ms)")
    }

    /**
     * 경로에서 베어링 계산
     */
    private fun calculateBearingFromPath(path: List<LatLng>, currentIndex: Int): Float {
        if (currentIndex >= path.size - 1) return lastBearing

        val currentPoint = path[currentIndex]
        val nextPoint = path[currentIndex + 1]
        return calculateBearing(currentPoint, nextPoint)
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
                // 네비게이션 중에는 화면이 꺼지지 않도록 설정
                keepScreenOn(true)
            } else {
                stopNavigationMode()
                // 네비게이션 종료 시 화면 켜짐 유지 해제
                keepScreenOn(false)
            }

            // OMS 스타일: Location Snapping 및 Camera Follow
            // 시뮬레이션 모드가 아닐 때만 스냅 로직 실행 (시뮬레이션은 직접 인덱스 제어)
            if (state.isNavigating && isNavigating && state.currentLocation != null && state.currentRoute != null && !isSimulationMode) {
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
                        checkAndReroute(gpsLocation)
                    }

                    // 3. Camera Follow: 스냅된 위치로 카메라 추적 (제스처 모드가 아닐 때만)
                    if (snappedLocation != null && !isGestureMode) {
                        updateCameraFollow(snappedLocation!!, currentBearing, currentSpeed)
                        updateCurrentLocationMarker(snappedLocation!!)

                        // 네비게이션 모드의 카메라 상태 저장
                        lastNavigationZoom = currentZoom
                        lastNavigationTilt = currentTilt
                        lastNavigationBearing = currentBearing
                        lastBearing = currentBearing
                    } else if (snappedLocation != null) {
                        // 제스처 모드일 때는 마커만 업데이트
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
                        if (isRerouteNavigation) {
                            // 재탐색인 경우: "경로를 재탐색했습니다" + 첫 안내
                            voiceGuideManager.speakPlain("경로를 재탐색했습니다")
                            // 첫 안내 메시지도 재생 (QUEUE_ADD로 순차 재생)
                            // speakInstruction은 queueMode를 받지 않으므로, 직접 speak을 사용
                            val distance = instruction.distanceToInstruction
                            val cleanMessage = instruction.message
                                .replace(Regex("\\d+\\s*킬로미터\\s*(후|전방|앞)\\s*"), "")
                                .replace(Regex("\\d+\\s*미터\\s*(후|전방|앞)\\s*"), "")
                                .replace(Regex("\\d+\\.?\\d*\\s*km\\s*(후|전방|앞)\\s*"), "")
                                .replace(Regex("\\d+\\s*m\\s*(후|전방|앞)\\s*"), "")
                                .trim()
                            val message = when {
                                distance >= 1000 -> {
                                    val km = distance / 1000.0
                                    "${String.format("%.1f", km)}킬로미터 후 $cleanMessage"
                                }
                                distance >= 500 -> "500미터 후 $cleanMessage"
                                distance >= 300 -> "300미터 후 $cleanMessage"
                                distance >= 100 -> {
                                    val hm = (distance / 100) * 100
                                    "${hm}미터 후 $cleanMessage"
                                }
                                distance >= 50 -> "곧 $cleanMessage"
                                else -> cleanMessage
                            }
                            voiceGuideManager.speak(message, android.speech.tts.TextToSpeech.QUEUE_ADD)
                            Timber.d("Reroute announcement: 경로를 재탐색했습니다 + ${instruction.message}")
                            isRerouteNavigation = false  // 플래그 리셋
                        } else {
                            // 최초 시작인 경우: "경로 안내를 시작합니다" + 첫 안내
                            voiceGuideManager.speakNavigationStart(instruction)
                            Timber.d("Navigation start announcement: 경로 안내를 시작합니다 + ${instruction.message}")
                        }
                    }
                }
            }
        }

        // 경로 데이터 관찰
        navigationViewModel.navigationRoute.observe(this) { route ->
            route?.let { newRoute ->
                currentRoute = newRoute
                displayRoute(newRoute, isShowingFullRoute)

                // 재탐색 후 초기화
                if (isRerouting) {
                    isRerouting = false
                    isRerouteNavigation = true  // 재탐색 플래그 설정
                    Toast.makeText(this, "경로를 재검색했습니다", Toast.LENGTH_SHORT).show()

                    // 재탐색 후 스냅 위치 초기화
                    val referenceLocation = lastKnownLocation ?: newRoute.summary.startLocation
                    val snapResult = snapLocationToRoute(referenceLocation, newRoute.path, 0)
                    snappedLocation = snapResult.snappedLocation
                    currentPathIndex = snapResult.pathIndex
                    updateCurrentLocationMarker(snappedLocation!!)
                } else {
                    // 최초 시작 시 출발지로 초기화
                    isRerouteNavigation = false  // 최초 시작이므로 재탐색 아님
                    snappedLocation = newRoute.summary.startLocation
                    currentPathIndex = 0
                    updateCurrentLocationMarker(snappedLocation!!)
                }

                navigationManager.startNavigation(newRoute)

                // 네비게이션 시작 시 즉시 3D 뷰로 전환
                if (isMapReady && snappedLocation != null) {
                    updateCameraFollow(snappedLocation!!, currentBearing, 0f)
                }

                // 시뮬레이션 모드 시작
                if (isSimulationMode) {
                    startSimulation(newRoute)
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

        // 경로 전체 표시 버튼 (제스처 모드에서 사용)
        binding.btnReturnToCurrentLocation.setOnClickListener {
            toggleFullRouteView()
        }
    }

    /**
     * 전체 경로 표시 토글
     */
    private fun toggleFullRouteView() {
        if (isShowingFullRoute || isGestureMode) {
            // 현재 위치로 복귀
            returnToCurrentLocationMode()
        } else {
            // 전체 경로 표시 (제스처 모드 진입)
            handleUserGesture()

            // 전체 경로 표시 시 패딩 제거 (MainActivity와 동일한 바운더리 계산을 위해)
            naverMap?.setContentPadding(0, 0, 0, 0)
            isShowingFullRoute = true

            // 전체 경로 표시 시 카메라를 전체 경로에 맞춤
            currentRoute?.let { route ->
                val bounds = LatLngBounds.Builder()
                    .include(route.summary.startLocation)
                    .include(route.summary.endLocation)
                    .apply {
                        route.path.forEach { point ->
                            include(point)
                        }
                    }
                    .build()
                // MainActivity와 동일한 패딩 사용
                naverMap?.moveCamera(CameraUpdate.fitBounds(bounds, 150))
            }
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
                bestIndex = if (calculateDistance(snapped, p1) < calculateDistance(
                        snapped,
                        p2
                    )
                ) i else i + 1
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
        if(isGestureMode) {
            return
        }
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
     * 한국 도로는 우측 통행이므로 재탐색 위치를 약간 오른쪽으로 이동
     */
    private fun checkAndReroute(gpsLocation: LatLng) {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastReroute = currentTime - lastRerouteTime

        // 쿨다운 체크
        if (timeSinceLastReroute < REROUTE_COOLDOWN_MS) {
            return
        }

        // 재탐색 위치 계산: 현재 위치에서 약간 오른쪽으로 이동 (한국 도로 우측 통행 고려)
        // 베어링을 기준으로 오른쪽으로 약 10-15m 이동
        val rerouteLocation = offsetLocationToRight(gpsLocation, currentBearing, 12.0)  // 12m 오른쪽

        // 재탐색 실행
        isRerouting = true
        lastRerouteTime = currentTime
        Timber.d("Rerouting triggered from: $gpsLocation -> $rerouteLocation (offset to right)")

        // 음성 안내는 재탐색 완료 후 shouldPlayNavigationStart에서 처리
        // (중복 방지를 위해 여기서는 제거)

        navigationViewModel.reroute(rerouteLocation)
        binding.tvCurrentInstruction.text = "경로를 재검색 중입니다..."
    }

    /**
     * 위치를 베어링 기준으로 오른쪽으로 이동 (한국 도로 우측 통행 고려)
     * @param location 원본 위치
     * @param bearing 현재 진행 방향 (도)
     * @param offsetMeters 오른쪽으로 이동할 거리 (미터)
     * @return 오른쪽으로 이동한 위치
     */
    private fun offsetLocationToRight(
        location: LatLng,
        bearing: Float,
        offsetMeters: Double = 10.0
    ): LatLng {
        // 베어링을 라디안으로 변환
        val bearingRad = Math.toRadians(bearing.toDouble())

        // 오른쪽 방향 = 베어링 - 90도
        val rightBearingRad = bearingRad - Math.PI / 2

        // 위도 1도 ≈ 111km, 경도는 위도에 따라 다름
        val latOffset = offsetMeters / 111000.0 * cos(rightBearingRad)
        val lngOffset =
            offsetMeters / (111000.0 * cos(Math.toRadians(location.latitude))) * sin(rightBearingRad)

        return LatLng(
            location.latitude + latOffset,
            location.longitude + lngOffset
        )
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
            binding.btnStopNavigation.visibility =
                if (state.isNavigating) View.VISIBLE else View.GONE
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

        // 다음 안내 메시지 업데이트
        updateNextInstructionUI(state.nextInstruction)
    }

    /**
     * 다음 안내 메시지 UI 업데이트
     */
    private fun updateNextInstructionUI(nextInstruction: Instruction?) {
        if (nextInstruction == null) {
            binding.tvNextInstruction.text = ""
            binding.tvNextInstruction.visibility = View.GONE
            return
        }

        binding.tvNextInstruction.visibility = View.VISIBLE

        val route = currentRoute ?: return
        val currentPos = snappedLocation ?: lastKnownLocation ?: return

        // 다음 안내까지의 거리 계산
        val targetIndex = nextInstruction.pointIndex.coerceIn(0, route.path.size - 1)
        val distance = if (currentPathIndex < targetIndex) {
            calculateRouteDistance(currentPathIndex, targetIndex, route.path)
        } else {
            calculateDistance(currentPos, nextInstruction.location)
        }

        // 메시지에서 거리 정보 제거 (이미 거리를 표시할 것이므로)
        val cleanMessage = nextInstruction.message
            .replace(Regex("\\d+\\s*킬로미터\\s*(후|전방|앞)\\s*"), "")
            .replace(Regex("\\d+\\s*미터\\s*(후|전방|앞)\\s*"), "")
            .replace(Regex("\\d+\\.?\\d*\\s*km\\s*(후|전방|앞)\\s*"), "")
            .replace(Regex("\\d+\\s*m\\s*(후|전방|앞)\\s*"), "")
            .trim()

        // 다음 안내 메시지에 거리 정보 추가
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

        binding.tvNextInstruction.text = messageWithDistance
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

    private fun displayRoute(route: NavigationRoute, showFullRoute: Boolean = false) {
        val nMap = naverMap ?: return

        // 기존 경로 오버레이만 제거 (화살표는 유지)
        pathOverlays.forEach { it.map = null }
        pathOverlays.clear()
        endMarker?.map = null

        if (showFullRoute) {
            // 전체 경로를 혼잡도별로 표시 (MainActivity와 동일한 방식)
            displayRouteWithCongestion(route, nMap)
        } else {
            // 현재 위치 기준 앞부분만 표시 (기본)
            displayRouteAhead(route, nMap)
        }

        // 도착지 마커
        endMarker = Marker().apply {
            position = route.summary.endLocation
            captionText = "도착지"
            map = nMap
        }

        Timber.d("Route displayed with ${route.path.size} points, showFullRoute=$showFullRoute")
    }

    /**
     * 경로를 혼잡도별로 표시 (전체 경로)
     */
    private fun displayRouteWithCongestion(route: NavigationRoute, nMap: NaverMap) {
        if (route.sections.isEmpty()) {
            // sections가 없으면 전체 경로를 하나로 표시
            pathOverlays.add(PathOverlay().apply {
                coords = route.path
                color = resources.getColor(R.color.skyBlue, null)
                outlineColor = Color.WHITE
                width = 40
                map = nMap
            })
            return
        }

        // 혼잡도에 따라 경로를 구간별로 나눠서 표시
        val groupedPaths = mutableListOf<Pair<List<LatLng>, Int>>()
        val sortedSections = route.sections.sortedBy { it.pointIndex }

        var currentCongestion: Int? = null
        var currentPathGroup = mutableListOf<LatLng>()
        var lastEndIndex = 0

        sortedSections.forEachIndexed { index, section ->
            val startIndex = section.pointIndex
            val endIndex = minOf(startIndex + section.pointCount, route.path.size)

            // 첫 섹션 이전의 경로 처리
            if (index == 0 && startIndex > 0) {
                val beforePath = route.path.subList(0, startIndex)
                if (beforePath.isNotEmpty() && beforePath.size >= 2) {
                    val firstCongestion = section.congestion
                    groupedPaths.add(Pair(beforePath, firstCongestion))
                }
            }

            // 섹션 사이의 빈 구간 처리
            if (startIndex > lastEndIndex) {
                val gapPath = route.path.subList(lastEndIndex, startIndex)
                if (gapPath.isNotEmpty() && gapPath.size >= 2) {
                    val gapCongestion = currentCongestion ?: section.congestion
                    groupedPaths.add(Pair(gapPath, gapCongestion))
                }
            }

            // 현재 섹션의 경로 처리
            val sectionPath = route.path.subList(startIndex, endIndex)

            if (section.congestion == currentCongestion) {
                currentPathGroup.addAll(sectionPath)
            } else {
                if (currentPathGroup.size >= 2 && currentCongestion != null) {
                    groupedPaths.add(Pair(currentPathGroup.toList(), currentCongestion))
                }
                currentPathGroup = sectionPath.toMutableList()
                currentCongestion = section.congestion
            }

            lastEndIndex = endIndex
        }

        // 마지막 그룹 저장
        if (currentPathGroup.size >= 2 && currentCongestion != null) {
            groupedPaths.add(Pair(currentPathGroup, currentCongestion))
        }

        // 마지막 섹션 이후의 남은 경로 처리
        if (lastEndIndex < route.path.size) {
            val remainingPath = route.path.subList(lastEndIndex, route.path.size)
            if (remainingPath.isNotEmpty() && remainingPath.size >= 2) {
                val lastCongestion =
                    currentCongestion ?: sortedSections.lastOrNull()?.congestion ?: 1
                groupedPaths.add(Pair(remainingPath, lastCongestion))
            }
        }

        // 그룹화된 경로들을 PathOverlay로 표시
        groupedPaths.forEach { (path, congestion) ->
            val overlay = PathOverlay().apply {
                coords = path
                color = getCongestionColor(congestion)
                outlineColor = Color.WHITE
                width = 40
                map = nMap
            }
            pathOverlays.add(overlay)
        }
    }

    /**
     * 경로의 앞부분만 표시 (현재 위치 기준)
     */
    private fun displayRouteAhead(route: NavigationRoute, nMap: NaverMap) {
        // 현재 위치 이후의 경로만 표시
        val startIndex = currentPathIndex.coerceIn(0, route.path.size - 1)
        val pathAhead = route.path.subList(startIndex, route.path.size)

        if (pathAhead.size >= 2) {
            pathOverlays.add(PathOverlay().apply {
                coords = pathAhead
                color = resources.getColor(R.color.skyBlue, null)
                outlineColor = Color.WHITE
                width = 40
                map = nMap
            })
        }
    }

    /**
     * 혼잡도에 따른 색상 반환
     * @param congestion 0: 값없음(회색), 1: 원활(녹색), 2: 서행(주황색), 3: 혼잡(빨간색)
     */
    private fun getCongestionColor(congestion: Int): Int {
        return when (congestion) {
            0 -> 0xFF808080.toInt() // 값없음: 회색
            1 -> 0xFF00AA00.toInt() // 원활: 녹색
            2 -> 0xFFFFAA00.toInt() // 서행: 주황색
            3 -> 0xFFFF0000.toInt() // 혼잡: 빨간색
            else -> 0xFF808080.toInt() // 기타: 회색
        }
    }

    private fun createCurrentLocationMarker() {
        val map = naverMap ?: return

        currentLocationMarker = Marker().apply {
            icon = OverlayImage.fromResource(R.drawable.a)
            position = LatLng(37.5665, 126.9780)
            this.map = map
            anchor = PointF(0.5f, 0.5f)
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

    /**
     * 화면 켜짐 유지 설정/해제
     * 네비게이션 중에는 화면이 꺼지지 않도록 합니다.
     */
    private fun keepScreenOn(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Timber.d("Screen keep-on enabled for navigation")
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Timber.d("Screen keep-on disabled")
        }
    }

    // ==================== Location Updates ====================

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdates() {
        // 시뮬레이션 모드일 때는 실제 위치 업데이트 시작하지 않음
        if (isSimulationMode) {
            Timber.d("Simulation mode active - skipping real location updates")
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(1f)
            .setWaitForAccurateLocation(true)
            .build()

        if (fusedCallback == null) {
            fusedCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    // 시뮬레이션 모드일 때는 실제 위치 업데이트 무시
                    if (isSimulationMode) return

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

                    // Telemetry API 호출 (시뮬레이션이 아닐 때만)
                    sendLocationTelemetry(loc)

                    Timber.d("Location: $latLng, bearing=${currentBearing}°, speed=${currentSpeed * 3.6f}km/h")
                }
            }
        }

        fusedClient.requestLocationUpdates(request, fusedCallback as LocationCallback, mainLooper)

        // 마지막 알려진 위치 즉시 반영
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (isSimulationMode) return@addOnSuccessListener
            loc?.let {
                val latLng = LatLng(it.latitude, it.longitude)
                lastKnownLocation = latLng
                lastLocation = it
                navigationManager.updateCurrentLocation(latLng)

                // Telemetry API 호출 (시뮬레이션이 아닐 때만)
                sendLocationTelemetry(it)
            }
        }
    }

    // ==================== Telemetry (위치 전송) ====================

    /**
     * 위치 정보를 Telemetry API로 전송
     * 시뮬레이션 모드가 아닐 때만 호출됩니다.
     */
    private fun sendLocationTelemetry(location: Location) {
        if (isSimulationMode) return

        try {
            val dateFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
            val regDate = dateFormat.format(Date(location.time))

            val payload = VehicleLocationPayload(
                vecNavType = 1,  // 네비게이션 타입 (필요에 따라 변경 가능)
                vecLat = location.latitude,
                vecLon = location.longitude,
                vecAcc = location.accuracy.toDouble(),
                regDate = regDate
            )

            // vehicleId는 임시로 1로 설정 (필요시 Intent로 전달받거나 설정에서 가져올 수 있음)
            val vehicleId = 1

            navigationViewModel.sendTelemetry(vehicleId, payload)
            Timber.d("Telemetry sent: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m")
        } catch (e: Exception) {
            Timber.e("Failed to send telemetry: ${e.message}")
        }
    }

    // ==================== Simulation Mode (시뮬레이션 모드) ====================

    /**
     * 시뮬레이션 모드 시작
     * 경로를 따라 일정 시간마다 인덱스를 증가시키며 가짜 위치를 생성합니다.
     */
    private fun startSimulation(route: NavigationRoute) {
        if (route.path.isEmpty()) {
            Timber.w("Cannot start simulation: route path is empty")
            return
        }

        // 기존 시뮬레이션 중지
        stopSimulation()

        Timber.d("Starting simulation mode - route has ${route.path.size} points")

        // 시뮬레이션 시작 위치 초기화
        currentPathIndex = 0
        snappedLocation = route.path[0]
        lastKnownLocation = route.path[0]

        // 시뮬레이션 코루틴 시작
        simulationJob = lifecycleScope.launch {
            while (isNavigating && currentPathIndex < route.path.size - 1) {
                // 다음 경로 포인트로 이동
                currentPathIndex++
                val nextLocation = route.path[currentPathIndex]
                snappedLocation = nextLocation
                lastKnownLocation = nextLocation

                // 가짜 Location 객체 생성 및 저장
                val simulatedLocation = createSimulatedLocation(nextLocation, route)
                lastLocation = simulatedLocation

                // 속도 및 베어링 업데이트
                currentSpeed = simulatedLocation.speed
                if (simulatedLocation.hasBearing()) {
                    currentBearing = simulatedLocation.bearing
                } else if (currentPathIndex > 0) {
                    val prevLocation = route.path[currentPathIndex - 1]
                    currentBearing = calculateBearing(prevLocation, nextLocation)
                }

                // NavigationManager에 시뮬레이션 위치 업데이트
                // 주의: 이 호출은 setupObservers의 navigationState.observe를 트리거하지만,
                // 시뮬레이션 모드에서는 스냅 로직이 실행되지 않으므로 currentPathIndex가 유지됩니다
                navigationManager.updateCurrentLocation(nextLocation)

                // 카메라 업데이트
                if (isMapReady) {
                    updateCameraFollow(nextLocation, currentBearing, currentSpeed)
                    updateCurrentLocationMarker(nextLocation)
                }

                Timber.d("Simulation: index=$currentPathIndex/${route.path.size - 1}, location=$nextLocation, speed=${currentSpeed * 3.6f}km/h")

                // 도착 확인
                val distanceToDestination = calculateDistance(
                    nextLocation,
                    route.summary.endLocation
                )
                if (distanceToDestination <= ARRIVAL_THRESHOLD_M) {
                    Timber.d("Simulation arrived at destination!")
                    navigationManager.stopNavigation()
                    stopSimulation()
                    Toast.makeText(this@NavigationActivity, "목적지에 도착했습니다!", Toast.LENGTH_SHORT)
                        .show()
                    break
                }

                // 다음 업데이트까지 대기
                kotlinx.coroutines.delay(SIMULATION_UPDATE_INTERVAL_MS)
            }
        }
    }

    /**
     * 시뮬레이션 모드 중지
     */
    private fun stopSimulation() {
        simulationJob?.cancel()
        simulationJob = null
        Timber.d("Simulation stopped")
    }

    /**
     * 시뮬레이션용 가짜 Location 객체 생성
     */
    private fun createSimulatedLocation(latLng: LatLng, route: NavigationRoute): Location {
        val location = Location("simulation")
        location.latitude = latLng.latitude
        location.longitude = latLng.longitude
        location.accuracy = 5f  // 시뮬레이션은 정확도 5m
        location.speed = SIMULATION_SPEED_KMH / 3.6f  // m/s
        location.time = System.currentTimeMillis()
        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

        // 베어링 설정
        if (currentPathIndex > 0 && currentPathIndex < route.path.size) {
            val prevLocation = route.path[currentPathIndex - 1]
            val bearing = calculateBearing(prevLocation, latLng)
            location.bearing = bearing
            location.bearingAccuracyDegrees = 5f
        }

        return location
    }

    /**
     * 두 지점 간의 방향 계산 (도)
     */
    private fun calculateBearing(from: LatLng, to: LatLng): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLng = Math.toRadians(to.longitude - from.longitude)

        val y = sin(deltaLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLng)

        val bearing = Math.toDegrees(atan2(y, x))
        return normalizeBearing(bearing.toFloat())
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
        val endIndexExclusive =
            minOf(path.size, pointIndex + 2) // +2 (exclusive) → pointIndex+1까지 포함

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
     * 현재 instruction의 분기점에 화살표를 표시하고,
     * 다음 instruction으로 넘어가면 자동으로 다음 분기점의 화살표로 업데이트됩니다.
     */
    private fun updateDirectionArrow(instruction: Instruction, route: NavigationRoute) {
        val nMap = naverMap ?: return

        // 기존 화살표 제거 (이전 분기점 화살표)
        directionArrowOverlay?.map = null
        directionArrowOverlay = null

        // 화살표 경로 생성 (현재 instruction의 분기점 기준)
        val (arrowPath, _) = createDirectionArrow(instruction, route)

        // ArrowheadPathOverlay 사용: 경로를 따라가고 끝에 자동으로 화살표 머리가 생김
        if (arrowPath.size >= 2) {
            directionArrowOverlay = ArrowheadPathOverlay().apply {
                coords = arrowPath
                color = Color.WHITE
                outlineColor = Color.BLUE
                width = 20
                map = nMap
                zIndex = 1000  // 경로 위에 표시
            }
            Timber.d("Direction arrow updated: instruction pointIndex=${instruction.pointIndex}, arrowPath size=${arrowPath.size}")
        } else {
            Timber.d("Direction arrow not created: insufficient path points (${arrowPath.size})")
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

        // 시뮬레이션 중지
        stopSimulation()

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
        stopSimulation()  // 시뮬레이션 중지
        gestureTimeoutJob?.cancel()  // 제스처 타임아웃 작업 취소
        navigationManager.stopNavigation()
        voiceGuideManager.release()
        
        // 화면 켜짐 유지 해제
        keepScreenOn(false)

        try {
            fusedCallback?.let { cb ->
                fusedClient.removeLocationUpdates(cb)
            }
        } catch (e: Exception) {
            Timber.e("Error stopping location updates: ${e.message}")
        }
    }
}
