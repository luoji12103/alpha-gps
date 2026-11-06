package com.saschl.cameragps.service.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresPermission
import com.sasch.cameragps.sharednew.bluetooth.SonyBluetoothConstants
import com.sasch.cameragps.sharednew.bluetooth.transport.BleOperationStatus
import com.sasch.cameragps.sharednew.bluetooth.transport.BlePeripheralTransport
import com.sasch.cameragps.sharednew.bluetooth.transport.BleTransportEvent
import com.sasch.cameragps.sharednew.bluetooth.transport.BleInitiationResult
import com.saschl.cameragps.service.coordinator.ServiceConnectionControl
import com.saschl.cameragps.utils.PreferencesManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import timber.log.Timber
import java.util.Collections
import java.util.UUID

/**
 * Android implementation of [BlePeripheralTransport] over BluetoothGatt.
 *
 * Owns the GATT connection registry (bond check + `autoConnect = true`,
 * absorbed from the former `CameraConnectionManager`) and the GATT callback;
 * all sequencing/orchestration lives in the shared layer. GATT callbacks
 * arrive on binder threads and only feed the event channel — they never touch
 * shared state directly.
 *
 * A disconnect keeps the GATT handle open so `autoConnect` can resume the
 * connection when the camera reappears; [disconnectAll] is the only close path.
 */
