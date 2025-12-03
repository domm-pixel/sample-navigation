package com.dom.samplenavigation.navigation.engine

import android.location.Location
import com.dom.samplenavigation.navigation.filter.PathSpatialIndex
import com.dom.samplenavigation.navigation.model.NavigationRoute
import com.naver.maps.geometry.LatLng
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import timber.log.Timber
import kotlin.math.asin

data class SnapResult(
    val snappedLatLng: LatLng,
    val routeIndex: Int,
    val bearing: Float?
)

/**
 * 경로 위로 현재 위치를 스냅하고 진행 방향을 계산하는 유틸리티.
 * MapLibre 스타일의 step 단위 스냅과 기존 가중치 기반 스냅을 모두 지원합니다.
 */
class RouteSnapper {

    /**
     * @param rawLocation   실제(필터링된) GPS 위치
     * @param route         현재 네비게이션 경로
     * @param startIndex    이전 경로 인덱스 (이 근처부터 검색)
     * @param currentBearing 현재 이동 방향 (도, 0-360), null이면 방향 점수 제외
     * @param currentSpeed  현재 속도 (m/s)
     * @param spatialIndex  공간 인덱스 (선택적, 성능 향상용)
     */
    fun snapToRoute(
        rawLocation: LatLng,
        route: NavigationRoute,
        startIndex: Int,
        currentBearing: Float? = null,
        currentSpeed: Float = 0f,
        spatialIndex: PathSpatialIndex? = null
    ): SnapResult {
        val points = route.path
        if (points.isEmpty()) {
            return SnapResult(rawLocation, startIndex, null)
        }
        if (points.size < 2) {
            return SnapResult(points[0], 0, null)
        }
        if (startIndex < 0 || startIndex >= points.size) {
            return SnapResult(points[0], 0, null)
        }

        try {
            // 1. 먼저 가장 가까운 경로 지점을 찾아서 거리 확인
            var minDistanceToPath = Float.MAX_VALUE
            var closestPointIndex = startIndex
            for (i in points.indices) {
                val dist = calculateDistance(rawLocation, points[i])
                if (dist < minDistanceToPath) {
                    minDistanceToPath = dist
                    closestPointIndex = i
                }
            }

            // 2. 경로 이탈 정도에 따라 검색 범위 동적 조정
            val isFarFromPath = minDistanceToPath > 100f
            val isVeryFarFromPath = minDistanceToPath > 60f

            // 3. 속도 기반 기본 검색 범위
            val baseSearchRange = when {
                currentSpeed > 33.3f -> 500  // 초고속 (120km/h 이상)
                currentSpeed > 27.8f -> 400  // 고속 (100km/h 이상)
                currentSpeed > 13.9f -> 200  // 중고속 (50km/h 이상)
                currentSpeed > 4.2f -> 100   // 중속 (15km/h 이상)
                else -> 50                   // 저속
            }

            val searchRange = when {
                isVeryFarFromPath -> points.size
                isFarFromPath -> baseSearchRange * 3
                else -> baseSearchRange
            }

            // 4. 공간 인덱싱 사용 여부 결정
            val useSpatialIndex = spatialIndex?.isAvailable() == true && points.size >= 100

            // 5. 검색할 인덱스 범위 결정
            val searchIndices = if (useSpatialIndex) {
                val radiusMeters = when {
                    isVeryFarFromPath -> 1000.0
                    isFarFromPath -> 700.0
                    currentSpeed > 33.3f -> 1000.0
                    currentSpeed > 27.8f -> 800.0
                    currentSpeed > 13.9f -> 500.0
                    currentSpeed > 4.2f -> 300.0
                    else -> 150.0
                }
                val nearbyIndices = spatialIndex!!.findNearbyPoints(
                    center = rawLocation,
                    radiusMeters = radiusMeters,
                    startIndex = if (isVeryFarFromPath || isFarFromPath) 0 else startIndex
                )
                if (isVeryFarFromPath || isFarFromPath) {
                    nearbyIndices
                } else {
                    val maxIndex = minOf(startIndex + baseSearchRange, points.size)
                    nearbyIndices.filter { it in startIndex until maxIndex }
                }
            } else {
                if (isVeryFarFromPath || isFarFromPath) {
                    (0 until points.size).toList()
                } else {
                    (startIndex until minOf(startIndex + searchRange, points.size)).toList()
                }
            }

            val searchEnd = if (isVeryFarFromPath || isFarFromPath) points.size else minOf(startIndex + searchRange, points.size)

            var bestScore = Float.MAX_VALUE
            var bestIndex = closestPointIndex

            // 6. 선분들에 대한 가중치 점수 계산
            val indicesToCheck = if (useSpatialIndex) {
                searchIndices.filter { it < points.size - 1 }
            } else {
                (0 until searchEnd - 1).toList()
            }

            for (i in indicesToCheck) {
                val p1 = points.getOrNull(i) ?: continue
                val p2 = points.getOrNull(i + 1) ?: continue

                // 거리 점수
                val distanceToSegment = distanceToLineSegment(rawLocation, p1, p2)
                val distanceScore = distanceToSegment * 1.0f

                // 진행 방향 점수
                val directionScore = if (currentBearing != null && currentBearing > 0f) {
                    val pathBearing = calculateBearing(p1, p2)
                    val bearingDiff = abs(shortestAngleDiff(currentBearing, pathBearing))
                    bearingDiff * 0.1f
                } else {
                    0f
                }

                // 진행률 점수 (뒤로 가면 페널티)
                val progressScore = if (i < startIndex) {
                    val penaltyMultiplier = when {
                        isVeryFarFromPath -> 0.5f
                        isFarFromPath -> 1.0f
                        distanceToSegment > 100f -> 1.5f
                        else -> 10.0f
                    }
                    (startIndex - i) * penaltyMultiplier
                } else {
                    0f
                }

                // 속도 기반 보너스
                val speedBonus = when {
                    currentSpeed > 33.3f && distanceToSegment < 150f -> -8f
                    currentSpeed > 27.8f && distanceToSegment < 120f -> -6f
                    currentSpeed > 10f && distanceToSegment < 100f -> -5f
                    currentSpeed < 1f && distanceToSegment > 50f -> 20f
                    else -> 0f
                }

                // 선분 길이 고려
                val segmentLength = calculateDistance(p1, p2)
                val segmentLengthBonus = if (segmentLength < 10f && distanceToSegment < 20f) {
                    -2f
                } else {
                    0f
                }

                val totalScore = distanceScore + directionScore + progressScore + speedBonus + segmentLengthBonus

                if (totalScore < bestScore) {
                    bestScore = totalScore
                    val distToP1 = calculateDistance(rawLocation, p1)
                    val distToP2 = calculateDistance(rawLocation, p2)
                    bestIndex = if (distToP1 < distToP2) i else i + 1
                }
            }

            // 7. 경로상의 점들과의 직접 거리도 확인
            val pointIndicesToCheck = if (useSpatialIndex) {
                searchIndices
            } else {
                (0 until searchEnd).toList()
            }

            for (i in pointIndicesToCheck) {
                val point = points.getOrNull(i) ?: continue
                val distance = calculateDistance(rawLocation, point)

                var pointScore = distance * 1.0f

                // 진행률 페널티
                if (i < startIndex) {
                    val penaltyMultiplier = when {
                        isVeryFarFromPath -> 0.5f
                        isFarFromPath -> 1.0f
                        distance > 100f -> 1.5f
                        else -> 10.0f
                    }
                    pointScore += (startIndex - i) * penaltyMultiplier
                }

                // 방향 점수
                if (currentBearing != null && currentBearing > 0f && i < points.size - 1) {
                    val nextPoint = points.getOrNull(i + 1)
                    if (nextPoint != null) {
                        val pathBearing = calculateBearing(point, nextPoint)
                        val bearingDiff = abs(shortestAngleDiff(currentBearing, pathBearing))
                        pointScore += bearingDiff * 0.1f
                    }
                }

                if (pointScore < bestScore) {
                    bestScore = pointScore
                    bestIndex = i
                }
            }

            val finalIndex = bestIndex.coerceIn(0, points.size - 1)
            val snapped = points[finalIndex]

            // 8. bearing 계산
            val bearing = when {
                finalIndex < points.size - 1 ->
                    calculateBearing(snapped, points[finalIndex + 1])
                finalIndex > 0 ->
                    calculateBearing(points[finalIndex - 1], snapped)
                else -> null
            }

            return SnapResult(snapped, finalIndex, bearing)
        } catch (e: Exception) {
            Timber.e(e, "Error in RouteSnapper.snapToRoute")
            return SnapResult(points[startIndex.coerceIn(0, points.size - 1)], startIndex, null)
        }
    }

