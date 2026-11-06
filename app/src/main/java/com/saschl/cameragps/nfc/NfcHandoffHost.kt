package com.saschl.cameragps.nfc

import android.content.Context
import android.os.SystemClock
import com.saschl.cameragps.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** Main-thread confined. A tap may finish its safety-critical pause after the screen is left. */
internal object NfcHandoffHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: NfcHandoffSession? = null
    private var expiry: Job? = null
    private var release: Job? = null
    private val _message = MutableStateFlow<Int?>(null)
    val message = _message.asStateFlow()

    fun arm(context: Context, address: String): NfcHandoffSession {
        check(release?.isActive != true)
        session?.close()
        expiry?.cancel()
        val app = context.applicationContext
        val created = NfcHandoffSession(address, SystemClock::elapsedRealtime) { request ->
            _message.value = R.string.nfc_pausing
            release = scope.launch {
                val success = runCatching { NfcCameraActions(app).pauseForHandoff(request.address) }
                    .onFailure { Timber.w(it, "NFC handoff pause failed") }.getOrDefault(false)
                request.released(success)
                if (session === request) _message.value = if (success) R.string.nfc_source_paused else R.string.nfc_pause_failed
            }
        }
        session = created
        _message.value = R.string.nfc_offer_ready
        expiry = scope.launch {
            delay(120_000)
            if (session === created) {
                close(created)
                _message.value = R.string.nfc_offer_expired
            }
        }
        return created
    }

    fun command(command: ByteArray): ByteArray = session?.command(command) ?: NfcProtocol.denied
    fun deselect() { session?.deselect() }
    fun close(owned: NfcHandoffSession) {
        owned.close()
        if (session === owned) {
            session = null
            expiry?.cancel()
            expiry = null
        }
    }
}
