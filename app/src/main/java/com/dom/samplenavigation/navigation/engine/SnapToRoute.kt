package com.dom.samplenavigation.navigation.engine

import com.dom.samplenavigation.navigation.engine.Location
import com.naver.maps.geometry.LatLng
import android.location.Location as AndroidLocation
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.asin
import timber.log.Timber

/**
 * MapLibre 스타일의 경로 스냅핑 엔진.
 * 현재 step의 좌표 리스트에 대해 가장 가까운 점을 찾아 스냅합니다.
 */
open class SnapToRoute : Snap() {
    /**
     * Last calculated snapped bearing. This will be re-used if bearing can not calculated.
     * Is NULL if no bearing was calculated yet.
     */
    private var lastSnappedBearing: Float? = null

    /**
     * Calculate a snapped location along the route. Latitude, longitude and bearing are provided.
     *
     * @param location Current raw user location
     * @param routeProgress Current route progress
     * @return Snapped location along route
     */
    override fun getSnappedLocation(location: Location, routeProgress: RouteProgress): Location {
        val snappedLocation = snapLocationLatLng(location, routeProgress.currentStepPoints)
        Timber.d("SnapToRoute: rawLocation=${location.latLng}, snappedLocation=${snappedLocation.latLng}, currentStepPoints.size=${routeProgress.currentStepPoints.size}")
        // bearing 계산은 raw location을 사용 (MapLibre 방식)
        val bearing = snapLocationBearing(location, routeProgress)
        return snappedLocation.copy(bearing = bearing)
    }

    /**
     * Creates a snapped bearing for the snapped [Location].
     * MapLibre 방식: distanceTraveled를 사용해서 현재 지점과 1m 앞 지점을 계산하고 bearing 계산
     *
     * @param location Current raw user location (MapLibre 방식)
     * @param routeProgress Current route progress
     * @return Float bearing snapped to route (북쪽 기준 0-360도)
     */
    private fun snapLocationBearing(location: Location, routeProgress: RouteProgress): Float? {
        return getCurrentPoint(routeProgress)?.let { currentPoint ->
            getFuturePoint(routeProgress)?.let { futurePoint ->
                // Get bearing and convert azimuth to degrees (북쪽 기준)
                val azimuth = calculateBearing(currentPoint, futurePoint)
                // Naver Map SDK는 bearing이 진행 방향을 위쪽(북쪽)으로 향하도록 해석하므로,
                // 계산된 bearing에 180도를 더하여 방향을 반대로 조정
                val adjustedAzimuth = (azimuth + 180.0) % 360.0
                Timber.d("SnapToRoute: currentPoint=$currentPoint, futurePoint=$futurePoint, calculatedBearing=$azimuth, adjustedBearing=$adjustedAzimuth")
                wrap(adjustedAzimuth, 0.0, 360.0).toFloat()
                    .also { bearing -> 
                        lastSnappedBearing = bearing
                        Timber.d("SnapToRoute: finalBearing=$bearing")
                    }
            }
        }
            ?: lastSnappedBearing
            ?: location.bearing
    }
    
    /**
     * Current step point. MapLibre 방식: stepDistanceTraveled를 사용해서 정확한 위치 계산
     *
     * @param routeProgress Current route progress
     * @return Current step point or null if no current step is available
     */
    private fun getCurrentPoint(routeProgress: RouteProgress): LatLng? {
        return getCurrentStepPoint(routeProgress, 0.0)
    }

    /**
     * Snap coordinates of user's location to the closest position along the current step.
     *
     * @param location        the raw location
     * @param stepCoordinates the list of step geometry coordinates
     * @return the altered user location
     */
    private fun snapLocationLatLng(location: Location, stepCoordinates: List<LatLng>): Location {
        // Uses Turf's pointOnLine approach, which takes a Point and a LineString to calculate the closest
        // Point on the LineString.
        return if (stepCoordinates.size > 1) {
            val point = nearestPointOnLine(location.latLng, stepCoordinates)
            val snapped = location.copy(
                latitude = point.latitude,
                longitude = point.longitude
            )
            // 스냅이 실패한 경우 (거리가 너무 멀면) 원본 위치 반환하지 않고 가장 가까운 점 반환
            val distance = calculateDistance(location.latLng, point)
            if (distance > 1000.0) {
                // 1km 이상 떨어져 있으면 스냅 실패로 간주하고 가장 가까운 점 사용
                Timber.w("SnapToRoute: Snapped location is too far (${distance}m), using closest point")
            }
            Timber.d("SnapToRoute: snapLocationLatLng - raw=${location.latLng}, snapped=${snapped.latLng}, distance=${distance}m, stepSize=${stepCoordinates.size}")
            snapped
        } else if (stepCoordinates.size == 1) {
            // step이 1개 점만 있으면 그 점 사용
            Timber.d("SnapToRoute: snapLocationLatLng - step has only 1 point, using that point")
            location.copy(
                latitude = stepCoordinates[0].latitude,
                longitude = stepCoordinates[0].longitude
            )
        } else {
            // step이 비어있으면 원본 위치 반환
            Timber.w("SnapToRoute: stepCoordinates is empty, returning original location")
            location.copy()
        }
    }

