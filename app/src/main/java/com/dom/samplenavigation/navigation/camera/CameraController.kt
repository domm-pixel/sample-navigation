package com.dom.samplenavigation.navigation.camera

/**
 * 카메라 제어 로직을 담당하는 클래스
 * 속도 기반 줌/틸트 계산 및 베어링 스무딩
 */
class CameraController {
    
    private var currentBearing: Float = 0f
    private var currentZoom: Double = 17.0
    private var currentTilt: Double = 0.0
    
    companion object {
        private const val ZOOM_LOW_SPEED = 18.0  // 저속 줌
        private const val ZOOM_DEFAULT = 17.0  // 기본 줌
        private const val ZOOM_HIGH_SPEED = 16.0  // 고속 줌
        private const val SPEED_THRESHOLD_SLOW = 4.2f  // ≈15km/h
        private const val SPEED_THRESHOLD_FAST = 13.9f  // ≈50km/h
        private const val HIGH_SPEED_TILT = 45.0
        private const val DEFAULT_TILT = 30.0
        private const val BEARING_SMOOTH_ALPHA = 0.3f  // 베어링 스무딩 계수
    }

    /**
     * 속도 기반 줌 계산
     */
    fun calculateZoomFromSpeed(speedMps: Float): Double {
        val speedKmh = speedMps * 3.6f
        return when {
            speedKmh < 15 -> ZOOM_LOW_SPEED  // 저속: 줌인
            speedKmh > 50 -> ZOOM_HIGH_SPEED  // 고속: 줌아웃
            else -> ZOOM_DEFAULT  // 기본
        }
    }

    /**
     * 속도 기반 틸트 계산
     */
    fun calculateTiltFromSpeed(speedMps: Float): Double {
        val speedKmh = speedMps * 3.6f
        return if (speedKmh > 50) HIGH_SPEED_TILT else DEFAULT_TILT
    }

    /**
     * 베어링 스무딩 (부드러운 회전)
     */
    fun smoothBearing(target: Float): Float {
        if (currentBearing == 0f) {
            currentBearing = target
            return target
        }

        val diff = shortestAngleDiff(currentBearing, target)
        val smoothed = currentBearing + diff * BEARING_SMOOTH_ALPHA
        currentBearing = normalizeBearing(smoothed)
        return currentBearing
    }

    /**
     * 최단 각도 차이 계산
     */
    private fun shortestAngleDiff(from: Float, to: Float): Float {
        var diff = (to - from) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return diff
    }

    /**
     * 각도를 0~360 범위로 정규화
     */
    private fun normalizeBearing(angle: Float): Float {
        var normalized = angle % 360f
        if (normalized < 0f) normalized += 360f
        return normalized
    }

    /**
     * 현재 줌 값 업데이트
     */
    fun updateZoom(zoom: Double) {
        currentZoom = zoom
    }

    /**
     * 현재 틸트 값 업데이트
     */
    fun updateTilt(tilt: Double) {
        currentTilt = tilt
    }

    /**
     * 현재 베어링 값 업데이트
     */
    fun updateBearing(bearing: Float) {
        currentBearing = bearing
    }

    /**
     * 현재 줌 값 가져오기
     */
    fun getCurrentZoom(): Double = currentZoom

    /**
     * 현재 틸트 값 가져오기
     */
    fun getCurrentTilt(): Double = currentTilt

    /**
     * 현재 베어링 값 가져오기
     */
    fun getCurrentBearing(): Float = currentBearing

    /**
     * 상태 리셋
     */
    fun reset() {
        currentBearing = 0f
        currentZoom = ZOOM_DEFAULT
        currentTilt = DEFAULT_TILT
    }
}

