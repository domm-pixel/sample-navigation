package com.dom.samplenavigation.view

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
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
import com.dom.samplenavigation.api.telemetry.model.VehicleLocationPayload
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
import com.naver.maps.geometry.LatLngBounds
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
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@AndroidEntryPoint
class NavigationActivity : BaseActivity<ActivityNavigationBinding>(
    R.layout.activity_navigation
), OnMapReadyCallback {

    private val navigationViewModel: NavigationViewModel by viewModels()
    private lateinit var navigationManager: NavigationManager
    private lateinit var voiceGuideManager: VoiceGuideManager

    private var naverMap: NaverMap? = null
    private var pathOverlays: MutableList<PathOverlay> = mutableListOf()

    //    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var currentLocationMarker: Marker? = null
    private var isMapReady = false
    private var lastBearing: Float = 0f
    private var isNavigationModeActive = false
    private var previousLocationForBearing: LatLng? = null
    private var currentPathIndex: Int = 0  // 현재 경로상 위치 인덱스
    private var isNavigating = false  // 네비게이션 진행 중 여부
    private var isRerouting = false  // 재검색 중 여부
    private var lastRerouteTime: Long = 0  // 마지막 재검색 시간
    private var isGestureMode = false  // 사용자 제스처 모드 여부
    private var lastGestureTime: Long = 0  // 마지막 제스처 시간
    private var lastNavigationZoom: Double = 17.0  // 네비게이션 모드의 줌 레벨
    private var lastNavigationBearing: Float = 0f  // 네비게이션 모드의 방향
    private var lastNavigationTilt: Double = 0.0   // 네비게이션 모드의 기울기
    private var lastKnownLocation: LatLng? = null  // 마지막 알려진 위치 (GPS 끊김 대비)
    private var lastLocationUpdateTime: Long = 0  // 마지막 위치 업데이트 시간
    private var isInTunnel: Boolean = false  // 터널/지하차도 모드 여부
    // Dead-reckoning 향상용 상태 값
    private var lastFixElapsedMs: Long = SystemClock.elapsedRealtime()
    private var lastToastElapsedMs: Long = 0L
    private var lastSpeedEma: Float? = null
    private var lastInstructionCleanMessage: String? = null
    private var lastInstructionTargetIndex: Int? = null
    private var offRouteConfirmCount: Int = 0
    private var lastStoppedElapsedMs: Long = 0L
    private var pendingRerouteLocation: LatLng? = null
    private var lastSpeedMps: Float = 0f
    private var cameraSpeedInitialized: Boolean = false
    private var lastTelemetrySentElapsed: Long = 0L
    private val telemetryDateFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
    private val vehicleId: Int = 1 // TODO replace with runtime vehicle identifier

    // Fused Location
    private lateinit var fusedClient: FusedLocationProviderClient
    private var fusedCallback: LocationCallback? = null
    private var isUsingFused: Boolean = false

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val OFF_ROUTE_THRESHOLD = 30f  // 오차 범위 (미터) - GPS 오차를 고려하여 증가
        private const val ARRIVAL_THRESHOLD = 25f  // 도착 판정 거리 (미터)
        private const val REROUTE_THRESHOLD = 70f  // 경로 재검색 임계값 (미터) - OFF_ROUTE보다 충분히 큼
        private const val GESTURE_TIMEOUT = 10000L  // 제스처 모드 자동 복귀 시간 (10초)
        private const val LOCATION_TIMEOUT = 10000L  // 위치 업데이트 타임아웃 (10초) - GPS 끊김 감지
        private const val TUNNEL_SPEED_ESTIMATE = 60f  // 터널 내 추정 속도 (km/h)
        // Dead-reckoning 보강용 상수
        private const val TUNNEL_ENTER_MS = LOCATION_TIMEOUT      // 터널 진입 판정(모노토닉)
        private const val TUNNEL_EXIT_MS  = 3_000L                // 신호 회복 후 이탈 히스테리시스
        private const val SPEED_MIN_MPS   = 1.0f                  // 최소 1 m/s (3.6 km/h)
        private const val SPEED_MAX_MPS   = 33.3f                 // 최대 33.3 m/s (120 km/h)
        private const val SPEED_EMA_ALPHA = 0.25f                 // 속도 EMA 가중치
        private const val TOAST_COOLDOWN_MS = 5_000L              // 토스트 중복 방지
        private const val REROUTE_COOLDOWN_MS = 7_000L            // 재검색 쿨다운 강화
        private const val OFF_ROUTE_MIN_ACCURACY = 80f            // 오프루트 판정에 요구되는 최대 정확도(m)
        private const val OFF_ROUTE_CONFIRM_COUNT = 2             // 연속 N회 확인 후 재검색
        private const val STOP_RESUME_GRACE_MS = 4_000L           // 정차 후 재가속 시 유예 시간
        private const val SPEED_EMA_ALPHA_CAMERA = 0.2f           // 카메라 줌용 속도 EMA
        private const val ZOOM_LOW_SPEED = 18.0
        private const val ZOOM_DEFAULT = 17.0
        private const val ZOOM_HIGH_SPEED = 16.0
        private const val SPEED_THRESHOLD_SLOW = 4.2f     // ≈15km/h
        private const val SPEED_THRESHOLD_FAST = 13.9f    // ≈50km/h
        private const val HIGH_SPEED_TILT = 35.0
        private const val DEFAULT_TILT = 0.0
        private const val CAMERA_ZOOM_EPS = 0.05
        private const val CAMERA_TILT_EPS = 1.0
        private const val TELEMETRY_INTERVAL_MS = 1_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fused client 초기화
        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        // 네비게이션 매니저 초기화
        navigationManager = NavigationManager(this, lifecycleScope)
        voiceGuideManager = VoiceGuideManager(this)

        // VoiceGuideManager 초기화 확인 (약간의 딜레이 후)
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1000)  // TTS 초기화 대기
            Timber.d("🔊 VoiceGuideManager ready status: ${voiceGuideManager.isReady()}")
        }

        // 전달받은 데이터 설정
        val startLat = intent.getDoubleExtra("start_lat", 0.0)
        val startLng = intent.getDoubleExtra("start_lng", 0.0)
        val destination = intent.getStringExtra("destination")

        if (startLat != 0.0 && startLng != 0.0 && !destination.isNullOrEmpty()) {
            val startLocation = LatLng(startLat, startLng)
            navigationViewModel.setRoute(startLocation, destination)
            Timber.d("📍 Navigation data set: $startLocation -> $destination")
        } else {
            Timber.w("📍 Navigation data not available")
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
//        naverMap.uiSettings.isZoomControlsEnabled = true
        naverMap.uiSettings.isCompassEnabled = false
        naverMap.uiSettings.isLocationButtonEnabled = false
        naverMap.uiSettings.isZoomControlEnabled = false
        naverMap.buildingHeight = 0.2f

        // 운전자 시야 확보를 위해 지도 중심을 화면 하단 쪽으로 오프셋
        val density = resources.displayMetrics.density
        val topPaddingPx = (600 * density).toInt()
        val bottomPaddingPx = (0 * density).toInt()
        naverMap.setContentPadding(0, topPaddingPx, 0, bottomPaddingPx)
        Timber.d("🗺️ Map content padding set - top: $topPaddingPx, bottom: $bottomPaddingPx")

        // 지도 제스처 리스너 설정
        naverMap.setOnMapClickListener { _, _ ->
            handleUserGesture()
        }
        naverMap.setOnMapLongClickListener { _, _ ->
            handleUserGesture()
        }
        naverMap.addOnCameraChangeListener { reason, animated ->
            // 제스처로 인한 카메라 변경 감지
            // NaverMap SDK의 카메라 변경 이유는 정수로 반환됨
            // 0 = 프로그램적 변경, 1 = 제스처 변경
            if (reason == 1 || reason == CameraUpdate.REASON_GESTURE) {
                handleUserGesture()
            }
        }

        Timber.d("🗺️ Map is ready, creating current location marker")

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
            // state가 null이면 아무것도 하지 않음
            if (state == null) {
                Timber.w("⚠️ Navigation state is null")
                return@observe
            }

            updateNavigationUI(state)

            // 네비게이션 모드 자동 전환
            if (state.isNavigating) {
                startNavigationMode()
            } else {
                stopNavigationMode()
            }

            // 제스처 모드가 아닐 때만 자동 추적 실행
            if (!isGestureMode) {
                // 현재 위치가 있으면 경로와 통합하여 처리
                if (state.isNavigating && isNavigating) {
                    state.currentLocation?.let { currentLocation ->
                        state.currentRoute?.let { route ->
                            if (isMapReady) {
                                try {
                                    // 1. 앞으로 진행할 경로에서 가장 가까운 지점 찾기
                                    val nearestPoint = findClosestPathPointAhead(
                                        currentLocation,
                                        route.path,
                                        currentPathIndex
                                    )
                                    val distanceToPath =
                                        calculateDistance(currentLocation, route.path[nearestPoint])

                                    Timber.d("📍 GPS Location: $currentLocation")
                                    Timber.d("📍 Nearest path point index: $nearestPoint (current: $currentPathIndex), distance: ${distanceToPath}m")

                                    // 2. 경로 이탈 확인 - 70m 이상이면 후보
                                    if (distanceToPath >= REROUTE_THRESHOLD && !isRerouting) {
                                        // 정확도/속도/터널 상태 필터
                                        val acc = lastLocation?.accuracy ?: 0f
                                        val spd = lastLocation?.speed ?: 0f
                                        val accuracyOk = acc in 0f..OFF_ROUTE_MIN_ACCURACY
                                        val speedOk = spd > 0.3f
                                        val tunnelOk = !isInTunnel
                                        val nowMono = SystemClock.elapsedRealtime()
                                        val timeSinceStop = nowMono - lastStoppedElapsedMs
                                        val resumeOk = timeSinceStop > STOP_RESUME_GRACE_MS

                                        if (accuracyOk && speedOk && tunnelOk && resumeOk) {
                                            offRouteConfirmCount += 1
                                            Timber.d("🔎 Off-route candidate: d=${distanceToPath}m, acc=${acc}m, spd=${spd}m/s, hit=${offRouteConfirmCount}, Δstop=${timeSinceStop}ms")
                                        } else {
                                            // 조건 불충족 시 카운터 리셋
                                            offRouteConfirmCount = 0
                                            Timber.d("⏸️ Off-route suppressed: accOk=$accuracyOk, speedOk=$speedOk, tunnelOk=$tunnelOk, resumeOk=$resumeOk (Δstop=${timeSinceStop}ms)")
                                        }

                                        // 연속 N회 확정 + 쿨다운 체크 후 재검색 실행
                                        if (offRouteConfirmCount >= OFF_ROUTE_CONFIRM_COUNT) {
                                            offRouteConfirmCount = 0
                                            val currentTime = System.currentTimeMillis()
                                            if (currentTime - lastRerouteTime > REROUTE_COOLDOWN_MS) {
                                                Timber.d("🔄 Off-route confirmed! Distance: ${distanceToPath}m - Initiating reroute...")
                                                val rerouteFrom = lastKnownLocation ?: currentLocation
                                                requestReroute(rerouteFrom)
                                                lastRerouteTime = currentTime

                                                // 경로 이탈 시에는 실제 GPS 위치에 마커 표시
                                                updateCurrentLocationMarker(rerouteFrom)
                                                followRoute(rerouteFrom)
                                            } else {
                                                Timber.d("⏳ Reroute request skipped (cooldown)")
                                            }
                                        }
                                    } else {
                                        // 3. 70m 이내면 항상 경로 위에 스냅 (팩맨처럼!)
                                        // 재검색 플래그 해제 (경로 복귀)
                                        if (isRerouting) {
                                            isRerouting = false
                                            Timber.d("✅ Returned to route")
                                        }

                                        // 경로 안이면 오프루트 카운터 리셋
                                        offRouteConfirmCount = 0

                                        // 진행 방향 고려하여 인덱스 업데이트
                                        if (nearestPoint >= currentPathIndex) {
                                            val oldIndex = currentPathIndex
                                            currentPathIndex = nearestPoint

                                            if (currentPathIndex > oldIndex) {
                                                Timber.d("📍 Path index progressed: $oldIndex -> $currentPathIndex")
                                                // 지나온 경로 숨기기
                                                updatePassedRoute(route.path, currentPathIndex)
                                            }
                                        }

                                        // 4. 🎮 팩맨 모드: 마커는 항상 경로 위에! (Snap-to-road)
                                        val pathLocation = route.path[currentPathIndex]
                                        updateCurrentLocationMarker(pathLocation)
                                        Timber.d("🎮 Marker snapped to path: $pathLocation (distance from GPS: ${distanceToPath}m)")

                                        // 5. 진행 방향 계산 및 지도 회전 (한 스텝 이전 경로의 방향 사용)
                                        val bearingIndex =
                                            if (currentPathIndex > 0) currentPathIndex - 1 else currentPathIndex
                                        val bearing =
                                            calculateBearingFromPath(route.path, bearingIndex)
                                        if (bearing >= 0) {
                                            followRouteWithBearing(pathLocation, bearing)
                                            updateCurrentLocationMarkerDirection(bearing)
                                        } else {
                                            followRoute(pathLocation)
                                        }

                                        // 6. 도착지 근처 도착 확인 (25미터)
                                        val distanceToDestination = calculateDistance(
                                            pathLocation,
                                            route.summary.endLocation
                                        )
                                        if (distanceToDestination <= ARRIVAL_THRESHOLD) {
                                            Timber.d("✅ Arrived at destination! (${distanceToDestination}m)")
                                            navigationManager.stopNavigation()
                                            Toast.makeText(this, "목적지에 도착했습니다!", Toast.LENGTH_SHORT)
                                                .show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Timber.e("❌ Error in navigation tracking: ${e.message}")
                                    e.printStackTrace()
                                }
                            }
                        }
                    } ?: run {
                        Timber.w("📍 Current location is null")
                        // GPS 끊김 시 추정 항법 시도 (경로가 있을 때만)
                        state.currentRoute?.let { route ->
                            checkAndHandleLocationTimeout(route)
                        }
                    }
                } else if (!isNavigating && state.currentRoute != null) {
                    // 네비게이션 시작 전 초기 위치 표시
                    state.currentRoute?.let { route ->
                        if (isMapReady) {
                            currentPathIndex = 0
                            updateCurrentLocationMarker(route.summary.startLocation)
                            Timber.d("📍 Marker set to start location: ${route.summary.startLocation}")
                        }
                    }
                }
            } else {
                // 제스처 모드에서는 자동 추적 비활성화
                Timber.d("🎯 Gesture mode active - auto tracking disabled")
            }
        }

        // 현재 안내 메시지 관찰 (UI 업데이트만)
        navigationManager.currentInstruction.observe(this) { instruction ->
            instruction?.let {
                updateInstructionUI(it)
            }
        }

        // 음성 안내 트리거 관찰 (음성 재생만)
        navigationManager.shouldPlayVoice.observe(this) { shouldPlay ->
            if (shouldPlay == true) {
                navigationManager.currentInstruction.value?.let { instruction ->
                    if (voiceGuideManager.isReady()) {
                        voiceGuideManager.speakInstruction(instruction)
                        Timber.d("🔊 Voice instruction spoken: ${instruction.message}")
                    } else {
                        Timber.w("🔊 VoiceGuideManager not ready")
                    }
                } ?: run {
                    Timber.w("🔊 Current instruction is null")
                }
            }
        }

        // 안내 시작 알림 관찰 ("경로 안내를 시작합니다" + 첫 안내)
        navigationManager.shouldPlayNavigationStart.observe(this) { shouldPlay ->
            if (shouldPlay == true) {
                navigationManager.currentInstruction.value?.let { instruction ->
                    if (voiceGuideManager.isReady()) {
                        voiceGuideManager.speakNavigationStart(instruction)
                        Timber.d("🔊 Navigation start announcement: 경로 안내를 시작합니다 + ${instruction.message}")
                    } else {
                        Timber.w("🔊 VoiceGuideManager not ready for start announcement")
                    }
                } ?: run {
                    Timber.w("🔊 Current instruction is null for start announcement")
                }
            }
        }

        // 권한 요청 관찰
        navigationManager.permissionRequired.observe(this) { required ->
            if (required) {
                requestLocationPermission()
            }
        }

        // 경로 데이터 관찰
        navigationViewModel.navigationRoute.observe(this) { route ->
            route?.let { newRoute ->
                displayRoute(newRoute)

                val wasRerouting = isRerouting
                val anchorLocation = pendingRerouteLocation
                    ?: navigationManager.navigationState.value?.currentLocation
                    ?: lastKnownLocation

                if (wasRerouting) {
                    isRerouting = false
                    Toast.makeText(this, "경로를 재검색했습니다", Toast.LENGTH_SHORT).show()
                    Timber.d("✅ Reroute completed, new route displayed")

                    val referenceLocation = anchorLocation ?: newRoute.summary.startLocation
                    currentPathIndex = findClosestPathPointAhead(referenceLocation, newRoute.path, 0)
                    val snappedLocation = newRoute.path.getOrElse(currentPathIndex) { newRoute.summary.startLocation }
                    updateCurrentLocationMarker(snappedLocation)
                    val bearing = calculateBearingFromPath(newRoute.path, currentPathIndex)
                    if (bearing > 0) followRouteWithBearing(snappedLocation, bearing) else followRoute(snappedLocation)
                }

                // 속도 기반 카메라 상태 초기화
                cameraSpeedInitialized = false
                lastSpeedMps = 0f

                navigationManager.startNavigation(newRoute)

                if (wasRerouting) {
                    anchorLocation?.let { navigationManager.updateCurrentLocation(it) }
                    navigationManager.currentInstruction.value?.let { inst ->
                        updateInstructionUI(inst)
                        refreshInstructionDistance()
                        if (voiceGuideManager.isReady()) {
                            voiceGuideManager.speakInstruction(inst)
                        }
                    }
                    pendingRerouteLocation = null
                }

                // 최초 시작 시에만 출발지로 마커 초기화 (재탐색 시엔 현재 위치 유지)
                if (!wasRerouting && isMapReady && currentLocationMarker != null) {
                    updateCurrentLocationMarker(newRoute.summary.startLocation)
                    Timber.d("📍 Marker initialized to start location: ${newRoute.summary.startLocation}")
                }

                // 네비게이션 시작 시 즉시 3D 뷰로 전환
                if (isMapReady) {
                    val currentLocation = navigationManager.navigationState.value?.currentLocation
                    if (currentLocation != null) {
                        Timber.d("🗺️ Switching to 3D navigation view with current location")
                        followRoute(currentLocation)
                    } else {
                        // 현재 위치가 없으면 출발지로 시작
                        Timber.d("🗺️ Switching to 3D navigation view with start location")
                        followRoute(route.summary.startLocation)
                    }
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

        // 음성 안내 스위치 (기본값: ON)
        binding.switchVoiceGuide.isChecked = true
        binding.switchVoiceGuide.setOnCheckedChangeListener { _, isChecked ->
            voiceGuideManager.setEnabled(isChecked)
            Timber.d("🔊 Voice guide ${if (isChecked) "enabled" else "disabled"}")
        }

        // 현위치로 버튼 (제스처 모드에서만 표시)
        binding.btnReturnToCurrentLocation.setOnClickListener {
            returnToCurrentLocationMode()
        }
    }

    /**
     * 네비게이션 중지 확인 다이얼로그 표시
     */
    private fun showStopNavigationDialog() {
        AlertDialog.Builder(this)
            .setTitle("안내 종료")
            .setMessage("안내를 종료하시겠어요?")
            .setPositiveButton("확인") { _, _ ->
                // 확인 시 안내 종료 및 액티비티 종료
                stopNavigationAndFinish()
            }
            .setNegativeButton("취소") { dialog, _ ->
                // 취소 시 다이얼로그만 닫기 (안내 계속)
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    /**
     * 네비게이션 종료 및 액티비티 종료
     */
    private fun stopNavigationAndFinish() {
        isNavigating = false
        currentPathIndex = 0
        isGestureMode = false
        navigationManager.stopNavigation()
        navigationViewModel.stopNavigation()
        cameraSpeedInitialized = false
        lastSpeedMps = 0f

        // 액티비티 종료
        finish()
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
            Timber.d("🎯 User gesture detected - entering gesture mode")
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
        navigationManager.navigationState.value?.currentRoute?.let { route ->
            displayRouteWithCongestion(route)
        }

        // 자동 추적 비활성화
        naverMap?.let { map ->
            map.locationTrackingMode = LocationTrackingMode.None
        }

        // UI 업데이트
        updateNavigationUI(navigationManager.navigationState.value ?: NavigationState())

        // 10초 후 자동 복귀 타이머 시작
        startGestureTimeoutTimer()

        Timber.d("🎯 Entered gesture mode - congestion display enabled, auto tracking disabled")
    }

    /**
     * 현재 위치 모드로 복귀
     */
    private fun returnToCurrentLocationMode() {
        Timber.d("🎯 returnToCurrentLocationMode() called")
        Timber.d("🎯 Current state - isGestureMode: $isGestureMode, isNavigating: ${navigationManager.navigationState.value?.isNavigating}, currentLocation: ${navigationManager.navigationState.value?.currentLocation}")

        isGestureMode = false

        // 단색 경로로 복귀
        navigationManager.navigationState.value?.currentRoute?.let { route ->
            displayRoute(route)
            Timber.d("🎯 Route displayed (single color)")
        }

        // 네비게이션 모드 재활성화 (수동 카메라 제어로 복귀)
        // 네비게이션 중이면 자동으로 startNavigationMode()가 호출되므로 여기서는 명시적으로 호출
        if (navigationManager.navigationState.value?.isNavigating == true) {
            startNavigationMode()
            Timber.d("🎯 Navigation mode reactivated")
        }

        // UI 업데이트
        updateNavigationUI(navigationManager.navigationState.value ?: NavigationState())

        // 현재 위치로 카메라 이동 (저장된 줌과 방향 유지)
        val currentLocation = navigationManager.navigationState.value?.currentLocation
        val currentRoute = navigationManager.navigationState.value?.currentRoute

        if (currentLocation != null && naverMap != null) {
            Timber.d("🎯 Moving camera to current location: $currentLocation")

            val bearing = if (lastNavigationBearing > 0) {
                Timber.d("🎯 Using last navigation bearing: $lastNavigationBearing")
                lastNavigationBearing
            } else {
                // 방향이 없으면 경로 기반으로 계산
                if (currentRoute != null && currentPathIndex < currentRoute.path.size - 1) {
                    val pathBearing = calculateBearingFromPath(currentRoute.path, currentPathIndex)
                    Timber.d("🎯 Calculated bearing from path: $pathBearing")
                    pathBearing
                } else {
                    Timber.d("🎯 Using last bearing: $lastBearing")
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

            Timber.d("🎯 Camera moved to current location - zoom=${lastNavigationZoom}, bearing=$bearing°")
        } else {
            // 현재 위치가 없으면 경로의 시작점으로 이동
            if (currentRoute != null && naverMap != null) {
                val startLocation = if (currentPathIndex < currentRoute.path.size) {
                    currentRoute.path[currentPathIndex]
                } else {
                    currentRoute.summary.startLocation
                }

                Timber.w("⚠️ Current location is null, using route location: $startLocation")

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

                Timber.d("🎯 Camera moved to route location: $startLocation")
            } else {
                Timber.e("❌ Cannot return to location - currentLocation: null, currentRoute: ${currentRoute != null}, naverMap: ${naverMap != null}")
                Toast.makeText(this, "현재 위치를 가져올 수 없습니다. GPS를 확인해주세요.", Toast.LENGTH_SHORT).show()
            }
        }

        Timber.d("🎯 Returned to current location mode complete")
    }

    /**
     * 제스처 모드 자동 복귀 타이머
     */
    private fun startGestureTimeoutTimer() {
        // 기존 타이머가 있다면 취소하고 새로 시작
        // 실제 구현에서는 Handler나 Timer를 사용할 수 있지만, 여기서는 간단히 로그만
        Timber.d("🎯 Gesture timeout timer started (${GESTURE_TIMEOUT}ms)")
    }

    private fun checkLocationPermission() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        Timber.d("📍 checkLocationPermission() - hasPermission: $hasPermission")

        if (!hasPermission) {
            Timber.d("📍 Requesting location permission")
            requestLocationPermission()
        } else {
            // 권한이 이미 허용된 경우 위치 업데이트 시작
            Timber.d("📍 Permission already granted, starting location updates")
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
                Timber.d("📍 Location permission granted")
                // 권한이 허용되면 위치 업데이트 시작
                startLocationUpdates()
            } else {
                Timber.w("📍 Location permission denied")
                // 권한이 거부되면 에러 메시지 표시
                binding.tvCurrentInstruction.text = "위치 권한이 필요합니다. 설정에서 권한을 허용해주세요."
            }
        }
    }

    /**
     * 위치 업데이트 시작
     */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdates() {
        Timber.d("📍 startLocationUpdates() using Fused if available")
        // 우선 Fused 사용 시도
        val started = startFusedLocationUpdates()
        if (!started) {
            Timber.w("📍 Fused start failed → fallback to LocationManager")
            startLocationUpdatesLegacy()
        }
    }

    /** FusedLocationProviderClient 기반 업데이트 시작 */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startFusedLocationUpdates(): Boolean {
        return try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .setMinUpdateDistanceMeters(1f)
                .setWaitForAccurateLocation(true)
                .build()

            if (fusedCallback == null) {
                fusedCallback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        val loc = result.lastLocation ?: return
                        val nowMono = SystemClock.elapsedRealtime()
                        val latLng = LatLng(loc.latitude, loc.longitude)

                        // GPS 신호 복구 처리
                        if (isInTunnel) {
                            isInTunnel = false
                            Timber.d("🌞 GPS signal restored (Fused) - exiting tunnel mode")
                            maybeToast("GPS 신호 복구됨")
                        }

                        lastKnownLocation = latLng
                        lastLocationUpdateTime = System.currentTimeMillis()
                        lastFixElapsedMs = SystemClock.elapsedRealtime()
                        if (loc.speed <= 1.0f) {
                            lastStoppedElapsedMs = nowMono
                        }
                        lastSpeedMps = smoothCameraSpeed(loc.speed)

                        val stableBearing = navigationManager.calculateStableBearing(loc)
                        if (stableBearing > 0f) {
                            lastBearing = stableBearing
                            Timber.d("🧭 Stable bearing updated: ${stableBearing}° (speed: ${loc.speed}m/s)")
                        } else if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.0f && loc.bearingAccuracyDegrees <= 90f) {
                            lastBearing = loc.bearing
                            Timber.d("🧭 Fallback GPS bearing used: ${loc.bearing}°")
                        }
                        lastLocation = loc
                        updateCurrentLocation(latLng)
                        maybeSendTelemetry(loc)
                        Timber.d("📍 Fused location: $latLng, bearing=${loc.bearing}°, speed=${loc.speed}m/s acc=${loc.accuracy}m")
                    }
                }
            }

            fusedClient.requestLocationUpdates(request, fusedCallback as LocationCallback, mainLooper)
            isUsingFused = true
            // 마지막 알려진 위치 즉시 반영
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                loc?.let {
                    val latLng = LatLng(it.latitude, it.longitude)
                    lastKnownLocation = latLng
                    lastLocationUpdateTime = System.currentTimeMillis()
                    lastFixElapsedMs = SystemClock.elapsedRealtime()
                    updateCurrentLocation(latLng)
                    Timber.d("📍 Fused last known location: $latLng")
                }
            }
            Timber.d("📍 Fused location updates started")
            true
        } catch (e: SecurityException) {
            Timber.e("📍 Fused permission error: ${e.message}")
            false
        } catch (e: Exception) {
            Timber.e("📍 Error starting fused updates: ${e.message}")
            false
        }
    }

    /** 기존 LocationManager 기반 업데이트 (폴백) */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdatesLegacy() {
        Timber.d("📍 startLocationUpdates() called")

        try {
            val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

            // GPS가 활성화되어 있는지 확인
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled =
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            Timber.d("📍 GPS enabled: $isGpsEnabled, Network enabled: $isNetworkEnabled")

            if (!isGpsEnabled && !isNetworkEnabled) {
                Timber.w("📍 Both GPS and Network are disabled")
                binding.tvCurrentInstruction.text = "위치 서비스가 비활성화되어 있습니다. 설정에서 GPS를 켜주세요."
                return
            }

            // GPS 우선, 없으면 네트워크 사용
            val provider = if (isGpsEnabled) {
                LocationManager.GPS_PROVIDER
            } else {
                LocationManager.NETWORK_PROVIDER
            }

            Timber.d("📍 Using location provider: $provider")

            // 위치 업데이트 요청
            locationManager.requestLocationUpdates(
                provider,
                1000L, // 1초마다 업데이트
                1f,    // 1미터 이동시 업데이트
                locationListener
            )

            Timber.d("📍 Location updates requested from provider: $provider")

            // 마지막 알려진 위치로 즉시 업데이트
            val lastKnownLocation = locationManager.getLastKnownLocation(provider)
            if (lastKnownLocation != null) {
                val latLng = LatLng(lastKnownLocation.latitude, lastKnownLocation.longitude)
                this.lastKnownLocation = latLng
                lastLocationUpdateTime = System.currentTimeMillis()
                lastFixElapsedMs = SystemClock.elapsedRealtime()
                updateCurrentLocation(latLng)
                Timber.d("📍 Using last known location: $latLng")
            } else {
                Timber.w("📍 No last known location available")
                // 초기 시간 설정
                lastLocationUpdateTime = System.currentTimeMillis()
                lastFixElapsedMs = SystemClock.elapsedRealtime()
            }

            Timber.d("📍 Legacy LocationManager updates started successfully")
        } catch (e: SecurityException) {
            Timber.e("📍 Location permission not granted: ${e.message}")
            binding.tvCurrentInstruction.text = "위치 권한이 필요합니다."
        } catch (e: Exception) {
            Timber.e("📍 Error starting location updates: ${e.message}")
            binding.tvCurrentInstruction.text = "위치 업데이트 중 오류가 발생했습니다."
        }
    }

    /**
     * 위치 리스너
     */
    private var lastLocation: Location? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            try {
                val nowMono = SystemClock.elapsedRealtime()
                val latLng = LatLng(location.latitude, location.longitude)

                // GPS 신호 복구 확인
                if (isInTunnel) {
                    isInTunnel = false
                    Timber.d("🌞 GPS signal restored - exiting tunnel mode")
                    Toast.makeText(this@NavigationActivity, "GPS 신호 복구됨", Toast.LENGTH_SHORT).show()
                }

                // 마지막 알려진 위치 및 시간 업데이트
                lastKnownLocation = latLng
                lastLocationUpdateTime = System.currentTimeMillis()
                lastFixElapsedMs = SystemClock.elapsedRealtime()
                if (location.speed <= 1.0f) {
                    lastStoppedElapsedMs = nowMono
                }

                // GPS bearing을 사용하여 방향 업데이트 (실제 이동 방향 반영)
                val stableBearing = navigationManager.calculateStableBearing(location)
                if (stableBearing > 0f) {
                    lastBearing = stableBearing
                    Timber.d("🧭 Stable bearing updated (legacy): ${stableBearing}° (speed: ${location.speed}m/s)")
                } else if (location.hasBearing() && location.hasSpeed() && location.speed > 1.0f) {
                    // 속도가 1m/s 이상일 때만 GPS bearing 사용 (정지 시 방향 변경 방지)
                    lastBearing = location.bearing
                    Timber.d("🧭 GPS bearing fallback: ${location.bearing}° (speed: ${location.speed}m/s)")
                }
                lastSpeedMps = smoothCameraSpeed(location.speed)

                lastLocation = location
                updateCurrentLocation(latLng)
                maybeSendTelemetry(location)
                Timber.d("📍 Location updated: $latLng, bearing: ${location.bearing}°, speed: ${location.speed}m/s")
            } catch (e: Exception) {
                Timber.e("❌ Error in locationListener: ${e.message}")
                e.printStackTrace()
            }
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            Timber.d("📍 Location status changed: $provider, status: $status")
        }

        override fun onProviderEnabled(provider: String) {
            Timber.d("📍 Location provider enabled: $provider")
        }

        override fun onProviderDisabled(provider: String) {
            Timber.w("📍 Location provider disabled: $provider")
        }
    }

    /**
     * 현재 위치 업데이트
     */
    private fun updateCurrentLocation(latLng: LatLng) {
        try {
            // NavigationManager에 현재 위치 업데이트
            navigationManager.updateCurrentLocation(latLng)

            // 마커 업데이트는 setupObservers에서 처리 (팩맨 모드)
            // 여기서는 마커를 업데이트하지 않음!

            // 네비게이션 중이고 제스처 모드가 아닐 때는 setupObservers에서 처리
            // 네비게이션 중이 아니거나 제스처 모드일 때만 여기서 처리
            if (navigationManager.navigationState.value?.isNavigating != true || isGestureMode) {
                updateCurrentLocationMarker(latLng)
                if (!isGestureMode) {
                    followRoute(latLng)
                }
            }

            // 안내까지 남은 거리 실시간 갱신
            refreshInstructionDistance()
        } catch (e: Exception) {
            Timber.e("❌ Error updating current location: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * GPS 끊김 감지 및 추정 항법 처리 (터널/지하차도)
     */
    private fun checkAndHandleLocationTimeout(route: NavigationRoute) {
        // 모노토닉 시간으로 GPS 끊김 판단
        val now = SystemClock.elapsedRealtime()
        val timeSinceLastFix = now - lastFixElapsedMs

        // 경로/상태 방어
        val path = route.path
        if (path.isNullOrEmpty() || path.size == 1) return
        val safeStartIdx = currentPathIndex.coerceIn(0, path.size - 1)
        lastKnownLocation ?: return

        // 터널 모드 진입 (히스테리시스)
        if (!isInTunnel && timeSinceLastFix >= TUNNEL_ENTER_MS) {
            isInTunnel = true
            maybeToast("터널 구간 진입 - 추정 항법 사용")
            Timber.w("🚇 GPS lost → tunnel mode ON (${timeSinceLastFix}ms)")
        }
        if (!isInTunnel) return

        // 속도 추정: 마지막 GPS 속도 또는 추정값 → EMA → 클램프
        val baseSpeedMps = lastLocation?.takeIf { it.hasSpeed() }?.speed
            ?: (TUNNEL_SPEED_ESTIMATE / 3.6f)
        val speedEma = smoothSpeed(baseSpeedMps)
        val speedMps = speedEma.coerceIn(SPEED_MIN_MPS, SPEED_MAX_MPS)

        // 경과 시간(초)와 이동 거리(남은 경로로 상한)
        val elapsedSec = timeSinceLastFix / 1000f
        val rawDistance = speedMps * elapsedSec
        val remaining = remainingDistanceOnPath(path, safeStartIdx)
        val estimatedDistance = rawDistance.coerceAtMost(remaining)

        Timber.d("🚇 DR: v=%.2f m/s (ema), t=%.1f s, d=%.1f m (cap=%.1f m)"
            .format(speedMps, elapsedSec, estimatedDistance, remaining))

        // 선분 보간으로 추정 위치 계산
        val (estIndex, estPos) = advanceAlongPath(safeStartIdx, path, estimatedDistance)

        // 마커/카메라 업데이트
        updateCurrentLocationMarker(estPos)

        val bearing = runCatching { calculateBearingFromPath(path, estIndex) }
            .getOrNull()
            ?.takeIf { it > 0 }
            ?: lastBearing

        if (bearing > 0f) {
            followRouteWithBearing(estPos, bearing)
        } else {
            followRoute(estPos)
        }

        Timber.d("🚇 Using estimated location: $estPos (index: $estIndex)")

        // 추정 위치 기반으로도 남은 거리 갱신되도록 마지막 위치 갱신 및 UI 갱신
        lastKnownLocation = estPos
        refreshInstructionDistance()
    }

    /**
     * 추정 거리를 기반으로 경로상의 인덱스 계산
     */
    private fun findEstimatedPathIndex(
        startIndex: Int,
        path: List<LatLng>,
        distanceMeters: Float
    ): Int {
        if (startIndex >= path.size - 1) return startIndex

        var accumulatedDistance = 0f
        var currentIndex = startIndex

        while (currentIndex < path.size - 1 && accumulatedDistance < distanceMeters) {
            val segmentDistance = calculateDistance(path[currentIndex], path[currentIndex + 1])
            accumulatedDistance += segmentDistance

            if (accumulatedDistance >= distanceMeters) {
                // 목표 거리에 도달
                return currentIndex + 1
            }

            currentIndex++
        }

        return minOf(currentIndex, path.size - 1)
    }

    /** 경로의 남은 거리(m) 계산 */
    private fun remainingDistanceOnPath(path: List<LatLng>, startIndex: Int): Float {
        if (path.size < 2 || startIndex >= path.lastIndex) return 0f
        var sum = 0f
        for (i in startIndex until path.lastIndex) {
            sum += calculateDistance(path[i], path[i + 1])
        }
        return sum
    }

    data class PathAdvanceResult(val index: Int, val position: LatLng)

    /** startIndex에서 distanceMeters만큼 경로를 전진한 위치(선분 보간 포함) */
    private fun advanceAlongPath(
        startIndex: Int,
        path: List<LatLng>,
        distanceMeters: Float
    ): PathAdvanceResult {
        if (startIndex >= path.lastIndex) return PathAdvanceResult(path.lastIndex, path.last())
        var distLeft = distanceMeters
        var idx = startIndex
        while (idx < path.lastIndex) {
            val a = path[idx]
            val b = path[idx + 1]
            val seg = calculateDistance(a, b)
            if (seg >= distLeft) {
                val t = if (seg > 0f) distLeft / seg else 0f
                val lat = a.latitude + (b.latitude - a.latitude) * t
                val lng = a.longitude + (b.longitude - a.longitude) * t
                return PathAdvanceResult(idx, LatLng(lat, lng))
            }
            distLeft -= seg
            idx++
        }
        return PathAdvanceResult(path.lastIndex, path.last())
    }

    /** 속도 EMA 계산 */
    private fun smoothSpeed(base: Float): Float {
        val ema = lastSpeedEma?.let { it + SPEED_EMA_ALPHA * (base - it) } ?: base
        lastSpeedEma = ema
        return ema
    }

    /** 카메라용 속도 EMA 계산 */
    private fun smoothCameraSpeed(rawSpeed: Float): Float {
        if (!rawSpeed.isFinite()) {
            return lastSpeedMps
        }
        val clamped = rawSpeed.coerceIn(0f, SPEED_MAX_MPS)
        lastSpeedMps = if (!cameraSpeedInitialized) {
            cameraSpeedInitialized = true
            clamped
        } else {
            lastSpeedMps + SPEED_EMA_ALPHA_CAMERA * (clamped - lastSpeedMps)
        }
        return lastSpeedMps
    }

    /** 중복 토스트 방지 */
    private fun maybeToast(msg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastToastElapsedMs >= TOAST_COOLDOWN_MS) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            lastToastElapsedMs = now
        }
    }

    private fun displayRoute(route: NavigationRoute) {
        val nMap = naverMap ?: return

        // 기존 오버레이 제거
        pathOverlays.forEach { it.map = null }
        pathOverlays.clear()
//        startMarker?.map = null
        endMarker?.map = null

        // NavigationActivity에서는 단색으로 경로 표시 (혼잡도 구분 없이)
        pathOverlays.add(PathOverlay().apply {
            coords = route.path
            color = Color.BLUE
            patternImage = OverlayImage.fromResource(R.drawable.path_pattern)
            patternInterval = 85
            outlineColor = Color.WHITE
            width = 40
            map = nMap
        })

        // 출발지 마커
//        startMarker = Marker().apply {
//            position = route.summary.startLocation
//            captionText = "출발지"
//            map = nMap
//        }

        // 도착지 마커
        endMarker = Marker().apply {
            position = route.summary.endLocation
            captionText = "도착지"
            map = nMap
        }

//        // 지도 범위 조정 (전체 경로 포인트 포함)
//        val bounds = LatLngBounds.Builder()
//            // 출발지와 도착지 포함
//            .include(route.summary.startLocation)
//            .include(route.summary.endLocation)
//            // 전체 경로의 모든 포인트 포함
//            .apply {
//                route.path.forEach { point ->
//                    include(point)
//                }
//            }
//            .build()
//
//        // 패딩을 좀 더 크게 설정하여 경로가 잘리지 않도록 함
//        nMap.moveCamera(CameraUpdate.fitBounds(bounds, 1000))

        Timber.d("🗺️ Route displayed with ${route.path.size} points (single color)")
    }

    /**
     * 혼잡도별 색상으로 경로 표시 (제스처 모드에서 사용)
     */
    private fun displayRouteWithCongestion(route: NavigationRoute) {
        val nMap = naverMap ?: return

        // 기존 오버레이 제거
        pathOverlays.forEach { it.map = null }
        pathOverlays.clear()

        // 혼잡도에 따라 경로를 구간별로 나눠서 표시 (끊어지지 않도록 연결)
        if (route.sections.isNotEmpty()) {
            val groupedPaths = mutableListOf<Pair<List<LatLng>, Int>>()

            // sections를 pointIndex 기준으로 정렬
            val sortedSections = route.sections.sortedBy { it.pointIndex }

            var currentCongestion: Int? = null
            var currentPathGroup = mutableListOf<LatLng>()
            var lastEndIndex = 0

            sortedSections.forEachIndexed { index, section ->
                val startIndex = section.pointIndex
                val endIndex = minOf(startIndex + section.pointCount, route.path.size)

                // 첫 섹션 이전의 경로 처리 (0부터 첫 섹션까지)
                if (index == 0 && startIndex > 0) {
                    val beforePath = route.path.subList(0, startIndex)
                    if (beforePath.isNotEmpty() && beforePath.size >= 2) {
                        // 첫 섹션과 같은 혼잡도로 처리하거나 기본값 사용
                        val firstCongestion = section.congestion
                        groupedPaths.add(Pair(beforePath, firstCongestion))
                        Timber.d("📍 Added pre-section path: 0-$startIndex, congestion=$firstCongestion")
                    }
                }

                // 섹션 사이의 빈 구간 처리
                if (startIndex > lastEndIndex) {
                    val gapPath = route.path.subList(lastEndIndex, startIndex)
                    if (gapPath.isNotEmpty() && gapPath.size >= 2) {
                        // 이전 섹션의 혼잡도를 이어받거나 새로운 섹션의 혼잡도 사용
                        val gapCongestion = currentCongestion ?: section.congestion
                        if (gapCongestion == section.congestion && currentPathGroup.isNotEmpty()) {
                            // 같은 혼잡도면 현재 그룹에 추가
                            currentPathGroup.addAll(gapPath)
                        } else {
                            // 다른 혼잡도면 별도로 저장
                            if (currentPathGroup.size >= 2 && currentCongestion != null) {
                                groupedPaths.add(Pair(currentPathGroup.toList(), currentCongestion))
                            }
                            currentPathGroup = gapPath.toMutableList()
                            currentCongestion = gapCongestion
                            groupedPaths.add(Pair(gapPath, gapCongestion))
                            Timber.d("📍 Added gap path: $lastEndIndex-$startIndex, congestion=$gapCongestion")
                            currentPathGroup.clear()
                            currentCongestion = null
                        }
                    }
                }

                // 현재 섹션의 경로 처리
                val sectionPath = route.path.subList(startIndex, endIndex)

                if (section.congestion == currentCongestion) {
                    // 같은 혼잡도면 현재 그룹에 추가
                    currentPathGroup.addAll(sectionPath)
                } else {
                    // 다른 혼잡도면 현재 그룹을 저장하고 새 그룹 시작
                    if (currentPathGroup.size >= 2 && currentCongestion != null) {
                        groupedPaths.add(Pair(currentPathGroup.toList(), currentCongestion))
                    }
                    currentPathGroup = sectionPath.toMutableList()
                    currentCongestion = section.congestion
                }

                lastEndIndex = endIndex
                Timber.d("📍 Section: ${section.name}, pointIndex=$startIndex-$endIndex, congestion=${section.congestion}")
            }

            // 마지막 그룹 저장
            if (currentPathGroup.size >= 2 && currentCongestion != null) {
                groupedPaths.add(Pair(currentPathGroup, currentCongestion))
            }

            // 마지막 섹션 이후의 남은 경로 처리
            if (lastEndIndex < route.path.size) {
                val remainingPath = route.path.subList(lastEndIndex, route.path.size)
                if (remainingPath.isNotEmpty() && remainingPath.size >= 2) {
                    // 마지막 섹션의 혼잡도를 이어받음
                    val lastCongestion =
                        currentCongestion ?: sortedSections.lastOrNull()?.congestion ?: 0
                    groupedPaths.add(Pair(remainingPath, lastCongestion))
                    Timber.d("📍 Added post-section path: $lastEndIndex-${route.path.size}, congestion=$lastCongestion")
                }
            }

            // 그룹화된 경로들을 PathOverlay로 표시
            groupedPaths.forEach { (path, congestion) ->
                val pathOverlay = PathOverlay().apply {
                    coords = path
                    color = getCongestionColor(congestion)
                    outlineColor = 0xFFFFFFFF.toInt() // 흰색 테두리
                    width = 20
                    map = nMap
                }
                pathOverlays.add(pathOverlay)
            }

            Timber.d("🗺️ Total segments: ${groupedPaths.size}, Total points: ${route.path.size}")
        } else {
            // sections가 없으면 전체 경로를 하나로 표시
            val pathOverlay = PathOverlay().apply {
                coords = route.path
                color = 0xFF00AA00.toInt() // 기본 녹색
                outlineColor = 0xFFFFFFFF.toInt()
                width = 20
                map = nMap
            }
            pathOverlays.add(pathOverlay)
        }

        // 출발지 마커
//        startMarker = Marker().apply {
//            position = route.summary.startLocation
//            captionText = "출발지"
//            map = nMap
//        }

        // 도착지 마커
        endMarker = Marker().apply {
            position = route.summary.endLocation
            captionText = "도착지"
            map = nMap
        }

//        // 지도 범위 조정
//        val bounds = LatLngBounds.Builder()
//            .include(route.summary.startLocation)
//            .include(route.summary.endLocation)
//            .build()
//
//        nMap.moveCamera(CameraUpdate.fitBounds(bounds, 100))

        Timber.d("🗺️ Route displayed with ${route.path.size} points, ${pathOverlays.size} segments by congestion")
    }

    /**
     * 혼잡도에 따른 색상 반환
     * @param congestion 0: 값없음(회색), 1: 원활(녹색), 2: 서행(주황색), 3: 혼잡(빨간색)
     * @return 색상 (ARGB)
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

    private fun updateNavigationUI(state: NavigationState) {
        // 네비게이션 중이면 중지 버튼만 표시 (시작 버튼은 없음 - 자동 시작)
        binding.btnStopNavigation.visibility = if (state.isNavigating) View.VISIBLE else View.GONE

        // 현위치로 버튼은 제스처 모드에서만 표시
        binding.btnReturnToCurrentLocation.visibility =
            if (isGestureMode) View.VISIBLE else View.GONE

        // 진행률 업데이트
        binding.progressNavigation.progress = (state.progress * 100).toInt()

        // 남은 거리 및 시간 업데이트
        val distanceKm = state.remainingDistance / 1000f

        // 남은 시간 계산 - API의 duration(밀리초)을 기반으로 진행률 적용
        val remainingTimeMinutes = state.currentRoute?.let { route ->
            val totalDurationMs = route.summary.totalDuration // 밀리초 단위
            val progress = state.progress

            // 남은 시간 = 총 시간 * (1 - 진행률)
            val remainingMs = (totalDurationMs * (1.0 - progress)).toInt()
            remainingMs / 1000 / 60 // 밀리초 → 초 → 분
        } ?: if (distanceKm > 0) {
            // 경로 정보가 없으면 거리 기반 계산 (시속 40km로 가정)
            (distanceKm / 40f * 60f).toInt()
        } else {
            0
        }

        // 디버깅 로그
        Timber.d("📊 UI Update:")
        Timber.d(
            "   Remaining Distance: ${state.remainingDistance}m (${
                String.format(
                    "%.1f",
                    distanceKm
                )
            }km)"
        )
        Timber.d("   Remaining Time: ${remainingTimeMinutes}분")
        Timber.d("   Progress: ${(state.progress * 100).toInt()}%")
        Timber.d("   Current Location: ${state.currentLocation}")

        // 시간 표시 개선 (1시간 이상일 때 "X시간 Y분"으로 표시)
        val timeString = if (remainingTimeMinutes >= 60) {
            val hours = remainingTimeMinutes / 60
            val mins = remainingTimeMinutes % 60
            if (mins > 0) "${hours}시간 ${mins}분" else "${hours}시간"
        } else {
            "${remainingTimeMinutes}분"
        }

        binding.tvRemainingDistance.text = "남은 거리: ${String.format("%.1f", distanceKm)}km"
        binding.tvRemainingTime.text = "남은 시간: ${timeString}"

        // 현재 경로가 있으면 지도에 표시
        state.currentRoute?.let { route ->
            if (naverMap != null && pathOverlays.isEmpty()) {
                displayRoute(route)
            }
        }

        // 마커와 카메라 업데이트는 setupObservers에서 처리 (팩맨 모드)
        // 여기서는 UI 정보만 업데이트
    }

    private fun updateInstructionUI(instruction: Instruction) {
        // ---- Recompute remaining distance to next maneuver from CURRENT position ----
        val navState = navigationManager.navigationState.value
        val routeForDist = navState?.currentRoute
        val currentPosForDist = navState?.currentLocation ?: lastKnownLocation
        val targetIdxForDist = instruction.pointIndex  // Instruction가 다음 기점의 path 인덱스를 제공한다고 가정
        val safeCurrentIdxForDist = currentPathIndex

        val distanceToInstruction: Int = if (
            routeForDist != null &&
            currentPosForDist != null &&
            targetIdxForDist != null
        ) {
            distanceToPathIndex(
                path = routeForDist.path,
                currentIndex = safeCurrentIdxForDist,
                currentPosition = currentPosForDist,
                targetIndex = targetIdxForDist
            ).toInt()
        } else {
            // 폴백: 기존 값 사용
            instruction.distanceToInstruction
        }

        // API 메시지에서 거리 정보 제거 (예: "500미터 후", "1킬로미터 후" 등)
        val cleanMessage = instruction.message
            .replace(Regex("\\d+\\s*킬로미터\\s*(후|전방|앞)\\s*"), "")
            .replace(Regex("\\d+\\s*미터\\s*(후|전방|앞)\\s*"), "")
            .replace(Regex("\\d+\\.?\\d*\\s*km\\s*(후|전방|앞)\\s*"), "")
            .replace(Regex("\\d+\\s*m\\s*(후|전방|앞)\\s*"), "")
            .trim()

        // 실시간 거리 정보와 함께 메시지 표시
        val messageWithDistance = if (distanceToInstruction > 0) {
            if (distanceToInstruction >= 1000) {
                val km = distanceToInstruction / 1000.0
                "[${String.format("%.1f", km)}km] $cleanMessage"
            } else {
                "[${distanceToInstruction}m] $cleanMessage"
            }
        } else {
            cleanMessage
        }

        binding.tvCurrentInstruction.text = messageWithDistance

        // 다음 위치 갱신 시 재계산을 위해 상태 저장
        lastInstructionCleanMessage = cleanMessage
        lastInstructionTargetIndex = targetIdxForDist

        // 다음 안내 메시지 표시 (간단한 예시)
        val baseNextDist = if (instruction.distance > 0) instruction.distance else distanceToInstruction
        val nextMessage = if (baseNextDist > 1000) {
            "앞으로 ${baseNextDist / 1000}km 직진하세요"
        } else {
            "앞으로 ${baseNextDist}m 직진하세요"
        }
        binding.tvNextInstruction.text = nextMessage
    }

    /**
     * 현재 진행 중인 다음 기점까지 남은 거리를 실시간으로 재계산하여 표시
     */
    private fun refreshInstructionDistance() {
        val instruction = navigationManager.currentInstruction.value ?: return
        val route = navigationManager.navigationState.value?.currentRoute ?: return

        val currentPos = navigationManager.navigationState.value?.currentLocation
            ?: lastKnownLocation ?: return

        val targetIdx = instruction.pointIndex ?: lastInstructionTargetIndex ?: return
        val cleanMessage = lastInstructionCleanMessage ?: run {
            // fallback: 필요 시 즉석에서 클린 처리
            instruction.message
                .replace(Regex("\\d+\\s*킬로미터\\s*(후|전방|앞)\\s*"), "")
                .replace(Regex("\\d+\\s*미터\\s*(후|전방|앞)\\s*"), "")
                .replace(Regex("\\d+\\.?\\d*\\s*km\\s*(후|전방|앞)\\s*"), "")
                .replace(Regex("\\d+\\s*m\\s*(후|전방|앞)\\s*"), "")
                .trim()
        }

        val distance = distanceToPathIndex(
            path = route.path,
            currentIndex = currentPathIndex,
            currentPosition = currentPos,
            targetIndex = targetIdx
        ).toInt()

        val messageWithDistance = if (distance > 0) {
            if (distance >= 1000) {
                val km = distance / 1000.0
                "[${String.format("%.1f", km)}km] $cleanMessage"
            } else {
                "[${distance}m] $cleanMessage"
            }
        } else {
            cleanMessage
        }

        binding.tvCurrentInstruction.text = messageWithDistance
    }

    /**
     * 현재 위치 마커 생성
     */
    private fun createCurrentLocationMarker() {
        val map = naverMap ?: run {
            Timber.w("📍 NaverMap is null, cannot create marker")
            return
        }

        currentLocationMarker = Marker().apply {
            icon = OverlayImage.fromResource(R.drawable.a)
            // 위치는 updateCurrentLocationMarker에서 설정되므로 여기서는 임시 위치만 설정
            // 실제 위치는 네비게이션이 시작되면 업데이트됨
            position = LatLng(37.5665, 126.9780)
            this.map = map
            zIndex = 10000 // 다른 마커들보다 위에 표시
            width = 150
            height = 150
        }
        Timber.d("📍 Current location marker created at: ${currentLocationMarker?.position}")
        Timber.d("📍 Marker map: ${currentLocationMarker?.map}, visible: ${currentLocationMarker?.map != null}")
    }

    /**
     * 현재 위치 마커 업데이트
     */
    private fun updateCurrentLocationMarker(location: LatLng) {
        if (currentLocationMarker == null) {
            Timber.w("📍 Current location marker is null, creating new one")
            createCurrentLocationMarker()
        }

        currentLocationMarker?.let { marker ->
            val oldPosition = marker.position
            marker.position = location
            // 마커가 지도에 표시되도록 보장
            val map = naverMap
            marker.map = map
            // 마커가 항상 보이도록 zIndex 업데이트
            marker.zIndex = 10000

            Timber.d("📍 Current location marker updated:")
            Timber.d("   Old position: $oldPosition")
            Timber.d("   New position: $location")
            Timber.d("   Marker position: ${marker.position}")
            Timber.d("   Marker map: ${marker.map}")
            Timber.d("   Marker zIndex: ${marker.zIndex}")
            Timber.d("   Marker visible: ${marker.map != null}")
        } ?: run {
            Timber.e("📍 Failed to update current location marker - marker is null")
        }
    }

    /**
     * 현재 위치 마커의 방향 업데이트
     * 지도가 회전하므로 마커는 회전하지 않음 (마커는 항상 위쪽을 향함)
     */
    private fun updateCurrentLocationMarkerDirection(bearing: Float) {
        // 마커는 회전하지 않고, 지도만 회전함
        // 마커의 angle은 0도로 유지 (항상 위쪽 향함)
        currentLocationMarker?.let { marker ->
            marker.angle = 0f
            Timber.d("🧭 Marker angle set to 0 (map will rotate instead)")
        }
    }

    /**
     * 방향각을 방향 텍스트로 변환
     */
    private fun getDirectionText(bearing: Float): String {
        return when {
            bearing >= 337.5f || bearing < 22.5f -> "북"
            bearing >= 22.5f && bearing < 67.5f -> "북동"
            bearing >= 67.5f && bearing < 112.5f -> "동"
            bearing >= 112.5f && bearing < 157.5f -> "남동"
            bearing >= 157.5f && bearing < 202.5f -> "남"
            bearing >= 202.5f && bearing < 247.5f -> "남서"
            bearing >= 247.5f && bearing < 292.5f -> "서"
            bearing >= 292.5f && bearing < 337.5f -> "북서"
            else -> "알 수 없음"
        }
    }

    /**
     * 지도를 현재 위치로 이동
     */
    private fun moveMapToCurrentLocation(location: LatLng) {
        naverMap?.let { map ->
            val cameraUpdate = CameraUpdate.scrollTo(location)
            map.moveCamera(cameraUpdate)
            Timber.d("🗺️ Map moved to current location: $location")
        }
    }

    /** 속도 및 다음 분기 거리 기반 카메라 파라미터 계산 */
    private fun getAdaptiveCameraParams(): Pair<Double, Double> {
        val nextDistance = navigationManager.currentInstruction.value?.distanceToInstruction ?: Int.MAX_VALUE
        if (nextDistance in 0..120) {
            return ZOOM_LOW_SPEED to DEFAULT_TILT
        }

        val speed = lastSpeedMps.coerceIn(0f, SPEED_MAX_MPS)
        return when {
            speed >= SPEED_THRESHOLD_FAST -> ZOOM_HIGH_SPEED to HIGH_SPEED_TILT
            speed <= SPEED_THRESHOLD_SLOW -> ZOOM_LOW_SPEED to DEFAULT_TILT
            else -> ZOOM_DEFAULT to DEFAULT_TILT
        }
    }

    private fun resolveZoom(target: Double): Double {
        return if (abs(lastNavigationZoom - target) > CAMERA_ZOOM_EPS) target else lastNavigationZoom
    }

    private fun resolveTilt(target: Double): Double {
        return if (abs(lastNavigationTilt - target) > CAMERA_TILT_EPS) target else lastNavigationTilt
    }

    /**
     * 지도를 경로에 맞게 자동 추적 (현재 위치를 중앙에 배치, 3D 뷰)
     * GPS bearing을 사용하여 실제 이동 방향 반영
     */
    private fun followRoute(location: LatLng) {
        naverMap?.let { map ->
            // GPS bearing 사용 (이미 locationListener에서 업데이트됨)
            var bearing = lastBearing

            // bearing이 없으면 경로 기반으로 초기 방향 설정
            if (bearing <= 0) {
                val route = navigationManager.navigationState.value?.currentRoute
                if (route != null && route.path.size >= 2) {
                    // 현재 경로 인덱스 기반으로 방향 계산
                    bearing = calculateBearingFromPath(route.path, currentPathIndex)
                    if (bearing > 0) {
                        lastBearing = bearing
                        Timber.d("🧭 Using route bearing: $bearing°")
                    }
                }
            }

            // 네비게이션 뷰 설정
            if (bearing > 0) {
                val (targetZoom, targetTilt) = getAdaptiveCameraParams()
                val resolvedZoom = resolveZoom(targetZoom)
                val resolvedTilt = resolveTilt(targetTilt)
                lastNavigationZoom = resolvedZoom
                lastNavigationTilt = resolvedTilt
                lastNavigationBearing = bearing

                // 현재 위치를 중심으로 한 카메라 설정
                val cameraPosition = CameraPosition(
                    location,            // 카메라 타겟 (현재 위치를 중앙에)
                    resolvedZoom,        // 줌 레벨
                    resolvedTilt,        // 기울기
                    bearing.toDouble()   // GPS bearing (실제 이동 방향)
                )

                val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
                    .animate(CameraAnimation.Easing, 200)
                map.moveCamera(cameraUpdate)

                Timber.d("🗺️ Navigation view: location=$location, GPS bearing=$bearing°, zoom=$lastNavigationZoom")
            } else {
                // 기본 뷰 (bearing 없을 때)
                val (targetZoom, targetTilt) = getAdaptiveCameraParams()
                val resolvedZoom = resolveZoom(targetZoom)
                val resolvedTilt = resolveTilt(targetTilt)
                lastNavigationZoom = resolvedZoom
                lastNavigationTilt = resolvedTilt
                val cameraPosition = CameraPosition(
                    location,
                    resolvedZoom,
                    resolvedTilt,
                    0.0
                )
                val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
                    .animate(CameraAnimation.Easing, 200)
                map.moveCamera(cameraUpdate)
                Timber.d("🗺️ Navigation view (default): location=$location, no bearing")
            }
        }
    }

    /**
     * 지정된 bearing으로 지도 회전 (한 스텝 이전 경로의 방향 사용)
     */
    private fun followRouteWithBearing(location: LatLng, bearing: Float) {
        naverMap?.let { map ->
            // 부드러운 회전을 위한 보간
            val diff = if (lastBearing > 0) shortestAngleDiff(lastBearing, bearing) else 0f

            val absDiff = abs(diff)
            val smoothedBearing = if (absDiff > 45f) {
                // 급격한 변화는 제한 (최대 45도씩만) - 기존보다 빠르게 추종
                normalizeBearing(lastBearing + if (diff > 0) 45f else -45f)
            } else if (absDiff > 0.5f) {
                // 보간 비율 상향(85%)으로 응답 속도 개선
                normalizeBearing(lastBearing + diff * 0.85f)
            } else {
                // 변화량이 작으면 이전 베어링 유지
                lastBearing
            }

            if (smoothedBearing > 0) {
                lastBearing = smoothedBearing

                val (targetZoom, targetTilt) = getAdaptiveCameraParams()
                val resolvedZoom = resolveZoom(targetZoom)
                val resolvedTilt = resolveTilt(targetTilt)
                lastNavigationZoom = resolvedZoom
                lastNavigationTilt = resolvedTilt
                lastNavigationBearing = smoothedBearing

                // 현재 위치를 중심으로 한 카메라 설정
                val cameraPosition = CameraPosition(
                    location,
                    resolvedZoom,
                    resolvedTilt,
                    smoothedBearing.toDouble()
                )

                val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
                    .animate(CameraAnimation.Easing, 200)
                map.moveCamera(cameraUpdate)

                Timber.d("🗺️ Navigation view (lagged bearing): location=$location, bearing=$smoothedBearing° (target=$bearing°)")
            }
        }
    }

    /**
     * 경로에서 현재 위치에 가장 가까운 포인트 찾기 (오차 범위 고려)
     * @return Pair<가장 가까운 인덱스, 거리(미터)>, 경로 이탈 시 null
     */
    private fun findNearestPathPoint(
        currentLocation: LatLng,
        path: List<LatLng>,
        startIndex: Int = 0
    ): Pair<Int, Float>? {
        var minDistance = Float.MAX_VALUE
        var nearestIndex = startIndex

        // startIndex부터 검색하여 진행 방향 고려
        for (i in startIndex until path.size) {
            val distance = calculateDistance(currentLocation, path[i])
            if (distance < minDistance) {
                minDistance = distance
                nearestIndex = i
            }
        }

        // 오차 범위 내에 있는지 확인
        return if (minDistance <= OFF_ROUTE_THRESHOLD) {
            Pair(nearestIndex, minDistance)
        } else {
            null  // 경로 이탈
        }
    }

    /**
     * 경로상의 가장 가까운 포인트 찾기 (이전 인덱스 고려하여 앞으로만 검색)
     * 경로의 선분들에 대한 최단 거리를 계산하여 더 정확한 위치 찾기
     */
    private fun findClosestPathPointAhead(
        currentLocation: LatLng,
        path: List<LatLng>,
        currentIndex: Int
    ): Int {
        try {
            if (path.isEmpty()) return 0
            if (path.size < 2) return currentIndex.coerceIn(0, path.size - 1)
            if (currentIndex < 0 || currentIndex >= path.size) return 0

            var minDistance = Float.MAX_VALUE
            var closestIndex = currentIndex

            // 현재 인덱스부터 앞으로 일정 범위만 검색 (과거로 돌아가지 않음)
            val searchEnd = minOf(currentIndex + 100, path.size)  // 최대 100개 포인트만 검색

            // 경로상의 선분들에 대한 최단 거리 계산
            for (i in currentIndex until searchEnd - 1) {
                val p1 = path.getOrNull(i) ?: continue
                val p2 = path.getOrNull(i + 1) ?: continue

                // 선분에 대한 최단 거리 계산
                val distanceToSegment = distanceToLineSegment(currentLocation, p1, p2)

                if (distanceToSegment < minDistance) {
                    minDistance = distanceToSegment
                    // 선분에 가장 가까운 지점이 p1에 가까우면 i, p2에 가까우면 i+1
                    val distToP1 = calculateDistance(currentLocation, p1)
                    val distToP2 = calculateDistance(currentLocation, p2)
                    closestIndex = if (distToP1 < distToP2) i else i + 1
                }
            }

            // 경로상의 점들과의 직접 거리도 확인 (더 정확한 매칭을 위해)
            for (i in currentIndex until searchEnd) {
                val point = path.getOrNull(i) ?: continue
                val distance = calculateDistance(currentLocation, point)
                if (distance < minDistance) {
                    minDistance = distance
                    closestIndex = i
                }
            }

            return closestIndex.coerceIn(0, path.size - 1)
        } catch (e: Exception) {
            Timber.e("❌ Error in findClosestPathPointAhead: ${e.message}")
            return currentIndex.coerceIn(0, maxOf(0, path.size - 1))
        }
    }

    /**
     * 점에서 선분까지의 최단 거리 계산
     */
    private fun distanceToLineSegment(point: LatLng, lineStart: LatLng, lineEnd: LatLng): Float {
        val A = point.latitude - lineStart.latitude
        val B = point.longitude - lineStart.longitude
        val C = lineEnd.latitude - lineStart.latitude
        val D = lineEnd.longitude - lineStart.longitude

        val dot = A * C + B * D
        val lenSq = C * C + D * D

        if (lenSq == 0.0) {
            // 선분이 점인 경우
            return calculateDistance(point, lineStart)
        }

        val param = dot / lenSq

        val xx: Double
        val yy: Double

        if (param < 0) {
            // 선분의 시작점이 가장 가까움
            xx = lineStart.latitude
            yy = lineStart.longitude
        } else if (param > 1) {
            // 선분의 끝점이 가장 가까움
            xx = lineEnd.latitude
            yy = lineEnd.longitude
        } else {
            // 선분 내부의 점이 가장 가까움
            xx = lineStart.latitude + param * C
            yy = lineStart.longitude + param * D
        }

        point.latitude - xx
        point.longitude - yy
        return calculateDistance(point, LatLng(xx, yy))
    }

    /**
     * 두 지점 간의 거리 계산 (미터)
     */
    private fun calculateDistance(latLng1: LatLng, latLng2: LatLng): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            latLng1.latitude, latLng1.longitude,
            latLng2.latitude, latLng2.longitude,
            results
        )
        return results[0]
    }

    /**
     * 베어링 각도를 0~360도 범위로 정규화
     */
    private fun normalizeBearing(angle: Float): Float {
        var normalized = angle % 360f
        if (normalized < 0f) normalized += 360f
        return normalized
    }

    /**
     * 두 각도 사이의 최단 차이 계산 (-180 ~ 180)
     */
    private fun shortestAngleDiff(from: Float, to: Float): Float {
        var diff = (to - from) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return diff
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

    /**
     * 지나온 경로 숨기기 (지나온 경로는 반투명하게 처리)
     */
    private fun updatePassedRoute(path: List<LatLng>, passedIndex: Int) {
        // 경로 오버레이를 업데이트하여 지나온 부분은 숨기거나 반투명하게 처리
        if (pathOverlays.isNotEmpty() && passedIndex < path.size) {
            // 남은 경로만 표시
            val remainingPath = path.subList(passedIndex, path.size)
            if (remainingPath.size >= 2) {
                // 기존 오버레이 제거 후 남은 경로만 다시 그리기
                pathOverlays.forEach { it.map = null }
                pathOverlays.clear()

                naverMap?.let { nMap ->
                    pathOverlays.add(PathOverlay().apply {
                        coords = remainingPath
                        color = Color.BLUE
                        patternImage = OverlayImage.fromResource(R.drawable.path_pattern)
                        patternInterval = 85
                        outlineColor = Color.WHITE
                        width = 40
                        map = nMap
                    })
                }

                Timber.d("🗺️ Updated route: passed ${passedIndex} points, remaining ${remainingPath.size} points")
            }
        }
    }

    /**
     * 경로상의 현재 위치에서 진행 방향 계산
     */
    private fun calculateBearingFromPath(path: List<LatLng>, currentIndex: Int): Float {
        if (currentIndex < path.size - 1) {
            // 다음 포인트까지의 방향
            return calculateBearing(path[currentIndex], path[currentIndex + 1])
        } else if (path.size >= 2) {
            // 마지막 포인트면 이전 방향 유지
            return calculateBearing(path[path.size - 2], path[path.size - 1])
        }
        return -1f
    }

    /**
     * 경로 기반 지도 회전 (경로상의 위치와 베어링 사용)
     */
    private fun followRouteWithPath(location: LatLng, bearing: Float) {
        naverMap?.let { map ->
            // 회전이 급격하지 않도록 부드럽게 처리
            val diff = if (lastBearing > 0) shortestAngleDiff(lastBearing, bearing) else 0f

            val absDiff = abs(diff)
            val smoothedBearing = if (absDiff > 45f) {
                // 급격한 변화 제한을 완화하여 더 빠른 회전 허용
                normalizeBearing(lastBearing + if (diff > 0) 45f else -45f)
            } else if (absDiff > 0.5f) {
                // 보간 비율 상향(85%) 적용
                normalizeBearing(lastBearing + diff * 0.85f)
            } else {
                // 변화량이 작으면 이전 베어링 유지
                lastBearing
            }

            if (smoothedBearing > 0) {
                lastBearing = smoothedBearing

                val (targetZoom, targetTilt) = getAdaptiveCameraParams()
                val resolvedZoom = resolveZoom(targetZoom)
                val resolvedTilt = resolveTilt(targetTilt)
                lastNavigationZoom = resolvedZoom
                lastNavigationTilt = resolvedTilt
                lastNavigationBearing = smoothedBearing

                // 현재 위치를 지도 중앙에 오도록 설정
                val cameraPosition = CameraPosition(
                    location,            // 현재 위치를 중앙에
                    resolvedZoom,        // 줌 레벨
                    resolvedTilt,        // 기울기
                    smoothedBearing.toDouble() // 진행 방향
                )

                val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
                    .animate(CameraAnimation.Easing, 200) // 빠른 회전 애니메이션
                map.moveCamera(cameraUpdate)

                Timber.d("🗺️ Route-based navigation: location=$location (center), bearing=$smoothedBearing°")
            }
        }
    }

    /**
     * 현재 위치에서 특정 방향과 거리만큼 떨어진 위치 계산
     */
    private fun calculatePositionAhead(
        currentLocation: LatLng,
        bearing: Float,
        distanceMeters: Double
    ): LatLng {
        val earthRadius = 6371000.0 // 지구 반지름 (미터)
        val bearingRad = Math.toRadians(bearing.toDouble())
        val latRad = Math.toRadians(currentLocation.latitude)
        val lngRad = Math.toRadians(currentLocation.longitude)

        val newLatRad = asin(
            sin(latRad) * cos(distanceMeters / earthRadius) +
                    cos(latRad) * sin(distanceMeters / earthRadius) * cos(bearingRad)
        )

        val newLngRad = lngRad + atan2(
            sin(bearingRad) * sin(distanceMeters / earthRadius) * cos(latRad),
            cos(distanceMeters / earthRadius) - sin(latRad) * sin(newLatRad)
        )

        return LatLng(
            Math.toDegrees(newLatRad),
            Math.toDegrees(newLngRad)
        )
    }

    private fun maybeSendTelemetry(location: Location) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTelemetrySentElapsed < TELEMETRY_INTERVAL_MS) return
        lastTelemetrySentElapsed = now

        val payload = VehicleLocationPayload(
            vecNavType = 1,
            vecLat = location.latitude,
            vecLon = location.longitude,
            vecAcc = location.accuracy.toDouble(),
            regDate = telemetryDateFormat.format(Date())
        )

        navigationViewModel.sendTelemetry(vehicleId, payload)
    }

    /**
     * 경로 재검색 요청
     */
    private fun requestReroute(currentLocation: LatLng) {
        if (isRerouting) {
            Timber.d("⏳ Already rerouting, skipping request")
            return
        }

        isRerouting = true
        Timber.d("🔄 Requesting reroute from current location: $currentLocation")
        pendingRerouteLocation = currentLocation
        lastInstructionCleanMessage = null
        lastInstructionTargetIndex = null
        if (voiceGuideManager.isReady()) {
            voiceGuideManager.speakPlain("경로를 재탐색합니다")
        }
        navigationViewModel.reroute(currentLocation)

        // 재검색 중 안내 메시지 표시
        binding.tvCurrentInstruction.text = "경로를 재검색 중입니다..."
    }

    /**
     * 네비게이션 모드 시작 (수동 카메라 제어)
     */
    private fun startNavigationMode() {
        if (isNavigationModeActive) return

        isNavigationModeActive = true
        naverMap?.let { map ->
            // 수동 카메라 제어를 위해 None 모드로 설정
            map.locationTrackingMode = LocationTrackingMode.None
            Timber.d("🧭 Navigation mode started - Manual camera control enabled")
        }
    }

    /**
     * 네비게이션 모드 중지
     */
    private fun stopNavigationMode() {
        if (!isNavigationModeActive) return

        isNavigationModeActive = false
        naverMap?.let { map ->
            // Follow 모드로 변경 (일반 추적)
            map.locationTrackingMode = LocationTrackingMode.Follow
            Timber.d("🧭 Navigation mode stopped - Follow tracking enabled")
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        stopNavigationMode()
        navigationManager.stopNavigation()
        voiceGuideManager.release()

        // 위치 업데이트 중지
        try {
            // Fused 우선 해제
            if (isUsingFused) {
                fusedCallback?.let { cb ->
                    fusedClient.removeLocationUpdates(cb)
                }
                isUsingFused = false
                Timber.d("📍 Fused location updates stopped")
            }
            val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            locationManager.removeUpdates(locationListener)
            Timber.d("📍 Location updates stopped")
        } catch (e: Exception) {
            Timber.e("📍 Error stopping location updates: ${e.message}")
        }
    }

    /**
     * 현재 위치(보간 포함)에서 경로상의 targetIndex까지 남은 거리(m)
     */
    private fun distanceToPathIndex(
        path: List<LatLng>,
        currentIndex: Int,
        currentPosition: LatLng,
        targetIndex: Int
    ): Float {
        if (path.isEmpty()) return 0f
        val startIdx = currentIndex.coerceIn(0, path.lastIndex)
        val endIdx = targetIndex.coerceIn(0, path.lastIndex)
        if (endIdx <= startIdx) return 0f

        var sum = 0f
        val nextIdx = (startIdx + 1).coerceAtMost(path.lastIndex)
        sum += calculateDistance(currentPosition, path[nextIdx])
        for (i in nextIdx until endIdx) {
            sum += calculateDistance(path[i], path[i + 1])
        }
        return sum
    }
}