// The relay node's uplink, served by the phone: an ESP32-S3 relay dials
// ws://<phone>:<port>/node and hands over the head's raw UART bytes. This is the
// /node side of repo/web/serve.mjs, cut down to the one peer this app carries.
// GPL-3.0-or-later.
package com.onebody.widscan58.relay

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One listening socket and one active relay. A re-dial replaces the previous peer, the
 * way serve.mjs does; the app shows one instrument, not a node table. Callbacks fire on
 * worker threads, so the caller gets itself onto the UI thread.
 */
class RelayServer(val port: Int) {
    /** A relay finished the handshake and is now the current peer. */
    var onPeer: ((Peer) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    private val lock = Any()

    @Volatile private var listener: ServerSocket? = null
    @Volatile private var peer: Peer? = null

    val listening: Boolean get() = listener != null
    val peerId: String? get() = peer?.id

    /** The port actually bound; differs from [port] only when 0 was asked for. */
    val boundPort: Int get() = listener?.localPort ?: port

    /** Binds and starts accepting. Returns null, or why it could not. */
    fun start(): String? {
        if (listener != null) return null
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: Exception) {
            return e.message ?: e.javaClass.simpleName
        }
        listener = socket
        Thread({ acceptLoop(socket) }, "widscan58-relay-accept").apply {
            isDaemon = true
            start()
        }
        return null
    }

    /** Stops accepting and drops the current peer; its onClose runs as "stopped". */
    fun stop() {
        val socket = listener ?: return
        listener = null
        val current = synchronized(lock) {
            val p = peer
            peer = null
            p
        }
        current?.finish("stopped")
        runCatching { socket.close() }
    }

