package com.dom.samplenavigation.api.telemetry.model

data class VehicleLocationPayload(
    val vecNavType: Int,
    val vecLat: Double,
    val vecLon: Double,
    val vecAcc: Double,
    val regDate: String
)

