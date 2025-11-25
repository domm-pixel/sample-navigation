package com.dom.samplenavigation.navigation.filter

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * KalmanLocationFilter 단위 테스트
 * 
 * 테스트 전략:
 * 1. 초기화 테스트
 * 2. 필터링 동작 테스트
 * 3. 리셋 기능 테스트
 * 4. 노이즈 필터링 효과 테스트
 */
class KalmanLocationFilterTest {

    private lateinit var filter: KalmanLocationFilter

    @Before
    fun setUp() {
        filter = KalmanLocationFilter()
    }

    @Test
    fun `초기화 전에는 현재 추정값이 null이어야 함`() {
        // Given & When
        val estimate = filter.getCurrentEstimate()

        // Then
        assertNull("초기화 전에는 추정값이 null이어야 함", estimate)
        assertFalse("초기화되지 않아야 함", filter.isInitialized())
    }

    @Test
    fun `첫 번째 업데이트 시 측정값으로 초기화되어야 함`() {
        // Given
        val measuredLat = 37.497942
        val measuredLng = 127.027610
        val accuracy = 10f

        // When
        val (filteredLat, filteredLng) = filter.update(measuredLat, measuredLng, accuracy)

        // Then
        assertEquals("첫 측정값으로 초기화되어야 함", measuredLat, filteredLat, 0.000001)
        assertEquals("첫 측정값으로 초기화되어야 함", measuredLng, filteredLng, 0.000001)
        assertTrue("초기화되어야 함", filter.isInitialized())
    }

    @Test
    fun `연속된 업데이트 시 필터링이 적용되어야 함`() {
        // Given
        val firstLat = 37.497942
        val firstLng = 127.027610
        val secondLat = 37.498000  // 약간 이동
        val secondLng = 127.027650
        val accuracy = 10f

        // When
        val (firstFilteredLat, firstFilteredLng) = filter.update(firstLat, firstLng, accuracy)
        val (secondFilteredLat, secondFilteredLng) = filter.update(secondLat, secondLng, accuracy)

        // Then
        // 첫 번째는 측정값과 동일
        assertEquals(firstLat, firstFilteredLat, 0.000001)
        assertEquals(firstLng, firstFilteredLng, 0.000001)

        // 두 번째는 필터링된 값 (측정값과 이전 추정값의 가중 평균)
        assertNotEquals("필터링된 값은 측정값과 다를 수 있음", secondLat, secondFilteredLat, 0.000001)
        assertNotEquals("필터링된 값은 측정값과 다를 수 있음", secondLng, secondFilteredLng, 0.000001)
        
        // 필터링된 값은 측정값과 이전 추정값 사이에 있어야 함
        assertTrue("필터링된 값은 측정값과 이전 추정값 사이", 
            (firstLat <= secondFilteredLat && secondFilteredLat <= secondLat) ||
            (secondLat <= secondFilteredLat && secondFilteredLat <= firstLat))
    }

    @Test
    fun `높은 정확도일 때 측정값에 더 가까워야 함`() {
        // Given
        val firstLat = 37.497942
        val firstLng = 127.027610
        val secondLat = 37.498000
        val secondLng = 127.027650
        val highAccuracy = 5f  // 높은 정확도

        // When
        filter.update(firstLat, firstLng, highAccuracy)
        val (filteredLat, _) = filter.update(secondLat, secondLng, highAccuracy)

        // Then
        // 높은 정확도일 때는 측정값에 더 가까워야 함
        val distanceFromMeasurement = Math.abs(secondLat - filteredLat)
        assertTrue("높은 정확도일 때 측정값에 가까워야 함", distanceFromMeasurement < 0.0001)
    }

