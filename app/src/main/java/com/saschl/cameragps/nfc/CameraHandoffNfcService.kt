package com.saschl.cameragps.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/** Optional non-payment HCE service. No armed foreground offer means no data or action. */
class CameraHandoffNfcService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray =
        commandApdu?.let { NfcHandoffHost.command(it) } ?: NfcProtocol.malformed

    override fun onDeactivated(reason: Int) = NfcHandoffHost.deselect()
}
