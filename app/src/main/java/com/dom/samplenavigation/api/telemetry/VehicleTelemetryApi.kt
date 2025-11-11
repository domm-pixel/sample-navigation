package com.dom.samplenavigation.api.telemetry

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface VehicleTelemetryApi {

    @GET("vec/vecNavBasicWriteProc.dit")
    suspend fun postLocation(
        @Query("vecBasicId") vecBasicId: Int,
        @Query("vecNavType") vecNavType: Int,
        @Query("vecLat") vecLat: Double,
        @Query("vecLon") vecLon: Double,
        @Query("vecAcc") vecAcc: Double,
        @Query("vecPosTrsTime") vecPosTrsTime: String
    ): Response<Unit>
}

