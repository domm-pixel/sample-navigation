package com.dom.samplenavigation.navigation.engine

import com.dom.samplenavigation.navigation.model.NavigationRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Default implementation for NavigationEngine which is responsible for fetching location updates
 * and processing them to set the current navigation state.
 * MapLibre 스타일의 NavigationEngine 구현
 */
class MapLibreNavigationEngine(
    private val mapLibreNavigation: MapLibreNavigation,
    private val routeProcessor: NavigationRouteProcessor,
    private val backgroundScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val mainScope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val locationEngine: LocationEngine
        get() = mapLibreNavigation.locationEngine

    private val eventDispatcher: NavigationEventDispatcher
        get() = mapLibreNavigation.eventDispatcher

    private val processingMutex = Mutex()
    private var collectLocationJob: Job? = null

    /**
     * Start navigation for the given route.
     */
    fun startNavigation(route: NavigationRoute) {
        collectLocationJob?.cancel()

        collectLocationJob = backgroundScope.launch {
            Timber.d("MapLibreNavigationEngine.startNavigation: locationEngine type=${locationEngine::class.simpleName}")
            
            // 시뮬레이션 엔진인 경우 시작 대기
            if (locationEngine is SimulatedLocationEngine) {
                Timber.d("MapLibreNavigationEngine: Detected SimulatedLocationEngine, calling start()")
                (locationEngine as SimulatedLocationEngine).start()
                // 초기 위치가 설정될 때까지 약간 대기
                delay(200)
                Timber.d("MapLibreNavigationEngine: SimulatedLocationEngine start() completed, delay finished")
            }
            
            // 초기 위치 가져오기
            val initialLocation = locationEngine.getLastLocation()
                ?: Location(
                    latitude = route.summary.startLocation.latitude,
                    longitude = route.summary.startLocation.longitude
                )

            Timber.d("startNavigation: initialLocation=${initialLocation.latLng}, bearing=${initialLocation.bearing}")

            // 초기 경로 설정
            routeProcessor.startNewRoute(route, initialLocation)
            processLocationUpdate(initialLocation)

            // 위치 업데이트 수신
            locationEngine.listenToLocation(
                LocationEngine.Request(
                    minIntervalMilliseconds = LOCATION_ENGINE_INTERVAL,
                    maxIntervalMilliseconds = LOCATION_ENGINE_INTERVAL,
                )
            )
                .catch { e ->
                    Timber.e(e, "Error in location flow")
                }
                .onEach { location ->
                    Timber.d("Location update received: ${location.latLng}, bearing=${location.bearing}")
                    processLocationUpdate(location)
                }
                .launchIn(this)
        }
    }

    /**
     * Stop and cancel the current running navigation.
     */
    fun stopNavigation() {
        collectLocationJob?.cancel()
        collectLocationJob = null
    }

    /**
     * Check if the navigation is running
     */
    fun isRunning(): Boolean {
        return collectLocationJob?.isActive == true
    }

    /**
     * 위치 업데이트를 처리하여 RouteProgress를 생성하고 이벤트를 전달
     */
    private suspend fun processLocationUpdate(rawLocation: Location) {
        processingMutex.withLock {
            val route = mapLibreNavigation.route ?: return

            Timber.d("processLocationUpdate: rawLocation=${rawLocation.latLng}, bearing=${rawLocation.bearing}")

            // RouteProgress 생성 (MapLibre 방식: stepDistanceRemaining 기반)
            val routeProgress = routeProcessor.buildNewRouteProgress(route, rawLocation)

            // 오프루트 체크
            val userOffRoute = determineUserOffRoute(rawLocation, routeProgress)

            // 스냅된 위치 계산 (최종 RouteProgress 기반)
            val snappedLocation = findSnappedLocation(rawLocation, routeProgress, userOffRoute)

            Timber.d("processLocationUpdate: snappedLocation=${snappedLocation.latLng}, bearing=${snappedLocation.bearing}, pathIndex=${routeProgress.pathIndex}")

            // 이벤트 전달
            dispatchUpdate(userOffRoute, snappedLocation, routeProgress)
        }
    }

    /**
     * 오프루트 여부 판단
     */
    private fun determineUserOffRoute(
        location: Location,
        routeProgress: RouteProgress
    ): Boolean {
        val route = routeProgress.directionsRoute
        val snappedLocation = route.path.getOrNull(routeProgress.pathIndex)?.let { latLng ->
            Location(
                latitude = latLng.latitude,
                longitude = latLng.longitude
            )
        } ?: location

        val decision = mapLibreNavigation.offRouteDetector.evaluate(
            rawLocation = location.latLng,
            snappedLocation = snappedLocation.latLng,
            route = route
        )

        return decision.isOffRoute
    }

    /**
     * 스냅된 위치 계산 (MapLibre 방식)
     */
    private fun findSnappedLocation(
        rawLocation: Location,
        routeProgress: RouteProgress,
        userOffRoute: Boolean
    ): Location {
        // 시뮬레이션 모드일 때는 항상 스냅 활성화 (오프루트 체크 무시)
        val isSimulation = mapLibreNavigation.locationEngine is SimulatedLocationEngine
        val snapEnabled = mapLibreNavigation.options.snapToRoute && (!userOffRoute || isSimulation)

        return if (snapEnabled) {
            // MapLibre의 SnapToRoute 사용
            mapLibreNavigation.snapEngine.getSnappedLocation(rawLocation, routeProgress)
        } else {
            rawLocation
        }
    }

    /**
     * 이벤트 전달
     */
    private fun dispatchUpdate(
        userOffRoute: Boolean,
        location: Location,
        routeProgress: RouteProgress
    ) {
        mainScope.launch {
            dispatchRouteProgress(location, routeProgress)
            dispatchOffRoute(location, userOffRoute)
        }
    }

    private fun dispatchRouteProgress(location: Location, routeProgress: RouteProgress) {
        Timber.d("dispatchRouteProgress: location=${location.latLng}, bearing=${location.bearing}, pathIndex=${routeProgress.pathIndex}")
        eventDispatcher.onProgressChange(location, routeProgress)
    }

    private fun dispatchOffRoute(location: Location, isUserOffRoute: Boolean) {
        if (isUserOffRoute) {
            eventDispatcher.onUserOffRoute(location)
        }
    }

    companion object {
        const val LOCATION_ENGINE_INTERVAL = 1000L
    }
}