    /**
     * Get future point. MapLibre 방식: stepDistanceTraveled + 1.0을 사용해서 1m 앞의 지점 계산
     *
     * @param routeProgress Current route progress
     * @return Future point or null if no following point is available
     */
    private fun getFuturePoint(routeProgress: RouteProgress): LatLng? {
        return if (routeProgress.stepDistanceRemaining > 1) {
            // User has not reaching the end of current step. Use traveled distance + 1 meter for future point
            getCurrentStepPoint(routeProgress, 1.0)
        } else {
            // User has reached the end of step. Use upcoming step for future point if available.
            getUpcomingStepPoint(routeProgress)
        }
    }

    /**
     * Current step point plus additional distance value. MapLibre 방식
     *
     * @param routeProgress Current route progress
     * @param additionalDistance Additional distance to add to current step point
     * @return Current step point + additional distance or null if no current step is available
     */
    private fun getCurrentStepPoint(
        routeProgress: RouteProgress,
        additionalDistance: Double
    ): LatLng? {
        val stepPoints = routeProgress.currentStepPoints
        if (stepPoints.isEmpty()) return null

        val distanceTraveled = routeProgress.stepDistanceTraveled + additionalDistance
        
        Timber.d("SnapToRoute: getCurrentStepPoint - stepDistanceTraveled=${routeProgress.stepDistanceTraveled}, additionalDistance=$additionalDistance, totalDistance=$distanceTraveled, stepPoints.size=${stepPoints.size}")
        
        // TurfMeasurement.along과 유사하게 stepPoints에서 distanceTraveled만큼 떨어진 지점 계산
        val point = along(stepPoints, distanceTraveled)
        Timber.d("SnapToRoute: getCurrentStepPoint - result=$point")
        return point
    }
    
    /**
     * Step의 전체 거리 계산
     */
    private fun calculateStepTotalDistance(stepPoints: List<LatLng>): Double {
        if (stepPoints.size < 2) return 0.0
        
        var totalDistance = 0.0
        for (i in 0 until stepPoints.size - 1) {
            totalDistance += calculateDistance(stepPoints[i], stepPoints[i + 1])
        }
        return totalDistance
    }

    /**
     * Get upcoming step's start point. The second point of next step is used as start point to avoid
     * returning the same coordinates as the end point of the step before.
     *
     * @param routeProgress Current route progress
     * @return Next step's start point or null if no next step is available
     */
    private fun getUpcomingStepPoint(routeProgress: RouteProgress): LatLng? {
        val upcomingStepPoints = routeProgress.upcomingStepPoints
        if (upcomingStepPoints.isNullOrEmpty()) return null

        // While first point is the same point as the last point of the current step, use the second one.
        if (upcomingStepPoints.size > 1) {
            return along(upcomingStepPoints, 1.0)
        } else {
            return upcomingStepPoints.firstOrNull()
        }
    }


    /**
     * Calculate a point along a line at a specified distance.
     * Similar to TurfMeasurement.along
     */
    private fun along(points: List<LatLng>, distanceMeters: Double): LatLng? {
        if (points.isEmpty()) return null
        if (points.size == 1) return points[0]
        if (distanceMeters <= 0) return points[0]

        var accumulatedDistance = 0.0
        for (i in 0 until points.size - 1) {
            val segmentDistance = calculateDistance(points[i], points[i + 1])
            if (accumulatedDistance + segmentDistance >= distanceMeters) {
                // 목표 거리가 이 선분 안에 있음
                val remainingDistance = distanceMeters - accumulatedDistance
                val ratio = remainingDistance / segmentDistance
                return LatLng(
                    points[i].latitude + (points[i + 1].latitude - points[i].latitude) * ratio,
                    points[i].longitude + (points[i + 1].longitude - points[i].longitude) * ratio
                )
            }
            accumulatedDistance += segmentDistance
        }
        
        // 목표 거리가 전체 경로보다 길면 마지막 점 반환
        return points.last()
    }

    /**
     * Find the nearest point on a line string to a given point.
     * Similar to TurfMisc.nearestPointOnLine
     */
    private fun nearestPointOnLine(point: LatLng, lineCoordinates: List<LatLng>): LatLng {
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
     * Find the nearest point on a line segment to a given point.
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
     * Calculate bearing between two points
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
     * Calculate distance between two points in meters
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
     * Wrap a value to be within a range
     */
    private fun wrap(value: Double, min: Double, max: Double): Double {
        var wrapped = value
        val range = max - min
        while (wrapped < min) wrapped += range
        while (wrapped >= max) wrapped -= range
        return wrapped
    }
}

