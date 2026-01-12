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
import com.dom.samplenavigation.navigation.map.NavigationMapManager
import com.dom.samplenavigation.navigation.location.LocationRepository
import com.dom.samplenavigation.navigation.snapping.RouteSnappingService
import com.dom.samplenavigation.navigation.rerouting.ReroutingManager
import com.dom.samplenavigation.navigation.camera.CameraController
import com.dom.samplenavigation.view.viewmodel.NavigationViewModel
import com.dom.samplenavigation.api.telemetry.model.VehicleLocationPayload
import com.dom.samplenavigation.util.VehiclePreferences
import com.dom.samplenavigation.view.dialog.ArrivalDialog
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.MapFragment
import com.naver.maps.map.NaverMap
import com.naver.maps.map.OnMapReadyCallback
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    // 분리된 매니저 클래스들
    private var mapManager: NavigationMapManager? = null
    private lateinit var locationRepository: LocationRepository
    private lateinit var routeSnappingService: RouteSnappingService
    private lateinit var reroutingManager: ReroutingManager
    private lateinit var cameraController: CameraController

    // Map
    private var naverMap: NaverMap? = null
    private var isMapReady = false
    private var currentLocationMarker: Marker? = null
    private var isCameraUpdateFromCode: Boolean = false

    // Navigation State
    private var currentRoute: NavigationRoute? = null
    private var currentPathIndex: Int = 0
    private var snappedLocation: LatLng? = null
    private var isNavigating = false
    private var selectedRouteOption: RouteOptionType? = null  // 선택된 경로 옵션
    private var isShowingFullRoute = false  // 전체 경로 표시 여부
    private var isGestureMode = false  // 유저 제스처 모드 (지도 조작 중)
    private var lastGestureTime: Long = 0  // 마지막 유저 제스처 시간
    private var gestureTimeoutJob: Job? = null  // 제스처 타임아웃 작업
    private var isRerouteNavigation = false  // 재탐색으로 인한 네비게이션 시작 여부
    private var locationSnappingJob: Job? = null  // 위치 스냅 코루틴 작업 (중복 실행 방지)
    private var lastCameraUpdateTime: Long = 0  // 마지막 카메라 업데이트 시간 (디바운싱)

    // Simulation Mode
    private var isSimulationMode: Boolean = false  // 시뮬레이션 모드 플래그
    private var simulationJob: Job? = null  // 시뮬레이션 코루틴 작업

    // 카메라 상태 (cameraController로 이전했지만, 기존 코드 호환을 위해 유지)
    private var currentBearing: Float = 0f
    private var lastNavigationZoom: Double = 17.0
    private var lastNavigationTilt: Double = 10.0
    private var lastNavigationBearing: Float = 0f
    private var lastBearing: Float = 0f  // 일반 베어링 (경로 기반 계산용)

    // Location
    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationFlowJob: Job? = null
    private var lastLocation: Location? = null
    private var lastKnownLocation: LatLng? = null
    private var currentSpeed: Float = 0f

    // Voice Guide
    private var isVoiceGuideEnabled: Boolean = true
    private var suppressVoiceSwitchCallback: Boolean = false

    // Vehicle Preferences (차량 정보 저장/로드)
    private lateinit var vehiclePreferences: VehiclePreferences

    // Direction Arrow (분기점 화살표) 관리
    private var previousInstructionPointIndex: Int? =
        null  // 이전 instruction의 pointIndex (분기점 지난 후 제거 확인용)

    // 네비게이션 시작 시간 (소요 시간 계산용)
    private var navigationStartTime: Long = 0
    private var arrivalDialog: com.dom.samplenavigation.view.dialog.ArrivalDialog? = null
    private var hasArrived = false  // 도착 플래그 (중복 호출 방지)

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
        private const val ARRIVAL_THRESHOLD_M = 15f  // 도착 판정 거리 (미터)

        // Camera 상수
        private const val ZOOM_LOW_SPEED = 18.0  // 저속 줌
        private const val ZOOM_DEFAULT = 17.0  // 기본 줌
        private const val ZOOM_HIGH_SPEED = 16.0  // 고속 줌
        private const val SPEED_THRESHOLD_SLOW = 4.2f  // ≈15km/h
        private const val SPEED_THRESHOLD_FAST = 13.9f  // ≈50km/h
        private const val HIGH_SPEED_TILT = 45.0
        private const val DEFAULT_TILT = 30.0
        private const val BEARING_SMOOTH_ALPHA = 0.3f  // 베어링 스무딩 계수
        private const val CAMERA_UPDATE_MIN_INTERVAL_MS = 100L  // 카메라 업데이트 최소 간격 (100ms)

        // Simulation 상수
        private const val SIMULATION_UPDATE_INTERVAL_MS = 800L  // 시뮬레이션 업데이트 간격 (밀리초)
        private const val SIMULATION_SPEED_KMH = 50f  // 시뮬레이션 속도 (km/h)

        // User Gesture 상수
        private const val GESTURE_TIMEOUT = 5000L  // 유저 제스처 후 자동 복귀 시간 (5초)

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

        // VehiclePreferences 초기화
        vehiclePreferences = VehiclePreferences(this)

        // 분리된 매니저 클래스들 초기화
        locationRepository = LocationRepository(fusedClient)
        routeSnappingService = RouteSnappingService()
        reroutingManager = ReroutingManager()
        cameraController = CameraController()

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

        // navBasicId는 VehiclePreferences에서 직접 읽음 (Intent로 전달 불필요)

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

        // NavigationMapManager 초기화
        val density = resources.displayMetrics.density
        val topPaddingPx = (600 * density).toInt()
        mapManager = NavigationMapManager(naverMap, resources).apply {
            initializeMap(topPaddingPx)
        }

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
        map.addOnCameraChangeListener { reason, animated ->
            // 코드에서 카메라를 업데이트한 경우는 제외
            if (isCameraUpdateFromCode) {
                isCameraUpdateFromCode = false
                Timber.d("Camera change ignored: from code, reason=$reason")
                return@addOnCameraChangeListener
            }

            // 네비게이션 중일 때만 제스처 감지
            if (!isNavigating) {
                Timber.d("Camera change ignored: not navigating, reason=$reason")
                return@addOnCameraChangeListener
            }

            // 제스처로 인한 카메라 변경 감지
            // REASON_GESTURE는 사용자 제스처로 인한 변경
            Timber.d("Camera change detected: reason=$reason, animated=$animated, isNavigating=$isNavigating")
            if (reason == CameraUpdate.REASON_GESTURE) {
                Timber.d("User gesture detected from camera change: reason=GESTURE")
                handleUserGesture()
            } else {
                Timber.d("Camera change but not GESTURE: reason=$reason")
            }
        }

    }

    /**
     * 사용자 제스처 처리
     */
    private fun handleUserGesture() {
        if (!isNavigating) {
            Timber.d("handleUserGesture: not navigating, ignoring")
            return
        }

        val currentTime = System.currentTimeMillis()
        Timber.d("handleUserGesture called: isGestureMode=$isGestureMode, isNavigating=$isNavigating")

        // 제스처 모드 활성화
        if (!isGestureMode) {
            // 제스처 모드를 먼저 활성화하여 카메라 추적이 즉시 중단되도록 함
            isGestureMode = true
            lastGestureTime = currentTime
            enterGestureMode()
            Timber.d("User gesture detected - entering gesture mode")
        } else {
            // 제스처 모드가 이미 활성화된 경우 시간 갱신
            lastGestureTime = currentTime
            Timber.d("User gesture detected - updating lastGestureTime: $currentTime")
        }
    }

    /**
     * 제스처 모드 진입
     */
    private fun enterGestureMode() {
        // 교통량 표시로 전환 (전체 경로 표시)
        currentRoute?.let { route ->
            mapManager?.displayRoute(route, showFullRoute = true, currentPathIndex)
        }

        // 자동 추적 비활성화 (카메라가 사용자가 조작한 위치를 유지하도록)
        naverMap?.let { map ->
            map.locationTrackingMode = LocationTrackingMode.None
        }

        // 버튼 텍스트 변경
        binding.btnReturnToCurrentLocation.text = getString(R.string.navigation_current_location)
        binding.btnReturnToCurrentLocation.visibility = View.VISIBLE

        // 제스처 모드에서는 카메라를 현재 위치로 이동시키지 않음
        // 사용자가 조작한 카메라 위치를 유지하고, 타이머가 끝나면 자동으로 복귀

        // 3초 후 자동 복귀 타이머 시작 (전체 경로 표시 모드가 아닐 때만)
        if (!isShowingFullRoute) {
            startGestureTimeoutTimer()
        }

        Timber.d("Entered gesture mode - congestion display enabled, auto tracking disabled, camera position maintained")
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
        mapManager?.restoreOriginalPadding()

        // 단색 경로로 복귀 (현재 진행 구간부터 표시)
        currentRoute?.let { route ->
            mapManager?.displayRoute(
                route,
                showFullRoute = false,
                currentPathIndex = currentPathIndex
            )
            Timber.d("Route displayed (single color) from current path index")
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
                        val pathBearing = calculateBearingFromPath(
                            currentRoute.path,
                            currentPathIndex,
                            snappedLocation
                        )
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
            isCameraUpdateFromCode = true
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
                    calculateBearingFromPath(
                        currentRoute.path,
                        currentPathIndex,
                        snappedLocation
                    )
                } else {
                    lastBearing
                }

                val cameraPosition = CameraPosition(
                    startLocation,
                    lastNavigationZoom,
                    lastNavigationTilt,
                    bearing.toDouble()
                )
                isCameraUpdateFromCode = true
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
        binding.btnReturnToCurrentLocation.text = getString(R.string.navigation_full_route)

        Timber.d("Returned to current location mode complete")
    }

    /**
     * 제스처 모드 자동 복귀 타이머
     */
    private fun startGestureTimeoutTimer() {
        // 기존 타이머 취소 (중복 실행 방지)
        gestureTimeoutJob?.cancel()

        gestureTimeoutJob = lifecycleScope.launch {
            Timber.d("Gesture timeout timer started")

            // 조건이 만족될 때까지 반복 검사
            while (isActive && isGestureMode) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastGesture = currentTime - lastGestureTime

                // 남은 시간 계산
                val remainingTime = GESTURE_TIMEOUT - timeSinceLastGesture

                if (remainingTime <= 0) {
                    // 타임아웃 시간이 지났다면 모드 복귀
                    Timber.d("Gesture timeout: returning to current location mode")
                    returnToCurrentLocationMode()
                    break // 루프 종료
                } else {
                    // 시간이 아직 안 지났다면, '남은 시간' 만큼만 대기하고 다시 검사
                    Timber.d("Gesture timeout: recent gesture detected. Waiting for remaining ${remainingTime}ms")
                    delay(remainingTime)
                }
            }
        }
    }

    /**
     * 경로에서 베어링 계산 (스냅된 위치에서 경로의 앞부분을 향하도록)
     * 현재 위치에서 약 50-100m 앞의 경로 지점을 향하도록 계산하여 더 부드러운 방향 제공
     */
    private fun calculateBearingFromPath(
        path: List<LatLng>,
        currentIndex: Int,
        snappedLocation: LatLng? = null
    ): Float {
        if (currentIndex >= path.size - 1) return lastBearing

        val startPoint = snappedLocation ?: path[currentIndex]
        
        // 경로의 앞부분(약 50-100m 앞)을 찾기 위해 여러 포인트 확인
        val targetDistanceMin = 50f  // 최소 50m 앞
        val targetDistanceMax = 100f  // 최대 100m 앞
        
        // 최대 20개 포인트 앞까지 검색 (너무 멀리 가지 않도록)
        val maxSearchIndex = minOf(currentIndex + 20, path.size - 1)
        
        var bestIndex = currentIndex + 1
        var bestDistance = routeSnappingService.calculateDistance(startPoint, path[bestIndex])
        
        // 목표 범위(50-100m) 내의 최적 지점 찾기
        for (i in (currentIndex + 1)..maxSearchIndex) {
            val distance = routeSnappingService.calculateDistance(startPoint, path[i])
            
            // 목표 범위 내에 있으면 바로 사용
            if (distance in targetDistanceMin..targetDistanceMax) {
                return routeSnappingService.calculateBearing(startPoint, path[i])
            }
            
            // 목표 범위를 넘어섰으면 이전 지점 사용
            if (distance > targetDistanceMax) {
                return routeSnappingService.calculateBearing(startPoint, path[bestIndex])
            }
            
            // 목표 범위에 도달하지 않았지만 가장 가까운 지점 업데이트
            if (distance > bestDistance) {
                bestIndex = i
                bestDistance = distance
            }
        }
        
        // 목표 거리를 찾지 못했지만 가장 가까운 유효한 다음 포인트 사용
        
        return lastBearing
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
                    // 1. Location Snapping: GPS 위치를 경로에 스냅 (백그라운드 스레드에서 실행)
                    // 이전 코루틴이 실행 중이면 취소 (중복 실행 방지)
                    locationSnappingJob?.cancel()
                    locationSnappingJob = lifecycleScope.launch {
                        try {
                            // 제스처 모드 체크 (제스처 모드 진입 시점에 카메라 추적을 즉시 중단)
                            if (isGestureMode || isShowingFullRoute) {
                                Timber.d("Skipping camera follow: isGestureMode=$isGestureMode, isShowingFullRoute=$isShowingFullRoute")
                                return@launch
                            }

                            val snapResult = routeSnappingService.snapLocationToRoute(
                                gpsLocation,
                                route.path,
                                currentPathIndex
                            )
                            snappedLocation = snapResult.snappedLocation
                            currentPathIndex = snapResult.pathIndex

                            // 화살표 업데이트는 currentInstruction.observe에서 처리

                            // 2. 경로 이탈 감지 및 재탐색 (카운터 기반)
                            val distanceToPath = routeSnappingService.calculateDistance(
                                gpsLocation,
                                snappedLocation!!
                            )
                            if (reroutingManager.checkAndShouldReroute(distanceToPath)) {
                                checkAndReroute(gpsLocation)
                            }

                            // 3. Camera Follow: 스냅된 위치로 카메라 추적 (제스처 모드나 전체 경로 표시 모드가 아닐 때만)
                            // 제스처 모드 체크를 다시 수행 (제스처 모드 진입 시점에 카메라 추적을 즉시 중단)
                            if (snappedLocation != null && !isGestureMode && !isShowingFullRoute) {
                                // 경로 기반 베어링 계산 (스냅된 위치에서 경로의 앞부분을 향하도록)
                                val pathBearing = if (currentPathIndex < route.path.size - 1) {
                                    calculateBearingFromPath(route.path, currentPathIndex, snappedLocation)
                                } else {
                                    cameraController.getCurrentBearing()
                                }

                                // 속도 기반 줌/틸트 계산 및 베어링 스무딩
                                val targetZoom =
                                    cameraController.calculateZoomFromSpeed(currentSpeed)
                                val targetTilt =
                                    cameraController.calculateTiltFromSpeed(currentSpeed)
                                // 경로 방향과 GPS 베어링을 혼합하여 더 안정적인 베어링 계산
                                val gpsBearing = cameraController.getCurrentBearing()
                                val finalBearing =
                                    if (abs(shortestAngleDiff(pathBearing, gpsBearing)) < 45f) {
                                        // 경로 방향과 GPS 방향이 비슷하면 GPS 베어링 사용 (부드러운 회전)
                                        cameraController.smoothBearing(gpsBearing)
                                    } else {
                                        // 차이가 크면 경로 방향 우선 (직진 시 올바른 방향 유지)
                                        cameraController.smoothBearing(pathBearing)
                                    }

                                // 줌/틸트 스무딩 적용
                                val smoothedZoom = cameraController.updateZoom(targetZoom)
                                val smoothedTilt = cameraController.updateTilt(targetTilt)

                                // 카메라 업데이트 디바운싱 (너무 빈번한 업데이트 방지)
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastCameraUpdateTime >= CAMERA_UPDATE_MIN_INTERVAL_MS) {
                                    mapManager?.updateCameraFollow(
                                        snappedLocation!!,
                                        finalBearing,
                                        smoothedZoom,
                                        smoothedTilt
                                    )
                                    lastCameraUpdateTime = currentTime
                                }
                                // 마커는 항상 업데이트 (카메라보다 가벼움)
                                mapManager?.updateCurrentLocationMarker(snappedLocation!!)

                                // 네비게이션 모드의 카메라 상태 저장 (스무딩된 값 사용)
                                lastNavigationZoom = smoothedZoom
                                lastNavigationTilt = smoothedTilt
                                lastNavigationBearing = finalBearing
                                lastBearing = finalBearing
                            } else if (snappedLocation != null) {
                                // 제스처 모드일 때는 마커만 업데이트
                                mapManager?.updateCurrentLocationMarker(snappedLocation!!)
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Error in location snapping and camera follow")
                        }
                    }

                    // 4. 도착 확인
                    lifecycleScope.launch {
                        try {
                            val currentSnappedLocation = snappedLocation
                            if (currentSnappedLocation != null) {
                                val distanceToDestination = routeSnappingService.calculateDistance(
                                    currentSnappedLocation,
                                    route.summary.endLocation
                                )
                                if (distanceToDestination <= ARRIVAL_THRESHOLD_M && !hasArrived) {
                                    Timber.d("Arrived at destination! (${distanceToDestination}m)")
                                    hasArrived = true  // 도착 플래그 설정 (중복 호출 방지)
                                    // 네비게이션 중지
                                    isNavigating = false
                                    navigationManager.stopNavigation()
                                    // UI 스레드에서 도착 다이얼로그 표시
                                    runOnUiThread {
                                        showArrivalDialog()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Error in arrival check")
                        }
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
                                distance > 1000 -> {
                                    val km = distance / 1000.0
                                    "${String.format("%.1f", km)}킬로미터 후 $cleanMessage"
                                }

                                distance >= 1000 -> "1킬로미터 후 $cleanMessage"
                                distance >= 500 -> "500미터 후 $cleanMessage"
                                distance >= 200 -> "200미터 후 $cleanMessage"
                                distance >= 50 -> "곧 $cleanMessage"
                                else -> cleanMessage
                            }
                            voiceGuideManager.speak(
                                message,
                                android.speech.tts.TextToSpeech.QUEUE_ADD
                            )
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
                // 재탐색 시 이전 경로 완전히 제거
                if (reroutingManager.isRerouting()) {
                    mapManager?.clearAllOverlays()
                }

                currentRoute = newRoute
                mapManager?.displayRoute(newRoute, isShowingFullRoute, currentPathIndex)

                // 재탐색 후 초기화
                if (reroutingManager.isRerouting()) {
                    reroutingManager.onRerouteComplete()
                    isRerouteNavigation = true  // 재탐색 플래그 설정
                    Toast.makeText(
                        this,
                        getString(R.string.navigation_reroute_complete),
                        Toast.LENGTH_SHORT
                    ).show()

                    // 재탐색 후 스냅 위치 초기화
                    lifecycleScope.launch {
                        val referenceLocation = lastKnownLocation ?: newRoute.summary.startLocation
                        val snapResult = routeSnappingService.snapLocationToRoute(
                            referenceLocation,
                            newRoute.path,
                            0
                        )
                        snappedLocation = snapResult.snappedLocation
                        currentPathIndex = snapResult.pathIndex
                        mapManager?.updateCurrentLocationMarker(snappedLocation!!)
                    }
                } else {
                    // 최초 시작 시 출발지로 초기화
                    isRerouteNavigation = false  // 최초 시작이므로 재탐색 아님
                    snappedLocation = newRoute.summary.startLocation
                    currentPathIndex = 0
                    mapManager?.updateCurrentLocationMarker(snappedLocation!!)
                }

                // 재탐색 시 분기점 화살표 상태 초기화
                previousInstructionPointIndex = null
                mapManager?.removeDirectionArrow()

                navigationManager.startNavigation(newRoute)

                // 네비게이션 시작 시간 기록
                navigationStartTime = System.currentTimeMillis()
                hasArrived = false  // 도착 플래그 리셋

                // 네비게이션 시작 시 즉시 3D 뷰로 전환 (제스처 모드나 전체 경로 표시 중이 아닐 때만)
                if (isMapReady && snappedLocation != null && !isGestureMode && !isShowingFullRoute) {
                    val bearing = cameraController.getCurrentBearing()
                    val zoom = cameraController.getCurrentZoom()
                    val tilt = cameraController.getCurrentTilt()
                    mapManager?.updateCameraFollow(snappedLocation!!, bearing, zoom, tilt)
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
            // 전체 경로 표시 모드 진입
            isGestureMode = true
            isShowingFullRoute = true

            // 교통량 표시로 전환
            currentRoute?.let { route ->
                mapManager?.displayRoute(route, showFullRoute = true, currentPathIndex)
            }

            // 자동 추적 비활성화
            naverMap?.let { map ->
                map.locationTrackingMode = LocationTrackingMode.None
            }

            // 전체 경로 표시 시 패딩 제거 (MainActivity와 동일한 바운더리 계산을 위해)
            mapManager?.setContentPadding(0)

            // 버튼 텍스트 변경
            binding.btnReturnToCurrentLocation.text =
                getString(R.string.navigation_current_location)
            binding.btnReturnToCurrentLocation.visibility = View.VISIBLE

            // 전체 경로 표시 시 카메라를 전체 경로에 맞춤
            currentRoute?.let { route ->
                mapManager?.fitBounds(route)
            }

            // 전체 경로 표시 모드에서는 자동 복귀 타이머를 시작하지 않음 (사용자가 버튼을 눌러야 복귀)
            gestureTimeoutJob?.cancel()
            gestureTimeoutJob = null

            Timber.d("Full route view enabled - auto tracking disabled, no auto return timer")
        }
    }

    // ==================== OMS 스타일: Rerouting ====================

    /**
     * 경로 이탈 감지 및 재탐색 (ReroutingManager 사용)
     */
    private fun checkAndReroute(gpsLocation: LatLng) {
        navigationViewModel.reroute(gpsLocation)
        binding.tvCurrentInstruction.text = getString(R.string.navigation_rerouting)
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

        binding.tvRemainingDistance.text =
            getString(R.string.navigation_remaining_distance, String.format("%.1f", distanceKm))
        binding.tvRemainingTime.text = getString(R.string.navigation_remaining_time, timeString)

        // 현재 속도 표시 (m/s → km/h 변환)
        // 정차 판정: 2km/h 이하는 0으로 표시 (GPS 오차 보정)
        val speedKmh = (currentSpeed * 3.6f)
        val displaySpeed = if (speedKmh <= 2.0f) 0 else speedKmh.toInt()
        binding.tvCurrentSpeed.text = if (displaySpeed > 0) {
            getString(R.string.navigation_speed_format, displaySpeed)
        } else {
            getString(R.string.navigation_speed_unknown)
        }

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

        // 현재 안내(바로 당장의 거리)를 제외하고 다음 안내까지의 거리 계산
        val currentInstruction = navigationManager.currentInstruction.value
        val distance = if (currentInstruction != null) {
            // 현재 안내의 pointIndex부터 다음 안내의 pointIndex까지의 거리
            val currentInstructionIndex =
                currentInstruction.pointIndex.coerceIn(0, route.path.size - 1)
            val nextInstructionIndex = nextInstruction.pointIndex.coerceIn(0, route.path.size - 1)
            if (currentInstructionIndex < nextInstructionIndex) {
                calculateRouteDistance(currentInstructionIndex, nextInstructionIndex, route.path)
            } else {
                // 현재 안내가 다음 안내보다 뒤에 있으면 직접 거리 계산
                calculateDistance(currentInstruction.location, nextInstruction.location)
            }
        } else {
            // 현재 안내가 없으면 현재 위치부터 다음 안내까지의 거리
            val currentPos = snappedLocation ?: lastKnownLocation ?: return
            val targetIndex = nextInstruction.pointIndex.coerceIn(0, route.path.size - 1)
            if (currentPathIndex < targetIndex) {
                calculateRouteDistance(currentPathIndex, targetIndex, route.path)
            } else {
                calculateDistance(currentPos, nextInstruction.location)
            }
        }

        // 메시지에서 거리 정보 제거 (이미 거리를 표시할 것이므로)
        val cleanMessage = nextInstruction.message
            .replace(Regex("\\d+\\s*킬로미터\\s*(후|전방|앞)\\s*"), "")
            .replace(Regex("\\d+\\s*미터\\s*(후|전방|앞)\\s*"), "")
            .replace(Regex("\\d+\\.?\\d*\\s*km\\s*(후|전방|앞)\\s*"), "")
            .replace(Regex("\\d+\\s*m\\s*(후|전방|앞)\\s*"), "")
            .trim()

        // 다음 안내 메시지에 거리 정보 추가
        binding.tvNextInstructionDistance.text = if (distance >= 1000) {
            val km = distance / 1000.0
            "${String.format("%.1f", km)}km"
        } else {
            "${distance.toInt()}m"
        }

        binding.tvNextInstruction.text = cleanMessage

        val iconRes = getDirectionIconRes(nextInstruction.type)
        if (iconRes != null) {
            binding.clRouteNextNotification.visibility = View.VISIBLE
            binding.ivRouteNextDirection.setImageResource(iconRes)
        } else {
            binding.clRouteNextNotification.visibility = View.GONE
        }

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

        binding.tvRemainingJunctionDistance.text = if (distance >= 1000) {
            val km = distance / 1000.0
            "${String.format("%.1f", km)}km"
        } else {
            "${distance.toInt()}m"
        }
        binding.tvCurrentInstruction.text = cleanMessage

        // 분기점 아이콘 표시 (항상 표시)
        val iconRes = getDirectionIconRes(instruction.type)
        if (iconRes != null) {
            binding.clRouteNotification.visibility = View.VISIBLE
            binding.ivRouteDirection.setImageResource(iconRes)
        } else {
            binding.clRouteNotification.visibility = View.GONE
        }
    }

    /**
     * 안내 타입에 따라 분기점 아이콘 리소스 반환
     */
    private fun getDirectionIconRes(type: Int): Int? {
        return when (type) {
            // 직진
            1 -> R.drawable.ic_nav_straight_1

            // 좌회전 계열
            2, 8, 12 -> R.drawable.ic_nav_turn_left_2_8_12
            11 -> R.drawable.ic_nav_turn_left_11
            4, 13 -> R.drawable.ic_nav_turn_left_4_13
            57, 58, 59, 60 -> R.drawable.ic_nav_highway_left_57_58_59_60

            // 우회전 계열
            3, 15 -> R.drawable.ic_nav_turn_right_3_15
            5, 14 -> R.drawable.ic_nav_turn_right_5_14
            66, 67, 68, 69 -> R.drawable.ic_nav_highway_right_66_67_68_69
            16 -> R.drawable.ic_nav_turn_right_16
            54 -> R.drawable.ic_nav_54

            // 유턴
            6 -> R.drawable.ic_nav_uturn_6

            // 로터리/회전교차로 - 직진
            21, 28, 91, 98 -> R.drawable.ic_nav_roundabout_straight_21_28_91_98

            // 로터리/회전교차로 - 유턴
            22, 34, 92, 104 -> R.drawable.ic_nav_roundabout_uturn_22_34_92_104

            // 로터리/회전교차로 - 좌측
            23, 24, 93, 94 -> R.drawable.ic_nav_roundabout_left_23_24_93_94
            25, 95 -> R.drawable.ic_nav_roundabout_left_25_95
            26, 27, 96, 97 -> R.drawable.ic_nav_roundabout_left_26_27_96_97

            // 로터리/회전교차로 - 우측
            31, 101 -> R.drawable.ic_nav_roundabout_right_31_101
            29, 30, 99, 100 -> R.drawable.ic_nav_roundabout_right_29_30_99_100
            32, 33, 102, 103 -> R.drawable.ic_nav_roundabout_right_32_33_102_103

            // 고가차도, 지하차도
            55, 62, 71 -> R.drawable.ic_nav_overpass_55_62_71
            56, 64, 73 -> R.drawable.ic_nav_underpass_56_64_73
            // 본선 합류
            81 -> R.drawable.ic_nav_merge_left_81
            82 -> R.drawable.ic_nav_merge_right_82
            121,122,123 -> R.drawable.ic_nav_121_122_123

            else -> null
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

        // Flow를 사용하여 위치 업데이트 수신
        locationFlowJob = lifecycleScope.launch {
            locationRepository.getLocationFlow().collect { loc ->
                // 시뮬레이션 모드일 때는 실제 위치 업데이트 무시
                if (isSimulationMode) return@collect

                val latLng = LatLng(loc.latitude, loc.longitude)

                lastKnownLocation = latLng
                lastLocation = loc
                // GPS 속도가 음수이거나 너무 작으면 0으로 처리 (정차 판정)
                val rawSpeed = loc.speed.coerceAtLeast(0f)
                // 2km/h 이하는 정차로 판정 (GPS 오차 보정)
                currentSpeed = if (rawSpeed * 3.6f <= 2.0f) 0f else rawSpeed

                // 베어링 업데이트
                val stableBearing = navigationManager.calculateStableBearing(loc)
                val bearing = if (stableBearing > 0f) {
                    stableBearing
                } else if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.0f) {
                    loc.bearing
                } else {
                    cameraController.getCurrentBearing()
                }
                cameraController.updateBearing(bearing)

                // NavigationManager에 위치 업데이트
                navigationManager.updateCurrentLocation(latLng)

                // Telemetry API 호출 (시뮬레이션이 아닐 때만)
                sendLocationTelemetry(loc)

                Timber.d("Location: $latLng, bearing=${bearing}°, speed=${currentSpeed * 3.6f}km/h")
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

            // 저장된 navBasicId 사용
            val vehicleId = vehiclePreferences.getNavBasicId()

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

                // 화살표 업데이트는 currentInstruction.observe에서 처리 (시뮬레이션도 동일)

                // 가짜 Location 객체 생성 및 저장
                val simulatedLocation = createSimulatedLocation(nextLocation, route)
                lastLocation = simulatedLocation

                // 속도 및 베어링 업데이트
                currentSpeed = simulatedLocation.speed
                if (simulatedLocation.hasBearing()) {
                    currentBearing = simulatedLocation.bearing
                } else if (currentPathIndex > 0) {
                    val prevLocation = route.path[currentPathIndex - 1]
                    currentBearing = routeSnappingService.calculateBearing(prevLocation, nextLocation)
                }

                // CameraController 베어링 업데이트
                cameraController.updateBearing(currentBearing)

                // NavigationManager에 시뮬레이션 위치 업데이트
                // 주의: 이 호출은 setupObservers의 navigationState.observe를 트리거하지만,
                // 시뮬레이션 모드에서는 스냅 로직이 실행되지 않으므로 currentPathIndex가 유지됩니다
                navigationManager.updateCurrentLocation(nextLocation)

                // 카메라 업데이트 (속도 기반 줌/틸트) - 제스처 모드나 전체 경로 표시 중에는 실행하지 않음
                if (isMapReady && !isGestureMode && !isShowingFullRoute) {
                    // 경로 기반 베어링 계산 (스냅된 위치에서 경로의 앞부분을 향하도록)
                    val pathBearing =
                        if (currentPathIndex < route.path.size - 1) {
                            calculateBearingFromPath(route.path, currentPathIndex, nextLocation)
                        } else {
                            cameraController.getCurrentBearing()
                        }

                    val targetZoom = cameraController.calculateZoomFromSpeed(currentSpeed)
                    val targetTilt = cameraController.calculateTiltFromSpeed(currentSpeed)
                    // 경로 방향과 GPS 베어링을 혼합하여 더 안정적인 베어링 계산
                    val gpsBearing = cameraController.getCurrentBearing()
                    val finalBearing = if (abs(shortestAngleDiff(pathBearing, gpsBearing)) < 45f) {
                        // 경로 방향과 GPS 방향이 비슷하면 GPS 베어링 사용 (부드러운 회전)
                        cameraController.smoothBearing(gpsBearing)
                    } else {
                        // 차이가 크면 경로 방향 우선 (직진 시 올바른 방향 유지)
                        cameraController.smoothBearing(pathBearing)
                    }

                    // 줌/틸트 스무딩 적용
                    val smoothedZoom = cameraController.updateZoom(targetZoom)
                    val smoothedTilt = cameraController.updateTilt(targetTilt)
                    mapManager?.updateCameraFollow(
                        nextLocation,
                        finalBearing,
                        smoothedZoom,
                        smoothedTilt
                    )
                    mapManager?.updateCurrentLocationMarker(nextLocation)
                } else if (isMapReady) {
                    // 제스처 모드일 때는 마커만 업데이트
                    mapManager?.updateCurrentLocationMarker(nextLocation)
                }

                Timber.d("Simulation: index=$currentPathIndex/${route.path.size - 1}, location=$nextLocation, speed=${currentSpeed * 3.6f}km/h")

                // 도착 확인
                val distanceToDestination = calculateDistance(
                    nextLocation,
                    route.summary.endLocation
                )
                if (distanceToDestination <= ARRIVAL_THRESHOLD_M && !hasArrived) {
                    Timber.d("Simulation arrived at destination!")
                    hasArrived = true  // 도착 플래그 설정 (중복 호출 방지)
                    // 네비게이션 중지
                    isNavigating = false
                    navigationManager.stopNavigation()
                    stopSimulation()
                    // UI 스레드에서 도착 다이얼로그 표시
                    runOnUiThread {
                        showArrivalDialog()
                    }
                    break
                }

                // 다음 업데이트까지 대기
                delay(SIMULATION_UPDATE_INTERVAL_MS)
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
            val bearing = routeSnappingService.calculateBearing(prevLocation, latLng)
            location.bearing = bearing
            location.bearingAccuracyDegrees = 5f
        }

        return location
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
     * 현재 instruction의 분기점에 화살표를 표시합니다.
     * 분기점을 지나친 후 (currentPathIndex > instruction.pointIndex) 현재 화살표를 제거합니다.
     */
    private fun updateDirectionArrow(instruction: Instruction, route: NavigationRoute) {
        // 현재 instruction의 pointIndex를 이미 지나쳤는지 확인
        // 지나쳤다면 (currentPathIndex > instruction.pointIndex) 현재 화살표를 제거하고 생성하지 않음
        if (currentPathIndex > instruction.pointIndex) {
            // 분기점을 지나쳤으므로 현재 화살표 제거
            mapManager?.removeDirectionArrow()
            previousInstructionPointIndex = instruction.pointIndex
            Timber.d("Direction arrow removed: passed instruction pointIndex=${instruction.pointIndex} (currentPathIndex=$currentPathIndex)")
            return
        }

        // 이전 instruction의 pointIndex를 확인하여 이미 지나간 경우 이전 화살표 제거
        previousInstructionPointIndex?.let { prevIndex ->
            if (currentPathIndex > prevIndex) {
                // 이전 분기점을 지났으므로 이전 화살표 제거
                mapManager?.removeDirectionArrow()
                Timber.d("Previous direction arrow removed: passed pointIndex=$prevIndex (currentPathIndex=$currentPathIndex)")
            }
        }

        // 화살표 경로 생성 (현재 instruction의 분기점 기준)
        val (arrowPath, _) = createDirectionArrow(instruction, route)

        // ArrowheadPathOverlay 사용: 경로를 따라가고 끝에 자동으로 화살표 머리가 생김
        if (arrowPath.size >= 2) {
            mapManager?.updateDirectionArrow(arrowPath)
            previousInstructionPointIndex = instruction.pointIndex
            Timber.d("Direction arrow updated: instruction pointIndex=${instruction.pointIndex}, arrowPath size=${arrowPath.size}, currentPathIndex=$currentPathIndex")
        } else {
            Timber.d("Direction arrow not created: insufficient path points (${arrowPath.size})")
        }
    }

    // ==================== Utility Methods ====================

    // RouteSnappingService에 위임
    private fun calculateDistance(latLng1: LatLng, latLng2: LatLng): Float {
        return routeSnappingService.calculateDistance(latLng1, latLng2)
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
                binding.tvCurrentInstruction.text =
                    getString(R.string.navigation_location_permission_required)
            }
        }
    }

    // ==================== Navigation Control ====================

    /**
     * 도착 다이얼로그 표시 (5초 타이머 포함)
     */
    private fun showArrivalDialog() {
        // 이미 다이얼로그가 표시 중이면 무시
        if (arrivalDialog != null) {
            Timber.d("Arrival dialog already showing, ignoring duplicate call")
            return
        }

        val elapsedTimeMs = System.currentTimeMillis() - navigationStartTime
        arrivalDialog = ArrivalDialog(
            activity = this,
            lifecycleScope = lifecycleScope,
            elapsedTimeMs = elapsedTimeMs,
            onConfirm = {
                arrivalDialog = null  // 다이얼로그 참조 제거
                finish()
            }
        )
        arrivalDialog?.show()
    }

    private fun showStopNavigationDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.navigation_stop_title))
            .setMessage(getString(R.string.navigation_stop_message))
            .setPositiveButton(getString(R.string.navigation_stop_confirm)) { _, _ ->
                stopNavigationAndFinish()
            }
            .setNegativeButton(getString(R.string.navigation_stop_cancel)) { dialog, _ ->
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
        mapManager?.removeDirectionArrow()
        previousInstructionPointIndex = null

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
        locationSnappingJob?.cancel()  // 위치 스냅 코루틴 취소
        arrivalDialog?.dismiss()  // 도착 다이얼로그 닫기
        arrivalDialog = null  // 다이얼로그 참조 제거
        navigationManager.stopNavigation()
        voiceGuideManager.release()

        // 화면 켜짐 유지 해제
        keepScreenOn(false)

        // Flow 작업 취소
        locationFlowJob?.cancel()
        locationFlowJob = null
    }
}
