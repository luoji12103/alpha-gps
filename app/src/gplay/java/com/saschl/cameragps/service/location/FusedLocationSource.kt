package com.saschl.cameragps.service.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.sasch.cameragps.sharednew.bluetooth.location.GeoLocation
import com.sasch.cameragps.sharednew.bluetooth.location.LocationSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber

/** Every asynchronous completion belongs to one start/stop generation. Main-thread confined. */
class FusedLocationSource(private val context: Context) : LocationSource {
    private val locationChannel = Channel<GeoLocation>(Channel.CONFLATED)
    override val locations = locationChannel.receiveAsFlow()
    private val failureChannel = Channel<Unit>(Channel.CONFLATED)
    override val failures = failureChannel.receiveAsFlow()
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private var generation = 0L
    private var started = false
    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (started) return true
        if (!hasAnyLocationProviderEnabled(context) ||
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS
        ) return false
        drainChannels()
        val requestGeneration = ++generation
        started = true
        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!isCurrent(requestGeneration)) return
                result.lastLocation?.let { locationChannel.trySend(it.toGeoLocation()) }
            }
        }
        callback = listener
        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            AndroidLocationPowerPolicy.UPDATE_INTERVAL_MS,
        )
            .setMinUpdateIntervalMillis(AndroidLocationPowerPolicy.UPDATE_INTERVAL_MS)
            .setMaxUpdateAgeMillis(AndroidLocationPowerPolicy.MAX_INITIAL_FIX_AGE_MS)
            .setWaitForAccurateLocation(false)
            .build()
        return try {
            val settings = LocationSettingsRequest.Builder().addLocationRequest(request).build()
            LocationServices.getSettingsClient(context).checkLocationSettings(settings)
                .addOnSuccessListener {
                    if (isCurrent(requestGeneration)) {
                        try {
                            client.requestLocationUpdates(request, listener, Looper.getMainLooper())
                                .addOnSuccessListener {
                                    // stop() may have run before this registration finished.
                                    if (!isCurrent(requestGeneration)) client.removeLocationUpdates(listener)
                                }
                                .addOnFailureListener { fail(requestGeneration, it) }
                        } catch (e: Exception) {
                            fail(requestGeneration, e)
                        }
                    }
                }
                .addOnFailureListener { fail(requestGeneration, it) }
            // The first update can use a recent cached fix. No duplicate high-accuracy seed request.
            true
        } catch (e: Exception) {
            Timber.w(e, "Location registration could not start")
            stop()
            false
        }
    }

    override fun stop() {
        started = false
        generation++
        callback?.let { listener ->
            runCatching { client.removeLocationUpdates(listener) }
                .onFailure { Timber.w(it, "Could not remove fused location listener") }
        }
        callback = null
        drainChannels()
    }

    override fun hasPreciseAuthorization(): Boolean = hasPreciseLocationPermission(context)

    private fun isCurrent(requestGeneration: Long) = started && generation == requestGeneration

    private fun fail(requestGeneration: Long, error: Exception) {
        if (!isCurrent(requestGeneration)) return
        Timber.w(error, "Location registration failed")
        stop()
        failureChannel.trySend(Unit)
    }

    private fun drainChannels() {
        while (locationChannel.tryReceive().isSuccess) {}
        while (failureChannel.tryReceive().isSuccess) {}
    }
}
