package com.sasch.cameragps.sharednew.bluetooth.location

import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants

/** Acquisition is configured by the platform; these limits apply to camera writes. */
data class LocationTransmissionPolicy(
    val resendIntervalMs: Long = SonyBluetoothConstants.LOCATION_UPDATE_INTERVAL_MS,
    val maxFixAgeMs: Long = 30_000L,
    val minSendIntervalMs: Long = 0L,
    val sendOnUpdate: Boolean = false,
    val preferNewestFix: Boolean = false,
    /** Disabled by default; Android enables bounded registration-failure recovery. */
    val sourceRetryInitialDelayMs: Long = 0L,
    val sourceRetryMaxDelayMs: Long = 300_000L,
) {
    init {
        require(resendIntervalMs > 0 && maxFixAgeMs > 0 && minSendIntervalMs >= 0)
        require(sourceRetryInitialDelayMs >= 0 && sourceRetryMaxDelayMs >= sourceRetryInitialDelayMs)
    }
}