class AndroidBleTransport(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val scope: CoroutineScope,
) : BlePeripheralTransport, ServiceConnectionControl {

    private companion object {
        // BluetoothStatusCodes.ERROR_DEVICE_NOT_CONNECTED is hidden from the public SDK.
        const val ERROR_DEVICE_NOT_CONNECTED = 4
    }

    private val eventChannel = Channel<BleTransportEvent>(Channel.UNLIMITED)
    override val events: Flow<BleTransportEvent> = eventChannel.receiveAsFlow()

    private class Connection(val gatt: BluetoothGatt) {
        @Volatile
        var isActive = false

        @Volatile
        var mtu = 23
        var priorityTimeout: Job? = null
        @Volatile
        var highPriority = false
    }

    /** Uppercased MAC → connection. Entries survive disconnects (autoConnect). */
    private val connections = Collections.synchronizedMap(mutableMapOf<String, Connection>())

    /** Last requested enable state per "MAC:UUID", to label SubscriptionChanged events. */
    private val pendingSubscribeEnable =
        Collections.synchronizedMap(mutableMapOf<String, Boolean>())

    override fun connect(mac: String): Boolean {
        val address = mac.uppercase()
        if (!PreferencesManager.isAppEnabled(context) || PreferencesManager.isNfcCameraPaused(context, address)) return false
        if (connections.containsKey(address)) {
            return true
        }

        try {
            val device: BluetoothDevice = bluetoothManager.adapter.getRemoteDevice(address)
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                //Timber.w("Device $address is not paired. Cannot connect.")
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                val gatt = device.connectGatt(
                    BluetoothGattConnectionSettings.Builder()
                        .setAutoConnectEnabled(true)
                        .setTransport(BluetoothDevice.TRANSPORT_LE)
                        .setAutomaticMtuEnabled(true)
                        .build(),
                    context.mainExecutor,
                    gattCallback
                )
                    ?: throw IllegalStateException("Failed to connect to device $address: GATT is null")
                connections[address] = Connection(gatt)

            } else {
                val gatt = device.connectGatt(context, true, gattCallback)
                    ?: throw IllegalStateException("Failed to connect to device $address: GATT is null")
                connections[address] = Connection(gatt)
            }

        } catch (e: SecurityException) {
            Timber.e("SecurityException while connecting to device $address: ${e.message}")
            return false
        }

        return true
    }

    /** `true` if a GATT handle exists for this device (connected or waiting for autoConnect). */
    fun hasConnection(mac: String): Boolean = connections.containsKey(mac.uppercase())

    override fun hasConnections(): Boolean = connections.isNotEmpty()

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    override fun disconnectAll() {
        val snapshot: List<Connection> = synchronized(connections) {
            connections.values.toList().also { connections.clear() }
        }
        snapshot.forEach { connection ->
            connection.priorityTimeout?.cancel()
            runCatching {
                try {
                    connection.gatt.disconnect()
                } finally {
                    connection.gatt.close()
                }
            }.onFailure { Timber.w(it) }
        }
        pendingSubscribeEnable.clear()
    }

    /**
     * Explicit stop/disable: close only this handle. Ordinary link loss retains
     * autoConnect, and CDM disappearance is handled separately by the service.
     */
    @SuppressLint("MissingPermission")
    override fun pauseDevice(mac: String) {
        val address = mac.uppercase()
        val connection = connections.remove(address) ?: return
        connection.priorityTimeout?.cancel()
        connection.isActive = false
        synchronized(pendingSubscribeEnable) {
            pendingSubscribeEnable.keys.removeAll { it.startsWith("$address:") }
        }
        runCatching {
            try { connection.gatt.disconnect() } finally { connection.gatt.close() }
        }.onFailure { Timber.w(it, "Could not close stopped camera connection") }
        eventChannel.trySend(BleTransportEvent.Disconnected(address, statusCode = null))
    }

    /** Number of devices with a live (resumed) connection. */
    override fun connectedCount(): Int = synchronized(connections) {
        connections.values.count { it.isActive }
    }

    /**
     * Drop back to the default connection parameters once the handshake is done —
     * the periodic location writes do not need the short interval, and holding it
     * costs battery on both the phone and the camera.
     */
    @SuppressLint("MissingPermission")
    fun relaxConnection(mac: String) {
        val connection = connections[mac.uppercase()] ?: return
        connection.priorityTimeout?.cancel()
        connection.priorityTimeout = null
        if (!connection.highPriority) return
        connection.highPriority = false
        if (!connection.isActive) return
        if (!connection.gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)) {
            Timber.w("Could not relax connection priority for %s", mac)
        }
    }

    // ---------------------------------------------------------------------------
    // BlePeripheralTransport
    // ---------------------------------------------------------------------------

    override fun isConnected(identifier: String): Boolean =
        connections[identifier.uppercase()]?.isActive == true

    override fun wifiMtuRequest(identifier: String): Int? {
        val connection = connections[identifier.uppercase()] ?: return null
        // SSID/password values include a three-byte header; avoid fragmented credential reads.
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN && connection.mtu < 67) 517 else null
    }

    @SuppressLint("MissingPermission")
    override fun initiateMtuRequest(identifier: String, mtu: Int): Boolean {
        val connection = connections[identifier.uppercase()] ?: return false
        return connection.isActive && connection.gatt.requestMtu(mtu)
    }

    override fun hasCharacteristic(identifier: String, characteristicUuid: String): Boolean {
        val connection = connections[identifier.uppercase()] ?: return false
        return findCharacteristic(connection.gatt, characteristicUuid) != null
    }

    override fun supportsWriteWithResponse(
        identifier: String,
        characteristicUuid: String
    ): Boolean {
        val connection = connections[identifier.uppercase()] ?: return false
        val characteristic = findCharacteristic(connection.gatt, characteristicUuid) ?: return false
        return characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
    }

    override fun initiateWrite(
        identifier: String,
        characteristicUuid: String,
        value: ByteArray,
    ): Boolean = initiateWriteResult(identifier, characteristicUuid, value) == BleInitiationResult.Started

    override fun initiateWriteResult(
        identifier: String,
        characteristicUuid: String,
        value: ByteArray,
    ): BleInitiationResult {
        if (PreferencesManager.isNfcCameraPaused(context, identifier) &&
            !(characteristicUuid.equals(SonyBluetoothConstants.CHARACTERISTIC_ENABLE_UNLOCK_GPS_COMMAND, true) &&
                value.contentEquals(SonyBluetoothConstants.LOCATION_LOCK_RELEASE_COMMAND))
        ) return BleInitiationResult.Rejected
        val connection = connections[identifier.uppercase()] ?: return BleInitiationResult.Rejected
        // The GATT handle survives disconnects for autoConnect; it is not proof of a live link.
        if (!connection.isActive) return BleInitiationResult.Rejected
        val characteristic = findCharacteristic(connection.gatt, characteristicUuid) ?: return BleInitiationResult.Rejected
        return writeCharacteristicCompat(connection.gatt, characteristic, value)
    }

    @SuppressLint("MissingPermission")
    override fun initiateRead(identifier: String, characteristicUuid: String): Boolean {
        val connection = connections[identifier.uppercase()] ?: return false
        if (!connection.isActive) return false
        val characteristic = findCharacteristic(connection.gatt, characteristicUuid) ?: return false
        // The read forces link re-encryption on a fresh reconnect: a long gap
        // until the read response is the stack encrypting, not the queue
        Timber.d("Issuing read of %s to the stack", characteristicUuid)
        return connection.gatt.readCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    override fun initiateSubscribe(
        identifier: String,
        characteristicUuid: String,
        enable: Boolean,
    ): Boolean = initiateSubscribeResult(identifier, characteristicUuid, enable) == BleInitiationResult.Started

    @SuppressLint("MissingPermission")
    override fun initiateSubscribeResult(
        identifier: String,
        characteristicUuid: String,
        enable: Boolean,
    ): BleInitiationResult {
        val address = identifier.uppercase()
        val connection = connections[address] ?: return BleInitiationResult.Rejected
        if (!connection.isActive) return BleInitiationResult.Rejected
        val characteristic = findCharacteristic(connection.gatt, characteristicUuid) ?: return BleInitiationResult.Rejected
        val descriptor =
            characteristic.getDescriptor(UUID.fromString(SonyBluetoothConstants.CCCD_UUID))
                ?: return BleInitiationResult.Rejected

        if (!connection.gatt.setCharacteristicNotification(characteristic, enable)) return BleInitiationResult.Rejected

        pendingSubscribeEnable["$address:${characteristicUuid.lowercase()}"] = enable
        val descriptorValue = if (enable) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        val result = writeDescriptorCompat(connection.gatt, descriptor, descriptorValue)
        if (result != BleInitiationResult.Started) {
            pendingSubscribeEnable.remove("$address:${characteristicUuid.lowercase()}")
        }
        return result
    }

    @SuppressLint("MissingPermission")
    override fun initiateDiscoverServices(identifier: String): Boolean {
        val connection = connections[identifier.uppercase()] ?: return false
        if (!connection.isActive) return false
        return connection.gatt.discoverServices()
    }

    // ---------------------------------------------------------------------------
    // GATT callback — feeds the event channel only, from binder threads
    // ---------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address.uppercase()
            val connection = connections[address]?.takeIf { it.gatt === gatt } ?: return

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Timber.i("Connected to device with status %d", status)
                connection.isActive = true
                // Discovery + handshake are dozens of sequential ATT round trips,
                // each costing one connection interval (~50ms at the default).
                // Request the short interval for setup; relaxConnection() drops
                // it again after the handshake to save battery.
                if (!gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)) {
                    Timber.w("Could not request high connection priority for %s", address)
                }
                connection.highPriority = true
                scope.launch {
                    if (connections[address] !== connection || !connection.isActive) return@launch
                    connection.priorityTimeout?.cancel()
                    connection.priorityTimeout = scope.launch {
                        delay(60_000)
                        if (connections[address] === connection) relaxConnection(address)
                    }
                }
                eventChannel.trySend(BleTransportEvent.Connected(address))
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                connection.isActive = false
                connection.highPriority = false
                connection.mtu = 23
                connection.priorityTimeout?.cancel()
                if (status == 19 || status == 8 || status == 0) {
                    Timber.i("Device disconnected in callback with status: $status")
                } else {
                    Timber.e("An error happened: $status")
                }
                eventChannel.trySend(BleTransportEvent.Disconnected(address, status))
                return
            }

            Timber.d("Ignoring connection callback with status=$status and state=$newState")
        }

        /**
         * Hidden in the SDK but dispatched by signature at runtime: reports the
         * actual negotiated connection parameters (interval unit = 1.25 ms).
         * Tells us whether the CONNECTION_PRIORITY_HIGH request stuck or the
         * camera overrode it with its own preferred parameters.
         */
        /* @Suppress("unused")
         fun onConnectionUpdated(
             gatt: BluetoothGatt,
             interval: Int,
             latency: Int,
             timeout: Int,
             status: Int,
         ) {
             Timber.i(
                 "Connection updated for %s: interval=%d (%.1f ms), latency=%d, timeout=%d, status=%d",
                 gatt.device.address, interval, interval * 1.25, latency, timeout, status,
             )
         }*/

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrentGatt(gatt)) return
            Timber.i("MTU changed for %s: mtu=%d status=%d", gatt.device.address, mtu, status)
            val address = gatt.device.address.uppercase()
            if (status == BluetoothGatt.GATT_SUCCESS) connections[address]?.mtu = mtu
            eventChannel.trySend(BleTransportEvent.MtuChanged(address, mtu, statusOf(status)))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrentGatt(gatt)) return
            Timber.i("Services discovered for ${gatt.device.address} with status $status")
            eventChannel.trySend(
                BleTransportEvent.ServicesDiscovered(
                    gatt.device.address.uppercase(),
                    status == BluetoothGatt.GATT_SUCCESS,
                )
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic?,
            status: Int,
        ) {
            if (!isCurrentGatt(gatt)) return
            val uuid = characteristic?.uuid?.toString() ?: return
            eventChannel.trySend(
                BleTransportEvent.CharacteristicWritten(
                    gatt.device.address.uppercase(),
                    uuid,
                    statusOf(status),
                )
            )
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                emitCharacteristicRead(
                    gatt,
                    characteristic,
                    characteristic.value ?: ByteArray(0),
                    status
                )
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            emitCharacteristicRead(gatt, characteristic, value, status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (!isCurrentGatt(gatt)) return
            Timber.d("Characteristic changed: %s, bytes=%d", characteristic.uuid, value.size)
            eventChannel.trySend(
                BleTransportEvent.CharacteristicChanged(
                    gatt.device.address.uppercase(),
                    characteristic.uuid.toString(),
                    value,
                )
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!isCurrentGatt(gatt)) return
            val address = gatt.device.address.uppercase()
            val characteristicUuid = descriptor.characteristic.uuid.toString()
            Timber.d("Descriptor write completed for ${gatt.device.address} with status $status")
            val enabled =
                pendingSubscribeEnable.remove("$address:${characteristicUuid.lowercase()}")
                    ?: true
            eventChannel.trySend(
                BleTransportEvent.SubscriptionChanged(
                    address,
                    characteristicUuid,
                    enabled,
                    statusOf(status),
                )
            )
        }

        private fun emitCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (!isCurrentGatt(gatt)) return
            Timber.d("Read response for %s with status %d", characteristic.uuid, status)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w(
                    "BLE read failed: characteristic=%s status=%d mtu=%d", characteristic.uuid,
                    status, connections[gatt.device.address.uppercase()]?.mtu ?: 23
                )
            }
            eventChannel.trySend(
                BleTransportEvent.CharacteristicRead(
                    gatt.device.address.uppercase(),
                    characteristic.uuid.toString(),
                    value,
                    statusOf(status),
                )
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers (write compat absorbed from the former BluetoothGattUtils)
    // ---------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun isCurrentGatt(gatt: BluetoothGatt): Boolean =
        connections[gatt.device.address.uppercase()]?.gatt === gatt

    private fun statusOf(status: Int): BleOperationStatus = when (status) {
        BluetoothGatt.GATT_SUCCESS -> BleOperationStatus.Success

        SonyBluetoothConstants.ATT_ERROR_INSUFFICIENT_AUTHENTICATION,
        SonyBluetoothConstants.ATT_ERROR_INSUFFICIENT_ENCRYPTION,
            -> BleOperationStatus.AuthError

        else -> BleOperationStatus.Failure
    }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        uuid: String,
    ): BluetoothGattCharacteristic? {
        val target = UUID.fromString(uuid)
        return gatt.services?.flatMap { it.characteristics }?.find { it.uuid == target }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
    ): BleInitiationResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(characteristic, value, writeType)
            // The link can drop after the active check but before Android accepts the write.
            if (result == ERROR_DEVICE_NOT_CONNECTED) {
                Timber.d("Characteristic write not initiated because device disconnected")
                return BleInitiationResult.Rejected
            }
            when (result) {
                BluetoothStatusCodes.SUCCESS -> BleInitiationResult.Started
                BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> BleInitiationResult.Busy
                else -> {
                    Timber.w("Writing characteristic not initiated. Result: $result")
                    BleInitiationResult.Rejected
                }
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            val result = gatt.writeCharacteristic(characteristic)
            if (!result) {
                Timber.e("Writing characteristic failed (legacy API)")
            }
            if (result) BleInitiationResult.Started else BleInitiationResult.Rejected
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): BleInitiationResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeDescriptor(descriptor, value)
            if (result == ERROR_DEVICE_NOT_CONNECTED) {
                Timber.d("Descriptor write not initiated because device disconnected")
                return BleInitiationResult.Rejected
            }
            when (result) {
                BluetoothStatusCodes.SUCCESS -> BleInitiationResult.Started
                BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> BleInitiationResult.Busy
                else -> {
                    Timber.w("Writing descriptor not initiated. Result: $result")
                    BleInitiationResult.Rejected
                }
            }
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            val result = gatt.writeDescriptor(descriptor)
            if (!result) {
                Timber.e("Writing descriptor failed (legacy API)")
            }
            if (result) BleInitiationResult.Started else BleInitiationResult.Rejected
        }
    }
}
