package com.dom.samplenavigation.navigation.rerouting

import com.naver.maps.geometry.LatLng
import timber.log.Timber

/**
 * 경로 이탈 감지 및 재탐색을 관리하는 클래스
 * GPS가 순간적으로 튀는 것을 방지하기 위해 카운터 도입
 */
class ReroutingManager {
    
    private var offRouteCount = 0
    private var lastRerouteTime: Long = 0
    private var isRerouting = false
    
    companion object {
        private const val OFF_ROUTE_THRESHOLD_M = 35f  // 경로 이탈 임계값 (미터)
        private const val OFF_ROUTE_LIMIT = 3  // 연속 이탈 감지 횟수
        private const val REROUTE_COOLDOWN_MS = 5000L  // 재탐색 쿨다운 (밀리초)
    }

    /**
     * 경로 이탈 감지 및 재탐색 필요 여부 확인
     * @param distanceToPath 경로까지의 거리 (미터)
     * @return 재탐색이 필요한 경우 true
     */
    fun checkAndShouldReroute(distanceToPath: Float): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastReroute = currentTime - lastRerouteTime

        // 쿨다운 체크
        if (timeSinceLastReroute < REROUTE_COOLDOWN_MS) {
            return false
        }

        // 이미 재탐색 중이면 무시
        if (isRerouting) {
            return false
        }

        // 경로 이탈 감지
        if (distanceToPath > OFF_ROUTE_THRESHOLD_M) {
            offRouteCount++
            Timber.d("Off-route detected: distance=$distanceToPath m, count=$offRouteCount/$OFF_ROUTE_LIMIT")
            
            // 연속으로 3번 이상 이탈 감지되었을 때만 재탐색
            if (offRouteCount >= OFF_ROUTE_LIMIT) {
                isRerouting = true
                lastRerouteTime = currentTime
                offRouteCount = 0  // 카운터 리셋
                Timber.d("Rerouting triggered: distance=$distanceToPath m")
                return true
            }
        } else {
            // 정상 경로면 카운터 리셋
            if (offRouteCount > 0) {
                Timber.d("Back on route: resetting off-route count")
                offRouteCount = 0
            }
        }

        return false
    }

    /**
     * 재탐색 완료 처리
     */
    fun onRerouteComplete() {
        isRerouting = false
        offRouteCount = 0
        Timber.d("Reroute completed")
    }

    /**
     * 재탐색 상태 리셋
     */
    fun reset() {
        isRerouting = false
        offRouteCount = 0
        lastRerouteTime = 0
    }

    /**
     * 현재 재탐색 중인지 확인
     */
    fun isRerouting(): Boolean = isRerouting
}

