package com.saschl.cameragps.nfc

import java.security.SecureRandom

/** Small, versioned payloads. A tag identifies a camera; it never authorizes a handoff. */
internal object NfcProtocol {
    const val MIME_TYPE = "application/vnd.com.saschl.cameragps.camera"
    const val AID = "F053415347505301"
    const val TOKEN_SIZE = 16
    private val tagHeader = byteArrayOf(0x41, 0x47, 0x4e, 0x43, 1)
    private val offerHeader = byteArrayOf(0x41, 0x47, 0x4e, 0x48, 1)
    private val aidBytes = AID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val select: ByteArray get() = byteArrayOf(0, 0xa4.toByte(), 4, 0, 8) + aidBytes + byteArrayOf(0)
    val read: ByteArray get() = byteArrayOf(0x80.toByte(), 0x10, 0, 0, 0)
    val ok: ByteArray get() = byteArrayOf(0x90.toByte(), 0)
    val waiting: ByteArray get() = byteArrayOf(0x62, 0)
    val denied: ByteArray get() = byteArrayOf(0x69, 0x85.toByte())
    val malformed: ByteArray get() = byteArrayOf(0x67, 0)

    fun cameraPayload(address: String): ByteArray = tagHeader + macBytes(address)
    fun cameraAddress(payload: ByteArray): String? =
        if (payload.size == 11 && payload.take(5).toByteArray().contentEquals(tagHeader)) {
            decodeMac(payload.copyOfRange(5, 11))
        } else null

    fun offerPayload(address: String, token: ByteArray): ByteArray {
        require(token.size == TOKEN_SIZE)
        return offerHeader + macBytes(address) + token
    }

    class Offer(val address: String, val token: ByteArray, val recipient: ByteArray = newToken())

    fun parseOffer(payload: ByteArray): Offer? =
        if (payload.size == 27 && payload.take(5).toByteArray().contentEquals(offerHeader)) {
            decodeMac(payload.copyOfRange(5, 11))?.let { Offer(it, payload.copyOfRange(11, 27)) }
        } else null

    fun commit(token: ByteArray, recipient: ByteArray) = tokenCommand(0x20, token, recipient)
    fun status(token: ByteArray, recipient: ByteArray) = tokenCommand(0x30, token, recipient)
    fun newToken(): ByteArray = ByteArray(TOKEN_SIZE).also { SecureRandom().nextBytes(it) }

    private fun tokenCommand(instruction: Int, token: ByteArray, recipient: ByteArray): ByteArray {
        require(token.size == TOKEN_SIZE && recipient.size == TOKEN_SIZE)
        return byteArrayOf(0x80.toByte(), instruction.toByte(), 0, 0, 32) + token + recipient + byteArrayOf(0)
    }

    private fun macBytes(address: String): ByteArray {
        require(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(address))
        val bytes = address.split(':').map { it.toInt(16).toByte() }.toByteArray()
        require(decodeMac(bytes) != null)
        return bytes
    }

    private fun decodeMac(bytes: ByteArray): String? {
        if (bytes.all { it == 0.toByte() } || bytes.all { it == 0xff.toByte() }) return null
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}

/** No I/O under the NFC callback. Only an armed, fresh, one-use offer may request a release. */
internal class NfcHandoffSession(
    val address: String,
    private val now: () -> Long,
    token: ByteArray = NfcProtocol.newToken(),
    private val lifetimeMs: Long = 120_000,
    private val requestRelease: (NfcHandoffSession) -> Unit,
) {
    enum class Stage { Offered, Releasing, Paused, Failed, Closed }
    private val token = token.copyOf()
    private val createdAt = now()
    var stage: Stage = Stage.Offered
        private set
    private var selected = false
    private var recipient: ByteArray? = null

    init {
        require(lifetimeMs > 0)
        NfcProtocol.offerPayload(address, this.token)
    }

    fun command(command: ByteArray): ByteArray {
        if (stage == Stage.Closed || now() - createdAt !in 0 until lifetimeMs) return NfcProtocol.denied
        if (command.contentEquals(NfcProtocol.select)) {
            selected = true
            return NfcProtocol.ok
        }
        if (!selected) return NfcProtocol.denied
        if (command.contentEquals(NfcProtocol.read)) {
            if (stage != Stage.Offered) return NfcProtocol.denied
            return NfcProtocol.offerPayload(address, token) + NfcProtocol.ok
        }
        if (command.size != 38 || command[0] != 0x80.toByte() ||
            command[2] != 0.toByte() || command[3] != 0.toByte() ||
            command[4] != 32.toByte() || command[37] != 0.toByte()
        ) return NfcProtocol.malformed
        if (!command.copyOfRange(5, 21).contentEquals(token)) return NfcProtocol.denied
        val claimant = command.copyOfRange(21, 37)
        if (recipient?.contentEquals(claimant) == false) return NfcProtocol.denied
        return when (command[1].toInt() and 0xff) {
            0x20 -> {
                if (stage == Stage.Offered) {
                    recipient = claimant
                    stage = Stage.Releasing
                    requestRelease(this)
                }
                result()
            }
            0x30 -> if (recipient == null) NfcProtocol.denied else result()
            else -> NfcProtocol.malformed
        }
    }

    private fun result(): ByteArray = when (stage) {
        Stage.Paused -> NfcProtocol.ok
        Stage.Releasing -> NfcProtocol.waiting
        else -> NfcProtocol.denied
    }

    fun released(success: Boolean) {
        if (stage == Stage.Releasing) stage = if (success) Stage.Paused else Stage.Failed
    }

    fun deselect() { selected = false }
    fun close() { stage = Stage.Closed; selected = false }
}
