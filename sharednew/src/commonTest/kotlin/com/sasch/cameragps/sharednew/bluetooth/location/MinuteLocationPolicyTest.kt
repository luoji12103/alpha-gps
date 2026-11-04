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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MinuteLocationPolicyTest {
    private val policy = LocationTransmissionPolicy(
        resendIntervalMs = 5_000, maxFixAgeMs = 90_000, preferNewestFix = true,
    )
    @BeforeTest fun silenceLogs() { KmLogging.setLoggers() }
    @AfterTest fun restoreLogs() { KmLogging.setLoggers(PlatformLogger(FixedLogLevel(true))) }

    @Test fun hourOfMinuteSamplesRefreshesUnchangedCoordinatesEveryFiveSeconds() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.start()
        f.emit()
        repeat(60) {
            advanceTimeBy(60_000)
            runCurrent()
            f.emit()
        }
        assertEquals(721, f.port.writes.size)
        val coordinates = f.port.packets.first().sliceArray(11..18)
        f.port.packets.forEach { assertContentEquals(coordinates, it.sliceArray(11..18)) }
        assertTrue(f.manager.isTransmitting.value)
    }

    @Test fun callbackBurstsDoNotPostponeRefreshOrTriggerExtraWrites() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.start()
        f.emit()
        repeat(16) {
            advanceTimeBy(1_000)
            f.emit()
        }
        assertEquals(4, f.port.writes.size)
        f.ready.clear()
        f.manager.updateTracking()
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(4, f.port.writes.size)
        assertEquals(1, f.source.stops)
        assertFalse(f.manager.isActive.value)
    }

    @Test fun delayedNewSampleReplacesCoordinatesByNextRefreshEvenIfLessAccurate() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.start()
        f.emit()
        advanceTimeBy(63_000)
        runCurrent()
        val before = f.port.writes.size
        f.source.locations.emit(f.fix().copy(latitude = 53.0, horizontalAccuracyMeters = 2_000.0))
        runCurrent()
        assertEquals(before, f.port.writes.size)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(before + 1, f.port.writes.size)
        val latitudeE7 = f.port.packets.last().sliceArray(11..14)
            .fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xff) }
        assertEquals(530_000_000, latitudeE7)
    }

    @Test fun lostProviderAndRepeatedCacheStopAtOriginalNinetySecondDeadlineAndRecover() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.start()
        f.emit()
        advanceTimeBy(60_000)
        runCurrent()
        f.emit(age = 60_000) // Re-delivery of the first sample is not a new fix.
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(18, f.port.writes.size) // t=0, 5, ..., 85; never at t=90.
        assertFalse(f.manager.isTransmitting.value)
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(18, f.port.writes.size)
        f.emit()
        assertTrue(f.manager.isTransmitting.value)
        assertEquals(19, f.port.writes.size)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(20, f.port.writes.size)
    }

    @Test fun secondCameraGetsCachedFixImmediatelyAndKeepsTrackingAfterFirstDisconnects() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.start()
        f.emit()
        advanceTimeBy(22_000)
        runCurrent()
        f.ready += "B"
        f.manager.onDeviceReady("B")
        assertEquals(5, f.port.writes.count { it == "A" })
        assertEquals(1, f.port.writes.count { it == "B" })
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf("A", "B"), f.port.writes.takeLast(2))
        f.ready -= "A"
        f.manager.updateTracking()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("B", f.port.writes.last())
        assertEquals(0, f.source.stops)
        assertTrue(f.manager.isActive.value)
    }

    @Test fun noInitialFixDoesNotWriteAndLateFirstFixStartsPeriodicRefresh() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.start()
        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(f.port.writes.isEmpty())
        f.emit()
        assertEquals(1, f.port.writes.size)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, f.port.writes.size)
    }

    @Test fun resendRebuildsCurrentProtocolTimeWithoutRenewingSampleOrQueueLifetime() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.start()
        f.emit()
        assertEquals(-5_000, f.port.deadlines.single().elapsedNow().inWholeMilliseconds)
        advanceTimeBy(5_000)
        runCurrent()
        val first = f.port.packets.first()
        val next = f.port.packets.last()
        assertContentEquals(first.sliceArray(11..18), next.sliceArray(11..18))
        assertFalse(first.sliceArray(19..25).contentEquals(next.sliceArray(19..25)))
        assertTrue(f.port.deadlines.first().hasPassedNow())
        assertFalse(f.port.deadlines.last().hasPassedNow())
        advanceTimeBy(85_000)
        runCurrent()
        assertFalse(f.manager.isTransmitting.value)
        assertTrue(f.port.deadlines.all { it.hasPassedNow() })
    }

    @Test fun nearlyExpiredSampleCannotGetAFiveSecondQueueExtension() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy)
        f.start()
        f.emit(age = 88_000)
        assertEquals(-2_000, f.port.deadlines.single().elapsedNow().inWholeMilliseconds)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(f.port.deadlines.single().hasPassedNow())
        assertFalse(f.manager.isTransmitting.value)
        assertEquals(1, f.port.writes.size)
    }

    @Test fun sendOnUpdateDoesNotMakePeriodicRefreshAOneShot() = runTest {
        val f = LocationFreshnessTest.Fixture(this, policy.copy(sendOnUpdate = true))
        f.start()
        f.emit()
        advanceTimeBy(16_000)
        runCurrent()
        assertEquals(4, f.port.writes.size)
    }
}
