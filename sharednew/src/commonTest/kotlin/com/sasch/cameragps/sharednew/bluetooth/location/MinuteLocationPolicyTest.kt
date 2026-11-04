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
class MinuteLocationPolicyTest {
    private val policy = LocationTransmissionPolicy(
        resendIntervalMs = 75_000, maxFixAgeMs = 90_000, minSendIntervalMs = 60_000,
        sendOnUpdate = true, preferNewestFix = true,
    )
    @BeforeTest fun silenceLogs() { KmLogging.setLoggers() }
    @AfterTest fun restoreLogs() { KmLogging.setLoggers(PlatformLogger(FixedLogLevel(true))) }

    @Test fun hourOfMinuteUpdatesHasExactlyOneWritePerMinuteAndNoFallbackDuplicates() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.start()
        f.emit()
        repeat(60) {
            advanceTimeBy(60_000)
            runCurrent()
            f.emit()
        }
        assertEquals(61, f.port.writes.size)
        assertTrue(f.manager.isTransmitting.value)
    }

    @Test fun callbackBurstsAreMergedAndLastCameraDisconnectStopsAllWork() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.start()
        f.emit()
        repeat(5) {
            advanceTimeBy(1_000)
            f.emit()
        }
        assertEquals(1, f.port.writes.size)
        f.ready.clear()
        f.manager.updateTracking()
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(1, f.port.writes.size)
        assertEquals(1, f.source.stops)
        assertFalse(f.manager.isActive.value)
    }

    @Test fun delayedFreshCallbackSendsImmediatelyInsteadOfWaitingAnotherMinute() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.start()
        f.emit()
        advanceTimeBy(63_000)
        runCurrent()
        f.emit()
        assertEquals(2, f.port.writes.size)
    }

    @Test fun lostProviderAllowsOnlyOneFallbackAndExpiresAtNinetySeconds() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.start()
        f.emit()
        advanceTimeBy(75_000)
        runCurrent()
        assertEquals(2, f.port.writes.size)
        advanceTimeBy(15_000)
        runCurrent()
        assertFalse(f.manager.isTransmitting.value)
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(2, f.port.writes.size)
        f.emit()
        assertTrue(f.manager.isTransmitting.value)
        assertEquals(3, f.port.writes.size)
    }

    @Test fun secondCameraGetsFirstFixWithoutChangingFirstCameraCadence() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.start()
        f.emit()
        advanceTimeBy(20_000)
        f.ready += "B"
        f.manager.onDeviceReady("B")
        assertEquals(listOf("A", "B"), f.port.writes)
        f.manager.onDeviceReady("A")
        assertEquals(listOf("A", "B"), f.port.writes)
    }
}
