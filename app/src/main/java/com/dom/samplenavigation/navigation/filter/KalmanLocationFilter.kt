package com.dom.samplenavigation.navigation.filter

import timber.log.Timber

/**
 * 칼만 필터를 사용한 위치 추정 안정화 (T맵 스타일)
 * GPS 노이즈를 필터링하여 더 부드럽고 정확한 위치를 제공합니다.
 * 
 * 칼만 필터는 다음과 같은 상황에서 특히 유용합니다:
 * - GPS 정확도가 낮을 때 (건물 사이, 터널 근처)
 * - 위치가 자주 튀는 경우
 * - 부드러운 위치 추적이 필요한 경우
 */
class KalmanLocationFilter {
    
    // 상태 변수
    private var lat = 0.0  // 위도 추정값
    private var lng = 0.0  // 경도 추정값
    private var p = 1.0    // 추정 오차 공분산 (불확실성)
    
    // 초기화 여부
    private var isInitialized = false
    
    // 프로세스 노이즈 (시스템 모델의 불확실성)
    private val processNoise = 0.1
    
    companion object {
        // 최소 정확도 (너무 낮은 정확도는 신뢰하지 않음)
        private const val MIN_ACCURACY = 5.0
        
        // 최대 정확도 (너무 높은 정확도는 과신하지 않음)
        private const val MAX_ACCURACY = 100.0
    }
    
    /**
     * 위치 업데이트 (칼만 필터 적용)
     * 
     * @param measuredLat 측정된 위도
     * @param measuredLng 측정된 경도
     * @param accuracy GPS 정확도 (미터)
     * @return 필터링된 위치 (위도, 경도)
     */
    fun update(measuredLat: Double, measuredLng: Double, accuracy: Float): Pair<Double, Double> {
        val r = accuracy.toDouble().coerceIn(MIN_ACCURACY, MAX_ACCURACY)  // 측정 오차
        
        if (!isInitialized) {
            // 첫 번째 측정값으로 초기화
            lat = measuredLat
            lng = measuredLng
            p = r  // 초기 불확실성은 측정 오차와 동일
            isInitialized = true
            Timber.d("Kalman filter initialized: lat=$lat, lng=$lng, accuracy=$r")
            return Pair(lat, lng)
        }
        
        // 예측 단계 (시간이 지나면서 불확실성 증가)
        val pPred = p + processNoise
        
        // 업데이트 단계 (칼만 게인 계산)
        val k = pPred / (pPred + r)  // 칼만 게인 (0.0 ~ 1.0)
        
        // 상태 업데이트 (측정값과 예측값의 가중 평균)
        lat += k * (measuredLat - lat)
        lng += k * (measuredLng - lng)
        
        // 불확실성 업데이트
        p = (1.0 - k) * pPred
        
        Timber.d("Kalman filter: k=${String.format("%.3f", k)}, p=${String.format("%.2f", p)}, accuracy=$r")
        
        return Pair(lat, lng)
    }
    
    /**
     * 필터 리셋 (네비게이션 시작/종료 시)
     */
    fun reset() {
        lat = 0.0
        lng = 0.0
        p = 1.0
        isInitialized = false
        Timber.d("Kalman filter reset")
    }
    
    /**
     * 현재 추정 위치 반환
     */
    fun getCurrentEstimate(): Pair<Double, Double>? {
        return if (isInitialized) Pair(lat, lng) else null
    }
    
    /**
     * 필터 초기화 여부
     */
    fun isInitialized(): Boolean = isInitialized
}

