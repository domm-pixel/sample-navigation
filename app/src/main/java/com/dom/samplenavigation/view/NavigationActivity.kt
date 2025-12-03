package com.dom.samplenavigation.view

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.location.Location
import android.os.Build
import android.os.Bundle
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
import com.dom.samplenavigation.api.telemetry.model.VehicleLocationPayload
import com.dom.samplenavigation.base.BaseActivity
import com.dom.samplenavigation.databinding.ActivityNavigationBinding
import com.dom.samplenavigation.navigation.filter.PathSpatialIndex
import com.dom.samplenavigation.navigation.manager.NavigationManager
import com.dom.samplenavigation.navigation.model.Instruction
import com.dom.samplenavigation.navigation.model.NavigationRoute
import com.dom.samplenavigation.navigation.model.NavigationState
import com.dom.samplenavigation.navigation.engine.OffRouteDetector
import com.dom.samplenavigation.navigation.engine.MapLibreNavigation
import com.dom.samplenavigation.navigation.engine.AndroidLocationEngine
import com.dom.samplenavigation.navigation.engine.MapLibreNavigationOptions
import com.dom.samplenavigation.navigation.engine.ProgressChangeListener
import com.dom.samplenavigation.navigation.engine.OffRouteListener
import com.dom.samplenavigation.navigation.engine.RouteProgress
import com.dom.samplenavigation.navigation.engine.Location as NavigationLocation
import com.dom.samplenavigation.navigation.voice.VoiceGuideManager
import com.dom.samplenavigation.view.viewmodel.NavigationViewModel
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.MapFragment
import com.naver.maps.map.NaverMap
import com.naver.maps.map.OnMapReadyCallback
import android.graphics.PointF
import com.dom.samplenavigation.navigation.engine.SimulatedLocationEngine
import com.google.android.gms.location.LocationResult
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.Overlay
import com.naver.maps.map.overlay.OverlayImage
import com.naver.maps.map.overlay.PathOverlay
import com.naver.maps.map.overlay.ArrowheadPathOverlay
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
import kotlin.math.roundToInt
import kotlin.math.sin

