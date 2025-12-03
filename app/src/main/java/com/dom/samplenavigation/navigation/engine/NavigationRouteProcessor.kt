package com.dom.samplenavigation.navigation.engine

import com.dom.samplenavigation.navigation.model.Instruction
import com.dom.samplenavigation.navigation.model.NavigationRoute
import com.naver.maps.geometry.LatLng
import timber.log.Timber
import kotlin.math.abs

/**
 * 경로 진행 상황을 추적하고 RouteProgress를 생성하는 프로세서.
 * MapLibre 방식: pathIndex를 직접 업데이트하지 않고, stepDistanceRemaining 기반으로 step completion만 체크
 */
class NavigationRouteProcessor(
    private val offRouteDetector: OffRouteDetector
) {
    var routeProgress: RouteProgress? = null
        private set

    private var currentInstructionIndex: Int = 0
    private var currentRoute: NavigationRoute? = null

    /**
     * 새로운 경로로 시작하거나 경로가 변경되었을 때 호출
     */
    fun startNewRoute(route: NavigationRoute, initialLocation: Location) {
        currentRoute = route
        currentInstructionIndex = 0
        offRouteDetector.reset()

        // 초기 instruction은 첫 번째 instruction
        currentInstructionIndex = 0
        Timber.d("startNewRoute: initial instructionIndex=$currentInstructionIndex")

        routeProgress = buildRouteProgress(route, initialLocation)
    }

    /**
     * 위치 업데이트를 받아서 새로운 RouteProgress 생성
     * MapLibre 방식: stepDistanceRemaining을 계산하고 step completion만 체크
     */
    fun buildNewRouteProgress(
        route: NavigationRoute,
        location: Location
    ): RouteProgress {
        val routeChanged = currentRoute != route
        if (routeChanged) {
            startNewRoute(route, location)
            return routeProgress!!
        }

        // 이전 RouteProgress 기반으로 stepDistanceRemaining 계산
        val tempRouteProgress = buildRouteProgress(route, location)

        // stepDistanceRemaining을 기반으로 step completion 체크
        checkStepCompletion(route, location, tempRouteProgress)

        routeProgress = buildRouteProgress(route, location)
        return routeProgress!!
    }

    /**
     * Step completion 체크 (MapLibre 방식)
     * stepDistanceRemaining이 0에 가까우면 다음 instruction으로 진행
     */
    private fun checkStepCompletion(
        route: NavigationRoute,
        location: Location,
        routeProgress: RouteProgress
    ) {
        val stepDistanceRemaining = routeProgress.stepDistanceRemaining
        val currentInstruction = route.instructions.getOrNull(currentInstructionIndex)
        val nextInstruction = route.instructions.getOrNull(currentInstructionIndex + 1)

        // stepDistanceRemaining이 거의 0이면 (10m 이내) 다음 instruction으로 진행
        if (stepDistanceRemaining <= 10.0 && nextInstruction != null) {
            val oldIndex = currentInstructionIndex
            currentInstructionIndex = currentInstructionIndex + 1
            Timber.d("Step completed: instructionIndex $oldIndex -> $currentInstructionIndex (stepDistanceRemaining=${stepDistanceRemaining}m)")
        } else if (stepDistanceRemaining <= 0.0) {
            // stepDistanceRemaining이 0이면 강제로 다음 instruction으로 진행
            if (nextInstruction != null) {
                val oldIndex = currentInstructionIndex
                currentInstructionIndex = currentInstructionIndex + 1
                Timber.d("Step completed (forced): instructionIndex $oldIndex -> $currentInstructionIndex")
            }
        }
    }

    /**
     * RouteProgress 생성
     */
    private fun buildRouteProgress(
        route: NavigationRoute,
        location: Location
    ): RouteProgress {
        // 현재 instruction 구간의 포인트들
        val currentInstruction = route.instructions.getOrNull(currentInstructionIndex)
        val nextInstruction = route.instructions.getOrNull(currentInstructionIndex + 1)

        val currentStepPoints = if (currentInstruction != null) {
            val startIdx = currentInstruction.pointIndex
            val endIdx = nextInstruction?.pointIndex ?: route.path.size
            route.path.subList(startIdx, endIdx.coerceAtMost(route.path.size))
        } else {
            emptyList()
        }

        val upcomingStepPoints = if (nextInstruction != null) {
            val startIdx = nextInstruction.pointIndex
            val nextNextInstruction = route.instructions.getOrNull(currentInstructionIndex + 2)
            val endIdx = nextNextInstruction?.pointIndex ?: route.path.size
            route.path.subList(startIdx, endIdx.coerceAtMost(route.path.size))
        } else {
            null
        }

        // stepDistanceRemaining 계산 (MapLibre 방식: 스냅된 위치에서 다음 instruction까지의 거리)
        val stepDistanceRemaining = calculateStepDistanceRemaining(
            location,
            currentStepPoints,
            nextInstruction,
            route
        )

        // stepDistanceTraveled 계산 (MapLibre 방식: 현재 step의 시작점부터 스냅된 위치까지의 거리)
        val stepDistanceTraveled = calculateStepDistanceTraveled(
            location,
            currentStepPoints,
            currentInstruction
        )
        Timber.d("NavigationRouteProcessor: stepDistanceTraveled=$stepDistanceTraveled, stepDistanceRemaining=$stepDistanceRemaining, instructionIndex=$currentInstructionIndex")

        // pathIndex는 스냅된 위치를 기반으로 계산 (표시용, 업데이트하지 않음)
        val pathIndex = calculatePathIndexFromSnappedLocation(
            location,
            currentStepPoints,
            currentInstruction,
            route
        )

        // 전체 남은 거리 계산
        val distanceRemaining = calculateDistanceRemaining(route, pathIndex)

        return RouteProgress(
            directionsRoute = route,
            pathIndex = pathIndex,
            instructionIndex = currentInstructionIndex,
            distanceRemaining = distanceRemaining,
            currentStepPoints = currentStepPoints,
            upcomingStepPoints = upcomingStepPoints,
            stepDistanceRemaining = stepDistanceRemaining,
            stepDistanceTraveled = stepDistanceTraveled
        )
    }

    /**
     * Step distance traveled 계산 (MapLibre 방식)
     * 현재 step의 시작점부터 스냅된 위치까지의 거리
     */
    private fun calculateStepDistanceTraveled(
        location: Location,
        currentStepPoints: List<LatLng>,
        currentInstruction: Instruction?
    ): Double {
        if (currentStepPoints.size < 2 || currentInstruction == null) {
            return 0.0
        }

        // 스냅된 위치 계산 (currentStepPoints에서 가장 가까운 점)
        val snappedLocation = findNearestPointOnLine(location.latLng, currentStepPoints)

        // step의 시작점부터 스냅된 위치까지의 거리 계산
        // 각 선분에 대해 스냅된 위치가 선분 위에 있는지 확인하고 가장 가까운 선분 찾기
        var minDistance = Double.MAX_VALUE
        var bestSegmentIndex = -1
        var bestSnappedOnSegment: LatLng? = null

        for (i in 0 until currentStepPoints.size - 1) {
            val segmentStart = currentStepPoints[i]
            val segmentEnd = currentStepPoints[i + 1]

            // 선분 위의 가장 가까운 점 찾기
            val snappedOnSegment =
                nearestPointOnLineSegment(snappedLocation, segmentStart, segmentEnd)
            val distance = calculateDistance(snappedLocation, snappedOnSegment)

            if (distance < minDistance) {
                minDistance = distance
                bestSegmentIndex = i
                bestSnappedOnSegment = snappedOnSegment
            }
        }

        if (bestSegmentIndex < 0 || bestSnappedOnSegment == null) {
            return 0.0
        }

        // step의 시작점부터 bestSegmentIndex까지의 거리 + bestSegmentIndex 선분의 시작점부터 스냅된 위치까지의 거리
        var traveled = 0.0

        // bestSegmentIndex 이전의 모든 선분 길이 합산
        for (i in 0 until bestSegmentIndex) {
            traveled += calculateDistance(currentStepPoints[i], currentStepPoints[i + 1])
        }

        // bestSegmentIndex 선분의 시작점부터 스냅된 위치까지의 거리
        val segmentStart = currentStepPoints[bestSegmentIndex]
        traveled += calculateDistance(segmentStart, bestSnappedOnSegment)

        return traveled
    }

    /**
     * 선분 위의 가장 가까운 점 찾기 (nearestPointOnLineSegment)
     */
    private fun nearestPointOnLineSegment(
        point: LatLng,
        lineStart: LatLng,
        lineEnd: LatLng
    ): LatLng {
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

    /**
     * Step distance remaining 계산 (MapLibre 방식)
     * 스냅된 위치에서 다음 instruction까지의 거리
     */
    private fun calculateStepDistanceRemaining(
        location: Location,
        currentStepPoints: List<LatLng>,
        nextInstruction: Instruction?,
        route: NavigationRoute
    ): Double {
        if (currentStepPoints.size < 2) {
            return 0.0
        }

        // 스냅된 위치 계산 (currentStepPoints에서 가장 가까운 점)
        val snappedLocation = findNearestPointOnLine(location.latLng, currentStepPoints)

        // 다음 instruction이 없으면 0
        if (nextInstruction == null) {
            return 0.0
        }

        // 스냅된 위치에서 다음 instruction의 pointIndex까지의 거리 계산
        val nextInstructionPoint = route.path.getOrNull(nextInstruction.pointIndex)
        if (nextInstructionPoint == null) {
            return 0.0
        }

        // 스냅된 위치가 다음 instruction 지점과 같거나 지났으면 0
        val snappedIndex = findClosestIndexInStepPoints(
            snappedLocation,
            currentStepPoints,
            route,
            currentInstructionIndex
        )
        if (snappedIndex >= nextInstruction.pointIndex) {
            return 0.0
        }

        // 스냅된 위치에서 다음 instruction까지의 거리 계산
        var remaining = 0.0
        for (i in snappedIndex until nextInstruction.pointIndex.coerceAtMost(route.path.size - 1)) {
            remaining += calculateDistance(route.path[i], route.path[i + 1])
        }

        return remaining
    }

    /**
     * 스냅된 위치를 기반으로 pathIndex 계산 (표시용, 업데이트하지 않음)
     */
    private fun calculatePathIndexFromSnappedLocation(
        location: Location,
        currentStepPoints: List<LatLng>,
        currentInstruction: Instruction?,
        route: NavigationRoute
    ): Int {
        if (currentStepPoints.isEmpty() || currentInstruction == null) {
            return 0
        }

        // currentStepPoints에서 가장 가까운 점 찾기
        val snappedLocation = findNearestPointOnLine(location.latLng, currentStepPoints)

        // route.path에서 가장 가까운 인덱스 찾기
        val stepStartIndex = currentInstruction.pointIndex
        val nextInstruction = route.instructions.getOrNull(currentInstructionIndex + 1)
        val stepEndIndex = nextInstruction?.pointIndex ?: route.path.size

        var minDistance = Double.MAX_VALUE
        var closestIndex = stepStartIndex

        for (i in stepStartIndex until stepEndIndex.coerceAtMost(route.path.size)) {
            val distance = calculateDistance(snappedLocation, route.path[i])
            if (distance < minDistance) {
                minDistance = distance
                closestIndex = i
            }
        }

        return closestIndex
    }

    /**
     * currentStepPoints에서 가장 가까운 인덱스 찾기
     */
    private fun findClosestIndexInStepPoints(
        point: LatLng,
        stepPoints: List<LatLng>,
        route: NavigationRoute,
        instructionIndex: Int
    ): Int {
        if (stepPoints.isEmpty()) return 0

        var minDistance = Double.MAX_VALUE
        var closestStepIndex = 0

        for (i in stepPoints.indices) {
            val distance = calculateDistance(point, stepPoints[i])
            if (distance < minDistance) {
                minDistance = distance
                closestStepIndex = i
            }
        }

        // stepPoints 인덱스를 route.path 인덱스로 변환
        val currentInstruction = route.instructions.getOrNull(instructionIndex) ?: return 0
        return currentInstruction.pointIndex + closestStepIndex
    }

    /**
     * 선분 위의 가장 가까운 점 찾기 (TurfMisc.nearestPointOnLine 방식)
     */
    private fun findNearestPointOnLine(point: LatLng, lineCoordinates: List<LatLng>): LatLng {
        if (lineCoordinates.isEmpty()) return point
        if (lineCoordinates.size == 1) return lineCoordinates[0]

        var minDistance = Double.MAX_VALUE
        var nearestPoint = lineCoordinates[0]

        for (i in 0 until lineCoordinates.size - 1) {
            val segmentStart = lineCoordinates[i]
            val segmentEnd = lineCoordinates[i + 1]
            val snapped = nearestPointOnLineSegment(point, segmentStart, segmentEnd)
            val distance = calculateDistance(point, snapped)

            if (distance < minDistance) {
                minDistance = distance
                nearestPoint = snapped
            }
        }

        return nearestPoint
    }

    /**
     * 전체 남은 거리 계산
     */
    private fun calculateDistanceRemaining(route: NavigationRoute, currentPathIndex: Int): Double {
        if (currentPathIndex >= route.path.size - 1) {
            return 0.0
        }

        var remaining = 0.0
        for (i in currentPathIndex until route.path.size - 1) {
            remaining += calculateDistance(route.path[i], route.path[i + 1])
        }
        return remaining
    }

    /**
     * 두 지점 간 거리 계산 (미터)
     */
    private fun calculateDistance(p1: LatLng, p2: LatLng): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            p1.latitude, p1.longitude,
            p2.latitude, p2.longitude,
            results
        )
        return results[0].toDouble()
    }
}
