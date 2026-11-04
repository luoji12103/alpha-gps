package com.sasch.cameragps.sharednew.bluetooth.location

import com.diamondedge.logging.FixedLogLevel
import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.PlatformLogger
import com.sasch.cameragps.sharednew.bluetooth.coordinator.BleGattPort
import com.sasch.cameragps.sharednew.bluetooth.session.CameraAutoCorrectionSetting
import com.sasch.cameragps.sharednew.bluetooth.session.CameraSettingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LocationFreshnessTest {
    @BeforeTest fun silenceLogs() { KmLogging.setLoggers() }
    @AfterTest fun restoreLogs() { KmLogging.setLoggers(PlatformLogger(FixedLogLevel(true))) }

    @Test fun expiredFixStopsAllCamerasAndFreshFixResumes() = runTest {
        val f = Fixture(this)
        f.ready += "B"
        f.start()
        f.emit()
        assertEquals(listOf("A", "B"), f.port.writes)
        advanceTimeBy(29_999)
        runCurrent()
        assertTrue(f.manager.isTransmitting.value)
        advanceTimeBy(1)
        runCurrent()
        val count = f.port.writes.size
        assertFalse(f.manager.isTransmitting.value)
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(count, f.port.writes.size)
        f.emit()
        assertTrue(f.manager.isTransmitting.value)
        assertEquals(count + 2, f.port.writes.size)
    }

    @Test fun staleFutureAndInvalidCallbacksAreRejected() = runTest {
        val f = Fixture(this)
        f.start()
        for (age in listOf(30_000L, 30_001L, 60_000L, -1L, -60_000L)) f.emit(age)
        f.source.locations.emit(f.fix().copy(latitude = Double.NaN))
        f.source.locations.emit(f.fix().copy(longitude = 181.0))
        f.source.locations.emit(f.fix().copy(horizontalAccuracyMeters = -1.0))
        runCurrent()
        assertTrue(f.port.writes.isEmpty())
        assertFalse(f.manager.isTransmitting.value)
        f.emit(29_999)
        assertEquals(1, f.port.writes.size)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(f.manager.isTransmitting.value)
    }

    @Test fun reconnectNeverReusesPreviousSessionFix() = runTest {
        val f = Fixture(this)
        f.start()
        f.emit()
        f.ready.clear()
        f.manager.updateTracking()
        assertEquals(1, f.source.stops)
        f.ready += "A"
        f.start()
        assertEquals(1, f.port.writes.size)
        assertFalse(f.manager.isTransmitting.value)
        f.emit()
        assertEquals(2, f.port.writes.size)
    }

    @Test fun newCameraDoesNotReceiveExpiredSessionCache() = runTest {
        val f = Fixture(this)
        f.start()
        f.emit()
        advanceTimeBy(31_000)
        runCurrent()
        val count = f.port.writes.size
        f.ready += "B"
        f.manager.onDeviceReady("b")
        assertEquals(count, f.port.writes.size)
        assertFalse(f.manager.isTransmitting.value)
    }

    @Test fun backwardClockChangeCannotExtendFixLifetime() = runTest {
        val f = Fixture(this)
        f.start()
        f.emit()
        advanceTimeBy(10_000)
        f.wallOffset = -10_000
        advanceTimeBy(20_000)
        runCurrent()
        assertFalse(f.manager.isTransmitting.value)
        val count = f.port.writes.size
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(count, f.port.writes.size)
    }

    @Test fun forwardClockChangeRejectsCachedFixOnNextSend() = runTest {
        val f = Fixture(this)
        f.start()
        f.emit()
        f.wallOffset = 60_000
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, f.port.writes.size)
        assertFalse(f.manager.isTransmitting.value)
    }

    @Test fun asynchronousRegistrationFailureStopsTrackingAndCanRestart() = runTest {
        val f = Fixture(this)
        f.start()
        f.emit()
        f.source.failures.emit(Unit)
        runCurrent()
        assertFalse(f.manager.isActive.value)
        assertFalse(f.manager.isTransmitting.value)
        assertEquals(1, f.source.stops)
        f.start()
        f.emit()
        assertTrue(f.manager.isTransmitting.value)
        assertEquals(2, f.source.starts)
    }

    internal class Fixture(
        private val test: TestScope,
        policy: LocationTransmissionPolicy = LocationTransmissionPolicy(),
    ) {
        val source = Source()
        val port = Port()
        val ready = mutableSetOf("A")
        var wallOffset = 0L
        private val clock = object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(1_800_000_000_000 + test.testScheduler.currentTime + wallOffset)
        }
        val manager = LocationTransmissionManager(source, { ready }, { null }, port,
            test.backgroundScope, policy = policy, clock = clock, timeSource = test.testScheduler.timeSource)

        fun start() { manager.onDeviceReady("A"); test.runCurrent() }
        fun fix(age: Long = 0) = GeoLocation(52.52, 13.405, 5.0, clock.now().toEpochMilliseconds() - age)
        suspend fun emit(age: Long = 0) { source.locations.emit(fix(age)); test.runCurrent() }
    }

    internal class Source : LocationSource {
        override val locations = MutableSharedFlow<GeoLocation>()
        override val failures = MutableSharedFlow<Unit>()
        var starts = 0
        var stops = 0
        override fun start(): Boolean { starts++; return true }
        override fun stop() { stops++ }
        override fun hasPreciseAuthorization() = true
    }

    internal class Port : BleGattPort {
        val writes = mutableListOf<String>()
        override fun writeCharacteristic(identifier: String, characteristicUuid: String, value: ByteArray): Boolean {
            writes += identifier
            return true
        }
        override fun subscribeToNotifications(identifier: String, characteristicUuid: String) = true
        override fun isConnected(identifier: String) = true
        override fun hasRemoteControlCharacteristic(identifier: String) = false
        override fun isRemoteFeatureActive(identifier: String) = false
        override fun setRemoteFeatureActive(identifier: String, active: Boolean) = Unit
        override fun setShutterSequenceActive(identifier: String, active: Boolean) = Unit
        override fun setLocationDisabledByCamera(identifier: String, disabled: Boolean) = Unit
        override fun isLocationDisabledByCamera(identifier: String) = false
        override fun setAutoCorrectionState(identifier: String, setting: CameraAutoCorrectionSetting, state: CameraSettingState) = Unit
        override fun readCharacteristic(identifier: String, characteristicUuid: String) = false
        override fun hasCharacteristic(identifier: String, characteristicUuid: String) = true
    }
}