    private fun calculateDistance(latLng1: LatLng, latLng2: LatLng): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            latLng1.latitude, latLng1.longitude,
            latLng2.latitude, latLng2.longitude,
            results
        )
        return results[0]
    }

    private fun distanceToLineSegment(point: LatLng, lineStart: LatLng, lineEnd: LatLng): Float {
        val A = point.latitude - lineStart.latitude
        val B = point.longitude - lineStart.longitude
        val C = lineEnd.latitude - lineStart.latitude
        val D = lineEnd.longitude - lineStart.longitude

        val dot = A * C + B * D
        val lenSq = C * C + D * D

        if (lenSq == 0.0) {
            return calculateDistance(point, lineStart)
        }

        val param = dot / lenSq

        val xx: Double
        val yy: Double

        if (param < 0) {
            xx = lineStart.latitude
            yy = lineStart.longitude
        } else if (param > 1) {
            xx = lineEnd.latitude
            yy = lineEnd.longitude
        } else {
            xx = lineStart.latitude + param * C
            yy = lineStart.longitude + param * D
        }

        return calculateDistance(point, LatLng(xx, yy))
    }

    private fun calculateBearing(from: LatLng, to: LatLng): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val brng = Math.toDegrees(atan2(y, x))
        return ((brng + 360) % 360).toFloat()
    }

    private fun shortestAngleDiff(angle1: Float, angle2: Float): Float {
        var diff = angle2 - angle1
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        return diff
    }

    /**
     * MapLibre 스타일: Step 단위로 스냅 (현재 instruction 구간만 사용)
     * TurfMisc.nearestPointOnLine 방식과 유사하게 선분에 가장 가까운 점을 찾습니다.
     *
     * @param rawLocation   실제 GPS 위치
     * @param stepPoints    현재 step의 좌표 리스트 (instruction 구간)
     * @param routePath     전체 경로 (인덱스 찾기용)
     * @param startIndex    이전 경로 인덱스 (참고용)
     * @return SnapResult with snapped location and bearing
     */
    fun snapToStep(
        rawLocation: LatLng,
        stepPoints: List<LatLng>,
        routePath: List<LatLng>,
        startIndex: Int
    ): SnapResult {
        if (stepPoints.isEmpty()) {
            return SnapResult(rawLocation, startIndex, null)
        }
        if (stepPoints.size < 2) {
            // step이 1개 점만 있으면 그 점 사용
            val point = stepPoints[0]
            val routeIndex = routePath.indexOf(point).takeIf { it >= 0 } ?: startIndex
            return SnapResult(point, routeIndex, null)
        }

        // 선분들 중 가장 가까운 점 찾기
        var minDistance = Float.MAX_VALUE
        var bestSnappedPoint = stepPoints[0]
        var bestSegmentIndex = 0

        for (i in 0 until stepPoints.size - 1) {
            val p1 = stepPoints[i]
            val p2 = stepPoints[i + 1]
            val snapped = nearestPointOnLineSegment(rawLocation, p1, p2)
            val distance = calculateDistance(rawLocation, snapped)

            if (distance < minDistance) {
                minDistance = distance
                bestSnappedPoint = snapped
                bestSegmentIndex = i
            }
        }

        // 전체 경로에서 가장 가까운 인덱스 찾기
        val routeIndex = findClosestIndexInRoute(bestSnappedPoint, routePath, startIndex)

        // Bearing 계산: 현재 위치에서 1m 앞 지점 기반 (MapLibre 방식)
        val bearing = calculateBearing1mAhead(bestSnappedPoint, stepPoints, bestSegmentIndex)

        return SnapResult(bestSnappedPoint, routeIndex, bearing)
    }

    /**
     * 선분 위의 가장 가까운 점 계산 (TurfMisc.nearestPointOnLine과 유사)
     */
    private fun nearestPointOnLineSegment(point: LatLng, lineStart: LatLng, lineEnd: LatLng): LatLng {
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
     * 전체 경로에서 가장 가까운 인덱스 찾기
     */
    private fun findClosestIndexInRoute(point: LatLng, routePath: List<LatLng>, startIndex: Int): Int {
        if (routePath.isEmpty()) return startIndex

        var minDistance = Float.MAX_VALUE
        var closestIndex = startIndex.coerceIn(0, routePath.size - 1)

        // startIndex 근처부터 검색 (앞뒤 100개 포인트)
        val searchStart = maxOf(0, startIndex - 100)
        val searchEnd = minOf(routePath.size, startIndex + 100)

        for (i in searchStart until searchEnd) {
            val distance = calculateDistance(point, routePath[i])
            if (distance < minDistance) {
                minDistance = distance
                closestIndex = i
            }
        }

        return closestIndex
    }

    /**
     * MapLibre 방식: 현재 위치에서 1m 앞 지점을 계산하여 bearing 계산
     */
    private fun calculateBearing1mAhead(
        currentPoint: LatLng,
        stepPoints: List<LatLng>,
        segmentIndex: Int
    ): Float? {
        if (stepPoints.size < 2) return null

        // 현재 위치가 속한 선분 찾기
        val segmentStart = stepPoints[segmentIndex]
        val segmentEnd = stepPoints[segmentIndex + 1]

        // 선분의 방향 계산
        val segmentBearing = calculateBearing(segmentStart, segmentEnd)

        // 현재 위치에서 1m 앞 지점 계산
        val futurePoint = calculatePositionAhead(currentPoint, segmentBearing, 1.0)

        // 현재 위치에서 1m 앞 지점까지의 bearing
        return calculateBearing(currentPoint, futurePoint)
    }

    /**
     * 현재 위치에서 특정 방향과 거리만큼 떨어진 위치 계산
     */
    private fun calculatePositionAhead(
        currentLocation: LatLng,
        bearing: Float,
        distanceMeters: Double
    ): LatLng {
        val earthRadius = 6371000.0 // 지구 반지름 (미터)
        val bearingRad = Math.toRadians(bearing.toDouble())
        val latRad = Math.toRadians(currentLocation.latitude)
        val lngRad = Math.toRadians(currentLocation.longitude)

        val newLatRad = asin(
            sin(latRad) * cos(distanceMeters / earthRadius) +
                    cos(latRad) * sin(distanceMeters / earthRadius) * cos(bearingRad)
        )

        val newLngRad = lngRad + atan2(
            sin(bearingRad) * sin(distanceMeters / earthRadius) * cos(latRad),
            cos(distanceMeters / earthRadius) - sin(latRad) * sin(newLatRad)
        )

        return LatLng(
            Math.toDegrees(newLatRad),
            Math.toDegrees(newLngRad)
        )
    }
}


