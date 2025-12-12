package com.dom.samplenavigation.navigation.location

import android.Manifest
import android.annotation.SuppressLint
import android.location.Location
import androidx.annotation.RequiresPermission
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber

/**
 * 위치 정보를 Flow로 제공하는 Repository
 * LocationCallback 대신 Kotlin Flow를 사용하여 데이터 흐름 제어
 */
class LocationRepository(
    private val fusedLocationClient: FusedLocationProviderClient
) {
    private var locationCallback: LocationCallback? = null

    /**
     * 위치 업데이트를 Flow로 제공
     */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun getLocationFlow(): Flow<Location> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(1f)
            .setWaitForAccurateLocation(true)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    trySend(location)
                }
            }
        }

        locationCallback = callback
        fusedLocationClient.requestLocationUpdates(request, callback, android.os.Looper.getMainLooper())

        // 마지막 알려진 위치 즉시 반영
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let { trySend(it) }
        }

        awaitClose {
            locationCallback?.let { callback ->
                fusedLocationClient.removeLocationUpdates(callback)
            }
            locationCallback = null
            Timber.d("Location updates stopped")
        }
    }

    /**
     * 마지막 알려진 위치 가져오기
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    suspend fun getLastLocation(): Location? {
        return try {
            val task = fusedLocationClient.lastLocation
            // Task가 완료될 때까지 대기 (간단한 구현)
            var result: Location? = null
            var completed = false
            task.addOnSuccessListener { location ->
                result = location
                completed = true
            }.addOnFailureListener {
                completed = true
            }
            
            // 완료될 때까지 대기 (최대 1초)
            var waitCount = 0
            while (!completed && waitCount < 100) {
                kotlinx.coroutines.delay(10)
                waitCount++
            }
            
            result
        } catch (e: Exception) {
            Timber.e("Failed to get last location: ${e.message}")
            null
        }
    }
}

