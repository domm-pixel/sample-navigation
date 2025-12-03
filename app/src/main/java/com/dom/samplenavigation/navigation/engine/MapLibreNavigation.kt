package com.dom.samplenavigation.navigation.engine

import com.dom.samplenavigation.navigation.filter.PathSpatialIndex
import com.dom.samplenavigation.navigation.model.NavigationRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import timber.log.Timber

/**
 * A MapLibreNavigation class for interacting with and customizing a navigation session.
 * MapLibre 스타일의 메인 Navigation 클래스
 */
class MapLibreNavigation(
    val options: MapLibreNavigationOptions = MapLibreNavigationOptions(),
    locationEngine: LocationEngine,
    val offRouteDetector: OffRouteDetector = OffRouteDetector(),
    var snapEngine: Snap = SnapToRoute(),
) {
    private val navigationRunnerJob = Job()
    private val backgroundScope = CoroutineScope(Dispatchers.Default + navigationRunnerJob)
    private val mainScope = CoroutineScope(Dispatchers.Main)

    var locationEngine: LocationEngine = locationEngine
        set(value) {
            field = value
            // 현재 실행 중인 네비게이션 재시작
            route?.let { route ->
                if (navigationEngine?.isRunning() == true) {
                    navigationEngine?.stopNavigation()
                    navigationEngine?.startNavigation(route)
                }
            }
        }

    val eventDispatcher: NavigationEventDispatcher = NavigationEventDispatcher()

    var route: NavigationRoute? = null
        private set

    private val routeProcessor = NavigationRouteProcessor(offRouteDetector)
    private var navigationEngine: MapLibreNavigationEngine? = null
        set(value) {
            // 이전 엔진 정리
            field?.stopNavigation()
            field = value
        }

    init {
        navigationEngine = MapLibreNavigationEngine(
            mapLibreNavigation = this,
            routeProcessor = routeProcessor,
            backgroundScope = backgroundScope,
            mainScope = mainScope
        )
    }

    /**
     * Navigation 시작
     */
    fun startNavigation(directionsRoute: NavigationRoute) {
        this.route = directionsRoute
        Timber.d("MapLibreNavigation startNavigation called")

        navigationEngine?.startNavigation(directionsRoute)
        eventDispatcher.onNavigationEvent(true)
    }

    /**
     * Navigation 중지
     */
    fun stopNavigation() {
        Timber.d("MapLibreNavigation stopNavigation called")
        navigationEngine?.stopNavigation()
        eventDispatcher.onNavigationEvent(false)
    }

    /**
     * Navigation 실행 중인지 확인
     */
    fun isRunning(): Boolean {
        return navigationEngine?.isRunning() == true
    }

    /**
     * 현재 RouteProgress 가져오기
     */
    fun getCurrentRouteProgress(): RouteProgress? {
        return routeProcessor.routeProgress
    }

    /**
     * 리소스 정리
     */
    fun onDestroy() {
        stopNavigation()
        removeOffRouteListener(null)
        removeProgressChangeListener(null)
        navigationRunnerJob.cancel()
    }

    // Listeners

    /**
     * Progress change listener 추가
     */
    fun addProgressChangeListener(progressChangeListener: ProgressChangeListener) {
        eventDispatcher.addProgressChangeListener(progressChangeListener)
    }

    /**
     * Progress change listener 제거
     */
    fun removeProgressChangeListener(progressChangeListener: ProgressChangeListener?) {
        eventDispatcher.removeProgressChangeListener(progressChangeListener)
    }

    /**
     * Off route listener 추가
     */
    fun addOffRouteListener(offRouteListener: OffRouteListener) {
        eventDispatcher.addOffRouteListener(offRouteListener)
    }

    /**
     * Off route listener 제거
     */
    fun removeOffRouteListener(offRouteListener: OffRouteListener?) {
        eventDispatcher.removeOffRouteListener(offRouteListener)
    }
}

/**
 * NavigationEventDispatcher 확장 함수
 */
private fun NavigationEventDispatcher.onNavigationEvent(isRunning: Boolean) {
    // 필요시 추가 이벤트 처리
}

