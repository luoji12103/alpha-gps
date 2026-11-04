package com.saschl.cameragps.service.coordinator

import android.annotation.SuppressLint
import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import timber.log.Timber

class ServiceShutdownCoordinator(
    private val deviceDao: CameraDeviceDAO,
    private val transport: ServiceConnectionControl,
    private val onShutdownRequested: (Int?) -> Unit,
) {
    @SuppressLint("MissingPermission")
    suspend fun handleNoAddress(startId: Int) {
        if (deviceDao.getAlwaysOnEnabledDeviceCount() == 0) {
            Timber.i("No always-on devices found, shutting down service")
            onShutdownRequested(startId)
            return
        }

        runCatching {
            deviceDao.getAllCameraDevices()
                .filter { it.deviceEnabled && it.alwaysOnEnabled }
                .forEach { device ->
                    val accepted = runCatching { transport.connect(device.mac) }.getOrDefault(false)
                    if (!accepted) handleGattConnectionFailure(startId, device)
                }
        }
        // A rejected request has no autoConnect handle and cannot recover by waiting.
        if (!transport.hasConnections()) onShutdownRequested(startId)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleGattConnectionFailure(startId: Int, cameraDevice: CameraDevice) {
        Timber.e("Failed to connect to device ${cameraDevice.deviceName}, bluetooth is likely disabled")
    }

    @SuppressLint("MissingPermission")
    suspend fun handleShutdownRequest(address: String, startId: Int) {
        Timber.i("Shutdown requested for device $address")

        if (address.equals("all", ignoreCase = true)) {
            handleShutdownAllDevices(startId)
            return
        }

        // Always-on devices retain their connection until the setting is disabled.
        if (!deviceDao.isDeviceEnabled(address) || !deviceDao.isDeviceAlwaysOnEnabled(address)) {
            transport.pauseDevice(address)
        }
        if (!transport.hasConnections() && deviceDao.getAlwaysOnEnabledDeviceCount() == 0) {
            Timber.d("No connected or always on cameras remaining, shutting down service")
            onShutdownRequested(startId)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleShutdownAllDevices(startId: Int) {
        if (deviceDao.getAlwaysOnEnabledDeviceCount() == 0) {
            Timber.i("No always-on devices found, disconnecting all cameras and shutting down service")
            transport.disconnectAll()
            onShutdownRequested(startId)
        } else {
            Timber.i("At least one always-on device found, not shutting down service")
        }
    }
}
