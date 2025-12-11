package com.dom.samplenavigation.api.navigation

import com.dom.samplenavigation.api.navigation.model.ResultPath
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

// 네이버 경로 찾기 API (naveropenapi.apigw.ntruss.com)
interface NaverDirectionApi {
    @GET("map-direction/v1/driving")
    suspend fun getPath(
        @Query("start") start: String, // 출발지 좌표 (예: 127.027610,37.497942)
        @Query("goal") goal: String, // 도착지 좌표 (예: 127.027610,37.497942)
        @Query("option") option: String = "trafast:traoptimal:traavoidtoll", // 경로 옵션
        @Query("cartype") cartype: Int = 1 // 차량 유형 (1: 소형, 2: 중형, 3: 대형)
    ): Response<ResultPath>
}