@AndroidEntryPoint
class NavigationActivity : BaseActivity<ActivityNavigationBinding>(
    R.layout.activity_navigation
), OnMapReadyCallback {

    private val navigationViewModel: NavigationViewModel by viewModels()
    private lateinit var navigationManager: NavigationManager
    private lateinit var voiceGuideManager: VoiceGuideManager
    private var pathSpatialIndex: PathSpatialIndex? = null  // 공간 인덱스 (경로 로드 시 생성)
    private val offRouteDetector = OffRouteDetector()

    // MapLibreNavigation 인스턴스
    private var mapLibreNavigation: MapLibreNavigation? = null

    private var naverMap: NaverMap? = null
    private var pathOverlays: MutableList<Overlay> = mutableListOf()
    private var instructionMarkers: MutableList<Marker> = mutableListOf()  // 안내 지점 마커들

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
    private var lastStoppedElapsedMs: Long = 0L
    private var pendingRerouteLocation: LatLng? = null
    private var lastSpeedMps: Float = 0f
    private var cameraSpeedInitialized: Boolean = false
    private var lastTelemetrySentElapsed: Long = 0L
    private val telemetryDateFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
    private val vehicleId: Int = 1 // TODO replace with runtime vehicle identifier
    private var inLowAccuracyMode: Boolean = false
    private var accuracyStableCount: Int = 0
    private var isVoiceGuideEnabled: Boolean = true
    private var suppressVoiceSwitchCallback: Boolean = false
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
        private const val TUNNEL_EXIT_MS = 3_000L                // 신호 회복 후 이탈 히스테리시스
        private const val SPEED_MIN_MPS = 1.0f                  // 최소 1 m/s (3.6 km/h)
        private const val SPEED_MAX_MPS = 55.6f                 // 최대 55.6 m/s (200 km/h)
        private const val SPEED_EMA_ALPHA = 0.25f                 // 속도 EMA 가중치
        private const val TOAST_COOLDOWN_MS = 5_000L              // 토스트 중복 방지
        private const val REROUTE_COOLDOWN_MS = 7_000L            // 재검색 쿨다운 강화
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
        private const val ACCURACY_SNAP_THRESHOLD = 30f
        private const val ACCURACY_STABLE_COUNT = 3
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

        // VoiceGuideManager 초기화 확인 (약간의 딜레이 후)
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1000)  // TTS 초기화 대기
            Timber.d("VoiceGuideManager ready status: ${voiceGuideManager.isReady()}")
        }

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
//        naverMap.uiSettings.isZoomControlsEnabled = true
        naverMap.uiSettings.isCompassEnabled = false
        naverMap.uiSettings.isLocationButtonEnabled = false
        naverMap.uiSettings.isZoomControlEnabled = false
        naverMap.buildingHeight = 0.2f

        // 운전자 시야 확보를 위해 지도 중심을 화면 하단 쪽으로 오프셋
        val density = resources.displayMetrics.density
        val topPaddingPx = (0 * density).toInt()
        val bottomPaddingPx = (0 * density).toInt()
        naverMap.setContentPadding(0, topPaddingPx, 0, bottomPaddingPx)
        Timber.d("Map content padding set - top: $topPaddingPx, bottom: $bottomPaddingPx")

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

        Timber.d("Map is ready, creating current location marker")

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

            // 현재 위치가 있으면 경로와 통합하여 처리
            if (state.isNavigating && isNavigating) {
                state.currentLocation?.let { currentLocation ->
                    state.currentRoute?.let { route ->
                        if (isMapReady) {
                            if (inLowAccuracyMode) {
                                Timber.w("Low accuracy mode active - skipping snap/reroute (accuracy=${lastLocation?.accuracy})")
                                updateCurrentLocationMarker(currentLocation)
                                if (!isGestureMode) {
                                    followRoute(currentLocation)
                                }
                                return@let
                            }
                            try {
                                // MapLibreNavigation이 활성화되어 있으면 스냅핑은 MapLibreNavigation에서 처리
                                // 여기서는 오프루트 체크만 수행
                                if (mapLibreNavigation != null) {
                                    // 시뮬레이션 모드일 때는 실제 GPS 위치 기반 오프루트 체크 무시
                                    if (mapLibreNavigation?.locationEngine is SimulatedLocationEngine) {
                                        Timber.d("updateCurrentLocation: Ignoring off-route check in simulation mode")
                                        return@let
                                    }

                                    // MapLibreNavigation의 ProgressChangeListener에서 스냅된 위치를 받아 처리
                                    // 여기서는 오프루트 체크만 수행
                                    val routeProgress = mapLibreNavigation!!.getCurrentRouteProgress()
                                    if (routeProgress != null) {
                                        val distanceToPath = calculateDistance(
                                            currentLocation,
                                            routeProgress.currentStepPoints.firstOrNull() ?: currentLocation
                                        )

                                        // 오프루트 체크 (간단한 거리 기반)
                                        if (distanceToPath > 50f && !isRerouting) {
                                            val currentTime = System.currentTimeMillis()
                                            val timeSinceLastReroute = currentTime - lastRerouteTime
                                            if (timeSinceLastReroute >= REROUTE_COOLDOWN_MS && !isInTunnel) {
                                                val rerouteFrom = lastKnownLocation ?: currentLocation
                                                Timber.d("OffRoute detected: distanceToPath=${distanceToPath}m, triggering reroute")
                                                requestReroute(rerouteFrom)
                                                lastRerouteTime = currentTime
                                            }
                                        }
                                    }
                                } else {
                                    // MapLibreNavigation이 없으면 기존 방식 사용 (레거시)
                                    Timber.w("MapLibreNavigation not available, using legacy snap logic")
                                }
                            } catch (e: Exception) {
                                Timber.e(" Error in navigation tracking: ${e.message}")
                                e.printStackTrace()
                            }
                        }
                    } ?: run {
                        Timber.w("Current location is null")
                        // GPS 끊김 시 추정 항법 시도 (경로가 있을 때만)
                        state.currentRoute?.let { route ->
                            checkAndHandleLocationTimeout(route)
                        }
                    }
                }
            } else if (!isNavigating && state.currentRoute != null) {
                // 네비게이션 시작 전 초기 위치 표시
                state.currentRoute?.let { route ->
                    if (isMapReady) {
                        currentPathIndex = 0
                        updateCurrentLocationMarker(route.summary.startLocation)
                        Timber.d("Marker set to start location: ${route.summary.startLocation}")
                    }
                }
            }
            updatePictureInPictureParams()
        }

        // 현재 안내 메시지 관찰 (UI 업데이트만)
        navigationManager.currentInstruction.observe(this) { instruction ->
            instruction?.let {
                updateInstructionUI(it)
                updatePictureInPictureParams()
            }
        }

        // 음성 안내 트리거 관찰 (음성 재생만)
        navigationManager.shouldPlayVoice.observe(this) { shouldPlay ->
            if (shouldPlay == true) {
                navigationManager.currentInstruction.value?.let { instruction ->
                    if (voiceGuideManager.isReady()) {
                        voiceGuideManager.speakInstruction(instruction)
                        Timber.d("Voice instruction spoken: ${instruction.message}")
                    } else {
                        Timber.w("VoiceGuideManager not ready")
                    }
                } ?: run {
                    Timber.w("Current instruction is null")
                }
            }
        }

        // 안내 시작 알림 관찰 ("경로 안내를 시작합니다" + 첫 안내)
        navigationManager.shouldPlayNavigationStart.observe(this) { shouldPlay ->
            if (shouldPlay == true) {
                navigationManager.currentInstruction.value?.let { instruction ->
                    if (voiceGuideManager.isReady()) {
                        voiceGuideManager.speakNavigationStart(instruction)
                        Timber.d("Navigation start announcement: 경로 안내를 시작합니다 + ${instruction.message}")
                    } else {
                        Timber.w("VoiceGuideManager not ready for start announcement")
                    }
                } ?: run {
                    Timber.w("Current instruction is null for start announcement")
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
                // 공간 인덱스 구축 (경로가 로드될 때)
                pathSpatialIndex = PathSpatialIndex(newRoute.path)
                Timber.d("Spatial index created for route with ${newRoute.path.size} points")

                // MapLibreNavigation 초기화
                initializeMapLibreNavigation(newRoute, true)

                displayRoute(newRoute)

                val wasRerouting = isRerouting
                val anchorLocation = pendingRerouteLocation
                    ?: navigationManager.navigationState.value?.currentLocation
                    ?: lastKnownLocation

                if (wasRerouting) {
                    isRerouting = false
                    Toast.makeText(this, "경로를 재검색했습니다", Toast.LENGTH_SHORT).show()
                    Timber.d("Reroute completed, new route displayed")

                    // 재탐색 후 MapLibreNavigation이 새로운 경로로 업데이트됨
                    // ProgressChangeListener에서 스냅된 위치를 받아 처리
                    val referenceLocation = anchorLocation ?: newRoute.summary.startLocation
                    updateCurrentLocationMarker(referenceLocation)
                    followRoute(referenceLocation)
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
                    } ?: run {
                        newRoute.instructions.firstOrNull()?.let { inst ->
                            updateInstructionUI(inst)
                        }
                    }
                    pendingRerouteLocation = null
                }

                // 최초 시작 시에만 출발지로 마커 초기화 (재탐색 시엔 현재 위치 유지)
                if (!wasRerouting && isMapReady && currentLocationMarker != null) {
                    updateCurrentLocationMarker(newRoute.summary.startLocation)
                    Timber.d("Marker initialized to start location: ${newRoute.summary.startLocation}")
                }

                // 네비게이션 시작 시 즉시 3D 뷰로 전환
                if (isMapReady) {
                    val currentLocation = navigationManager.navigationState.value?.currentLocation
                    if (currentLocation != null) {
                        Timber.d("Switching to 3D navigation view with current location")
                        followRoute(currentLocation)
                    } else {
                        // 현재 위치가 없으면 출발지로 시작
                        Timber.d("Switching to 3D navigation view with start location")
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
        binding.switchVoiceGuide.isChecked = isVoiceGuideEnabled
        binding.switchVoiceGuide.setOnCheckedChangeListener { _, isChecked ->
            if (suppressVoiceSwitchCallback) return@setOnCheckedChangeListener
            updateVoiceGuideState(isChecked, fromUser = true)
            Timber.d("Voice guide ${if (isChecked) "enabled" else "disabled"}")
        }

        binding.btnEnterPip.setOnClickListener {
            enterPictureInPictureModeIfSupported()
        }

        // 현위치로 버튼 (제스처 모드에서만 표시)
        binding.btnReturnToCurrentLocation.setOnClickListener {
            returnToCurrentLocationMode()
        }
    }

    private fun updateVoiceGuideState(enabled: Boolean, fromUser: Boolean) {
        isVoiceGuideEnabled = enabled
        voiceGuideManager.setEnabled(enabled)
        if (binding.switchVoiceGuide.isChecked != enabled) {
            suppressVoiceSwitchCallback = true
            binding.switchVoiceGuide.isChecked = enabled
            suppressVoiceSwitchCallback = false
        }
        if (!fromUser) {
            Timber.d("Voice guide ${if (enabled) "enabled" else "disabled"} (synced)")
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
        Timber.d("Entering Picture-in-Picture mode")
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

    private fun applyPictureInPictureUiState(isInPip: Boolean) {
        isInPictureInPictureModeCompat = isInPip
        if (isInPip) {
            binding.btnEnterPip.visibility = View.GONE
            binding.bottomPanel.visibility = View.GONE
            binding.progressNavigation.visibility = View.GONE
            binding.btnStopNavigation.visibility = View.GONE
            binding.btnReturnToCurrentLocation.visibility = View.GONE
            binding.tvCurrentSpeed.visibility = View.GONE
        } else {
            binding.btnEnterPip.visibility = View.VISIBLE
            binding.bottomPanel.visibility = View.VISIBLE
            binding.progressNavigation.visibility = View.VISIBLE
            binding.btnStopNavigation.visibility =
                if (navigationManager.navigationState.value?.isNavigating == true) View.VISIBLE else View.GONE
            binding.btnReturnToCurrentLocation.visibility =
                if (isGestureMode) View.VISIBLE else View.GONE
            binding.tvCurrentSpeed.visibility = View.VISIBLE
        }
    }

    private fun handlePictureInPictureModeChange(isInPip: Boolean) {
        applyPictureInPictureUiState(isInPip)
        if (isInPip) {
            updatePictureInPictureParams()
        } else {
            navigationManager.navigationState.value?.let { updateNavigationUI(it) }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        handlePictureInPictureModeChange(isInPictureInPictureMode)
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
        updateSpeedDisplay(null)

        // 공간 인덱스 리셋
        pathSpatialIndex = null

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

        Timber.d("Entered gesture mode - congestion display enabled, auto tracking disabled")
    }

    /**
     * 현재 위치 모드로 복귀
     */
    private fun returnToCurrentLocationMode() {
        Timber.d("returnToCurrentLocationMode() called")
        Timber.d("Current state - isGestureMode: $isGestureMode, isNavigating: ${navigationManager.navigationState.value?.isNavigating}, currentLocation: ${navigationManager.navigationState.value?.currentLocation}")

        isGestureMode = false

        // 단색 경로로 복귀
        navigationManager.navigationState.value?.currentRoute?.let { route ->
            displayRoute(route)
            Timber.d("Route displayed (single color)")
        }

        // 네비게이션 모드 재활성화 (수동 카메라 제어로 복귀)
        // 네비게이션 중이면 자동으로 startNavigationMode()가 호출되므로 여기서는 명시적으로 호출
        if (navigationManager.navigationState.value?.isNavigating == true) {
            startNavigationMode()
            Timber.d("Navigation mode reactivated")
        }

        // UI 업데이트
        updateNavigationUI(navigationManager.navigationState.value ?: NavigationState())

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
                Timber.e(" Cannot return to location - currentLocation: null, currentRoute: ${currentRoute != null}, naverMap: ${naverMap != null}")
                Toast.makeText(this, "현재 위치를 가져올 수 없습니다. GPS를 확인해주세요.", Toast.LENGTH_SHORT).show()
            }
        }

        Timber.d("Returned to current location mode complete")
    }

    /**
     * 제스처 모드 자동 복귀 타이머
     */
    private fun startGestureTimeoutTimer() {
        // 기존 타이머가 있다면 취소하고 새로 시작
        // 실제 구현에서는 Handler나 Timer를 사용할 수 있지만, 여기서는 간단히 로그만
        Timber.d("Gesture timeout timer started (${GESTURE_TIMEOUT}ms)")
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPictureInPictureModeCompat &&
            supportsPictureInPicture() &&
            navigationManager.navigationState.value?.isNavigating == true
        ) {
            Timber.d("onUserLeaveHint → enter PIP")
            enterPictureInPictureModeIfSupported()
        }
    }

    private fun checkLocationPermission() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        Timber.d("checkLocationPermission() - hasPermission: $hasPermission")

        if (!hasPermission) {
            Timber.d("Requesting location permission")
            requestLocationPermission()
        } else {
            // 권한이 이미 허용된 경우 위치 업데이트 시작
            Timber.d("Permission already granted, starting location updates")
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
                Timber.d("Location permission granted")
                // 권한이 허용되면 위치 업데이트 시작
                startLocationUpdates()
            } else {
                Timber.w("Location permission denied")
                // 권한이 거부되면 에러 메시지 표시
                binding.tvCurrentInstruction.text = "위치 권한이 필요합니다. 설정에서 권한을 허용해주세요."
            }
        }
    }

    /**
     * 위치 업데이트 시작 (Fused Location Provider 사용)
     */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdates() {
        Timber.d("startLocationUpdates() using Fused Location Provider")
        val started = startFusedLocationUpdates()
        if (!started) {
            Timber.e("Failed to start Fused location updates")
            binding.tvCurrentInstruction.text = "위치 서비스를 시작할 수 없습니다. GPS를 확인해주세요."
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

                        // 시뮬레이션 모드일 때는 실제 GPS 위치 업데이트 무시
                        if (mapLibreNavigation != null && mapLibreNavigation?.locationEngine is SimulatedLocationEngine) {
                            Timber.d("Fused location callback: Ignoring real GPS location in simulation mode")
                            return
                        }

                        val nowMono = SystemClock.elapsedRealtime()

                        // Raw GPS 위치 직접 사용 (칼만 필터 제거)
                        val latLng = LatLng(loc.latitude, loc.longitude)

                        Timber.tag("LocationUpdate")
                            .d("Raw fused location: (${loc.latitude}, ${loc.longitude}), acc=${loc.accuracy}m")

                        // GPS 신호 복구 처리
                        if (isInTunnel) {
                            isInTunnel = false
                            Timber.d("GPS signal restored (Fused) - exiting tunnel mode")
                            maybeToast("GPS 신호 복구됨")
                        }

                        lastKnownLocation = latLng
                        lastLocationUpdateTime = System.currentTimeMillis()
                        lastFixElapsedMs = SystemClock.elapsedRealtime()
                        if (loc.speed <= 1.0f) {
                            lastStoppedElapsedMs = nowMono
                        }
                        lastSpeedMps = smoothCameraSpeed(loc.speed)
                        updateSpeedDisplay(lastSpeedMps)

                        updateAccuracyState(loc.accuracy)

                        val stableBearing = navigationManager.calculateStableBearing(loc)
                        if (stableBearing > 0f) {
                            lastBearing = stableBearing
                            Timber.d("Stable bearing updated: ${stableBearing}° (speed: ${loc.speed}m/s)")
                        } else if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.0f && loc.bearingAccuracyDegrees <= 90f) {
                            lastBearing = loc.bearing
                            Timber.d("Fallback GPS bearing used: ${loc.bearing}°")
                        }
                        lastLocation = loc
                        updateCurrentLocation(latLng)
                        maybeSendTelemetry(loc)
                        Timber.d("Fused location: $latLng, bearing=${loc.bearing}°, speed=${loc.speed}m/s acc=${loc.accuracy}m")
                    }
                }
            }

            fusedClient.requestLocationUpdates(
                request,
                fusedCallback as LocationCallback,
                mainLooper
            )
            isUsingFused = true
            // 마지막 알려진 위치 즉시 반영 (시뮬레이션 모드가 아닐 때만)
            if (mapLibreNavigation == null || mapLibreNavigation?.locationEngine !is SimulatedLocationEngine) {
                fusedClient.lastLocation.addOnSuccessListener { loc ->
                    loc?.let {
                        // Raw GPS 위치 직접 사용 (칼만 필터 제거)
                        val latLng = LatLng(it.latitude, it.longitude)
                        lastKnownLocation = latLng
                        lastLocationUpdateTime = System.currentTimeMillis()
                        lastFixElapsedMs = SystemClock.elapsedRealtime()
                        updateCurrentLocation(latLng)
                        Timber.d("Fused last known location: $latLng")
                    }
                }
            }
            Timber.d("Fused location updates started")
            true
        } catch (e: SecurityException) {
            Timber.e("Fused permission error: ${e.message}")
            false
        } catch (e: Exception) {
            Timber.e("Error starting fused updates: ${e.message}")
            false
        }
    }

    /**
     * 마지막 위치 저장 (Fused Location Provider에서 사용)
     */
    private var lastLocation: Location? = null

    /**
     * 현재 위치 업데이트
     */
    private fun updateCurrentLocation(latLng: LatLng) {
        try {
            // 시뮬레이션 모드일 때는 실제 GPS 위치 업데이트 무시
            // MapLibreNavigation이 활성화되어 있고 SimulatedLocationEngine을 사용 중이면 무시
            if (mapLibreNavigation != null && mapLibreNavigation?.locationEngine is SimulatedLocationEngine) {
                Timber.d("updateCurrentLocation: Ignoring real GPS location in simulation mode: $latLng")
                return
            }

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
            Timber.e(" Error updating current location: ${e.message}")
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

        Timber.d(
            "🚇 DR: v=%.2f m/s (ema), t=%.1f s, d=%.1f m (cap=%.1f m)"
                .format(speedMps, elapsedSec, estimatedDistance, remaining)
        )

        updateSpeedDisplay(speedMps)

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
        instructionMarkers.forEach { it.map = null }
        instructionMarkers.clear()
//        startMarker?.map = null
        endMarker?.map = null

        // NavigationActivity에서는 단색으로 경로 표시 (진행방향 패턴 제거)
        pathOverlays.add(PathOverlay().apply {
            coords = route.path
            color = resources.getColor(R.color.navigation_route_color, null)
            // patternImage 제거 - 진행방향 패턴 없음
            outlineColor = Color.WHITE
            width = 40
            map = nMap
        })

        // 각 안내 지점에 방향 화살표만 표시 (하이라이트 경로는 사용하지 않음)
        // 직진(type 4)만 제외하고 모든 instruction에 화살표 표시
        route.instructions.forEach { instruction ->
            // 직진(type 4)만 제외하고 나머지 모두 표시
            // type 1: 출발, 2: 좌회전, 3: 우회전, 5/6: 유턴, 68: 도시고속도로 진입, 88: 목적지, 95: 회전교차로 등
            val isStraight = instruction.type == 4
            if (!isStraight) {
                // 진행 방향 화살표 경로 계산 (분기점 전후의 경로 일부를 사용)
                val (arrowPath, _) = createDirectionArrow(instruction, route)

                // ArrowheadPathOverlay 사용: 경로를 따라가고 끝에 자동으로 화살표 머리가 생김
                if (arrowPath.isNotEmpty()) {
                    val arrowOverlay = ArrowheadPathOverlay().apply {
                        coords = arrowPath
                        color = Color.WHITE
                        outlineColor = Color.BLUE
                        width = 20
                        map = nMap
                    }
                    pathOverlays.add(arrowOverlay)
                }
            }
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

        Timber.d("Route displayed with ${route.path.size} points (single color)")
    }

    /**
     * 혼잡도별 색상으로 경로 표시 (제스처 모드에서 사용)
     */
    private fun displayRouteWithCongestion(route: NavigationRoute) {
        val nMap = naverMap ?: return

        // 기존 오버레이 제거
        pathOverlays.forEach { it.map = null }
        pathOverlays.clear()
        instructionMarkers.forEach { it.map = null }
        instructionMarkers.clear()

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
                        Timber.d("Added pre-section path: 0-$startIndex, congestion=$firstCongestion")
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
                            Timber.d("Added gap path: $lastEndIndex-$startIndex, congestion=$gapCongestion")
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
                Timber.d("Section: ${section.name}, pointIndex=$startIndex-$endIndex, congestion=${section.congestion}")
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
                    Timber.d("Added post-section path: $lastEndIndex-${route.path.size}, congestion=$lastCongestion")
                }
            }

            // 그룹화된 경로들을 PathOverlay로 표시
            groupedPaths.forEach { (path, congestion) ->
                val pathOverlay = PathOverlay().apply {
                    coords = path
                    color = getCongestionColor(congestion)
                    outlineColor = 0xFFFFFFFF.toInt() // 흰색 테두리
                    width = 40
                    map = nMap
                }
                pathOverlays.add(pathOverlay)
            }

            Timber.d("Total segments: ${groupedPaths.size}, Total points: ${route.path.size}")
        } else {
            // sections가 없으면 전체 경로를 하나로 표시
            val pathOverlay = PathOverlay().apply {
                coords = route.path
                color = 0xFF00AA00.toInt() // 기본 녹색
                outlineColor = 0xFFFFFFFF.toInt()
                width = 40
                map = nMap
            }
            pathOverlays.add(pathOverlay)
        }

        // 각 안내 지점에 방향 화살표만 표시 (혼잡도 모드에서도 동일하게 ArrowheadPathOverlay 사용)
        // 직진(type 4)만 제외하고 모든 instruction에 화살표 표시
        route.instructions.forEach { instruction ->
            // 직진(type 4)만 제외하고 나머지 모두 표시
            // type 1: 출발, 2: 좌회전, 3: 우회전, 5/6: 유턴, 68: 도시고속도로 진입, 88: 목적지, 95: 회전교차로 등
            val isStraight = instruction.type == 4
            if (!isStraight) {
                // 진행 방향 화살표 경로 계산 (분기점 전후의 경로 일부를 사용)
                val (arrowPath, _) = createDirectionArrow(instruction, route)

                // ArrowheadPathOverlay 사용: 경로를 따라가고 끝에 자동으로 화살표 머리가 생김
                if (arrowPath.isNotEmpty()) {
                    val arrowOverlay = ArrowheadPathOverlay().apply {
                        coords = arrowPath
                        color = Color.WHITE
                        outlineColor = Color.BLUE
                        width = 20
                        map = nMap
                    }
                    pathOverlays.add(arrowOverlay)
                }
            }
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

        Timber.d("Route displayed with ${route.path.size} points, ${pathOverlays.size} segments by congestion")
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
        if (!isInPictureInPictureModeCompat) {
            // 네비게이션 중이면 중지 버튼만 표시 (시작 버튼은 없음 - 자동 시작)
            binding.btnStopNavigation.visibility =
                if (state.isNavigating) View.VISIBLE else View.GONE

            // 현위치로 버튼은 제스처 모드에서만 표시
            binding.btnReturnToCurrentLocation.visibility =
                if (isGestureMode) View.VISIBLE else View.GONE
        }

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
        Timber.d("UI Update:")
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
        val baseNextDist =
            if (instruction.distance > 0) instruction.distance else distanceToInstruction
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
            Timber.w("NaverMap is null, cannot create marker")
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
        Timber.d("Current location marker created at: ${currentLocationMarker?.position}")
        Timber.d("Marker map: ${currentLocationMarker?.map}, visible: ${currentLocationMarker?.map != null}")
    }

    /**
     * 현재 위치 마커 업데이트 (MapLibre 스타일: 부드러운 애니메이션)
     */
    private fun updateCurrentLocationMarker(location: LatLng) {
        if (currentLocationMarker == null) {
            Timber.w("Current location marker is null, creating new one")
            createCurrentLocationMarker()
        }

        currentLocationMarker?.let { marker ->
            val oldPosition = marker.position

            // Naver Map SDK의 애니메이션 사용 (부드러운 이동)
            // Marker의 position을 직접 변경하면 자동으로 부드럽게 이동함
            marker.position = location

            // 마커가 지도에 표시되도록 보장
            val map = naverMap
            marker.map = map
            // 마커가 항상 보이도록 zIndex 업데이트
            marker.zIndex = 10000

            Timber.d("Current location marker updated (with animation):")
            Timber.d("   Old position: $oldPosition")
            Timber.d("   New position: $location")
            Timber.d("   Marker position: ${marker.position}")
        } ?: run {
            Timber.e("Failed to update current location marker - marker is null")
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
            Timber.d("Marker angle set to 0 (map will rotate instead)")
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
            Timber.d("Map moved to current location: $location")
        }
    }

    /** 속도 및 다음 분기 거리 기반 카메라 파라미터 계산 */
    private fun getAdaptiveCameraParams(): Pair<Double, Double> {
        val nextDistance =
            navigationManager.currentInstruction.value?.distanceToInstruction ?: Int.MAX_VALUE
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
                        Timber.d("Using route bearing: $bearing°")
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

                Timber.d("Navigation view: location=$location, GPS bearing=$bearing°, zoom=$lastNavigationZoom")
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
                Timber.d("Navigation view (default): location=$location, no bearing")
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

                Timber.d("Navigation view (lagged bearing): location=$location, bearing=$smoothedBearing° (target=$bearing°)")
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
     *
     * @deprecated Use findClosestPathPointWithWeight instead for better accuracy
     */
    private fun findClosestPathPointAhead(
        currentLocation: LatLng,
        path: List<LatLng>,
        currentIndex: Int
    ): Int {
        // 기본값으로 가중치 기반 메서드 호출 (하위 호환성)
        return findClosestPathPointWithWeight(
            currentLocation = currentLocation,
            path = path,
            currentIndex = currentIndex,
            currentBearing = null,
            currentSpeed = 0f
        )
    }

    /**
     * 가중치 기반 경로 매칭 (T맵 스타일)
     * 거리, 진행 방향, 속도, 진행률을 종합적으로 고려하여 최적의 경로상 위치를 찾습니다.
     * 공간 인덱싱을 사용하여 성능을 최적화합니다.
     *
     * @param currentLocation 현재 GPS 위치
     * @param path 경로 포인트 리스트
     * @param currentIndex 현재 경로 인덱스
     * @param currentBearing 현재 이동 방향 (도, 0-360), null이면 방향 점수 제외
     * @param currentSpeed 현재 속도 (m/s)
     * @return 최적의 경로상 인덱스
     */
    private fun findClosestPathPointWithWeight(
        currentLocation: LatLng,
        path: List<LatLng>,
        currentIndex: Int,
        currentBearing: Float?,
        currentSpeed: Float
    ): Int {
        try {
            if (path.isEmpty()) return 0
            if (path.size < 2) return currentIndex.coerceIn(0, path.size - 1)
            if (currentIndex < 0 || currentIndex >= path.size) return 0

            // 먼저 현재 위치에서 가장 가까운 경로 지점을 찾아서 거리 확인
            var minDistanceToPath = Float.MAX_VALUE
            var closestPointIndex = currentIndex
            for (i in 0 until path.size) {
                val dist = calculateDistance(currentLocation, path[i])
                if (dist < minDistanceToPath) {
                    minDistanceToPath = dist
                    closestPointIndex = i
                }
            }

            // 경로 이탈 정도에 따라 검색 범위 동적 조정
            val isFarFromPath = minDistanceToPath > 100f  // 100m 이상 떨어져 있으면 "멀리 이탈"
            val isVeryFarFromPath = minDistanceToPath > 60f  // 60m 이상 떨어져 있으면 "매우 멀리 이탈" (즉시 재탐색)

            // 속도 기반 기본 검색 범위 (고속도로 대응)
            val baseSearchRange = when {
                currentSpeed > 33.3f -> 500  // 초고속 (120km/h 이상): 매우 넓은 범위
                currentSpeed > 27.8f -> 400  // 고속 (100km/h 이상): 넓은 범위
                currentSpeed > 13.9f -> 200  // 중고속 (50km/h 이상): 넓은 범위
                currentSpeed > 4.2f -> 100   // 중속 (15km/h 이상): 기본 범위
                else -> 50                   // 저속: 좁은 범위
            }

            // 경로 이탈 시 검색 범위 확대
            val searchRange = when {
                isVeryFarFromPath -> path.size  // 매우 멀리 이탈 시 전체 경로 검색
                isFarFromPath -> baseSearchRange * 3  // 멀리 이탈 시 3배 확대
                else -> baseSearchRange
            }

            // 공간 인덱싱 사용 여부 결정
            val useSpatialIndex = pathSpatialIndex?.isAvailable() == true && path.size >= 100

            // 검색할 인덱스 범위 결정
            val searchIndices = if (useSpatialIndex) {
                // 공간 인덱스를 사용하여 근접 포인트만 검색
                val radiusMeters = when {
                    isVeryFarFromPath -> 1000.0  // 매우 멀리 이탈 시 1km 반경
                    isFarFromPath -> 700.0  // 멀리 이탈 시 700m 반경
                    currentSpeed > 33.3f -> 1000.0  // 초고속 (120km/h 이상): 1km 반경
                    currentSpeed > 27.8f -> 800.0  // 고속 (100km/h 이상): 800m 반경
                    currentSpeed > 13.9f -> 500.0  // 중고속 (50km/h 이상): 500m 반경
                    currentSpeed > 4.2f -> 300.0  // 중속: 300m 반경
                    else -> 150.0                  // 저속: 150m 반경
                }
                val nearbyIndices = pathSpatialIndex!!.findNearbyPoints(
                    center = currentLocation,
                    radiusMeters = radiusMeters,
                    startIndex = if (isVeryFarFromPath || isFarFromPath) 0 else currentIndex  // 멀리 이탈 시 시작 인덱스 제한 없음
                )
                // 경로 이탈 시에는 검색 범위 제한 없음
                if (isVeryFarFromPath || isFarFromPath) {
                    nearbyIndices
                } else {
                    val maxIndex = minOf(currentIndex + baseSearchRange, path.size)
                    nearbyIndices.filter { it in currentIndex until maxIndex }
                }
            } else {
                // 기존 방식: 순차 검색
                if (isVeryFarFromPath || isFarFromPath) {
                    // 멀리 이탈 시 전체 경로 검색
                    (0 until path.size).toList()
                } else {
                    (currentIndex until minOf(currentIndex + searchRange, path.size)).toList()
                }
            }

            val searchEnd = if (isVeryFarFromPath || isFarFromPath) path.size else minOf(
                currentIndex + searchRange,
                path.size
            )

            var bestScore = Float.MAX_VALUE
            var bestIndex = closestPointIndex  // 초기값을 가장 가까운 지점으로 설정

            Timber.d("Weighted path matching: speed=${currentSpeed}m/s, bearing=${currentBearing}°, distanceToPath=${minDistanceToPath.toInt()}m, range=$searchRange, spatialIndex=${if (useSpatialIndex) "ON" else "OFF"}, candidates=${searchIndices.size}, farFromPath=$isFarFromPath")

            // 경로상의 선분들에 대한 가중치 점수 계산
            // 공간 인덱스를 사용하면 후보 인덱스만 검색, 아니면 기존 방식
            val indicesToCheck = if (useSpatialIndex) {
                searchIndices.filter { it < path.size - 1 }  // 선분 검색을 위해 마지막 인덱스 제외
            } else {
                (0 until searchEnd - 1).toList()  // 경로 이탈 시 전체 검색
            }

            for (i in indicesToCheck) {
                val p1 = path.getOrNull(i) ?: continue
                val p2 = path.getOrNull(i + 1) ?: continue

                // 1. 거리 점수 (가까울수록 좋음) - 가중치: 1.0
                val distanceToSegment = distanceToLineSegment(currentLocation, p1, p2)
                val distanceScore = distanceToSegment * 1.0f

                // 2. 진행 방향 점수 (방향이 맞을수록 좋음) - 가중치: 0.1
                val directionScore = if (currentBearing != null && currentBearing > 0f) {
                    val pathBearing = calculateBearing(p1, p2)
                    val bearingDiff = abs(shortestAngleDiff(currentBearing, pathBearing))
                    // 방향 차이가 클수록 페널티 (0-180도 범위)
                    bearingDiff * 0.1f
                } else {
                    0f
                }

                // 3. 진행률 점수 (뒤로 가면 페널티) - 거리 기반으로 조정
                // 경로에서 멀리 떨어져 있으면 뒤로 가는 것에 대한 페널티를 줄임
                val progressScore = if (i < currentIndex) {
                    val penaltyMultiplier = when {
                        isVeryFarFromPath -> 0.5f  // 매우 멀리 이탈 시 페널티 50% 감소
                        isFarFromPath -> 1.0f  // 멀리 이탈 시 페널티 유지
                        distanceToSegment > 100f -> 1.5f  // 선분에서 멀면 페널티 약간 증가
                        else -> 10.0f  // 가까우면 기존처럼 큰 페널티
                    }
                    (currentIndex - i) * penaltyMultiplier
                } else {
                    0f
                }

                // 4. 속도 기반 보너스 (고속일 때 가까운 경로에 보너스) - 고속도로 대응
                val speedBonus = when {
                    // 초고속 (120km/h 이상)에서는 더 넓은 범위에서 보너스
                    currentSpeed > 33.3f && distanceToSegment < 150f -> -8f  // 초고속 + 가까우면 큰 보너스
                    currentSpeed > 27.8f && distanceToSegment < 120f -> -6f  // 고속 (100km/h 이상) + 가까우면 보너스
                    currentSpeed > 10f && distanceToSegment < 100f -> -5f  // 중고속 + 가까우면 보너스
                    currentSpeed < 1f && distanceToSegment > 50f -> 20f  // 정지 중 + 멀면 페널티
                    else -> 0f
                }

                // 5. 선분 길이 고려 (짧은 선분은 더 정확하게 매칭)
                val segmentLength = calculateDistance(p1, p2)
                val segmentLengthBonus = if (segmentLength < 10f && distanceToSegment < 20f) {
                    -2f  // 짧은 선분 + 가까우면 보너스
                } else {
                    0f
                }

                val totalScore =
                    distanceScore + directionScore + progressScore + speedBonus + segmentLengthBonus

                if (totalScore < bestScore) {
                    bestScore = totalScore
                    // 선분에 가장 가까운 지점이 p1에 가까우면 i, p2에 가까우면 i+1
                    val distToP1 = calculateDistance(currentLocation, p1)
                    val distToP2 = calculateDistance(currentLocation, p2)
                    bestIndex = if (distToP1 < distToP2) i else i + 1
                }
            }

            // 경로상의 점들과의 직접 거리도 확인 (더 정확한 매칭을 위해)
            val pointIndicesToCheck = if (useSpatialIndex) {
                searchIndices  // 공간 인덱스 후보 사용
            } else {
                (0 until searchEnd).toList()  // 경로 이탈 시 전체 검색
            }

            for (i in pointIndicesToCheck) {
                val point = path.getOrNull(i) ?: continue
                val distance = calculateDistance(currentLocation, point)

                // 점 거리도 가중치 적용
                var pointScore = distance * 1.0f

                // 진행률 페널티 - 거리 기반으로 조정
                if (i < currentIndex) {
                    val penaltyMultiplier = when {
                        isVeryFarFromPath -> 0.5f  // 매우 멀리 이탈 시 페널티 50% 감소
                        isFarFromPath -> 1.0f  // 멀리 이탈 시 페널티 유지
                        distance > 100f -> 1.5f  // 점에서 멀면 페널티 약간 증가
                        else -> 10.0f  // 가까우면 기존처럼 큰 페널티
                    }
                    pointScore += (currentIndex - i) * penaltyMultiplier
                }

                // 방향 점수
                if (currentBearing != null && currentBearing > 0f && i < path.size - 1) {
                    val nextPoint = path.getOrNull(i + 1)
                    if (nextPoint != null) {
                        val pathBearing = calculateBearing(point, nextPoint)
                        val bearingDiff = abs(shortestAngleDiff(currentBearing, pathBearing))
                        pointScore += bearingDiff * 0.1f
                    }
                }

                if (pointScore < bestScore) {
                    bestScore = pointScore
                    bestIndex = i
                }
            }

            val finalDistance = calculateDistance(currentLocation, path[bestIndex])
            Timber.d("Best match: index=$bestIndex (was $currentIndex), score=$bestScore, distance=${finalDistance}m, farFromPath=$isFarFromPath")

            return bestIndex.coerceIn(0, path.size - 1)
        } catch (e: Exception) {
            Timber.e(" Error in findClosestPathPointWithWeight: ${e.message}")
            e.printStackTrace()
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
     * 지나온 경로 숨기기
     * - Naver PathOverlay.progress + passedColor 를 사용해서
     *   지나온 구간은 투명 처리하고, 남은 구간만 보이게 만든다.
     * - 별도의 PathOverlay 재생성 / 좌표 잘라내기 필요 없음.
     */
    private fun updatePassedRoute(path: List<LatLng>, passedIndex: Int) {
        if (path.isEmpty()) return
        if (pathOverlays.isEmpty()) return

        // 네비게이션 메인 경로용 PathOverlay 찾기
        val pathOverlay = pathOverlays.filterIsInstance<PathOverlay>().firstOrNull() ?: return

        // 인덱스를 0~(size-1) 범위로 클램프
        val clampedIndex = passedIndex.coerceIn(0, path.size - 1)

        // 전체 길이 대비 지나온 비율 (0.0 ~ 1.0)
        val progress = if (path.size <= 1) {
            0f
        } else {
            clampedIndex.toFloat() / (path.size - 1).toFloat()
        }

        // 지나온 구간은 투명, 남은 구간은 기존 color 로 표시
        pathOverlay.passedColor = Color.TRANSPARENT
        pathOverlay.progress = progress.toDouble()

        Timber.d("Updated route progress: index=$passedIndex (clamped=$clampedIndex), progress=$progress")
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

                Timber.d("Route-based navigation: location=$location (center), bearing=$smoothedBearing°")
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
        if(location.latitude == 0.0 || location.longitude == 0.0) return
        lastTelemetrySentElapsed = now

        val navType = if (location.accuracy <= 30f) 1 else 2

        val payload = VehicleLocationPayload(
            vecNavType = navType,
            vecLat = location.latitude,
            vecLon = location.longitude,
            vecAcc = location.accuracy.toDouble(),
            regDate = telemetryDateFormat.format(Date())
        )

        navigationViewModel.sendTelemetry(vehicleId, payload)
    }

    private fun updateAccuracyState(accuracy: Float) {
        if (accuracy <= ACCURACY_SNAP_THRESHOLD) {
            accuracyStableCount++
            if (inLowAccuracyMode && accuracyStableCount >= ACCURACY_STABLE_COUNT) {
                inLowAccuracyMode = false
                Timber.d("Accuracy recovered (<=${ACCURACY_SNAP_THRESHOLD}m)")
            }
        } else {
            if (!inLowAccuracyMode) {
                Timber.w("Entering low accuracy mode: accuracy=${accuracy}m")
            }
            inLowAccuracyMode = true
            accuracyStableCount = 0
        }
    }

    /**
     * 스마트 재탐색 조건 평가 (T맵 스타일)
     * 여러 신호를 종합하여 재탐색이 필요한지 판단합니다.
     *
     * @param distanceToPath 경로로부터의 거리 (미터)
     * @param currentSpeed 현재 속도 (m/s)
     * @param accuracy GPS 정확도 (미터)
     * @param timeSinceLastReroute 마지막 재탐색으로부터 경과 시간 (밀리초)
     * @param consecutiveOffRouteCount 연속 이탈 횟수
     * @param isInTunnel 터널 모드 여부
     * @param lastKnownBearing 현재 이동 방향 (도, null 가능)
     * @param pathBearing 경로 방향 (도, null 가능)
     * @return 재탐색 결정 결과
     */
//    private fun evaluateRerouteCondition(
//        distanceToPath: Float,
//        currentSpeed: Float,
//        accuracy: Float,
//        timeSinceLastReroute: Long,
//        consecutiveOffRouteCount: Int,
//        isInTunnel: Boolean,
//        lastKnownBearing: Float?,
//        pathBearing: Float?
//    ): RerouteDecision {
//        // 1. 기본 조건 체크 - 재탐색 불가능한 상황
//        if (isInTunnel) {
//            return RerouteDecision(false, 0f, "터널 모드")
//        }
//        if (accuracy > OFF_ROUTE_MIN_ACCURACY) {
//            return RerouteDecision(false, 0f, "GPS 정확도 낮음 (${accuracy}m)")
//        }
//        if (timeSinceLastReroute < REROUTE_COOLDOWN_MS) {
//            return RerouteDecision(false, 0f, "쿨다운 중 (${timeSinceLastReroute}ms)")
//        }
//        // 속도 조건: Mock location이나 텔레포트 감지 시 완화
//        // 거리가 매우 멀면 (100m 이상) 속도 조건 무시
//        if (currentSpeed < 0.3f && distanceToPath < 100f) {
//            return RerouteDecision(false, 0f, "정지 중 (${currentSpeed}m/s, 거리=${distanceToPath.toInt()}m)")
//        }
//
//        var confidence = 0f
//        val reasons = mutableListOf<String>()
//
//        // 2. 거리 기반 신호 (가장 중요한 요소) - 더 민감하게 조정
//        when {
//            distanceToPath >= 60f -> {
//                confidence += 0.7f  // 60m 이상 이탈 시 매우 높은 확신도 (즉시 재탐색)
//                reasons.add("거리 60m 이상 (즉시 재탐색)")
//            }
//            distanceToPath >= 100f -> {
//                confidence += 0.5f  // 멀리 이탈 시 높은 확신도
//                reasons.add("거리 100m 이상")
//            }
//            distanceToPath >= REROUTE_THRESHOLD -> {
//                confidence += 0.4f  // 기본 임계값 이상
//                reasons.add("거리 ${REROUTE_THRESHOLD.toInt()}m 이상")
//            }
//            distanceToPath >= 50f -> {
//                confidence += 0.3f  // 50m 이상도 상당한 신호
//                reasons.add("거리 50m 이상")
//            }
//            distanceToPath >= 30f -> {
//                confidence += 0.15f  // 30m 이상도 약간의 신호
//                reasons.add("거리 30m 이상")
//            }
//        }
//
//        // 3. 방향 불일치 신호 (방향이 많이 다르면 재탐색 필요) - 더 민감하게
//        if (lastKnownBearing != null && pathBearing != null && lastKnownBearing > 0f) {
//            val bearingDiff = abs(shortestAngleDiff(lastKnownBearing, pathBearing))
//            if (bearingDiff > 45f && currentSpeed > 5f) {
//                confidence += 0.35f  // 방향 불일치 시 높은 신호
//                reasons.add("방향 불일치 ${bearingDiff.toInt()}°")
//            } else if (bearingDiff > 30f && currentSpeed > 10f) {
//                confidence += 0.25f
//                reasons.add("방향 차이 ${bearingDiff.toInt()}° (고속)")
//            } else if (bearingDiff > 30f && distanceToPath > 50f) {
//                confidence += 0.15f  // 중속이어도 거리가 멀면 신호
//                reasons.add("방향 차이 ${bearingDiff.toInt()}°")
//            }
//        }
//
//        // 4. 연속 이탈 신호 (여러 번 이탈하면 확신도 증가)
//        when {
//            consecutiveOffRouteCount >= 3 -> {
//                confidence += 0.25f  // 연속 이탈 시 높은 신호
//                reasons.add("연속 이탈 ${consecutiveOffRouteCount}회")
//            }
//            consecutiveOffRouteCount >= 2 -> {
//                confidence += 0.15f
//                reasons.add("연속 이탈 ${consecutiveOffRouteCount}회")
//            }
//            consecutiveOffRouteCount >= 1 -> {
//                confidence += 0.05f  // 1회 이탈도 약간의 신호
//                reasons.add("이탈 감지")
//            }
//        }
//
//        // 5. 속도 기반 신호 (고속일 때 더 민감하게 반응)
//        if (currentSpeed > 15f && distanceToPath > 50f) {
//            confidence += 0.15f  // 고속 주행 중 이탈 시 높은 신호
//            reasons.add("고속 주행 중 (${(currentSpeed * 3.6f).toInt()}km/h)")
//        } else if (currentSpeed > 10f && distanceToPath > 70f) {
//            confidence += 0.1f
//            reasons.add("중속 주행 중")
//        } else if (currentSpeed > 5f && distanceToPath > 100f) {
//            confidence += 0.1f  // 중저속이어도 거리가 멀면 신호
//            reasons.add("주행 중")
//        }
//
//        // 6. 정확도 기반 보정 (정확도가 좋으면 더 확신)
//        if (accuracy < 20f && distanceToPath > 50f) {
//            confidence += 0.1f  // 정확도가 좋으면 확신도 증가
//            reasons.add("GPS 정확도 양호")
//        } else if (accuracy > 50f) {
//            confidence -= 0.05f  // 정확도가 나쁘면 확신도 약간 감소 (완화)
//            reasons.add("GPS 정확도 낮음")
//        }
//
//        // 7. 예측 기반 신호 (5초 후 위치 예측)
//        if (lastKnownBearing != null && lastKnownBearing > 0f && currentSpeed > 5f) {
//            currentSpeed * 5f  // 5초 후 거리
//            // 예측 위치는 evaluateRerouteCondition 호출부에서 계산하여 전달받음
//            // 여기서는 예측 기반 신호를 별도로 처리하지 않음 (호출부에서 처리)
//        }
//
//        // 확신도는 0.0 ~ 1.0 범위로 제한
//        confidence = confidence.coerceIn(0f, 1f)
//
//        // 50% 이상 확신 시 재탐색 (기존 60%에서 완화) - 더 민감하게 반응
//        // 단, 거리가 매우 멀면 (60m 이상) 확신도와 관계없이 즉시 재탐색
//        val shouldReroute = confidence >= 0.5f || distanceToPath >= 60f
//
//        return RerouteDecision(
//            shouldReroute,
//            confidence,
//            if (reasons.isEmpty()) "조건 불충족" else reasons.joinToString(", ")
//        )
//    }

    /**
     * 경로 재검색 요청
     */
    private fun requestReroute(currentLocation: LatLng) {
        if (isRerouting) {
            Timber.d("Already rerouting, skipping request")
            return
        }

        isRerouting = true
        Timber.d("Requesting reroute from current location: $currentLocation")
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
            Timber.d("Navigation mode started - Manual camera control enabled")
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
            Timber.d("Navigation mode stopped - Follow tracking enabled")
        }
    }


    override fun onDestroy() {
        unregisterPipActionReceiver()
        super.onDestroy()
        stopNavigationMode()
        navigationManager.stopNavigation()
        mapLibreNavigation?.onDestroy()
        voiceGuideManager.release()

        // 위치 업데이트 중지
        try {
            if (isUsingFused && fusedCallback != null) {
                fusedClient.removeLocationUpdates(fusedCallback!!)
                isUsingFused = false
                Timber.d("Fused location updates stopped")
            }
        } catch (e: Exception) {
            Timber.e("Error stopping location updates: ${e.message}")
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

    private fun updateSpeedDisplay(speedMps: Float?) {
        val speedText = speedMps
            ?.takeIf { it.isFinite() && it >= 0f }
            ?.let { mps ->
                val speedKmh = (mps * 3.6f).coerceAtLeast(0f)
                String.format(Locale.getDefault(), "%d km/h", speedKmh.roundToInt())
            } ?: "-- km/h"

        binding.tvCurrentSpeed.text = speedText
    }

    /**
     * Instruction 지점 앞쪽 경로 구간을 하얀색으로 강조할 경로 반환
     * 분기점 앞쪽부터 화살표 위치까지 하얀색으로 표시
     */
    private fun getInstructionHighlightPath(
        instruction: Instruction,
        route: NavigationRoute,
        arrowCenter: LatLng? = null
    ): List<LatLng> {
        val pointIndex = instruction.pointIndex
        if (pointIndex >= route.path.size) return emptyList()

        val highlightPath = mutableListOf<LatLng>()

        // 1. 분기점 앞쪽 20미터 정도의 경로 구간
        val beforeDistanceMeters = 20.0
        var accumulatedDistance = 0.0

        // instruction 지점부터 역방향으로 경로 추적
        for (i in pointIndex downTo 1) {
            val segment = route.path.subList(i - 1, i + 1)
            val segmentDistance = calculateDistance(segment[0], segment[1])

            if (accumulatedDistance + segmentDistance <= beforeDistanceMeters) {
                highlightPath.add(0, segment[0])  // 앞에 추가 (역방향이므로)
                accumulatedDistance += segmentDistance
            } else {
                // 남은 거리만큼 보간
                val remainingDistance = beforeDistanceMeters - accumulatedDistance
                val ratio = remainingDistance / segmentDistance
                val interpolated = interpolateLatLng(segment[0], segment[1], ratio.toFloat())
                highlightPath.add(0, interpolated)
                break
            }
        }

        // 2. 분기점 추가
        highlightPath.add(route.path[pointIndex])

        // 3. 화살표 위치까지 경로 연장 (분기점부터 화살표까지)
        if (arrowCenter != null && pointIndex < route.path.size - 1) {
            // 분기점부터 화살표 위치까지의 경로 찾기
            var foundArrowPoint = false
            var currentDistance = 0.0

            for (i in pointIndex until route.path.size - 1) {
                val segmentStart = route.path[i]
                val segmentEnd = route.path[i + 1]
                val segmentDistance = calculateDistance(segmentStart, segmentEnd)

                // 화살표 위치와의 거리 확인
                val distToArrow = calculateDistance(segmentEnd, arrowCenter)

                if (distToArrow < 5.0) { // 5미터 이내면 화살표 위치 근처
                    // 세그먼트 내에서 화살표 위치에 가장 가까운 지점 찾기
                    val totalDist = calculateDistance(segmentStart, segmentEnd)
                    val distFromStart = calculateDistance(segmentStart, arrowCenter)

                    if (distFromStart < totalDist) {
                        // 세그먼트 내에 화살표 위치가 있으면 보간
                        val ratio = (distFromStart / totalDist).toFloat().coerceIn(0f, 1f)
                        val interpolated = interpolateLatLng(segmentStart, segmentEnd, ratio)
                        highlightPath.add(interpolated)
                    } else {
                        highlightPath.add(segmentEnd)
                    }
                    foundArrowPoint = true
                    break
                } else {
                    highlightPath.add(segmentEnd)
                    currentDistance += segmentDistance
                }
            }

            // 화살표 위치를 찾지 못했으면 마지막으로 화살표 위치 추가
            if (!foundArrowPoint) {
                highlightPath.add(arrowCenter)
            }
        }

        return highlightPath
    }

    /**
     * Instruction 지점 주변의 경로 일부를 잘라서 ArrowheadPathOverlay용 경로 생성
     * - Naver SDK가 polyline의 끝점 방향으로 자동으로 화살표 머리를 그려주므로
     *   여기서는 "분기점 전후의 짧은 경로"만 만들어주면 됨.
     *
     * @return Pair<화살표 경로(Polyline), 분기점 위치>
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
     * 두 지점 사이 보간
     */
    private fun interpolateLatLng(from: LatLng, to: LatLng, ratio: Float): LatLng {
        val lat = from.latitude + (to.latitude - from.latitude) * ratio
        val lng = from.longitude + (to.longitude - from.longitude) * ratio
        return LatLng(lat, lng)
    }


    /**
     * MapLibreNavigation 초기화 및 리스너 등록
     */
    private fun initializeMapLibreNavigation(route: NavigationRoute, useSimulation: Boolean = false) {
        // 기존 인스턴스 정리
        mapLibreNavigation?.onDestroy()

        // LocationEngine 선택 (시뮬레이션 또는 실제 GPS)
        val locationEngine = if (useSimulation) {
            SimulatedLocationEngine(route, updateIntervalMs = 1000L) // 1초마다 다음 위치로 이동
        } else {
            AndroidLocationEngine(fusedClient)
        }

        mapLibreNavigation = MapLibreNavigation(
            options = MapLibreNavigationOptions(
                snapToRoute = true,
                offRouteMinimumDistanceMetersAfterReroute = 50f
            ),
            locationEngine = locationEngine,
            offRouteDetector = offRouteDetector
        )

        // ProgressChangeListener 등록
        mapLibreNavigation?.addProgressChangeListener(object : ProgressChangeListener {
            override fun onProgressChange(
                location: NavigationLocation,
                routeProgress: RouteProgress
            ) {
                // UI 업데이트
                updateNavigationFromMapLibre(location, routeProgress)
            }
        })

        // OffRouteListener 등록
        mapLibreNavigation?.addOffRouteListener(object : OffRouteListener {
            override fun onUserOffRoute(location: NavigationLocation) {
                // 시뮬레이션 모드일 때는 재탐색 무시
                if (mapLibreNavigation?.locationEngine is SimulatedLocationEngine) {
                    Timber.d("OffRouteListener: Ignoring off-route event in simulation mode")
                    return
                }

                // 재탐색 요청
                if (!isRerouting) {
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastReroute = currentTime - lastRerouteTime
                    if (timeSinceLastReroute >= REROUTE_COOLDOWN_MS && !isInTunnel) {
                        val rerouteFrom = location.latLng
                        requestReroute(rerouteFrom)
                        lastRerouteTime = currentTime
                    }
                }
            }
        })

        // 네비게이션 시작
        Timber.d("initializeMapLibreNavigation: Starting navigation with route, useSimulation=$useSimulation, locationEngine type=${locationEngine::class.simpleName}")
        mapLibreNavigation?.startNavigation(route)
        Timber.d("MapLibreNavigation initialized and started with route")
    }

    /**
     * MapLibreNavigation의 RouteProgress를 기반으로 UI 업데이트
     */
    private fun updateNavigationFromMapLibre(
        location: NavigationLocation,
        routeProgress: RouteProgress
    ) {
        if (!isMapReady || isGestureMode) return

        try {
            val snappedLatLng = location.latLng
            val bearing = location.bearing ?: lastBearing

            Timber.d("updateNavigationFromMapLibre: location=${snappedLatLng}, bearing=$bearing, pathIndex=${routeProgress.pathIndex}")

            // 경로 인덱스 업데이트
            currentPathIndex = routeProgress.pathIndex

            // 마커 업데이트 (스냅된 위치)
            updateCurrentLocationMarker(snappedLatLng)
            Timber.d("updateNavigationFromMapLibre: Marker updated to $snappedLatLng")

            // 진행 방향 업데이트
            if (bearing > 0f) {
                followRouteWithBearing(snappedLatLng, bearing)
                updateCurrentLocationMarkerDirection(bearing)
                lastBearing = bearing
            } else {
                followRoute(snappedLatLng)
            }

            // 도착 확인
            val distanceToDestination = routeProgress.distanceRemaining
            if (distanceToDestination <= ARRIVAL_THRESHOLD) {
                Timber.d("Arrived at destination! (${distanceToDestination}m)")
                mapLibreNavigation?.stopNavigation()
                navigationManager.stopNavigation()
                Toast.makeText(this, "목적지에 도착했습니다!", Toast.LENGTH_SHORT).show()
            }

            // 안내 메시지 업데이트
            routeProgress.currentInstruction?.let { instruction ->
                val currentMessage = binding.tvCurrentInstruction.text?.toString()
                val newMessage = instruction.message
                if (currentMessage != newMessage) {
                    Timber.d("Instruction message changed: '$currentMessage' -> '$newMessage' (instructionIndex=${routeProgress.instructionIndex}, pathIndex=${routeProgress.pathIndex})")
                }
                updateInstructionUI(instruction)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error updating navigation from MapLibre")
        }
    }
}