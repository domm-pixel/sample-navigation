package com.dom.samplenavigation.navigation.map

import android.content.res.Resources
import android.graphics.Color
import android.graphics.PointF
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraAnimation
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.LocationTrackingMode
import com.naver.maps.map.NaverMap
import com.naver.maps.map.overlay.ArrowheadPathOverlay
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.Overlay
import com.naver.maps.map.overlay.OverlayImage
import com.naver.maps.map.overlay.PathOverlay
import com.dom.samplenavigation.R
import com.dom.samplenavigation.navigation.model.NavigationRoute
import timber.log.Timber

/**
 * 네비게이션 지도 관련 로직을 관리하는 클래스
 * NaverMap 객체, PathOverlay, Marker, CameraUpdate 등 지도와 관련된 모든 로직을 담당
 */
class NavigationMapManager(
    private val naverMap: NaverMap,
    private val resources: Resources
) {
    private var pathOverlays: MutableList<Overlay> = mutableListOf()
    private var directionArrowOverlay: ArrowheadPathOverlay? = null
    private var endMarker: Marker? = null
    private var currentLocationMarker: Marker? = null
    private var originalTopPadding: Int = 0
    private var isCameraUpdateFromCode: Boolean = false

    /**
     * 지도 초기 설정
     */
    fun initializeMap(topPaddingPx: Int) {
        originalTopPadding = topPaddingPx
        naverMap.uiSettings.isCompassEnabled = false
        naverMap.uiSettings.isLocationButtonEnabled = false
        naverMap.uiSettings.isZoomControlEnabled = false
        naverMap.buildingHeight = 0.2f
        naverMap.mapType = NaverMap.MapType.Navi
        naverMap.setContentPadding(0, topPaddingPx, 0, 0)
        
        createCurrentLocationMarker()
    }

    /**
     * 경로 표시
     */
    fun displayRoute(route: NavigationRoute, showFullRoute: Boolean, currentPathIndex: Int) {
        // 기존 경로 오버레이만 제거 (화살표는 유지)
        pathOverlays.forEach { it.map = null }
        pathOverlays.clear()
        endMarker?.map = null

        if (showFullRoute) {
            displayRouteWithCongestion(route)
        } else {
            displayRouteAhead(route, currentPathIndex)
        }

        // 도착지 마커
        endMarker = Marker().apply {
            position = route.summary.endLocation
            captionText = resources.getString(R.string.navigation_destination)
            map = naverMap
        }

        Timber.d("Route displayed with ${route.path.size} points, showFullRoute=$showFullRoute")
    }

    /**
     * 경로를 혼잡도별로 표시 (전체 경로)
     */
    private fun displayRouteWithCongestion(route: NavigationRoute) {
        if (route.sections.isEmpty()) {
            pathOverlays.add(PathOverlay().apply {
                coords = route.path
                color = resources.getColor(R.color.skyBlue, null)
                outlineColor = Color.WHITE
                width = 40
                map = naverMap
            })
            return
        }

        val groupedPaths = mutableListOf<Pair<List<LatLng>, Int>>()
        val sortedSections = route.sections.sortedBy { it.pointIndex }

        var currentCongestion: Int? = null
        var currentPathGroup = mutableListOf<LatLng>()
        var lastEndIndex = 0

        sortedSections.forEachIndexed { index, section ->
            val startIndex = section.pointIndex
            val endIndex = minOf(startIndex + section.pointCount, route.path.size)

            if (index == 0 && startIndex > 0) {
                val beforePath = route.path.subList(0, startIndex)
                if (beforePath.isNotEmpty() && beforePath.size >= 2) {
                    groupedPaths.add(Pair(beforePath, section.congestion))
                }
            }

            if (startIndex > lastEndIndex) {
                val gapPath = route.path.subList(lastEndIndex, startIndex)
                if (gapPath.isNotEmpty() && gapPath.size >= 2) {
                    val gapCongestion = currentCongestion ?: section.congestion
                    groupedPaths.add(Pair(gapPath, gapCongestion))
                }
            }

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

        if (currentPathGroup.size >= 2 && currentCongestion != null) {
            groupedPaths.add(Pair(currentPathGroup, currentCongestion))
        }

        if (lastEndIndex < route.path.size) {
            val remainingPath = route.path.subList(lastEndIndex, route.path.size)
            if (remainingPath.isNotEmpty() && remainingPath.size >= 2) {
                val lastCongestion = currentCongestion ?: sortedSections.lastOrNull()?.congestion ?: 1
                groupedPaths.add(Pair(remainingPath, lastCongestion))
            }
        }

        groupedPaths.forEach { (path, congestion) ->
            val overlay = PathOverlay().apply {
                coords = path
                color = getCongestionColor(congestion)
                outlineColor = Color.WHITE
                width = 40
                map = naverMap
            }
            pathOverlays.add(overlay)
        }
    }

    /**
     * 경로의 앞부분만 표시 (현재 위치 기준)
     */
    private fun displayRouteAhead(route: NavigationRoute, currentPathIndex: Int) {
        val startIndex = currentPathIndex.coerceIn(0, route.path.size - 1)
        val pathAhead = route.path.subList(startIndex, route.path.size)

        if (pathAhead.size >= 2) {
            pathOverlays.add(PathOverlay().apply {
                coords = pathAhead
                color = resources.getColor(R.color.skyBlue, null)
                outlineColor = Color.WHITE
                width = 40
                map = naverMap
            })
        }
    }

    /**
     * 혼잡도에 따른 색상 반환
     */
    private fun getCongestionColor(congestion: Int): Int {
        return when (congestion) {
            0 -> 0xFF808080.toInt() // 값없음: 회색
            1 -> 0xFF00AA00.toInt() // 원활: 녹색
            2 -> 0xFFFFAA00.toInt() // 서행: 주황색
            3 -> 0xFFFF0000.toInt() // 혼잡: 빨간색
            else -> 0xFF808080.toInt()
        }
    }

    /**
     * 현재 위치 마커 생성
     */
    private fun createCurrentLocationMarker() {
        currentLocationMarker = Marker().apply {
            icon = OverlayImage.fromResource(R.drawable.a)
            position = LatLng(37.5665, 126.9780)
            map = naverMap
            anchor = PointF(0.5f, 0.5f)
            zIndex = 10000
            width = 150
            height = 150
        }
    }

    /**
     * 현재 위치 마커 업데이트
     */
    fun updateCurrentLocationMarker(location: LatLng) {
        currentLocationMarker?.let { marker ->
            marker.position = location
            marker.map = naverMap
            marker.zIndex = 10000
        } ?: run {
            createCurrentLocationMarker()
            updateCurrentLocationMarker(location)
        }
    }

    /**
     * 카메라를 위치로 추적
     */
    fun updateCameraFollow(location: LatLng, bearing: Float, zoom: Double, tilt: Double) {
        val cameraPosition = CameraPosition(
            location,
            zoom,
            tilt,
            bearing.toDouble()
        )

        isCameraUpdateFromCode = true
        val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
            .animate(CameraAnimation.Easing, 300)  // 애니메이션 시간 증가 (200ms → 300ms)
        naverMap.moveCamera(cameraUpdate)
    }

    /**
     * 카메라를 전체 경로에 맞춤
     */
    fun fitBounds(route: NavigationRoute) {
        val bounds = LatLngBounds.Builder()
            .include(route.summary.startLocation)
            .include(route.summary.endLocation)
            .apply {
                route.path.forEach { point ->
                    include(point)
                }
            }
            .build()

        isCameraUpdateFromCode = true
        naverMap.moveCamera(CameraUpdate.fitBounds(bounds, 150))
    }

    /**
     * 카메라를 특정 위치로 이동 (저장된 줌과 방향 유지)
     */
    fun moveCameraToLocation(location: LatLng, zoom: Double, tilt: Double, bearing: Float) {
        val cameraPosition = CameraPosition(
            location,
            zoom,
            tilt,
            bearing.toDouble()
        )
        isCameraUpdateFromCode = true
        val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
            .animate(CameraAnimation.Easing, 300)  // 애니메이션 시간 증가 (200ms → 300ms)
        naverMap.moveCamera(cameraUpdate)
    }

    /**
     * 분기점 화살표 업데이트
     */
    fun updateDirectionArrow(arrowPath: List<LatLng>) {
        directionArrowOverlay?.map = null

        if (arrowPath.size >= 2) {
            directionArrowOverlay = ArrowheadPathOverlay().apply {
                coords = arrowPath
                color = Color.WHITE
                outlineColor = Color.BLUE
                width = 20
                map = naverMap
                zIndex = 1000
            }
        }
    }

    /**
     * 분기점 화살표 제거
     */
    fun removeDirectionArrow() {
        directionArrowOverlay?.map = null
        directionArrowOverlay = null
    }

    /**
     * 네비게이션 모드 시작
     */
    fun startNavigationMode() {
        naverMap.locationTrackingMode = LocationTrackingMode.None
    }

    /**
     * 네비게이션 모드 종료
     */
    fun stopNavigationMode() {
        naverMap.locationTrackingMode = LocationTrackingMode.Follow
    }

    /**
     * 콘텐츠 패딩 설정
     */
    fun setContentPadding(top: Int) {
        naverMap.setContentPadding(0, top, 0, 0)
    }

    /**
     * 원래 패딩 복원
     */
    fun restoreOriginalPadding() {
        naverMap.setContentPadding(0, originalTopPadding, 0, 0)
    }

    /**
     * 코드에서 카메라 업데이트 중인지 확인
     */
    fun isCameraUpdateFromCode(): Boolean = isCameraUpdateFromCode

    /**
     * 카메라 업데이트 플래그 리셋
     */
    fun resetCameraUpdateFlag() {
        isCameraUpdateFromCode = false
    }

    /**
     * 모든 오버레이 제거
     */
    fun clearAllOverlays() {
        pathOverlays.forEach { it.map = null }
        pathOverlays.clear()
        endMarker?.map = null
        endMarker = null
        removeDirectionArrow()
    }
}

