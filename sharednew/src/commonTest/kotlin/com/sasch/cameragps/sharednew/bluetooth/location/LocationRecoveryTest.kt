package com.sasch.cameragps.sharednew.bluetooth.location

import com.diamondedge.logging.FixedLogLevel
import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.PlatformLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocationRecoveryTest {
    private val policy = LocationTransmissionPolicy(
        maxFixAgeMs = 90_000,
        preferNewestFix = true,
        sourceRetryInitialDelayMs = 30_000,
        sourceRetryMaxDelayMs = 300_000,
    )

    @BeforeTest fun silenceLogs() { KmLogging.setLoggers() }
    @AfterTest fun restoreLogs() { KmLogging.setLoggers(PlatformLogger(FixedLogLevel(true))) }

    @Test fun rejectedRegistrationBacksOffToFiveMinutesWithoutSendingCoordinates() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.source.canStart = false
        f.start()
        assertEquals(1, f.source.starts)
        listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L).forEachIndexed { index, gap ->
            advanceTimeBy(gap - 1)
            runCurrent()
            assertEquals(index + 1, f.source.starts)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(index + 2, f.source.starts)
        }
        assertFalse(f.manager.isActive.value)
        assertFalse(f.manager.isTransmitting.value)
        assertTrue(f.port.writes.isEmpty())
    }

    @Test fun asynchronousFailureRecoversWithoutReconnectingTheCameraOrReusingItsOldFix() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.start()
        f.emit()
        f.source.failures.emit(Unit)
        runCurrent()
        assertFalse(f.manager.isTransmitting.value)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, f.source.starts)
        assertTrue(f.manager.isActive.value)
        assertEquals(1, f.port.writes.size)
        f.emit()
        assertEquals(2, f.port.writes.size)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(3, f.port.writes.size)
    }

    @Test fun onlyAUsableSampleResetsTheFailureBackoff() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.start()
        f.source.failures.emit(Unit)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        f.source.failures.emit(Unit)
        runCurrent()
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(2, f.source.starts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, f.source.starts)
        f.emit()
        f.source.failures.emit(Unit)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(4, f.source.starts)
    }

    @Test fun lastCameraDisconnectCancelsPendingRecovery() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.source.canStart = false
        f.start()
        f.ready.clear()
        f.manager.updateTracking()
        f.source.canStart = true
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(1, f.source.starts)
        assertFalse(f.manager.isActive.value)
    }

    @Test fun disablingTransmissionCancelsPendingRecovery() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.source.canStart = false
        f.start()
        f.allowed = false
        f.manager.updateTracking()
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(1, f.source.starts)
        assertFalse(f.manager.isActive.value)
    }

    @Test fun shutdownCancelsRecoveryAndNextSessionStartsImmediately() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.source.canStart = false
        f.start()
        f.manager.shutdown()
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(1, f.source.starts)
        f.source.canStart = true
        f.start()
        f.emit()
        assertEquals(2, f.source.starts)
        assertTrue(f.manager.isTransmitting.value)
    }

    @Test fun repeatedSessionEventsDoNotDuplicateOrPostponeRecovery() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.source.canStart = false
        f.start()
        repeat(29) {
            advanceTimeBy(1_000)
            f.start()
            f.manager.updateTracking()
        }
        assertEquals(1, f.source.starts)
        f.source.canStart = true
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, f.source.starts)
        assertTrue(f.manager.isActive.value)
    }

    @Test fun missingFreshSamplesDoNotRestartARegisteredProvider() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.start()
        f.emit()
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(1, f.source.starts)
        assertEquals(18, f.port.writes.size)
        assertFalse(f.manager.isTransmitting.value)
    }

    @Test fun registrationExceptionCanRecoverWithoutCrashingTracking() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.source.registrationFailure = IllegalStateException("provider temporarily unavailable")
        f.start()
        assertFalse(f.manager.isActive.value)
        f.source.registrationFailure = null
        advanceTimeBy(30_000)
        runCurrent()
        f.emit()
        assertTrue(f.manager.isTransmitting.value)
        assertEquals(2, f.source.starts)
    }
}
