package com.dom.samplenavigation.view

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dom.samplenavigation.R
import com.dom.samplenavigation.base.BaseActivity
import com.dom.samplenavigation.databinding.ActivityMainBinding
import com.dom.samplenavigation.navigation.model.NavigationRoute
import com.dom.samplenavigation.view.viewmodel.MainViewModel
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.MapFragment
import com.naver.maps.map.NaverMap
import com.naver.maps.map.OnMapReadyCallback
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.PathOverlay
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : BaseActivity<ActivityMainBinding>(
    R.layout.activity_main
), OnMapReadyCallback {

    private val mainViewModel: MainViewModel by viewModels()
    var naverMap : NaverMap? = null
    private lateinit var locationManager: LocationManager
    private var currentLocation: LatLng? = null
    private var pathOverlay: PathOverlay? = null
    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var currentRoute: NavigationRoute? = null
    
    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        
        binding {
            loadMap()
            getCurrentLocation()
            setupObservers()

            // 목적지 입력 (클릭 시 입력 다이얼로그 표시 또는 직접 텍스트 입력)
//            tvDestination.setOnClickListener {
//                // 간단한 예시: 직접 텍스트 입력 가능하도록
//                // 실제로는 EditText나 다이얼로그를 사용하는 것이 좋습니다
//                tvDestination.text = "서울특별시 종로구 사직로 161"
//            }

            // 검색 버튼 클릭
            tvSearch.setOnClickListener {
                // 키보드 숨기기
                hideKeyboard()
                
                val destination = tvDestination.text.toString()
                if (destination.isEmpty() || destination == "목적지를 입력하세요") {
                    Toast.makeText(this@MainActivity, "목적지를 입력해주세요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                if (currentLocation == null) {
                    Toast.makeText(this@MainActivity, "현재 위치를 가져오는 중입니다", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                // 경로 검색
                mainViewModel.searchPath(currentLocation!!, destination)
            }

            // 안내 시작 버튼 클릭
            btnStartNavigation.setOnClickListener {
                if (currentLocation != null && mainViewModel.destinationAddress != null) {
                    // 네비게이션 화면으로 이동하면서 데이터 전달
                    val intent = Intent(this@MainActivity, NavigationActivity::class.java)
                    intent.putExtra("start_lat", currentLocation!!.latitude)
                    intent.putExtra("start_lng", currentLocation!!.longitude)
                    intent.putExtra("destination", mainViewModel.destinationAddress!!)
                    // 경로 데이터도 전달 (Parcelable로 전달)
                    if (currentRoute != null) {
                        // NavigationRoute를 Intent로 전달하려면 Parcelable로 구현해야 합니다
                        // 여기서는 간단하게 start/end 위치만 전달하고 NavigationActivity에서 다시 조회하도록 합니다
                    }
                    startActivity(intent)
                }
            }
        }
    }

    /**
     * 네이버 지도 로드
     */
    private fun loadMap() {
        val fm = this.supportFragmentManager
        val nmapFragment = fm.findFragmentById(R.id.mapView_map) as MapFragment?
            ?: MapFragment.newInstance().also {
                fm.beginTransaction().replace(R.id.mapView_map, it).commit()
            }
        nmapFragment.getMapAsync(this)
    }

    override fun onMapReady(nMap: NaverMap) {
        naverMap = nMap
    }

    /**
     * 옵저버 설정
     */
    private fun setupObservers() {
        binding {
            // 경로 검색 결과 관찰
            mainViewModel.navigationRoute.observe(this@MainActivity) { route ->
                route?.let {
                    currentRoute = it
                    displayRoute(it)
                    // 안내 시작 버튼 표시
                    btnStartNavigation.visibility = View.VISIBLE
                    Timber.d("✅ Route displayed, navigation button shown")
                }
            }

            // 에러 메시지 관찰
            mainViewModel.errorMessage.observe(this@MainActivity) { message ->
                message?.let {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                    btnStartNavigation.visibility = View.GONE
                }
            }
        }
    }

    private var pathOverlays: MutableList<PathOverlay> = mutableListOf()

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

    /**
     * 경로 표시 (MainActivity에서 혼잡도별 색상으로 표시)
     */
    private fun displayRoute(route: NavigationRoute) {
        val nMap = naverMap ?: return

        // 기존 오버레이 제거
        pathOverlay?.map = null
        pathOverlays.forEach { it.map = null }
        pathOverlays.clear()
        startMarker?.map = null
        endMarker?.map = null

        // 혼잡도에 따라 경로를 구간별로 나눠서 표시
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
                
                // 첫 섹션 이전의 경로 처리
                if (index == 0 && startIndex > 0) {
                    val beforePath = route.path.subList(0, startIndex)
                    if (beforePath.isNotEmpty() && beforePath.size >= 2) {
                        val firstCongestion = section.congestion
                        groupedPaths.add(Pair(beforePath, firstCongestion))
                        Timber.d("📍 Added pre-section path: 0-$startIndex, congestion=$firstCongestion")
                    }
                }
                
                // 섹션 사이의 빈 구간 처리
                if (startIndex > lastEndIndex) {
                    val gapPath = route.path.subList(lastEndIndex, startIndex)
                    if (gapPath.isNotEmpty() && gapPath.size >= 2) {
                        val gapCongestion = currentCongestion ?: section.congestion
                        if (gapCongestion == section.congestion && currentPathGroup.isNotEmpty()) {
                            currentPathGroup.addAll(gapPath)
                        } else {
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
                    currentPathGroup.addAll(sectionPath)
                } else {
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
                    val lastCongestion = currentCongestion ?: sortedSections.lastOrNull()?.congestion ?: 0
                    groupedPaths.add(Pair(remainingPath, lastCongestion))
                    Timber.d("📍 Added post-section path: $lastEndIndex-${route.path.size}, congestion=$lastCongestion")
                }
            }
            
            // 그룹화된 경로들을 PathOverlay로 표시
            groupedPaths.forEach { (path, congestion) ->
                val overlay = PathOverlay().apply {
                    coords = path
                    color = getCongestionColor(congestion)
                    outlineColor = Color.WHITE
                    width = 20
                    map = nMap
                }
                pathOverlays.add(overlay)
            }
            
            Timber.d("🗺️ Total segments: ${groupedPaths.size}, Total points: ${route.path.size}")
        } else {
            // sections가 없으면 전체 경로를 하나로 표시
            val overlay = PathOverlay().apply {
                coords = route.path
                color = Color.BLUE
                outlineColor = Color.WHITE
                width = 20
                map = nMap
            }
            pathOverlays.add(overlay)
        }

        // 출발지 마커
        startMarker = Marker().apply {
            position = route.summary.startLocation
            captionText = "출발지"
            map = nMap
        }

        // 도착지 마커
        endMarker = Marker().apply {
            position = route.summary.endLocation
            captionText = "도착지"
            map = nMap
        }

        // 지도 범위 조정 (전체 경로 포인트 포함)
        val bounds = LatLngBounds.Builder()
            // 출발지와 도착지 포함
            .include(route.summary.startLocation)
            .include(route.summary.endLocation)
            // 전체 경로의 모든 포인트 포함
            .apply {
                route.path.forEach { point ->
                    include(point)
                }
            }
            .build()

        // 패딩을 좀 더 크게 설정하여 경로가 잘리지 않도록 함
        nMap.moveCamera(CameraUpdate.fitBounds(bounds, 150))

        Timber.d("🗺️ Route displayed with ${route.path.size} points, ${pathOverlays.size} segments by congestion")
    }
    
    /**
     * 현재 위치 가져오기
     */
    private fun getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            return
        }
        
        try {
            val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastKnownLocation != null) {
                updateCurrentLocation(LatLng(lastKnownLocation.latitude, lastKnownLocation.longitude))
            } else {
                // 실시간 위치 요청
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1f,
                    locationListener
                )
            }
        } catch (e: SecurityException) {
            Timber.e("Location permission not granted: ${e.message}")
        }
    }
    
    /**
     * 위치 리스너
     */
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val latLng = LatLng(location.latitude, location.longitude)
            updateCurrentLocation(latLng)
            locationManager.removeUpdates(this)
        }
        
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    
    /**
     * 현재 위치 업데이트
     */
    private fun updateCurrentLocation(latLng: LatLng) {
        currentLocation = latLng
        
        // 지도 중심을 현재 위치로 이동
        naverMap?.let { map ->
            map.moveCamera(CameraUpdate.scrollTo(latLng))
        }
        
        Timber.d("📍 Current location updated: $latLng")
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getCurrentLocation()
            } else {
                Timber.w("📍 Location permission denied")
            }
        }
    }
}