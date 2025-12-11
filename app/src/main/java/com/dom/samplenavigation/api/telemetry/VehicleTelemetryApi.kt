package com.dom.samplenavigation.api.telemetry

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface VehicleTelemetryApi {

    @GET("nav/navBasicWriteProc.dit")
    suspend fun postLocation(
        @Query("navBasicId") vecBasicId: Int,
        @Query("navType") vecNavType: Int,
        @Query("navLat") vecLat: Double,
        @Query("navLon") vecLon: Double,
        @Query("navAcc") vecAcc: Double,
        @Query("navTrsTime") vecPosTrsTime: String
    ): Response<Unit>
}

