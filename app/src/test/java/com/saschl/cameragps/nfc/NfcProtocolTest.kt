package com.saschl.cameragps.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NfcProtocolTest {
    private val camera = "12:34:56:78:9A:BC"
    private val token = ByteArray(16) { it.toByte() }
    private val recipient = ByteArray(16) { (it + 32).toByte() }
    private var now = 0L
    private var releases = 0
    private fun session(token: ByteArray = this.token) = NfcHandoffSession(camera, { now }, token) { releases++ }
    private fun select(s: NfcHandoffSession) = assertArrayEquals(NfcProtocol.ok, s.command(NfcProtocol.select))
    private fun commit() = NfcProtocol.commit(token, recipient)

    @Test fun cameraTagRoundTripHasOnlyAStableIdentifier() {
        val payload = NfcProtocol.cameraPayload(camera.lowercase())
        assertEquals(11, payload.size)
        assertEquals(camera, NfcProtocol.cameraAddress(payload))
        assertNull(NfcProtocol.parseOffer(payload))
    }

    @Test fun unknownVersionsTruncationAndExtraDataAreRejected() {
        val payload = NfcProtocol.cameraPayload(camera)
        for (size in 0 until payload.size) assertNull(NfcProtocol.cameraAddress(payload.copyOf(size)))
        assertNull(NfcProtocol.cameraAddress(payload + byteArrayOf(0)))
        assertNull(NfcProtocol.cameraAddress(payload.copyOf().also { it[4] = 2 }))
        assertNull(NfcProtocol.cameraAddress(payload.copyOf().also { it[0] = 0 }))
        assertNull(NfcProtocol.cameraAddress(payload.copyOf().also { it.fill(0, 5, 11) }))
        assertNull(NfcProtocol.cameraAddress(payload.copyOf().also { it.fill(0xff.toByte(), 5, 11) }))
    }

    @Test fun readingAnOfferNeverPausesTheSender() {
        val s = session()
        select(s)
        val response = s.command(NfcProtocol.read)
        val offer = NfcProtocol.parseOffer(response.dropLast(2).toByteArray())!!
        assertEquals(camera, offer.address)
        assertArrayEquals(token, offer.token)
        assertNull(NfcProtocol.cameraAddress(response.dropLast(2).toByteArray()))
        assertEquals(0, releases)
        assertEquals(NfcHandoffSession.Stage.Offered, s.stage)
    }

    @Test fun noCommandsBeforeSelectionOrWithTheWrongToken() {
        val s = session()
        assertArrayEquals(NfcProtocol.denied, s.command(commit()))
        assertArrayEquals(NfcProtocol.denied, s.command(NfcProtocol.read))
        select(s)
        assertArrayEquals(NfcProtocol.denied, s.command(NfcProtocol.commit(ByteArray(16) { 99 }, recipient)))
        assertArrayEquals(NfcProtocol.denied, s.command(NfcProtocol.status(token, recipient)))
        assertEquals(0, releases)
    }

    @Test fun releaseIsRequestedOnceAndSuccessIsAcknowledgedOnlyAfterItFinishes() {
        val s = session()
        select(s)
        assertArrayEquals(NfcProtocol.waiting, s.command(commit()))
        assertArrayEquals(NfcProtocol.waiting, s.command(commit()))
        assertArrayEquals(NfcProtocol.waiting, s.command(NfcProtocol.status(token, recipient)))
        assertEquals(1, releases)
        s.released(true)
        assertArrayEquals(NfcProtocol.ok, s.command(NfcProtocol.status(token, recipient)))
        assertArrayEquals(NfcProtocol.ok, s.command(commit()))
        assertEquals(1, releases)
    }

    @Test fun aDifferentRecipientCannotReuseACommittedOffer() {
        val s = session()
        select(s)
        s.command(commit())
        s.released(true)
        assertArrayEquals(NfcProtocol.denied, s.command(NfcProtocol.read))
        val other = ByteArray(16) { 100 }
        assertArrayEquals(NfcProtocol.denied, s.command(NfcProtocol.commit(token, other)))
        assertArrayEquals(NfcProtocol.denied, s.command(NfcProtocol.status(token, other)))
        assertEquals(1, releases)
    }

    @Test fun lostFieldOrAckAllowsOnlyTheSameRecipientToRetry() {
        val s = session()
        select(s)
        s.command(commit())
        s.deselect()
        s.released(true)
        assertArrayEquals(NfcProtocol.denied, s.command(commit()))
        select(s)
        assertArrayEquals(NfcProtocol.ok, s.command(commit()))
        assertEquals(1, releases)
    }

    @Test fun failedPauseCannotBeAcknowledgedOrRetriedAsSuccess() {
        val s = session()
        select(s)
        s.command(commit())
        s.released(false)
        s.released(true)
        assertArrayEquals(NfcProtocol.denied, s.command(commit()))
        assertArrayEquals(NfcProtocol.denied, s.command(NfcProtocol.status(token, recipient)))
        assertEquals(1, releases)
    }

    @Test fun expiryRejectsAnUnusedOfferAndLateCompletion() {
        val unused = session()
        select(unused)
        now = 120_000
        assertArrayEquals(NfcProtocol.denied, unused.command(commit()))
        assertEquals(0, releases)
        val releasing = session()
        select(releasing)
        releasing.command(commit())
        now += 120_000
        releasing.released(true)
        assertArrayEquals(NfcProtocol.denied, releasing.command(NfcProtocol.select))
        assertArrayEquals(NfcProtocol.denied, releasing.command(commit()))
    }

    @Test fun closingAnOfferCannotBeUndoneByALateReleaseCallback() {
        val s = session()
        select(s)
        s.command(commit())
        s.close()
        s.released(true)
        assertEquals(NfcHandoffSession.Stage.Closed, s.stage)
        assertArrayEquals(NfcProtocol.denied, s.command(NfcProtocol.select))
        assertArrayEquals(NfcProtocol.denied, s.command(commit()))
    }

    @Test fun oldNonceCannotAuthorizeANewOffer() {
        val s = session(ByteArray(16) { 99 })
        select(s)
        assertArrayEquals(NfcProtocol.denied, s.command(commit()))
        assertEquals(0, releases)
    }

    @Test fun malformedApdusCannotTriggerAPause() {
        val s = session()
        select(s)
        for (size in 0 until commit().size) s.command(commit().copyOf(size))
        s.command(commit() + byteArrayOf(0))
        for (index in listOf(0, 1, 2, 3, 4, 37)) s.command(commit().also { it[index] = 0x7f })
        assertEquals(0, releases)
        assertEquals(NfcHandoffSession.Stage.Offered, s.stage)
    }

    @Test fun anUnsolicitedReleaseCompletionDoesNotAuthorizeAReceiver() {
        val s = session()
        select(s)
        s.released(true)
        assertEquals(NfcHandoffSession.Stage.Offered, s.stage)
        assertArrayEquals(NfcProtocol.denied, s.command(NfcProtocol.status(token, recipient)))
    }
}
