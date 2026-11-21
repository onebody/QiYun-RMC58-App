// The relay's side of the uplink, exercised with a hand-rolled client: handshake, a
// pipelined masked frame, a command back out, and the ping/pong keepalive.
// GPL-3.0-or-later.
package com.onebody.widscan58.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RelayServerTest {
    @Test
    fun handshakeCarriesFramesBothWays() {
        val server = RelayServer(0)
        val attached = LinkedBlockingQueue<RelayServer.Peer>()
        server.onPeer = { attached.put(it) }
        assertNull(server.start())
        try {
            Socket("127.0.0.1", server.boundPort).use { socket ->
                socket.soTimeout = 3000
                val key = "dGhlIHNhbXBsZSBub25jZQ=="
                val request = "GET /node?id=widscan58-ab12 HTTP/1.1\r\n" +
                    "Host: 192.168.4.2\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n"

                socket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.ISO_8859_1))
                    flush()
                }

                val response = readUntilBlankLine(socket.getInputStream())
                assertTrue(response.startsWith("HTTP/1.1 101"))
                val expected = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest(("$key" + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.ISO_8859_1)),
                )
                assertTrue(response.contains(expected))

                val peer = attached.poll(2, TimeUnit.SECONDS)
                assertNotNull(peer)
                assertEquals("widscan58-ab12", peer!!.id)

                val bytes = LinkedBlockingQueue<ByteArray>()
                val closed = LinkedBlockingQueue<String>()
                peer.onBytes = { bytes.put(it) }
                peer.onClose = { closed.put(it) }

                socket.getOutputStream().apply {
                    write(mask(0x2, "STATUS?\n".toByteArray()))
                    flush()
                }

                val got = bytes.poll(2, TimeUnit.SECONDS)
                assertNotNull(got)
                assertArrayEquals("STATUS?\n".toByteArray(), got)

                assertTrue(server.write("OK\n".toByteArray()))
                val frame = readFrame(socket.getInputStream())
                assertEquals(0x2, frame.opcode)
                assertArrayEquals("OK\n".toByteArray(), frame.payload)

                // A ping is answered in kind; the relay's keepalive relies on it.
                socket.getOutputStream().apply {
                    write(mask(0x9, byteArrayOf(1, 2, 3)))
                    flush()
                }
                val pong = readFrame(socket.getInputStream())
                assertEquals(0xA, pong.opcode)
                assertArrayEquals(byteArrayOf(1, 2, 3), pong.payload)

                server.stop()
                assertEquals("stopped", closed.poll(2, TimeUnit.SECONDS))

                assertEquals("widscan58-ab12", peer.id)
            }
        } finally {
            server.stop()
        }
    }

    /** The relay uplink is a 1024-byte chunk per frame, so the extended length header matters. */
    @Test
    fun largeInboundFrameArrivesWhole() {
        val server = RelayServer(0)
        val attached = LinkedBlockingQueue<RelayServer.Peer>()
        server.onPeer = { attached.put(it) }
        assertNull(server.start())
        try {
            Socket("127.0.0.1", server.boundPort).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().apply {
                    write((
                        "GET /node?id=widscan58-ab12 HTTP/1.1\r\n" +
                            "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                            "Sec-WebSocket-Version: 13\r\n\r\n"
                        ).toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                readUntilBlankLine(socket.getInputStream())
                val peer = attached.poll(2, TimeUnit.SECONDS)!!
                val bytes = LinkedBlockingQueue<ByteArray>()
                peer.onBytes = { bytes.put(it) }

                val payload = ByteArray(1024) { (it * 7).toByte() }
                socket.getOutputStream().apply {
                    write(mask(0x2, payload))
                    flush()
                }
                assertArrayEquals(payload, bytes.poll(2, TimeUnit.SECONDS))
            }
        } finally {
            server.stop()
        }
    }

    private class Inbound(val opcode: Int, val payload: ByteArray)

    /** A client frame, masked as RFC6455 requires. */
    private fun mask(opcode: Int, payload: ByteArray): ByteArray {
        val header = if (payload.size < 126) {
            byteArrayOf((0x80 or opcode).toByte(), (0x80 or payload.size).toByte())
        } else {
            byteArrayOf(
                (0x80 or opcode).toByte(), (0x80 or 126).toByte(),
                (payload.size shr 8).toByte(), payload.size.toByte(),
            )
        }
        val key = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val body = ByteArray(payload.size) { i -> (payload[i].toInt() xor key[i and 3].toInt()).toByte() }
        return header + key + body
    }

    private fun readUntilBlankLine(input: InputStream): String {
        val text = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) break
            text.append(b.toChar())
            if (text.endsWith("\r\n\r\n")) break
        }
        return text.toString()
    }

    private fun readFrame(input: InputStream): Inbound {
        fun byte(): Int = input.read().also { if (it < 0) throw IllegalStateException("eof") }
        val first = byte()
        val second = byte()
        var length = second and 0x7f
        if (length == 126) {
            length = (byte() shl 8) or byte()
        } else if (length == 127) {
            length = 0
            repeat(8) { length = (length shl 8) or byte() }
        }
        assertEquals(0, second and 0x80) // server frames are never masked
        val payload = ByteArray(length)
        var at = 0
        while (at < payload.size) {
            val read = input.read(payload, at, payload.size - at)
            if (read < 0) throw IllegalStateException("eof")
            at += read
        }
        return Inbound(first and 0x0f, payload)
    }
}
