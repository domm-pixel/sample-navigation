package com.dom.samplenavigation.api.telemetry.repo

import com.dom.samplenavigation.api.telemetry.VehicleTelemetryApi
import com.dom.samplenavigation.api.telemetry.model.VehicleLocationPayload
import javax.inject.Inject

class TelemetryRepository @Inject constructor(
    private val vehicleTelemetryApi: VehicleTelemetryApi
) {

    suspend fun sendLocation(vehicleId: Int, payload: VehicleLocationPayload): Result<Unit> {
        return try {
            val response = vehicleTelemetryApi.postLocation(
                vecBasicId = vehicleId,
                vecNavType = payload.vecNavType,
                vecLat = payload.vecLat,
                vecLon = payload.vecLon,
                vecAcc = payload.vecAcc,
                vecPosTrsTime = payload.regDate
            )
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Telemetry send failed: ${response.code()} ${response.message()}"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}

