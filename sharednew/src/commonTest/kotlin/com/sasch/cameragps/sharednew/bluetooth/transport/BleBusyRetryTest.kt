package com.sasch.cameragps.sharednew.bluetooth.transport

import com.diamondedge.logging.FixedLogLevel
import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.PlatformLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class BleBusyRetryTest {
    @BeforeTest fun silenceLogs() { KmLogging.setLoggers() }
    @AfterTest fun restoreLogs() { KmLogging.setLoggers(PlatformLogger(FixedLogLevel(true))) }

    @Test fun busyWriteRetriesWithoutWaitingForCallback() = runTest {
        val t = Transport(busyAttempts = 1)
        val q = BleOperationQueue(t, backgroundScope)
        val result = async { q.execute("A", BleOperation.Write("write", byteArrayOf(1))) }
        runCurrent()
        assertEquals(1, t.attempts)
        advanceTimeBy(50)
        runCurrent()
        assertEquals(2, t.attempts)
        assertFalse(result.isCompleted)
        q.onTransportEvent(BleTransportEvent.CharacteristicWritten("A", "write", BleOperationStatus.Success))
        assertTrue(result.await() is BleOperationResult.Success)
    }

    @Test fun busyDescriptorRetriesWithSameEnableState() = runTest {
        val t = Transport(busyAttempts = 1)
        val q = BleOperationQueue(t, backgroundScope)
        val result = async { q.execute("A", BleOperation.Subscribe("cccd", false)) }
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf(false, false), t.subscriptionStates)
        q.onTransportEvent(BleTransportEvent.SubscriptionChanged("A", "cccd", false, BleOperationStatus.Success))
        assertTrue(result.await() is BleOperationResult.Success)
    }

    @Test fun persistentBusyReleasesLaneAfterFourAttempts() = runTest {
        val t = Transport(busyAttempts = Int.MAX_VALUE)
        val q = BleOperationQueue(t, backgroundScope)
        val result = async { q.execute("A", BleOperation.Write("busy", byteArrayOf(1))) }
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        assertEquals(4, t.attempts)
        assertEquals(BleOperationResult.NotInitiated, result.await())
        assertEquals(400, testScheduler.currentTime)
        t.busyAttempts = 0
        q.enqueue("A", BleOperation.Write("next", byteArrayOf(1)))
        runCurrent()
        assertEquals("next", t.writes.last())
    }

    @Test fun disconnectCancelsBackoffAndParkedWork() = runTest {
        val t = Transport(busyAttempts = Int.MAX_VALUE)
        val q = BleOperationQueue(t, backgroundScope)
        val result = async { q.execute("A", BleOperation.Write("busy", byteArrayOf(1))) }
        runCurrent()
        q.enqueue("A", BleOperation.Write("next", byteArrayOf(1)))
        q.onTransportEvent(BleTransportEvent.Disconnected("A", 0))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(BleOperationResult.Cancelled, result.await())
        assertEquals(1, t.attempts)
    }

    @Test fun expiryDuringBackoffPreventsRetry() = runTest {
        val t = Transport(busyAttempts = Int.MAX_VALUE)
        val q = BleOperationQueue(t, backgroundScope)
        val result = async { q.execute("A", BleOperation.Write("location", byteArrayOf(1),
            testScheduler.timeSource.markNow() + 25.milliseconds)) }
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertEquals(BleOperationResult.Cancelled, result.await())
        assertEquals(1, t.attempts)
    }

    @Test fun cancellationDuringBackoffDoesNotIssueAnotherWriteOrKillLane() = runTest {
        val t = Transport(busyAttempts = Int.MAX_VALUE)
        val q = BleOperationQueue(t, backgroundScope)
        val result = async { q.execute("A", BleOperation.Write("cancelled", byteArrayOf(1))) }
        runCurrent()
        result.cancelAndJoin()
        t.busyAttempts = 0
        q.enqueue("A", BleOperation.Write("next", byteArrayOf(1)))
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf("cancelled", "next"), t.writes)
    }

    @Test fun inlineCompletionIsMatchedBeforeInitiateReturns() = runTest {
        val t = Transport(busyAttempts = 0)
        val q = BleOperationQueue(t, backgroundScope)
        t.onWrite = { id, uuid -> q.onTransportEvent(BleTransportEvent.CharacteristicWritten(id, uuid, BleOperationStatus.Success)) }
        val result = async { q.execute("A", BleOperation.Write("inline", byteArrayOf(1))) }
        runCurrent()
        assertTrue(result.isCompleted)
        assertTrue(result.await() is BleOperationResult.Success)
    }

    private class Transport(var busyAttempts: Int) : BlePeripheralTransport {
        override val events = emptyFlow<BleTransportEvent>()
        var attempts = 0
        val writes = mutableListOf<String>()
        val subscriptionStates = mutableListOf<Boolean>()
        var onWrite: ((String, String) -> Unit)? = null
        override fun isConnected(identifier: String) = true
        override fun hasCharacteristic(identifier: String, characteristicUuid: String) = true
        override fun supportsWriteWithResponse(identifier: String, characteristicUuid: String) = true
        override fun initiateWrite(identifier: String, characteristicUuid: String, value: ByteArray) = error("Use result API")
        override fun initiateWriteResult(identifier: String, characteristicUuid: String, value: ByteArray): BleInitiationResult {
            writes += characteristicUuid
            if (++attempts <= busyAttempts) return BleInitiationResult.Busy
            onWrite?.invoke(identifier, characteristicUuid)
            return BleInitiationResult.Started
        }
        override fun initiateRead(identifier: String, characteristicUuid: String) = true
        override fun initiateSubscribe(identifier: String, characteristicUuid: String, enable: Boolean) = error("Use result API")
        override fun initiateSubscribeResult(identifier: String, characteristicUuid: String, enable: Boolean): BleInitiationResult {
            subscriptionStates += enable
            return if (++attempts <= busyAttempts) BleInitiationResult.Busy else BleInitiationResult.Started
        }
        override fun initiateDiscoverServices(identifier: String) = true
    }
}
