package com.saschl.cameragps.service.location

import com.sasch.cameragps.sharednew.bluetooth.location.LocationTransmissionPolicy
import android.os.SystemClock
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit

/** A fixed minute mode, without activity sensors, extra permissions or another polling loop. */
internal object AndroidLocationPowerPolicy {
    const val UPDATE_INTERVAL_MS = 60_000L
    const val MAX_FIX_AGE_MS = 90_000L
    const val MAX_INITIAL_FIX_AGE_MS = 30_000L

    // System.nanoTime() can stop during Android deep sleep. A minute throttle
    // must count suspended time too, or screen-off callbacks can be suppressed.
    val timeSource = object : AbstractLongTimeSource(DurationUnit.MILLISECONDS) {
        override fun read(): Long = SystemClock.elapsedRealtime()
    }

    val transmission = LocationTransmissionPolicy(
        resendIntervalMs = 75_000L,
        maxFixAgeMs = MAX_FIX_AGE_MS,
        minSendIntervalMs = UPDATE_INTERVAL_MS,
        sendOnUpdate = true,
        preferNewestFix = true,
    )
}
