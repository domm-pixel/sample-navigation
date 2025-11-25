package com.dom.samplenavigation.navigation.filter

import com.naver.maps.geometry.LatLng
import timber.log.Timber

/**
 * 경로 공간 인덱싱 (T맵 스타일)
 * 경로를 그리드로 분할하여 빠른 근접 검색을 가능하게 합니다.
 * 
 * 장점:
 * - 대용량 경로 (수천 개 포인트)에서 성능 향상
 * - 특정 반경 내 포인트만 빠르게 찾기
 * - O(n) → O(1) 검색 시간 개선 (이상적인 경우)
 * 
 * 단점:
 * - 초기 인덱스 구축 비용
 * - 메모리 사용량 증가
 * - 경로가 작을 때는 오버헤드
 */
class PathSpatialIndex(private val path: List<LatLng>) {
    
    // 그리드 크기 (약 1km = 0.01도)
    // 위도 1도 ≈ 111km, 경도 1도 ≈ 111km × cos(위도)
    private val gridSize = 0.01  // 약 1.1km
    
    // 그리드 맵: "위도_그리드,경도_그리드" -> [인덱스 리스트]
    private val grid = mutableMapOf<String, MutableList<Int>>()
    
    // 인덱스 구축 여부
    private var isBuilt = false
    
    companion object {
        // 최소 경로 크기 (이보다 작으면 인덱싱 불필요)
        private const val MIN_PATH_SIZE_FOR_INDEXING = 100
    }
    
    init {
        buildIndex()
    }
    
    /**
     * 공간 인덱스 구축
     */
    private fun buildIndex() {
        if (path.size < MIN_PATH_SIZE_FOR_INDEXING) {
            Timber.d("Path too small (${path.size} points) - skipping spatial index")
            return
        }
        
        val startTime = System.currentTimeMillis()
        path.forEachIndexed { index, point ->
            val gridKey = getGridKey(point)
            grid.getOrPut(gridKey) { mutableListOf() }.add(index)
        }
        isBuilt = true
        val buildTime = System.currentTimeMillis() - startTime
        Timber.d("Spatial index built: ${grid.size} grids, ${path.size} points, ${buildTime}ms")
    }
    
    /**
     * 좌표를 그리드 키로 변환
     */
    private fun getGridKey(point: LatLng): String {
        val gridX = (point.latitude / gridSize).toInt()
        val gridY = (point.longitude / gridSize).toInt()
        return "$gridX,$gridY"
    }
    
    /**
     * 특정 반경 내의 근접 포인트 인덱스 찾기
     * 
     * @param center 중심 위치
     * @param radiusMeters 검색 반경 (미터)
     * @param startIndex 검색 시작 인덱스 (진행 방향 고려)
     * @return 근접 포인트 인덱스 리스트 (정렬됨)
     */
    fun findNearbyPoints(
        center: LatLng,
        radiusMeters: Double,
        startIndex: Int = 0
    ): List<Int> {
        if (!isBuilt || path.isEmpty()) {
            // 인덱스가 없으면 전체 검색 (기존 방식)
            return (startIndex until path.size).toList()
        }
        
        // 반경을 그리드 단위로 변환 (대략적으로)
        // 1도 ≈ 111km, gridSize = 0.01도 ≈ 1.1km
        val gridRadius = ((radiusMeters / 111000.0) / gridSize).toInt() + 1
        
        val centerGridX = (center.latitude / gridSize).toInt()
        val centerGridY = (center.longitude / gridSize).toInt()
        
        val nearbyIndices = mutableSetOf<Int>()
        
        // 중심 그리드 주변 검색
        for (dx in -gridRadius..gridRadius) {
            for (dy in -gridRadius..gridRadius) {
                val key = "${centerGridX + dx},${centerGridY + dy}"
                grid[key]?.let { indices ->
                    // 시작 인덱스 이후의 포인트만 포함 (진행 방향 고려)
                    indices.filter { it >= startIndex }.forEach { nearbyIndices.add(it) }
                }
            }
        }
        
        val result = nearbyIndices.sorted()
        Timber.d("Spatial index search: radius=${radiusMeters.toInt()}m, found ${result.size} points (from ${path.size} total)")
        
        return result
    }
    
    /**
     * 인덱스 리셋 (새 경로 로드 시)
     */
    fun reset(newPath: List<LatLng>) {
        grid.clear()
        isBuilt = false
        // 새 경로로 재구축은 외부에서 새 인스턴스 생성 권장
    }
    
    /**
     * 인덱스 사용 가능 여부
     */
    fun isAvailable(): Boolean = isBuilt
}

