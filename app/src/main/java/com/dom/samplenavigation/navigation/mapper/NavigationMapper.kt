package com.dom.samplenavigation.navigation.mapper

import com.dom.samplenavigation.api.navigation.model.ResultPath
import com.dom.samplenavigation.api.navigation.model.RouteOptionRaw
import com.dom.samplenavigation.navigation.model.Instruction
import com.dom.samplenavigation.navigation.model.NavigationRoute
import com.dom.samplenavigation.navigation.model.NavigationOptionRoute
import com.dom.samplenavigation.navigation.model.RouteOptionType
import com.dom.samplenavigation.navigation.model.RouteSection
import com.dom.samplenavigation.navigation.model.RouteSummary
import com.naver.maps.geometry.LatLng

/**
 * API 응답을 앱에서 사용할 모델로 변환하는 매퍼
 */
object NavigationMapper {

    fun mapToNavigationRoute(resultPath: ResultPath): NavigationRoute? {
        val routes = mapToNavigationOptionRoutes(resultPath)
        return routes.firstOrNull { it.optionType == RouteOptionType.TRAOPTIMAL }?.route
            ?: routes.firstOrNull()?.route
    }

    fun mapToNavigationOptionRoutes(resultPath: ResultPath): List<NavigationOptionRoute> {
        if (resultPath.code != 0) return emptyList()

        val routeRoot = resultPath.route ?: return emptyList()
        val options = mutableListOf<NavigationOptionRoute>()

        routeRoot.trafast?.firstOrNull()?.let { option ->
            mapRouteOption(option)?.let {
                options += NavigationOptionRoute(RouteOptionType.TRAFAST, it)
            }
        }
        routeRoot.traoptimal?.firstOrNull()?.let { option ->
            mapRouteOption(option)?.let {
                options += NavigationOptionRoute(RouteOptionType.TRAOPTIMAL, it)
            }
        }
        routeRoot.traavoidtoll?.firstOrNull()?.let { option ->
            mapRouteOption(option)?.let {
                options += NavigationOptionRoute(RouteOptionType.TRAAVOIDTOLL, it)
            }
        }

        return options
    }

    private fun mapRouteOption(route: RouteOptionRaw): NavigationRoute? {
        val path = route.path?.map { coordinates ->
            if (coordinates.size >= 2) {
                LatLng(coordinates[1], coordinates[0])
            } else {
                LatLng(0.0, 0.0)
            }
        } ?: emptyList()

        if (path.isEmpty()) return null

        val instructions = route.guide?.map { guide ->
            val pointIndex = guide.pointIndex ?: 0
            val location = if (pointIndex in path.indices) {
                path[pointIndex]
            } else {
                path.lastOrNull() ?: LatLng(0.0, 0.0)
            }

            Instruction(
                distance = guide.distance ?: 0,
                duration = guide.duration ?: 0,
                message = guide.instructions ?: "",
                pointIndex = pointIndex,
                type = guide.type ?: 0,
                location = location
            )
        } ?: emptyList()

        val sections = route.section?.map { section ->
            RouteSection(
                name = section.name ?: "",
                distance = section.distance ?: 0,
                speed = section.speed ?: 0,
                congestion = section.congestion ?: 1,
                pointIndex = section.pointIndex ?: 0,
                pointCount = section.pointCount ?: 0
            )
        } ?: emptyList()

        val summary = route.summary?.let { s ->
            val startLocation = s.start?.location?.let { loc ->
                if (loc.size >= 2) {
                    LatLng(loc[1], loc[0])
                } else {
                    LatLng(0.0, 0.0)
                }
            } ?: LatLng(0.0, 0.0)

            val endLocation = s.goal?.location?.let { loc ->
                if (loc.size >= 2) {
                    LatLng(loc[1], loc[0])
                } else {
                    LatLng(0.0, 0.0)
                }
            } ?: LatLng(0.0, 0.0)

            RouteSummary(
                totalDistance = s.distance ?: 0,
                totalDuration = s.duration ?: 0,
                startLocation = startLocation,
                endLocation = endLocation,
                fuelPrice = s.fuelPrice ?: 0,
                taxiFare = s.taxiFare ?: 0,
                tollFare = s.tollFare ?: 0
            )
        } ?: return null

        return NavigationRoute(
            path = path,
            instructions = instructions,
            summary = summary,
            sections = sections
        )
    }
    
