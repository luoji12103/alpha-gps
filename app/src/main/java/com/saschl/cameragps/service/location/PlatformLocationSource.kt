package com.saschl.cameragps.service.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.sasch.cameragps.sharednew.bluetooth.location.GeoLocation
import com.sasch.cameragps.sharednew.bluetooth.location.LocationSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber

/** Platform-only minute updates. Prefer the low-power provider when fused is unavailable. */
class PlatformLocationSource(private val context: Context) : LocationSource {
    private val locationChannel = Channel<GeoLocation>(Channel.CONFLATED)
    override val locations = locationChannel.receiveAsFlow()
    private var started = false
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (started) return true
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        locationManager = manager
        while (locationChannel.tryReceive().isSuccess) {}
        return try {
            val provider = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                hasPreciseAuthorization() && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> return false
            }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (started && locationListener === this) locationChannel.trySend(location.toGeoLocation())
                }
            }
            locationListener = listener
            started = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val request = LocationRequest.Builder(AndroidLocationPowerPolicy.UPDATE_INTERVAL_MS)
                    .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                    .setMinUpdateIntervalMillis(AndroidLocationPowerPolicy.UPDATE_INTERVAL_MS)
                    .build()
                manager.requestLocationUpdates(provider, request, context.mainExecutor, listener)
            } else {
                @Suppress("DEPRECATION")
                manager.requestLocationUpdates(provider, AndroidLocationPowerPolicy.UPDATE_INTERVAL_MS,
                    0f, listener, Looper.getMainLooper())
            }
            // A cached fix is useful only if it is recent; choose by elapsed time, not GPS-first.
            val cached = listOf(provider, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .distinct()
                .filter { it != LocationManager.GPS_PROVIDER || hasPreciseAuthorization() }
                .mapNotNull { name -> runCatching { manager.getLastKnownLocation(name) }.getOrNull() }
                .filterNot { isLocationTooOld(it) }
                .maxByOrNull { it.elapsedRealtimeNanos }
            cached?.let { locationChannel.trySend(it.toGeoLocation()) }
            Timber.i("Started minute location updates with provider %s", provider)
            true
        } catch (e: Exception) {
            Timber.w(e, "Failed to register platform location updates")
            stop()
            false
        }
    }

    override fun stop() {
        started = false
        val listener = locationListener
        locationListener = null
        if (listener != null) runCatching { locationManager?.removeUpdates(listener) }
            .onFailure { Timber.w(it, "Could not remove platform location listener") }
        while (locationChannel.tryReceive().isSuccess) {}
    }

    override fun hasPreciseAuthorization(): Boolean = hasPreciseLocationPermission(context)
}

internal fun hasPreciseLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

internal fun hasAnyLocationProviderEnabled(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(LocationManager::class.java) ?: return false
    manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER))
}.getOrDefault(false)

internal fun isLocationTooOld(location: Location): Boolean {
    val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
    return ageNanos < 0 || ageNanos >= AndroidLocationPowerPolicy.MAX_INITIAL_FIX_AGE_MS * 1_000_000
}

/** Normalize the fix age from Android's monotonic clock, including on API 26–32. */
internal fun Location.toGeoLocation(): GeoLocation {
    val ageNanos = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos
    val timestamp = if (ageNanos < 0 || elapsedRealtimeNanos <= 0) Long.MAX_VALUE
        else System.currentTimeMillis() - ageNanos / 1_000_000
    return GeoLocation(latitude, longitude, if (hasAccuracy()) accuracy.toDouble() else -1.0,
        timestamp)
}