    @Test
    fun `낮은 정확도일 때 이전 추정값에 더 가까워야 함`() {
        // Given
        val firstLat = 37.497942
        val firstLng = 127.027610
        val secondLat = 37.498000  // 약간 이동 (약 8m)
        val secondLng = 127.027650
        val lowAccuracy = 50f  // 낮은 정확도 (50m)
        val highAccuracy = 5f  // 높은 정확도 (5m)

        // When - 낮은 정확도로 필터링
        filter.update(firstLat, firstLng, lowAccuracy)
        val (filteredLatLow, _) = filter.update(secondLat, secondLng, lowAccuracy)
        
        // 높은 정확도로 필터링 (비교용)
        filter.reset()
        filter.update(firstLat, firstLng, highAccuracy)
        val (filteredLatHigh, _) = filter.update(secondLat, secondLng, highAccuracy)

        // Then
        // 낮은 정확도일 때는 필터링된 값이 측정값(secondLat)에 덜 가까워야 함
        // 즉, 이전 추정값(firstLat)에 더 가까워야 함
        val distanceFromMeasurementLow = Math.abs(secondLat - filteredLatLow)
        val distanceFromMeasurementHigh = Math.abs(secondLat - filteredLatHigh)
        
        // 낮은 정확도일 때 측정값으로부터 더 멀리 있어야 함 (이전 값에 더 가까움)
        assertTrue("낮은 정확도일 때 측정값으로부터 더 멀어야 함 (이전 추정값에 가까움)", 
            distanceFromMeasurementLow > distanceFromMeasurementHigh)
    }

    @Test
    fun `리셋 후 초기화 상태로 돌아가야 함`() {
        // Given
        filter.update(37.497942, 127.027610, 10f)
        assertTrue("초기화되어 있어야 함", filter.isInitialized())

        // When
        filter.reset()

        // Then
        assertFalse("리셋 후 초기화되지 않아야 함", filter.isInitialized())
        assertNull("리셋 후 추정값이 null이어야 함", filter.getCurrentEstimate())
    }

    @Test
    fun `리셋 후 새로운 측정값으로 다시 초기화 가능해야 함`() {
        // Given
        filter.update(37.497942, 127.027610, 10f)
        filter.reset()

        // When
        val newLat = 37.500000
        val newLng = 127.030000
        val (filteredLat, filteredLng) = filter.update(newLat, newLng, 10f)

        // Then
        assertEquals("새 측정값으로 초기화되어야 함", newLat, filteredLat, 0.000001)
        assertEquals("새 측정값으로 초기화되어야 함", newLng, filteredLng, 0.000001)
        assertTrue("다시 초기화되어야 함", filter.isInitialized())
    }

    @Test
    fun `노이즈가 많은 측정값도 부드럽게 필터링되어야 함`() {
        // Given
        val baseLat = 37.497942
        val baseLng = 127.027610
        val accuracy = 10f

        // When - 노이즈가 많은 연속 측정값
        var filteredLat = 0.0
        var filteredLng = 0.0
        
        // 첫 번째 측정값
        val (lat1, lng1) = filter.update(baseLat, baseLng, accuracy)
        
        // 노이즈가 있는 측정값들 (위치가 튀는 경우)
        val (lat2, lng2) = filter.update(baseLat + 0.001, baseLng + 0.001, accuracy)  // 튐
        val (lat3, lng3) = filter.update(baseLat - 0.0005, baseLng - 0.0005, accuracy)  // 다시 튐
        val (lat4, lng4) = filter.update(baseLat, baseLng, accuracy)  // 원래 위치로

        // Then
        // 필터링된 값들은 부드럽게 변화해야 함
        val jump1 = Math.abs(lat2 - lat1)
        val jump2 = Math.abs(lat3 - lat2)
        val jump3 = Math.abs(lat4 - lat3)
        
        // 필터링된 값의 변화량은 측정값의 변화량보다 작아야 함 (부드러움)
        assertTrue("필터링으로 인한 변화가 부드러워야 함", jump1 < 0.001)
        assertTrue("필터링으로 인한 변화가 부드러워야 함", jump2 < 0.001)
        assertTrue("필터링으로 인한 변화가 부드러워야 함", jump3 < 0.001)
    }
}

