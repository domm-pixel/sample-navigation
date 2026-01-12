package com.dom.samplenavigation.navigation.snapping

import android.location.Location
import com.naver.maps.geometry.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 경로 스냅 로직을 처리하는 서비스
 * 무거운 연산을 백그라운드 스레드에서 처리
 */
class RouteSnappingService {
    
    // calculateDistance 메모리 할당 최적화: 재사용 가능한 FloatArray
    private val distanceResults = FloatArray(1)
    
    companion object {
        private const val SNAP_TOLERANCE_M = 20f  // 경로 스냅 허용 거리 (미터)
        private const val SEARCH_RANGE = 100  // 현재 인덱스 주변 검색 범위
    }

    /**
     * GPS 위치를 경로에 스냅 (백그라운드 스레드에서 실행)
     */
    suspend fun snapLocationToRoute(
        gpsLocation: LatLng,
        path: List<LatLng>,
        currentIndex: Int
    ): SnapResult = withContext(Dispatchers.Default) {
        if (path.isEmpty()) {
            return@withContext SnapResult(gpsLocation, 0)
        }

        var minDistance = Float.MAX_VALUE
        var bestSnappedLocation = gpsLocation
        var bestIndex = currentIndex.coerceIn(0, path.size - 1)

        // 현재 인덱스 주변 검색 (성능 최적화)
        val startIndex = maxOf(0, currentIndex - SEARCH_RANGE)
        val endIndex = minOf(path.size - 1, currentIndex + SEARCH_RANGE)

        // 선분들에 대해 가장 가까운 점 찾기
        for (i in startIndex until endIndex) {
            if (i >= path.size - 1) break
            val p1 = path[i]
            val p2 = path[i + 1]

            val snapped = snapToLineSegment(gpsLocation, p1, p2)
            val distance = calculateDistance(gpsLocation, snapped)

            if (distance < minDistance) {
                minDistance = distance
                bestSnappedLocation = snapped
                bestIndex = if (calculateDistance(snapped, p1) < calculateDistance(snapped, p2)) {
                    i
                } else {
                    i + 1
                }
            }
        }

        // 스냅 허용 범위 내에 있으면 스냅, 아니면 GPS 위치 사용
        if (minDistance <= SNAP_TOLERANCE_M) {
            SnapResult(bestSnappedLocation, bestIndex)
        } else {
            SnapResult(gpsLocation, bestIndex)
        }
    }

    /**
     * 점을 선분에 스냅
     */
    private fun snapToLineSegment(point: LatLng, lineStart: LatLng, lineEnd: LatLng): LatLng {
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
     * 두 지점 간의 거리 계산 (메모리 할당 최적화)
     */
    fun calculateDistance(latLng1: LatLng, latLng2: LatLng): Float {
        Location.distanceBetween(
            latLng1.latitude, latLng1.longitude,
            latLng2.latitude, latLng2.longitude,
            distanceResults
        )
        return distanceResults[0]
    }

    /**
     * 두 지점 간의 방향 계산 (도)
     */
    fun calculateBearing(from: LatLng, to: LatLng): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLng = Math.toRadians(to.longitude - from.longitude)

        val y = sin(deltaLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLng)

        val bearing = Math.toDegrees(atan2(y, x))
        return normalizeBearing(bearing.toFloat())
    }

    /**
     * 각도를 0~360 범위로 정규화
     */
    private fun normalizeBearing(angle: Float): Float {
        var normalized = angle % 360f
        if (normalized < 0f) normalized += 360f
        return normalized
    }
}

/**
 * 스냅 결과 데이터 클래스
 */
data class SnapResult(
    val snappedLocation: LatLng,
    val pathIndex: Int
)

