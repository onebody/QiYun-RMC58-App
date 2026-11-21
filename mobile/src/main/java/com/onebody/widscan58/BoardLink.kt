// The byte pipe the instrument talks to, whichever transport carries it: the USB CDC
// port or the Wi-Fi relay's WebSocket uplink. Both feed the same parser and runner.
// GPL-3.0-or-later.
package com.onebody.widscan58

/** What the screen needs from a wire; [description] is what the status line calls it. */
interface BoardLink {
    var onBytes: ((ByteArray) -> Unit)?
    var onClose: ((String) -> Unit)?

    val description: String

    /** Whole command lines out; false when the wire is gone. */
    fun write(data: ByteArray): Boolean

    fun close()
}
