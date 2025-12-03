package com.dom.samplenavigation.navigation.engine

import com.dom.samplenavigation.navigation.model.NavigationRoute
import com.naver.maps.geometry.LatLng
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 경로 이탈 여부와 재탐색 필요 여부를 판단하는 간단한 엔진.
 * NavigationActivity 에 있던 거리/정확도/쿨다운 기반 로직을
 * 한 곳으로 모으기 위한 래퍼 클래스.
 */
class OffRouteDetector(
    private val offRouteThresholdMeters: Float = 30f,
    private val rerouteThresholdMeters: Float = 70f,
    private val minDistanceAfterRerouteMeters: Float = 50f,
    private val confirmCountRequired: Int = 2
) {

    data class Decision(
        val isOffRoute: Boolean,
        val shouldReroute: Boolean,
        val reason: String
    )

    private var lastReroutePosition: LatLng? = null
    private var confirmCounter: Int = 0

    fun reset() {
        confirmCounter = 0
    }

    /**
     * @param rawLocation      실제 GPS 위치
     * @param snappedLocation  경로 위로 스냅된 위치
     * @param route            현재 경로 (향후 고도화시 사용 가능)
     */
    fun evaluate(
        rawLocation: LatLng,
        snappedLocation: LatLng,
        route: NavigationRoute
    ): Decision {
        val distToRoute = distanceMeters(rawLocation, snappedLocation)

        // 1. 리루트 직후 일정 거리 전에는 오프루트 무시
        lastReroutePosition?.let { last ->
            val fromLastReroute = distanceMeters(rawLocation, last)
            if (fromLastReroute < minDistanceAfterRerouteMeters) {
                confirmCounter = 0
                return Decision(false, false, "recent_reroute_protection")
            }
        }

        // 2. 경로에서 충분히 벗어나지 않았다면 리셋
        if (distToRoute < offRouteThresholdMeters) {
            confirmCounter = 0
            return Decision(false, false, "within_threshold")
        }

        // 3. 연속 N회 이상 이탈 시에만 확정
        confirmCounter++
        if (confirmCounter < confirmCountRequired) {
            return Decision(false, false, "waiting_confirm_$confirmCounter")
        }

        // 4. 확정 off-route. REROUTE 임계값 이상이면 재탐색
        val shouldReroute = distToRoute >= rerouteThresholdMeters
        if (shouldReroute) {
            lastReroutePosition = rawLocation
            confirmCounter = 0
        }

        return Decision(
            isOffRoute = true,
            shouldReroute = shouldReroute,
            reason = if (shouldReroute) "reroute" else "offroute_hold"
        )
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val sa = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2)
        val sb = kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        val c = 2 * kotlin.math.atan2(
            sqrt(
                sa + cos(Math.toRadians(a.latitude)) *
                        cos(Math.toRadians(b.latitude)) * sb
            ),
            Math.sqrt(1 - (sa + sb))
        )
        return R * c
    }
}


