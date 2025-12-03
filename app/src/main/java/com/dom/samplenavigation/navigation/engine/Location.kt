package com.dom.samplenavigation.navigation.engine

import com.naver.maps.geometry.LatLng

/**
 * A generic model that represents a user location.
 * MapLibre 스타일의 Location 모델
 */
data class Location(
    /**
     * Latitude, in degrees.
     */
    val latitude: Double,

    /**
     * Longitude, in degrees.
     */
    val longitude: Double,

    /**
     * Horizontal accuracy of the latitude and longitude, in meters.
     * If not available, it will be `null`.
     */
    val accuracyMeters: Float? = null,

    /**
     * Altitude of this location, in meters above the WGS84 reference ellipsoid.
     */
    val altitude: Double? = null,

    /**
     * Vertical accuracy of the [altitude], in meters.
     * If not available, it will be `null`.
     */
    val altitudeAccuracyMeters: Float? = null,

    /**
     * Speed of user in meters per second.
     * If not available, it will be `null`.
     */
    val speedMetersPerSeconds: Float? = null,

    /**
     * Bearing of the user, in degrees.
     * If not available, it will be `null`.
     */
    val bearing: Float? = null,

    /**
     * Date time of this location fix. This value is in milliseconds
     * since epoch (1970-01-01T00:00:00Z) in UTC.
     */
    val timeMilliseconds: Long? = null,

    /**
     * Provider that generated this location.
     */
    val provider: String? = null,
) {

    /**
     * Returns a [LatLng] representation of this location.
     */
    val latLng: LatLng
        get() = LatLng(latitude, longitude)
}

