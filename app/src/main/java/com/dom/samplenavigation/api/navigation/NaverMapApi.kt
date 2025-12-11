package com.dom.samplenavigation.api.navigation

import com.dom.samplenavigation.api.navigation.model.NaverGeocodeResponseModel
import com.dom.samplenavigation.api.navigation.model.NaverReverseGeocodeResponseModel
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

// 네이버 지도 API (maps.apigw.ntruss.com)
interface NaverMapApi {
    @GET("map-reversegeocode/v2/gc") // Naver Map reverse geocode API endpoint
    suspend fun getReverseGeocode(
        @Query("coords") coords: String, // 좌표 (예: 127.585,34.9765)
        @Query("output") output: String = "json", // 출력 형식
        @Query("orders") orders: String = "roadaddr,addr" // 요청할 정보
    ): Response<NaverReverseGeocodeResponseModel>

    @GET("map-geocode/v2/geocode")
    suspend fun getAddressGeocode(
        @Query("query") query: String // 주소 (예: 분당구 불정로 6)
    ): Response<NaverGeocodeResponseModel>
}
