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
import timber.log.Timber
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

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val OFF_ROUTE_THRESHOLD = 30f  // 오차 범위 (미터) - GPS 오차를 고려하여 증가
        private const val ARRIVAL_THRESHOLD = 25f  // 도착 판정 거리 (미터)
        private const val REROUTE_THRESHOLD = 70f  // 경로 재검색 임계값 (미터) - OFF_ROUTE보다 충분히 큼
        private const val GESTURE_TIMEOUT = 10000L  // 제스처 모드 자동 복귀 시간 (10초)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 네비게이션 매니저 초기화
        navigationManager = NavigationManager(this, lifecycleScope)
        voiceGuideManager = VoiceGuideManager(this)
        
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
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapView_navigation) as MapFragment?
            ?: MapFragment.newInstance().also {
                supportFragmentManager.beginTransaction().replace(R.id.mapView_navigation, it).commit()
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
                                // 1. 앞으로 진행할 경로에서 가장 가까운 지점 찾기
                                val nearestPoint = findClosestPathPointAhead(currentLocation, route.path, currentPathIndex)
                                val distanceToPath = calculateDistance(currentLocation, route.path[nearestPoint])
                                
                                Timber.d("📍 GPS Location: $currentLocation")
                                Timber.d("📍 Nearest path point index: $nearestPoint (current: $currentPathIndex), distance: ${distanceToPath}m")
                                
                                // 2. 경로 이탈 확인 - 70m 이상이면 재검색
                                if (distanceToPath >= REROUTE_THRESHOLD && !isRerouting) {
                                    val currentTime = System.currentTimeMillis()
                                    // 최소 5초 간격으로 재검색 제한 (너무 자주 재검색 방지)
                                    if (currentTime - lastRerouteTime > 5000) {
                                        Timber.d("🔄 Off-route detected! Distance: ${distanceToPath}m - Initiating reroute...")
                                        requestReroute(currentLocation)
                                        lastRerouteTime = currentTime
                                        
                                        // 경로 이탈 시에는 실제 GPS 위치에 마커 표시
                                        updateCurrentLocationMarker(currentLocation)
                                        followRoute(currentLocation)
                                    } else {
                                        Timber.d("⏳ Reroute request skipped (cooldown)")
                                    }
                                } else {
                                    // 3. 70m 이내면 항상 경로 위에 스냅 (팩맨처럼!)
                                    // 재검색 플래그 해제 (경로 복귀)
                                    if (isRerouting) {
                                        isRerouting = false
                                        Timber.d("✅ Returned to route")
                                    }
                                    
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
                                    val bearingIndex = if (currentPathIndex > 0) currentPathIndex - 1 else currentPathIndex
                                    val bearing = calculateBearingFromPath(route.path, bearingIndex)
                                    if (bearing >= 0) {
                                        followRouteWithBearing(pathLocation, bearing)
                                        updateCurrentLocationMarkerDirection(bearing)
                                    } else {
                                        followRoute(pathLocation)
                                    }
                                    
                                    // 6. 도착지 근처 도착 확인 (25미터)
                                    val distanceToDestination = calculateDistance(pathLocation, route.summary.endLocation)
                                    if (distanceToDestination <= ARRIVAL_THRESHOLD) {
                                        Timber.d("✅ Arrived at destination! (${distanceToDestination}m)")
                                        navigationManager.stopNavigation()
                                        Toast.makeText(this, "목적지에 도착했습니다!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    } ?: run {
                        Timber.w("📍 No route available")
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
                    }
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
                    }
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
            route?.let {
                displayRoute(it)
                
                // 재검색인 경우
                if (isRerouting) {
                    // 재검색 완료 처리
                    isRerouting = false
                    currentPathIndex = 0  // 새로운 경로이므로 인덱스 초기화
                    Toast.makeText(this, "경로를 재검색했습니다", Toast.LENGTH_SHORT).show()
                    Timber.d("✅ Reroute completed, new route displayed")
                }
                
                navigationManager.startNavigation(it)
                
                // 네비게이션 시작 시 마커를 출발지로 초기 설정
                if (isMapReady && currentLocationMarker != null) {
                    // 출발지로 마커 위치 설정
                    updateCurrentLocationMarker(route.summary.startLocation)
                    Timber.d("📍 Marker initialized to start location: ${route.summary.startLocation}")
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
        
        binding.switchVoiceGuide.setOnCheckedChangeListener { _, isChecked ->
            voiceGuideManager.setEnabled(isChecked)
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
                0.0,
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
                    0.0,
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
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
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
        Timber.d("📍 startLocationUpdates() called")
        
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            // GPS가 활성화되어 있는지 확인
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            
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
                updateCurrentLocation(latLng)
                Timber.d("📍 Using last known location: $latLng")
            } else {
                Timber.w("📍 No last known location available")
            }
            
            Timber.d("📍 Location updates started successfully")
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
            val latLng = LatLng(location.latitude, location.longitude)
            
            // GPS bearing을 사용하여 방향 업데이트 (실제 이동 방향 반영)
            if (location.hasBearing() && location.hasSpeed() && location.speed > 1.0f) {
                // 속도가 1m/s 이상일 때만 GPS bearing 사용 (정지 시 방향 변경 방지)
                lastBearing = location.bearing
                Timber.d("🧭 GPS bearing updated: ${location.bearing}° (speed: ${location.speed}m/s)")
            }
            
            lastLocation = location
            updateCurrentLocation(latLng)
            Timber.d("📍 Location updated: $latLng, bearing: ${location.bearing}°, speed: ${location.speed}m/s")
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
                    val lastCongestion = currentCongestion ?: sortedSections.lastOrNull()?.congestion ?: 0
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
        binding.btnReturnToCurrentLocation.visibility = if (isGestureMode) View.VISIBLE else View.GONE
        
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
        Timber.d("   Remaining Distance: ${state.remainingDistance}m (${String.format("%.1f", distanceKm)}km)")
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
        // NavigationManager에서 계산된 거리 정보 사용
        val distanceToInstruction = instruction.distanceToInstruction
        
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
        
        // 다음 안내 메시지 표시 (간단한 예시)
        val nextMessage = if (instruction.distance > 1000) {
            "앞으로 ${instruction.distance / 1000}km 직진하세요"
        } else {
            "앞으로 ${instruction.distance}m 직진하세요"
        }
        binding.tvNextInstruction.text = nextMessage
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
                // 네비게이션 모드의 줌과 방향 저장
                lastNavigationZoom = 17.0
                lastNavigationBearing = bearing
                
                // 현재 위치를 중심으로 한 카메라 설정
                val cameraPosition = CameraPosition(
                    location,            // 카메라 타겟 (현재 위치를 중앙에)
                    lastNavigationZoom,  // 줌 레벨
                    0.0,                 // 기울기
                    bearing.toDouble()   // GPS bearing (실제 이동 방향)
                )
                
                val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
                    .animate(CameraAnimation.Easing, 200)
                map.moveCamera(cameraUpdate)
                
                Timber.d("🗺️ Navigation view: location=$location, GPS bearing=$bearing°, zoom=$lastNavigationZoom")
            } else {
                // 기본 뷰 (bearing 없을 때)
                val cameraPosition = CameraPosition(
                    location,
                    17.0,
                    0.0,
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
            
            val smoothedBearing = if (Math.abs(diff) > 30f) {
                // 급격한 변화는 제한 (최대 30도씩만)
                normalizeBearing(lastBearing + if (diff > 0) 30f else -30f)
            } else if (Math.abs(diff) > 1f) {
                // 부드러운 보간 (60% 적용)
                normalizeBearing(lastBearing + diff * 0.6f)
            } else {
                // 변화량이 작으면 이전 베어링 유지
                lastBearing
            }
            
            if (smoothedBearing > 0) {
                lastBearing = smoothedBearing
                
                // 네비게이션 모드의 줌과 방향 저장
                lastNavigationZoom = 17.0
                lastNavigationBearing = smoothedBearing
                
                // 현재 위치를 중심으로 한 카메라 설정
                val cameraPosition = CameraPosition(
                    location,
                    lastNavigationZoom,
                    0.0,
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
    private fun findNearestPathPoint(currentLocation: LatLng, path: List<LatLng>, startIndex: Int = 0): Pair<Int, Float>? {
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
    private fun findClosestPathPointAhead(currentLocation: LatLng, path: List<LatLng>, currentIndex: Int): Int {
        if (path.size < 2) return currentIndex
        
        var minDistance = Float.MAX_VALUE
        var closestIndex = currentIndex
        
        // 현재 인덱스부터 앞으로 일정 범위만 검색 (과거로 돌아가지 않음)
        val searchEnd = minOf(currentIndex + 100, path.size)  // 최대 100개 포인트만 검색
        
        // 경로상의 선분들에 대한 최단 거리 계산
        for (i in currentIndex until searchEnd - 1) {
            val p1 = path[i]
            val p2 = path[i + 1]
            
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
            val distance = calculateDistance(currentLocation, path[i])
            if (distance < minDistance) {
                minDistance = distance
                closestIndex = i
            }
        }
        
        return closestIndex
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
        
        val dx = point.latitude - xx
        val dy = point.longitude - yy
        return calculateDistance(point, LatLng(xx, yy))
    }

    /**
     * 두 지점 간의 거리 계산 (미터)
     */
    private fun calculateDistance(latLng1: LatLng, latLng2: LatLng): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
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
            
            val smoothedBearing = if (Math.abs(diff) > 30f) {
                // 급격한 변화는 제한 (최대 30도씩만)
                normalizeBearing(lastBearing + if (diff > 0) 30f else -30f)
            } else if (Math.abs(diff) > 1f) {
                // 빠른 보간 (60% 적용)
                normalizeBearing(lastBearing + diff * 0.6f)
            } else {
                // 변화량이 작으면 이전 베어링 유지
                lastBearing
            }
            
            if (smoothedBearing > 0) {
                lastBearing = smoothedBearing
                
                // 네비게이션 모드의 줌과 방향 저장
                lastNavigationZoom = 17.0
                lastNavigationBearing = smoothedBearing
                
                // 현재 위치를 지도 중앙에 오도록 설정
                val cameraPosition = CameraPosition(
                    location,            // 현재 위치를 중앙에
                    lastNavigationZoom,  // 줌 레벨
                    0.0,                 // 기울기
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
    private fun calculatePositionAhead(currentLocation: LatLng, bearing: Float, distanceMeters: Double): LatLng {
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
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.removeUpdates(locationListener)
            Timber.d("📍 Location updates stopped")
        } catch (e: Exception) {
            Timber.e("📍 Error stopping location updates: ${e.message}")
        }
    }
}