    /**
     * 안내 타입을 한글로 변환 (네이버 API 명세 기반)
     */
    fun getInstructionTypeText(type: Int): String {
        return when (type) {
            1 -> "직진 방향"
            2 -> "좌회전"
            3 -> "우회전"
            4 -> "왼쪽 방향"
            5 -> "오른쪽 방향"
            6 -> "유턴"
            8 -> "비보호 좌회전"
            11 -> "왼쪽 8시 방향"
            12 -> "왼쪽 9시 방향"
            13 -> "왼쪽 11시 방향"
            14 -> "오른쪽 1시 방향"
            15 -> "오른쪽 3시 방향"
            16 -> "오른쪽 4시 방향"
            21 -> "로터리에서 직진 방향"
            22 -> "로터리에서 유턴"
            23 -> "로터리에서 왼쪽 7시 방향"
            24 -> "로터리에서 왼쪽 8시 방향"
            25 -> "로터리에서 왼쪽 9시 방향"
            26 -> "로터리에서 왼쪽 10시 방향"
            27 -> "로터리에서 왼쪽 11시 방향"
            28 -> "로터리에서 12시 방향"
            29 -> "로터리에서 오른쪽 1시 방향"
            30 -> "로터리에서 오른쪽 2시 방향"
            31 -> "로터리에서 오른쪽 3시 방향"
            32 -> "로터리에서 오른쪽 4시 방향"
            33 -> "로터리에서 오른쪽 5시 방향"
            34 -> "로터리에서 6시 방향"
            41 -> "왼쪽 도로로 진입"
            42 -> "오른쪽 도로로 진입"
            47 -> "휴게소로 진입"
            48 -> "페리항로 진입"
            49 -> "페리항로 진출"
            50 -> "전방에 고속도로 진입"
            51 -> "전방에 고속도로 진출"
            52 -> "전방에 도시 고속 도로 진입"
            53 -> "전방에 도시 고속 도로 진출"
            54 -> "전방에 분기 도로 진입"
            55 -> "전방에 고가 차로 진입"
            56 -> "전방에 지하 차도 진입"
            57 -> "왼쪽에 고속 도로 진입"
            58 -> "왼쪽에 고속 도로 진출"
            59 -> "왼쪽에 도시 고속 도로 진입"
            60 -> "왼쪽에 도시 고속 도로 진출"
            62 -> "왼쪽에 고가 차도 진입"
            63 -> "왼쪽에 고가 차도 옆길"
            64 -> "왼쪽에 지하 차도 진입"
            65 -> "왼쪽에 지하 차도 옆길"
            66 -> "오른쪽에 고속 도로 진입"
            67 -> "오른쪽에 고속 도로 진출"
            68 -> "오른쪽에 도시 고속 도로 진입"
            69 -> "오른쪽에 도시 고속 도로 진출"
            71 -> "오른쪽에 고가 차도 진입"
            72 -> "오른쪽에 고가 차도 옆길"
            73 -> "오른쪽에 지하 차도 진입"
            74 -> "오른쪽에 지하 차도 옆길"
            75 -> "전방에 자동차 전용 도로 진입"
            76 -> "왼쪽에 자동차 전용 도로 진입"
            77 -> "오른쪽에 자동차 전용 도로 진입"
            78 -> "전방에 자동차 전용 도로 진출"
            79 -> "왼쪽에 자동차 전용 도로 진출"
            80 -> "오른쪽에 자동차 전용 도로 진출"
            81 -> "왼쪽에 본선으로 합류"
            82 -> "오른쪽에 본선으로 합류"
            87 -> "경유지"
            88 -> "도착지"
            91 -> "회전 교차로에서 직진 방향"
            92 -> "회전 교차로에서 유턴"
            93 -> "회전 교차로에서 왼쪽 7시 방향"
            94 -> "회전 교차로에서 왼쪽 8시 방향"
            95 -> "회전 교차로에서 왼쪽 9시 방향"
            96 -> "회전 교차로에서 왼쪽 10시 방향"
            97 -> "회전 교차로에서 왼쪽 11시 방향"
            98 -> "회전 교차로에서 12시 방향"
            99 -> "회전 교차로에서 오른쪽 1시 방향"
            100 -> "회전 교차로에서 오른쪽 2시 방향"
            101 -> "회전 교차로에서 오른쪽 3시 방향"
            102 -> "회전 교차로에서 오른쪽 4시 방향"
            103 -> "회전 교차로에서 오른쪽 5시 방향"
            104 -> "회전 교차로에서 6시 방향"
            121 -> "톨게이트"
            122 -> "하이패스 전용 톨게이트"
            123 -> "원톨링 톨게이트"
            else -> "직진"
        }
    }
    
    /**
     * 안내 메시지를 더 자연스럽게 포맷팅
     */
    fun formatInstructionMessage(instruction: Instruction): String {
        val distance = instruction.distance
        val message = instruction.message
        
        return when {
            distance >= 1000 -> {
                val km = distance / 1000
                if (km >= 2) {
                    "앞으로 ${km}킬로미터 후 $message"
                } else {
                    "앞으로 1킬로미터 후 $message"
                }
            }
            distance >= 500 -> "500미터 후 $message"
            distance >= 300 -> "300미터 후 $message"
            distance >= 200 -> "200미터 후 $message"
            distance >= 100 -> "100미터 후 $message"
            distance >= 50 -> "50미터 후 $message"
            else -> message
        }
    }
}
