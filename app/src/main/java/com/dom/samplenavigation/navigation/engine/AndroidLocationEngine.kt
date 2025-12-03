package com.dom.samplenavigation.navigation.engine

import android.location.Location as AndroidLocation
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android FusedLocationProviderClient를 MapLibre 스타일 LocationEngine으로 래핑
 */
class AndroidLocationEngine(
    private val fusedClient: FusedLocationProviderClient
) : LocationEngine {

    override fun listenToLocation(request: LocationEngine.Request): Flow<Location> = callbackFlow {
        val locationRequest = LocationRequest.Builder(
            when (request.accuracy) {
                LocationEngine.Request.Accuracy.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
                LocationEngine.Request.Accuracy.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
                LocationEngine.Request.Accuracy.LOW -> Priority.PRIORITY_LOW_POWER
                LocationEngine.Request.Accuracy.PASSIVE -> Priority.PRIORITY_PASSIVE
            },
            request.minIntervalMilliseconds
        )
            .setMinUpdateIntervalMillis(request.minIntervalMilliseconds)
            .setMaxUpdateDelayMillis(request.maxUpdateDelayMilliseconds)
            .setMinUpdateDistanceMeters(request.minUpdateDistanceMeters)
            .build()

        val callback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                result.lastLocation?.let { androidLoc ->
                    trySend(androidLoc.toNavigationLocation())
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(
                locationRequest,
                callback,
                android.os.Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }

        awaitClose {
            fusedClient.removeLocationUpdates(callback)
        }
    }

    override suspend fun getLastLocation(): Location? {
        return suspendCancellableCoroutine { continuation ->
            fusedClient.lastLocation
                .addOnSuccessListener { androidLoc ->
                    continuation.resume(androidLoc?.toNavigationLocation())
                }
                .addOnFailureListener { e ->
                    continuation.resumeWithException(e)
                }
        }
    }

    private fun AndroidLocation.toNavigationLocation(): Location {
        return Location(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            altitude = if (hasAltitude()) altitude else null,
            altitudeAccuracyMeters = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
            speedMetersPerSeconds = if (hasSpeed()) speed else null,
            bearing = if (hasBearing()) bearing else null,
            timeMilliseconds = time,
            provider = provider
        )
    }
}

