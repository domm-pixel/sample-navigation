package com.dom.samplenavigation.navigation.model

import com.naver.maps.geometry.LatLng

/**
 * 네비게이션에서 사용할 경로 데이터 모델
 */
data class NavigationRoute(
    val path: List<LatLng>,           // 전체 경로 좌표들
    val instructions: List<Instruction>, // 안내 메시지들
    val summary: RouteSummary,        // 경로 요약 정보
    val sections: List<RouteSection>  // 구간별 정보
)

/**
 * 안내 메시지 데이터
 */
data class Instruction(
    val distance: Int,              // 다음 안내까지의 거리 (미터)
    val duration: Int,              // 예상 소요 시간 (초)
    val message: String,            // 안내 메시지
    val pointIndex: Int,            // 경로상의 위치 인덱스
    val type: Int,                  // 안내 타입 (좌회전, 우회전 등)
    val location: LatLng           // 안내 지점의 좌표
)

/**
 * 경로 요약 정보
 */
data class RouteSummary(
    val totalDistance: Int,         // 총 거리 (미터)
    val totalDuration: Int,         // 총 소요 시간 (초)
    val startLocation: LatLng,      // 출발지
    val endLocation: LatLng,        // 도착지
    val fuelPrice: Int,             // 연료비
    val taxiFare: Int              // 택시비
)

/**
 * 구간별 정보
 */
data class RouteSection(
    val name: String,               // 도로명
    val distance: Int,              // 구간 거리
    val speed: Int,                 // 제한 속도
    val congestion: Int,            // 혼잡도 (1: 원활, 2: 보통, 3: 혼잡)
    val pointIndex: Int,            // 시작 지점 인덱스
    val pointCount: Int             // 구간 내 포인트 수
)

/**
 * 현재 네비게이션 상태
 */
data class NavigationState(
    val isNavigating: Boolean = false,           // 네비게이션 진행 중 여부
    val currentLocation: LatLng? = null,         // 현재 위치
    val currentInstruction: Instruction? = null, // 현재 안내 메시지
    val nextInstruction: Instruction? = null,    // 다음 안내 메시지
    val remainingDistance: Int = 0,              // 남은 거리
    val progress: Float = 0f,                    // 진행률 (0.0 ~ 1.0)
    val currentRoute: NavigationRoute? = null    // 현재 경로
)
