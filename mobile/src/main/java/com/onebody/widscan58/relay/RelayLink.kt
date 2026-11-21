// The BoardLink view of one attached relay: commands go out as WS binary frames, the
// relay's frames come back through Peer.onBytes, and closing drops just this peer.
// GPL-3.0-or-later.
package com.onebody.widscan58.relay

import com.onebody.widscan58.BoardLink

class RelayLink(
    val peer: RelayServer.Peer,
    private val label: String,
) : BoardLink {
    override var onBytes: ((ByteArray) -> Unit)? = null
    override var onClose: ((String) -> Unit)? = null

    override val description: String get() = "$label · ${peer.id}"

    override fun write(data: ByteArray): Boolean {
        var at = 0
        var ok = true
        while (at < data.size) {
            val end = minOf(at + RelayServer.MAX_COMMAND, data.size)
            ok = peer.send(RelayServer.OP_BIN, data.copyOfRange(at, end)) && ok
            at = end
        }
        return ok
    }

    override fun close() = peer.close("closed")
}
