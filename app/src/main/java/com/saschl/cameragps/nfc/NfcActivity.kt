package com.saschl.cameragps.nfc

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.TagTechnology
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.sasch.cameragps.sharednew.ui.theme.CameraGpsTheme
import com.saschl.cameragps.AppServices
import com.saschl.cameragps.MainActivity
import com.saschl.cameragps.R
import com.saschl.cameragps.utils.PreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** NFC is explicitly armed in the foreground. No reader, advertisement, GPS or peer loop at idle. */
class NfcActivity : AppCompatActivity() {
    private enum class Mode { Idle, Send, ReadOffer, CommitOffer, WriteTag, ReadTag }
    private var mode by mutableStateOf(Mode.Idle)
    private var selected by mutableStateOf<String?>(null)
    private var message by mutableStateOf<Int?>(null)
    private var nfcEnabled by mutableStateOf(false)
    private var pendingOffer by mutableStateOf<NfcProtocol.Offer?>(null)
    private var tagConfirmation by mutableStateOf<String?>(null)
    private var ownedOffer: NfcHandoffSession? = null
    private val adapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val actions by lazy { NfcCameraActions(applicationContext) }
    private var resumed = false
    @Volatile private var generation = 0L
    @Volatile private var technology: TagTechnology? = null
    private val busy = AtomicBoolean(false)
    @Volatile private var operation: Job? = null
    private var readerTimeout: Job? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Approximate location is sufficient; Android may grant coarse while denying fine.
        message = if (actions.hasPermissions()) R.string.nfc_permissions_ready else R.string.nfc_permissions_needed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        readIntent(intent)
        setContent { CameraGpsTheme { Surface { Content() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        changeMode(Mode.Idle)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        nfcEnabled = adapter?.isEnabled == true
        configureRadio()
    }

    override fun onPause() {
        resumed = false
        changeMode(Mode.Idle)
        super.onPause()
    }

    private fun readIntent(incoming: Intent?) {
        if (incoming?.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return
        // Exported NFC intents are untrusted; a valid record still requires local pairing and confirmation.
        tagConfirmation = runCatching {
            @Suppress("DEPRECATION")
            val messages = if (Build.VERSION.SDK_INT >= 33) {
                incoming.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)?.toList()
            } else incoming.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.filterIsInstance<NdefMessage>()
            messages?.firstNotNullOfOrNull { cameraFromMessage(it) }
        }.getOrNull()
        if (tagConfirmation == null) message = R.string.nfc_invalid_tag
    }

    private fun changeMode(next: Mode) {
        generation++
        readerTimeout?.cancel()
        readerTimeout = null
        operation?.cancel()
        runCatching { technology?.close() }
        technology = null
        runCatching { adapter?.disableReaderMode(this) }
        runCatching { adapter?.let { CardEmulation.getInstance(it).unsetPreferredService(this) } }
        ownedOffer?.let {
            if (it.stage == NfcHandoffSession.Stage.Releasing || it.stage == NfcHandoffSession.Stage.Paused) {
                message = R.string.nfc_cancel_after_commit
            }
            NfcHandoffHost.close(it)
        }
        ownedOffer = null
        mode = next
        if (resumed) configureRadio()
    }

    private fun configureRadio() {
        val nfc = adapter ?: return
        if (!resumed || !nfc.isEnabled) return
        try {
            if (mode == Mode.Send) {
                if (!packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
                    message = R.string.nfc_hce_unavailable
                    mode = Mode.Idle
                    return
                }
                ownedOffer = NfcHandoffHost.arm(this, selected ?: return)
                if (!CardEmulation.getInstance(nfc).setPreferredService(this, ComponentName(this, CameraHandoffNfcService::class.java))) {
                    ownedOffer?.let { NfcHandoffHost.close(it) }
                    ownedOffer = null
                    mode = Mode.Idle
                    message = R.string.nfc_radio_failed
                }
            } else if (mode != Mode.Idle) {
                val expected = mode
                val epoch = generation
                val skipNdef = expected == Mode.ReadOffer || expected == Mode.CommitOffer
                val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    if (skipNdef) NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK else
                        (NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V)
                nfc.enableReaderMode(this, { tag -> onTag(tag, expected, epoch) }, flags, null)
                readerTimeout = lifecycleScope.launch {
                    delay(120_000)
                    if (generation == epoch) {
                        pendingOffer = null
                        changeMode(Mode.Idle)
                        message = if (expected == Mode.CommitOffer) R.string.nfc_handoff_unconfirmed else R.string.nfc_reader_timeout
                    }
                }
            }
        } catch (_: RuntimeException) {
            ownedOffer?.let { NfcHandoffHost.close(it) }
            ownedOffer = null
            mode = Mode.Idle
            message = R.string.nfc_radio_failed
        }
    }

    private fun onTag(tag: Tag, expected: Mode, epoch: Long) {
        if (!busy.compareAndSet(false, true)) return
        // Enter Main before touching Compose state, Room, preferences or the BLE graph.
        val task = lifecycleScope.launch {
            try {
                if (!resumed || generation != epoch) return@launch
                when (expected) {
                    Mode.ReadOffer -> {
                        val offer = withContext(Dispatchers.IO) { exchangeOffer(tag) }
                        if (generation != epoch) return@launch
                        val error = actions.readinessError(offer.address)
                        if (error != null) { message = error; return@launch }
                        changeMode(Mode.Idle)
                        pendingOffer = offer
                        message = null
                    }
                    Mode.CommitOffer -> {
                        val offer = pendingOffer ?: return@launch
                        val error = actions.readinessError(offer.address)
                        if (error != null) { message = error; return@launch }
                        withContext(Dispatchers.IO) { exchangeOffer(tag, offer) }
                        if (!resumed || generation != epoch) return@launch
                        actions.connect(offer.address)
                        pendingOffer = null
                        changeMode(Mode.Idle)
                        message = R.string.nfc_connection_requested
                    }
                    Mode.WriteTag -> {
                        val address = selected ?: return@launch
                        withContext(Dispatchers.IO) { writeTag(tag, address) }
                        if (generation == epoch) {
                            changeMode(Mode.Idle)
                            message = R.string.nfc_tag_written
                        }
                    }
                    Mode.ReadTag -> {
                        val address = withContext(Dispatchers.IO) { readCameraTag(tag) }
                        if (generation == epoch) {
                            changeMode(Mode.Idle)
                            tagConfirmation = address
                        }
                    }
                    else -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: NfcFailure) {
                if (generation == epoch) message = failure.text
            } catch (_: IOException) {
                if (generation == epoch) message = R.string.nfc_tap_again
            } catch (_: Exception) {
                if (generation == epoch) message = R.string.nfc_operation_failed
            } finally {
                busy.set(false)
            }
        }
        operation = task
        // A launch cancelled before its body starts does not execute its finally block.
        task.invokeOnCompletion { busy.set(false) }
    }

    private suspend fun exchangeOffer(tag: Tag, expected: NfcProtocol.Offer? = null): NfcProtocol.Offer {
        val channel = IsoDep.get(tag) ?: throw NfcFailure(R.string.nfc_not_handoff_device)
        technology = channel
        try {
            channel.connect()
            channel.timeout = 1_500
            if (!channel.transceive(NfcProtocol.select).contentEquals(NfcProtocol.ok)) throw NfcFailure(R.string.nfc_offer_unavailable)
            if (expected != null) {
                // The nonce verifies the same offer. Only this recipient may retry a lost ACK.
                var result = channel.transceive(NfcProtocol.commit(expected.token, expected.recipient))
                val deadline = SystemClock.elapsedRealtime() + 6_000
                while (result.contentEquals(NfcProtocol.waiting) && SystemClock.elapsedRealtime() < deadline) {
                    delay(100)
                    result = channel.transceive(NfcProtocol.status(expected.token, expected.recipient))
                }
                if (!result.contentEquals(NfcProtocol.ok)) throw NfcFailure(R.string.nfc_handoff_unconfirmed)
                return expected
            }
            val response = channel.transceive(NfcProtocol.read)
            if (response.size < 2 || !response.takeLast(2).toByteArray().contentEquals(NfcProtocol.ok)) throw NfcFailure(R.string.nfc_offer_unavailable)
            return NfcProtocol.parseOffer(response.dropLast(2).toByteArray()) ?: throw NfcFailure(R.string.nfc_offer_unavailable)
        } finally {
            runCatching { channel.close() }
            if (technology === channel) technology = null
        }
    }

    private fun tagMessage(address: String) = NdefMessage(arrayOf(
        NdefRecord.createMime(NfcProtocol.MIME_TYPE, NfcProtocol.cameraPayload(address)),
        NdefRecord.createApplicationRecord(packageName),
    ))

    private fun writeTag(tag: Tag, address: String) {
        val message = tagMessage(address)
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            technology = ndef
            try {
                ndef.connect()
                if (!ndef.isWritable) throw NfcFailure(R.string.nfc_tag_readonly)
                if (ndef.maxSize < message.toByteArray().size) throw NfcFailure(R.string.nfc_tag_too_small)
                ndef.writeNdefMessage(message)
                if (cameraFromMessage(ndef.ndefMessage) != address) throw NfcFailure(R.string.nfc_operation_failed)
            } finally { runCatching { ndef.close() }; if (technology === ndef) technology = null }
        } else {
            val format = NdefFormatable.get(tag) ?: throw NfcFailure(R.string.nfc_tag_unsupported)
            technology = format
            try { format.connect(); format.format(message) }
            finally { runCatching { format.close() }; if (technology === format) technology = null }
        }
    }

    private fun readCameraTag(tag: Tag): String {
        val ndef = Ndef.get(tag) ?: throw NfcFailure(R.string.nfc_invalid_tag)
        technology = ndef
        try {
            ndef.connect()
            return cameraFromMessage(ndef.ndefMessage) ?: throw NfcFailure(R.string.nfc_invalid_tag)
        } finally { runCatching { ndef.close() }; if (technology === ndef) technology = null }
    }

    private fun cameraFromMessage(message: NdefMessage?): String? = message?.records?.firstNotNullOfOrNull {
        if (it.tnf == NdefRecord.TNF_MIME_MEDIA && it.type.contentEquals(NfcProtocol.MIME_TYPE.toByteArray(Charsets.US_ASCII))) {
            NfcProtocol.cameraAddress(it.payload)
        } else null
    }

    @Composable
    private fun Content() {
        val devices by AppServices.from(this).deviceDao.observeAllDevices().collectAsState(emptyList())
        val hostMessage by NfcHandoffHost.message.collectAsState()
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { finish() }) { Text(stringResource(R.string.nfc_back)) }
            TextButton(onClick = {
                startActivity(Intent(this@NfcActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }) { Text(stringResource(R.string.nfc_open_devices)) }
            Text(stringResource(R.string.nfc_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.nfc_intro))
            if (adapter == null) {
                Text(stringResource(R.string.nfc_unavailable))
                return@Column
            }
            if (!nfcEnabled) {
                Text(stringResource(R.string.nfc_disabled))
                Button(onClick = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text(stringResource(R.string.nfc_open_settings)) }
            }
            message?.let { Text(stringResource(it)) }
            if (mode == Mode.Send) hostMessage?.let { Text(stringResource(it)) }
            if (mode != Mode.Idle) {
                Text(stringResource(when (mode) {
                    Mode.Send -> R.string.nfc_send_instruction
                    Mode.ReadOffer -> R.string.nfc_receive_instruction
                    Mode.CommitOffer -> R.string.nfc_commit_instruction
                    Mode.WriteTag -> R.string.nfc_write_instruction
                    else -> R.string.nfc_read_instruction
                }))
                OutlinedButton(onClick = { changeMode(Mode.Idle) }) { Text(stringResource(R.string.nfc_cancel)) }
            } else {
                if (devices.isEmpty()) Text(stringResource(R.string.nfc_pair_camera_first))
                devices.forEach { device ->
                    Row(Modifier.fillMaxWidth().clickable { selected = device.mac }) {
                        RadioButton(selected = selected == device.mac, onClick = { selected = device.mac })
                        Text("${device.deviceName}\n${device.mac}", Modifier.padding(8.dp))
                    }
                }
                val canOffer = selected?.let { address ->
                    devices.any { it.mac == address && it.deviceEnabled } &&
                        PreferencesManager.isAppEnabled(this@NfcActivity) && !PreferencesManager.isNfcCameraPaused(this@NfcActivity, address)
                } == true
                Button(enabled = canOffer && nfcEnabled, onClick = { message = null; changeMode(Mode.Send) }) {
                    Text(stringResource(R.string.nfc_send))
                }
                Button(enabled = nfcEnabled, onClick = { pendingOffer = null; message = null; changeMode(Mode.ReadOffer) }) {
                    Text(stringResource(R.string.nfc_receive))
                }
                HorizontalDivider()
                Text(stringResource(R.string.nfc_tag_description))
                OutlinedButton(enabled = selected != null && nfcEnabled, onClick = { message = null; changeMode(Mode.WriteTag) }) {
                    Text(stringResource(R.string.nfc_write))
                }
                OutlinedButton(enabled = nfcEnabled, onClick = { message = null; changeMode(Mode.ReadTag) }) {
                    Text(stringResource(R.string.nfc_read))
                }
                TextButton(onClick = {
                    permissions.launch(buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
                    }.toTypedArray())
                }) { Text(stringResource(R.string.nfc_grant_permissions)) }
            }
        }
        pendingOffer?.takeIf { mode == Mode.Idle }?.let { offer ->
            ConfirmCamera(offer.address, R.string.nfc_handoff_confirmation, onDismiss = { pendingOffer = null }) {
                message = null
                changeMode(Mode.CommitOffer)
            }
        }
        tagConfirmation?.let { address ->
            ConfirmCamera(address, R.string.nfc_tag_confirmation, onDismiss = { tagConfirmation = null }) {
                tagConfirmation = null
                lifecycleScope.launch {
                    try {
                        val error = actions.readinessError(address)
                        if (error != null) message = error
                        else { actions.connect(address); message = R.string.nfc_connection_requested }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = R.string.nfc_operation_failed }
                }
            }
        }
    }

    @Composable
    private fun ConfirmCamera(address: String, text: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
        AlertDialog(onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.nfc_confirm_title)) },
            text = { Text(stringResource(text, address)) },
            confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.nfc_confirm)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.nfc_cancel)) } },
        )
    }

    private class NfcFailure(val text: Int) : IOException()
}
