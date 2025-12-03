package com.dom.samplenavigation.navigation.engine

import com.dom.samplenavigation.navigation.model.NavigationRoute
import com.dom.samplenavigation.navigation.model.Instruction
import com.naver.maps.geometry.LatLng
import kotlin.math.max

/**
 * This class contains all progress information at any given time during a navigation session.
 * MapLibre 스타일의 RouteProgress 모델 (우리 NavigationRoute 기반)
 */
data class RouteProgress(
    /**
     * The route the navigation session is currently using.
     */
    val directionsRoute: NavigationRoute,

    /**
     * Index representing the current path point the user is on.
     */
    val pathIndex: Int,

    /**
     * Index representing the current instruction the user is on.
     */
    val instructionIndex: Int,

    /**
     * Provides the distance remaining in meters till the user reaches the end of the route.
     */
    val distanceRemaining: Double,

    /**
     * Provides a list of points that represent the current instruction segment.
     */
    val currentStepPoints: List<LatLng>,

    /**
     * Provides a list of points that represent the upcoming instruction segment.
     */
    val upcomingStepPoints: List<LatLng>?,

    /**
     * Distance remaining in meters for the current instruction segment.
     */
    val stepDistanceRemaining: Double,

    /**
     * Distance traveled in meters for the current instruction segment.
     * MapLibre 방식: stepDistanceTraveled를 사용해서 정확한 위치 계산
     */
    val stepDistanceTraveled: Double = 0.0,
) {

    /**
     * Provides the current [Instruction] the user is on.
     */
    val currentInstruction: Instruction?
        get() = directionsRoute.instructions.getOrNull(instructionIndex)

    /**
     * Provides the next/upcoming [Instruction] immediately after the current instruction.
     */
    val upcomingInstruction: Instruction?
        get() = directionsRoute.instructions.getOrNull(instructionIndex + 1)

    /**
     * Total distance traveled in meters along route.
     */
    val distanceTraveled: Double
        get() = max(0.0, directionsRoute.summary.totalDistance.toDouble() - distanceRemaining)

    /**
     * Provides the duration remaining in seconds till the user reaches the end of the route.
     */
    val durationRemaining: Float
        get() = (1 - fractionTraveled) * directionsRoute.summary.totalDuration

    /**
     * Get the fraction traveled along the current route, this is a float value between 0 and 1.
     */
    val fractionTraveled: Float
        get() = directionsRoute.summary.totalDistance
            .takeIf { distance -> distance > 0 }
            ?.let { routeDistance ->
                max(0.0, distanceTraveled / routeDistance).toFloat()
            } ?: 1f
}

