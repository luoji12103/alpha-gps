package com.saschl.cameragps.service.location

import com.sasch.cameragps.sharednew.bluetooth.location.LocationTransmissionPolicy
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import android.os.SystemClock
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit

/** Minute acquisition and independent Sony BLE refresh, without activity sensors or extra permissions. */
internal object AndroidLocationPowerPolicy {
    const val UPDATE_INTERVAL_MS = 60_000L
    const val MAX_FIX_AGE_MS = 90_000L
    const val MAX_INITIAL_FIX_AGE_MS = 30_000L

    // System.nanoTime() can stop during Android deep sleep. Fix expiry must
    // count suspended time too, including while cached coordinates are resent.
    val timeSource = object : AbstractLongTimeSource(DurationUnit.MILLISECONDS) {
        override fun read(): Long = SystemClock.elapsedRealtime()
    }

    val transmission = LocationTransmissionPolicy(
        // Keep the upstream camera refresh cadence; this does not request a new fix.
        resendIntervalMs = SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS,
        maxFixAgeMs = MAX_FIX_AGE_MS,
        preferNewestFix = true,
    )
}
