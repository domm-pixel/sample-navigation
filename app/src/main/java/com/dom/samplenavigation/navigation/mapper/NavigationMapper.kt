package com.dom.samplenavigation.navigation.mapper

import com.dom.samplenavigation.api.navigation.model.ResultPath
import com.dom.samplenavigation.navigation.model.Instruction
import com.dom.samplenavigation.navigation.model.NavigationRoute
import com.dom.samplenavigation.navigation.model.RouteSection
import com.dom.samplenavigation.navigation.model.RouteSummary
import com.naver.maps.geometry.LatLng

/**
 * API 응답을 앱에서 사용할 모델로 변환하는 매퍼
 */
object NavigationMapper {
    
    fun mapToNavigationRoute(resultPath: ResultPath): NavigationRoute? {
        // API 응답이 성공인지 확인
        if (resultPath.code != 0) {
            return null
        }
        
        val route = resultPath.route?.traoptimal?.firstOrNull() ?: return null
        
        // 경로 좌표 변환
        val path = route.path?.map { coordinates ->
            if (coordinates.size >= 2) {
                LatLng(coordinates[1], coordinates[0]) // [lng, lat] -> LatLng(lat, lng)
            } else {
                LatLng(0.0, 0.0)
            }
        } ?: emptyList()
        
        // 안내 메시지 변환
        val instructions = route.guide?.map { guide ->
            val pointIndex = guide.pointIndex ?: 0
            val location = if (pointIndex < path.size) {
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
        
        // 구간 정보 변환
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
        
        // 요약 정보 변환
        val summary = route.summary?.let { s ->
            val startLocation = s.start?.location?.let { loc ->
                if (loc.size >= 2) {
                    LatLng(loc[1], loc[0]) // [lng, lat] -> LatLng(lat, lng)
                } else {
                    LatLng(0.0, 0.0)
                }
            } ?: LatLng(0.0, 0.0)
            
            val endLocation = s.goal?.location?.let { loc ->
                if (loc.size >= 2) {
                    LatLng(loc[1], loc[0]) // [lng, lat] -> LatLng(lat, lng)
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
            1 -> "출발"
            2 -> "좌회전"
            3 -> "우회전"
            4 -> "직진"
            5 -> "유턴"
            6 -> "유턴"
            7 -> "직진"
            8 -> "직진"
            9 -> "직진"
            10 -> "직진"
            11 -> "직진"
            12 -> "직진"
            13 -> "직진"
            14 -> "직진"
            15 -> "직진"
            16 -> "직진"
            17 -> "직진"
            18 -> "직진"
            19 -> "직진"
            20 -> "직진"
            21 -> "직진"
            22 -> "직진"
            23 -> "직진"
            24 -> "직진"
            25 -> "직진"
            26 -> "직진"
            27 -> "직진"
            28 -> "직진"
            29 -> "직진"
            30 -> "직진"
            31 -> "직진"
            32 -> "직진"
            33 -> "직진"
            34 -> "직진"
            35 -> "직진"
            36 -> "직진"
            37 -> "직진"
            38 -> "직진"
            39 -> "직진"
            40 -> "직진"
            41 -> "직진"
            42 -> "직진"
            43 -> "직진"
            44 -> "직진"
            45 -> "직진"
            46 -> "직진"
            47 -> "직진"
            48 -> "직진"
            49 -> "직진"
            50 -> "직진"
            51 -> "직진"
            52 -> "직진"
            53 -> "직진"
            54 -> "직진"
            55 -> "직진"
            56 -> "직진"
            57 -> "직진"
            58 -> "직진"
            59 -> "직진"
            60 -> "직진"
            61 -> "직진"
            62 -> "직진"
            63 -> "직진"
            64 -> "직진"
            65 -> "직진"
            66 -> "직진"
            67 -> "직진"
            68 -> "직진"
            69 -> "직진"
            70 -> "직진"
            71 -> "직진"
            72 -> "직진"
            73 -> "직진"
            74 -> "직진"
            75 -> "직진"
            76 -> "직진"
            77 -> "직진"
            78 -> "직진"
            79 -> "직진"
            80 -> "직진"
            81 -> "직진"
            82 -> "직진"
            83 -> "직진"
            84 -> "직진"
            85 -> "직진"
            86 -> "직진"
            87 -> "직진"
            88 -> "목적지"
            89 -> "직진"
            90 -> "직진"
            91 -> "직진"
            92 -> "직진"
            93 -> "직진"
            94 -> "직진"
            95 -> "회전교차로"
            96 -> "직진"
            97 -> "직진"
            98 -> "직진"
            99 -> "직진"
            100 -> "직진"
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
