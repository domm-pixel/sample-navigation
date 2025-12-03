package com.dom.samplenavigation.navigation.engine

/**
 * MapLibreNavigation 설정 옵션
 * MapLibre 스타일의 NavigationOptions
 */
data class MapLibreNavigationOptions(
    /**
     * 경로 위로 스냅할지 여부
     */
    val snapToRoute: Boolean = true,

    /**
     * 오프루트 감지 최소 거리 (미터)
     */
    val offRouteMinimumDistanceMetersAfterReroute: Float = 50f,

    /**
     * Maneuver zone radius (미터)
     */
    val maneuverZoneRadius: Double = 75.0,

    /**
     * Dead reckoning time interval
     */
    val deadReckoningTimeInterval: Double = 1.0
)

