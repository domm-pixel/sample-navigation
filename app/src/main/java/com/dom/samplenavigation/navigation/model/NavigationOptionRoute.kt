package com.dom.samplenavigation.navigation.model

enum class RouteOptionType(val apiKey: String, val displayName: String, val description: String) {
    TRAFAST("trafast", "실시간 빠른 길", "최소 시간"),
    TRAOPTIMAL("traoptimal", "실시간 최적 길", "균형 경로"),
    TRAAVOIDTOLL("traavoidtoll", "무료 우선", "통행료 최소")
}

data class NavigationOptionRoute(
    val optionType: RouteOptionType,
    val route: NavigationRoute
)

