package com.sasch.cameragps.sharednew.bluetooth.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A platform-neutral location fix.
 */
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy in meters; `< 0` means invalid (iOS convention). */
    val horizontalAccuracyMeters: Double,
    /** Epoch millis of the fix. */
    val timestampMillis: Long,
)

/**
 * Platform location provider. Android wraps FusedLocationProviderClient with a
 * LocationManager fallback; iOS wraps CLLocationManager.
 */
interface LocationSource {

    /** Hot stream of fixes while started. Emissions may come from any thread/looper. */
    val locations: Flow<GeoLocation>

    /** Asynchronous registration failures; the owner must stop tracking and clear its active state. */
    val failures: Flow<Unit> get() = emptyFlow()

    /**
     * Start delivering fixes. Idempotent. Android also fires its one-shot
     * `getCurrentLocation`/last-known seed here.
     * Returns `false` if the request was rejected synchronously (e.g. no provider enabled).
     * Async providers report later registration failures through [failures].
     */
    fun start(): Boolean

    fun stop()

    /** iOS: `CLAccuracyAuthorizationFullAccuracy`. Android: always `true`. */
    fun hasPreciseAuthorization(): Boolean
}
