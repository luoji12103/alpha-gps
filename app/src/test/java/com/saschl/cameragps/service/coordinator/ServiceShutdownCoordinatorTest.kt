package com.saschl.cameragps.service.coordinator

import com.sasch.cameragps.sharednew.database.devices.CameraDevice
import com.sasch.cameragps.sharednew.database.devices.CameraDeviceDAO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ServiceShutdownCoordinatorTest {
    @Test fun normalizedAllShutdownClosesEveryNonAlwaysOnConnection() = runBlocking {
        val f = Fixture(listOf(CameraDevice("A"), CameraDevice("B")))
        f.transport.handles.addAll(listOf("A", "B"))
        // ServiceCommandRouter normalizes every address, including this sentinel.
        f.coordinator.handleShutdownRequest("ALL", 1)
        assertFalse(f.transport.hasConnections())
        assertEquals(1, f.shutdowns)
    }

    @Test fun disabledAlwaysOnCameraReleasesItsHandleWithoutStoppingOtherCamera() = runBlocking {
        val f = Fixture(listOf(CameraDevice("A", deviceEnabled = false, alwaysOnEnabled = true), CameraDevice("B")))
        f.transport.handles.addAll(listOf("A", "B"))
        f.coordinator.handleShutdownRequest("A", 1)
        assertEquals(setOf("B"), f.transport.handles)
        assertEquals(0, f.shutdowns)
    }

    @Test fun disablingLastCameraStopsServiceEvenIfItsAlwaysOnFlagWasSet() = runBlocking {
        val f = Fixture(listOf(CameraDevice("A", deviceEnabled = false, alwaysOnEnabled = true)))
        f.transport.handles += "A"
        f.coordinator.handleShutdownRequest("A", 1)
        assertFalse(f.transport.hasConnections())
        assertEquals(1, f.shutdowns)
    }

    @Test fun stoppingOneCameraRetainsAnotherPendingAutoConnectHandle() = runBlocking {
        val f = Fixture(listOf(CameraDevice("A", deviceEnabled = false), CameraDevice("B")))
        f.transport.handles.addAll(listOf("A", "B"))
        f.transport.pending += "B"
        f.coordinator.handleShutdownRequest("A", 1)
        assertEquals(setOf("B"), f.transport.handles)
        assertEquals(0, f.transport.connectedCount())
        assertEquals(0, f.shutdowns)
    }

    @Test fun rejectedAlwaysOnConnectionDoesNotKeepForegroundServiceAlive() = runBlocking {
        val f = Fixture(listOf(CameraDevice("A", alwaysOnEnabled = true)))
        f.transport.accept = false
        f.coordinator.handleNoAddress(1)
        assertEquals(listOf("A"), f.transport.attempts)
        assertEquals(1, f.shutdowns)
    }

    @Test fun disabledDevicesAreNotIncludedInAlwaysOnReconnect() = runBlocking {
        val f = Fixture(listOf(CameraDevice("A", alwaysOnEnabled = true),
            CameraDevice("B", deviceEnabled = false, alwaysOnEnabled = true)))
        f.coordinator.handleNoAddress(1)
        assertEquals(listOf("A"), f.transport.attempts)
        assertEquals(0, f.shutdowns)
    }

    private class Fixture(devices: List<CameraDevice>) {
        val transport = Transport()
        var shutdowns = 0
        val coordinator = ServiceShutdownCoordinator(Dao(devices), transport) { shutdowns++ }
    }

    private class Transport : ServiceConnectionControl {
        val handles = mutableSetOf<String>()
        val attempts = mutableListOf<String>()
        val pending = mutableSetOf<String>()
        var accept = true
        override fun connect(mac: String): Boolean { attempts += mac; if (accept) handles += mac; return accept }
        override fun hasConnections() = handles.isNotEmpty()
        override fun pauseDevice(mac: String) { handles.remove(mac) }
        override fun connectedCount() = handles.count { it !in pending }
        override fun disconnectAll() = handles.clear()
    }

    private class Dao(val devices: List<CameraDevice>) : CameraDeviceDAO {
        override suspend fun getAllCameraDevices() = devices
        override fun observeAllDevices() = MutableStateFlow(devices)
        override suspend fun isDeviceEnabled(address: String) = devices.find { it.mac == address }?.deviceEnabled == true
        override suspend fun isDeviceAlwaysOnEnabled(address: String) = devices.find { it.mac == address }?.alwaysOnEnabled == true
        override suspend fun getAlwaysOnEnabledDeviceCount() = devices.count { it.deviceEnabled && it.alwaysOnEnabled }
        override suspend fun insertDevice(device: CameraDevice) = Unit
        override suspend fun setDeviceName(deviceId: String, name: String, isCustom: Boolean) = Unit
        override suspend fun getDeviceName(address: String): String? = null
        override suspend fun deleteDevice(device: CameraDevice) = Unit
        override suspend fun setDeviceEnabled(deviceId: String, enabled: Boolean) = Unit
        override suspend fun setAlwaysOnEnabled(deviceId: String, enabled: Boolean) = Unit
        override suspend fun findDeviceEnabled(address: String): Boolean? = null
        override suspend fun setRemoteControlEnabled(deviceId: String, enabled: Boolean) = 0
        override suspend fun isRemoteControlEnabled(address: String) = false
    }
}
