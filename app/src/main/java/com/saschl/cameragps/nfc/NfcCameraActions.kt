package com.saschl.cameragps.nfc

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.saschl.cameragps.AppServices
import com.saschl.cameragps.R
import com.saschl.cameragps.service.LocationSenderService
import com.saschl.cameragps.service.location.hasAnyLocationProviderEnabled
import com.saschl.cameragps.utils.PreferencesManager
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

internal class NfcCameraActions(private val context: Context) {
    private val services get() = AppServices.from(context)

    fun hasPermissions(): Boolean = LocationSenderService.hasLocationPermission(context) &&
        (Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

    @SuppressLint("MissingPermission")
    suspend fun readinessError(address: String): Int? {
        if (!hasPermissions()) return R.string.nfc_permissions_needed
        val adapter = services.bluetoothManager.adapter ?: return R.string.nfc_bluetooth_needed
        if (!adapter.isEnabled) return R.string.nfc_bluetooth_needed
        if (services.deviceDao.findDeviceEnabled(address) == null ||
            adapter.getRemoteDevice(address).bondState != BluetoothDevice.BOND_BONDED
        ) return R.string.nfc_pair_camera_first
        if (!hasAnyLocationProviderEnabled(context)) return R.string.nfc_location_needed
        return null
    }

    suspend fun connect(address: String) {
        check(readinessError(address) == null)
        check(PreferencesManager.setNfcCameraPaused(context, address, false))
        services.deviceDao.setDeviceEnabled(address, true)
        PreferencesManager.setAppEnabled(context, true)
        ContextCompat.startForegroundService(context, Intent(context, LocationSenderService::class.java).apply {
            putExtra("address", address)
        })
    }

    /** ACK means this phone has fenced itself and closed GATT, not that Sony accepted the next phone. */
    suspend fun pauseForHandoff(address: String): Boolean {
        val persisted = PreferencesManager.setNfcCameraPaused(context, address, true)
        try {
            // The transport gate is already active, including against stale always-on snapshots.
            services.orchestrator.clearDevice(address)
            services.deviceDao.setDeviceEnabled(address, false)
            withTimeoutOrNull(2_000) { services.wifiRemote.closeAndJoin(address) }
            withTimeoutOrNull(2_000) { services.orchestrator.releaseLocationForHandoff(address) }
        } finally {
            services.orchestrator.clearDevice(address)
            services.transport.pauseDevice(address)
            if (LocationSenderService.isRunning) {
                // Use the service command mutex/startId policy; another camera may be connecting.
                runCatching {
                    context.startService(Intent(context, LocationSenderService::class.java).apply {
                        action = SonyBluetoothConstants.ACTION_REQUEST_SHUTDOWN
                        putExtra("address", address)
                    })
                }.onFailure { Timber.w(it, "Could not request idle service cleanup after NFC pause") }
            }
        }
        return persisted && !services.transport.hasConnection(address)
    }
}
