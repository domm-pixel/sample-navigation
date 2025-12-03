package com.dom.samplenavigation.navigation.engine

import com.dom.samplenavigation.navigation.model.NavigationRoute
import com.naver.maps.geometry.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import android.location.Location as AndroidLocation
import timber.log.Timber
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 경로를 따라 이동하는 시뮬레이션 LocationEngine
 * 일정 시간마다 경로의 다음 위치로 순차적으로 이동합니다.
 */
class SimulatedLocationEngine(
    private val route: NavigationRoute,
    private val updateIntervalMs: Long = 1000L, // 1초마다 다음 위치로 이동
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : LocationEngine {

    private var currentPathIndex: Int = 0
    private var isRunning: Boolean = false
    private var simulationJob: Job? = null
    private var lastLocation: Location? = null
    
    // 위치 업데이트를 Flow로 전달하기 위한 SharedFlow
    private val locationFlow = MutableSharedFlow<Location>(replay = 1)

    /**
     * 시뮬레이션 시작
     */
    fun start() {
        Timber.d("SimulatedLocationEngine.start() called, isRunning=$isRunning, route.path.size=${route.path.size}")
        
        if (isRunning) {
            Timber.d("SimulatedLocationEngine: Already running, skipping start")
            return
        }

        if (route.path.isEmpty()) {
            Timber.e("SimulatedLocationEngine: Route path is empty, cannot start")
            return
        }

        isRunning = true
        currentPathIndex = 0

        // 초기 위치 설정 (동기적으로 설정하여 getLastLocation()이 즉시 반환 가능하도록)
        val initialLocation = createLocationFromPathPoint(route.path[0], 0)
        lastLocation = initialLocation
        Timber.d("SimulatedLocationEngine: Initial location set to ${initialLocation.latLng}")

        simulationJob = coroutineScope.launch {
            Timber.d("SimulatedLocationEngine: Simulation job launched")
            
            // 초기 위치를 먼저 emit
            lastLocation?.let { location ->
                locationFlow.emit(location)
                Timber.d("SimulatedLocationEngine: Initial location emitted to flow")
            }
            
            // 약간의 딜레이 후 시뮬레이션 시작
            delay(100)
            Timber.d("SimulatedLocationEngine: Starting simulateMovement()")
            simulateMovement()
        }

        Timber.d("SimulatedLocationEngine started: updateInterval=${updateIntervalMs}ms, route points=${route.path.size}")
    }

    /**
     * 시뮬레이션 중지
     */
    fun stop() {
        isRunning = false
        simulationJob?.cancel()
        simulationJob = null
        Timber.d("SimulatedLocationEngine stopped")
    }

    /**
     * 경로를 따라 이동 시뮬레이션
     * 일정 시간마다 경로의 다음 위치로 순차적으로 이동
     */
    private suspend fun simulateMovement() {
        Timber.d("SimulatedLocationEngine.simulateMovement() started")
        val path = route.path
        
        Timber.d("SimulatedLocationEngine: path.size=${path.size}, updateInterval=${updateIntervalMs}ms, isRunning=$isRunning")

        while (isRunning && currentPathIndex < path.size) {
            val currentPoint = path[currentPathIndex]
            
            // Bearing 계산 (다음 점이 있으면 다음 점 방향, 없으면 이전 점 방향)
            val bearing = if (currentPathIndex < path.size - 1) {
                calculateBearing(currentPoint, path[currentPathIndex + 1])
            } else if (currentPathIndex > 0) {
                calculateBearing(path[currentPathIndex - 1], currentPoint)
            } else {
                0.0
            }
            
            // 속도 계산 (이전 점과의 거리 기반)
            val speed = if (currentPathIndex > 0) {
                val distance = calculateDistance(path[currentPathIndex - 1], currentPoint)
                // 거리를 시간으로 나눔 (m/s)
                (distance / (updateIntervalMs / 1000.0)).toFloat()
            } else {
                0f
            }

            // Location 생성
            val location = Location(
                latitude = currentPoint.latitude,
                longitude = currentPoint.longitude,
                accuracyMeters = 5f, // 시뮬레이션이므로 정확도 5m
                speedMetersPerSeconds = speed,
                bearing = bearing.toFloat(),
                timeMilliseconds = System.currentTimeMillis(),
                provider = "Simulated"
            )

            lastLocation = location
            
            // Flow에 위치 업데이트 전달
            locationFlow.emit(location)
            Timber.d("SimulatedLocationEngine: Emitted location at index $currentPathIndex: ${currentPoint}")

            // 다음 경로 포인트로 이동
            currentPathIndex++

            // 경로 끝에 도달했는지 확인
            if (currentPathIndex >= path.size) {
                Timber.d("SimulatedLocationEngine: Reached end of route")
                // 마지막 위치에서 멈춤
                break
            }

            // 위치 업데이트 대기
            delay(updateIntervalMs)
        }

        isRunning = false
        Timber.d("SimulatedLocationEngine: Simulation completed")
    }

    /**
     * 두 점 사이의 거리 계산 (미터)
     */
    private fun calculateDistance(p1: LatLng, p2: LatLng): Double {
        val results = FloatArray(1)
        AndroidLocation.distanceBetween(
            p1.latitude, p1.longitude,
            p2.latitude, p2.longitude,
            results
        )
        return results[0].toDouble()
    }

    /**
     * 두 점 사이의 bearing 계산 (북쪽 기준 0-360도)
     */
    private fun calculateBearing(from: LatLng, to: LatLng): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val brng = Math.toDegrees(atan2(y, x))
        return (brng + 360) % 360
    }

    /**
     * 경로 포인트에서 Location 생성
     */
    private fun createLocationFromPathPoint(point: LatLng, pathIndex: Int): Location {
        val bearing = if (pathIndex < route.path.size - 1) {
            calculateBearing(point, route.path[pathIndex + 1])
        } else {
            0.0
        }

        return Location(
            latitude = point.latitude,
            longitude = point.longitude,
            accuracyMeters = 5f,
            speedMetersPerSeconds = 0f, // 초기 위치는 속도 0
            bearing = bearing.toFloat(),
            timeMilliseconds = System.currentTimeMillis(),
            provider = "Simulated"
        )
    }

    override fun listenToLocation(request: LocationEngine.Request): Flow<Location> {
        // 시뮬레이션이 시작되지 않았으면 시작
        if (!isRunning) {
            start()
        }
        
        // SharedFlow를 반환 (replay=1이므로 마지막 위치를 즉시 받을 수 있음)
        return locationFlow.asSharedFlow()
    }

    override suspend fun getLastLocation(): Location? {
        return lastLocation
    }

    /**
     * 리소스 정리
     */
    fun onDestroy() {
        stop()
        coroutineScope.cancel()
    }
}