    /** One command line out; the relay drops frames over [MAX_COMMAND] bytes. */
    fun write(data: ByteArray): Boolean {
        val p = peer ?: return false
        var at = 0
        var ok = true
        while (at < data.size) {
            val end = minOf(at + MAX_COMMAND, data.size)
            ok = p.send(OP_BIN, data.copyOfRange(at, end)) && ok
            at = end
        }
        return ok
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (listener === socket) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            Thread({ serve(client) }, "widscan58-relay-peer").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.tcpNoDelay = true
            val request = readRequest(client.getInputStream()) ?: return client.close()
            if (!"websocket".equals(request.headers["upgrade"], ignoreCase = true) ||
                request.headers["sec-websocket-key"].isNullOrEmpty()
            ) {
                onLog?.invoke("relay: not a websocket upgrade")
                return deny(client, "400 Bad Request", "not a websocket")
            }
            if (request.path != "/node") {
                onLog?.invoke("relay: refused ${request.path}")
                return deny(client, "404 Not Found", "no such socket")
            }
            val id = request.params["id"]?.trim().orEmpty()
            if (!ID_PATTERN.matches(id)) {
                onLog?.invoke("relay: bad id '$id'")
                return deny(client, "400 Bad Request", "bad id")
            }

            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((request.headers["sec-websocket-key"]!! + WS_MAGIC).toByteArray(Charsets.ISO_8859_1)),
            )
            val out = client.getOutputStream()
            out.write(
                ("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
            )
            out.flush()

            val attached = Peer(id, client)
            adopt(attached)
            attached.readLoop()
        } catch (e: IOException) {
            runCatching { client.close() }
        }
    }

    private fun adopt(next: Peer) {
        val old = synchronized(lock) {
            val previous = peer
            peer = next
            previous
        }
        old?.finish("replaced")
        onLog?.invoke("relay: ${next.id} attached")
        onPeer?.invoke(next)
    }

    private fun deny(client: Socket, code: String, reason: String) {
        runCatching {
            client.getOutputStream().apply {
                write("HTTP/1.1 $code\r\nConnection: close\r\nContent-Length: ${reason.length}\r\n\r\n$reason".toByteArray())
                flush()
            }
        }
        runCatching { client.close() }
        onLog?.invoke("relay: $code")
    }

    private class Request(
        val path: String,
        val params: Map<String, String>,
        val headers: Map<String, String>,
    )

    /** Reads through the blank line that ends the request. */
    private fun readRequest(input: InputStream): Request? {
        val buffer = ByteArrayOutputStream()
        val tail = IntArray(4)
        while (buffer.size() < MAX_HEADER) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            tail[0] = tail[1]
            tail[1] = tail[2]
            tail[2] = tail[3]
            tail[3] = b
            if (tail[0] == 13 && tail[1] == 10 && tail[2] == 13 && tail[3] == 10) break
        }
        val raw = buffer.toByteArray()
        if (raw.size < 4 || raw[raw.size - 4] != 13.toByte() || raw[raw.size - 1] != 10.toByte()) return null

        val text = String(raw, 0, raw.size - 4, Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty().split(' ')
        val target = requestLine.getOrNull(1).orEmpty()
        if (target.isEmpty()) return null

        val targetPath = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val params = query.split('&').filter { it.contains('=') }.associate {
            it.substringBefore('=') to it.substringAfter('=')
        }
        val headers = lines.drop(1).filter { it.contains(':') }.associate {
            it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
        }
        return Request(targetPath, params, headers)
    }

    /** One relay dial-in. Bytes arrive on this object's reader thread. */
    inner class Peer internal constructor(val id: String, private val socket: Socket) {
        @Volatile var onBytes: ((ByteArray) -> Unit)? = null
        @Volatile var onClose: ((String) -> Unit)? = null

        private val out: OutputStream = socket.getOutputStream()
        private val outbox = LinkedBlockingQueue<ByteArray>()
        private val done = AtomicBoolean(false)

        @Volatile private var closed = false

        // Commands are pumped from the UI thread; sockets may not be written there.
        private val writer = Thread({
            while (!closed) {
                val bytes = try {
                    outbox.poll(250, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                } ?: continue
                try {
                    out.write(bytes)
                    out.flush()
                } catch (e: IOException) {
                    finish("write failed")
                    break
                }
            }
        }, "widscan58-relay-writer").apply { isDaemon = true }

        init {
            writer.start()
        }

        internal fun send(opcode: Int, payload: ByteArray): Boolean {
            if (closed) return false
            outbox.offer(frame(opcode, payload))
            return true
        }

        fun close(reason: String = "closed") = finish(reason)

        internal fun readLoop() {
            var why = "peer closed"
            val reader = FrameReader(socket.getInputStream())
            var fragment: ByteArrayOutputStream? = null
            var misses = 0
            try {
                socket.soTimeout = IDLE_TIMEOUT_MS
                while (!closed) {
                    val next = try {
                        reader.next()
                    } catch (e: SocketTimeoutException) {
                        // No frame for a whole idle window: ask, and give up after a few.
                        if (++misses >= PING_MISSES) {
                            why = "silent"
                            break
                        }
                        if (!send(OP_PING, EMPTY)) {
                            why = "ping failed"
                            break
                        }
                        continue
                    }
                    if (next == null) break
                    misses = 0
                    when (next.opcode) {
                        OP_BIN -> if (next.fin) {
                            onBytes?.invoke(next.payload)
                        } else {
                            fragment = ByteArrayOutputStream().apply { write(next.payload) }
                        }
                        OP_CONT -> {
                            val acc = fragment ?: continue
                            acc.write(next.payload)
                            if (next.fin) {
                                fragment = null
                                onBytes?.invoke(acc.toByteArray())
                            }
                        }
                        OP_PING -> if (!send(OP_PONG, next.payload)) {
                            why = "pong failed"
                            break
                        }
                        OP_CLOSE -> {
                            why = closeReason(next.payload)
                            break
                        }
                        else -> Unit // pong and the unused text opcode
                    }
                }
            } catch (e: IOException) {
                why = e.message ?: "socket error"
            } finally {
                finish(why)
            }
        }

        /** Once per peer: the socket goes, the server forgets it, and the owner hears why. */
        internal fun finish(why: String) {
            if (!done.compareAndSet(false, true)) return
            closed = true
            runCatching { socket.close() }
            synchronized(lock) { if (peer === this) peer = null }
            onClose?.invoke(why)
        }

        private fun closeReason(payload: ByteArray): String {
            if (payload.size <= 2) return "relay closed"
            return String(payload, 2, payload.size - 2, Charsets.UTF_8).ifBlank { "relay closed" }
        }
    }

    private class Frame(val opcode: Int, val fin: Boolean, val payload: ByteArray)

    /** Minimal RFC6455 reader: clients must mask, and the relay never fragments a command. */
    private class FrameReader(private val input: InputStream) {
        private var buf = ByteArray(256)
        private var pos = 0
        private var end = 0

        fun next(): Frame? {
            if (!ensure(2)) return null
            val first = buf[pos].toInt() and 0xff
            val second = buf[pos + 1].toInt() and 0xff
            var offset = 2
            var length = (second and 0x7f).toLong()
            if (length == 126L) {
                if (!ensure(4)) return null
                length = (((buf[pos + 2].toInt() and 0xff) shl 8) or (buf[pos + 3].toInt() and 0xff)).toLong()
                offset = 4
            } else if (length == 127L) {
                if (!ensure(10)) return null
                length = 0
                for (i in 0..7) length = (length shl 8) or (buf[pos + 2 + i].toLong() and 0xff)
                offset = 10
            }
            if (length < 0 || length > MAX_FRAME) throw IOException("frame too large")
            val masked = second and 0x80 != 0
            val maskAt = offset
            if (masked) offset += 4
            val total = offset + length.toInt()
            if (!ensure(total)) return null

            val payload = ByteArray(length.toInt())
            System.arraycopy(buf, pos + offset, payload, 0, payload.size)
            if (masked) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor (buf[pos + maskAt + (i and 3)].toInt())).toByte()
                }
            }
            pos += total
            return Frame(first and 0x0f, first and 0x80 != 0, payload)
        }

        /** Makes [n] bytes available from pos, growing or refilling as needed; false at EOF. */
        private fun ensure(n: Int): Boolean {
            if (end - pos >= n) return true
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, end - pos)
                end -= pos
                pos = 0
            }
            if (buf.size < n) buf = buf.copyOf(maxOf(n, buf.size * 2))
            while (end < n) {
                val read = input.read(buf, end, buf.size - end)
                if (read < 0) return false
                end += read
            }
            return true
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080

        /** commands.c hands one WS frame to a 128-byte line buffer, terminator included. */
        const val MAX_COMMAND = 120

        private const val WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OP_CONT = 0x0
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val OP_CLOSE = 0x8
        internal const val OP_BIN = 0x2

        private const val MAX_HEADER = 8192
        private const val MAX_FRAME = 1 shl 20
        private const val IDLE_TIMEOUT_MS = 15_000
        private const val PING_MISSES = 3
        private val EMPTY = ByteArray(0)
        private val ID_PATTERN = Regex("[A-Za-z0-9_.:-]{1,64}")

        /** The Wi-Fi addresses a relay could dial, most useful first. */
        fun addresses(): List<String> {
            val out = mutableListOf<String>()
            val interfaces = try {
                NetworkInterface.getNetworkInterfaces() ?: return out
            } catch (e: Exception) {
                return out
            }
            for (ni in interfaces) {
                val usable = runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)
                if (!usable) continue
                for (address in ni.inetAddresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        address.hostAddress?.let { out.add(it) }
                    }
                }
            }
            return out.distinct()
        }

        private fun frame(opcode: Int, payload: ByteArray): ByteArray {
            val len = payload.size
            val header = when {
                len < 126 -> byteArrayOf((0x80 or opcode).toByte(), len.toByte())
                len < 65536 -> byteArrayOf(
                    (0x80 or opcode).toByte(), 126,
                    (len shr 8).toByte(), len.toByte(),
                )
                else -> ByteArray(10).apply {
                    this[0] = (0x80 or opcode).toByte()
                    this[1] = 127
                    for (i in 0..7) this[2 + i] = ((len.toLong() shr (8 * (7 - i))) and 0xff).toByte()
                }
            }
            return header + payload
        }
    }
}
