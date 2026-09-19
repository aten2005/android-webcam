package dev.aten.webcam.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class WebSocketTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun clientFrame(opcode: Int, payload: ByteArray, fin: Boolean = true, masked: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0) or opcode)
        val maskBit = if (masked) 0x80 else 0
        when {
            payload.size < 126 -> out.write(maskBit or payload.size)
            payload.size <= 0xFFFF -> {
                out.write(maskBit or 126)
                out.write(payload.size ushr 8)
                out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(maskBit or 127)
                for (i in 7 downTo 0) out.write(((payload.size.toLong() ushr (8 * i)) and 0xFF).toInt())
            }
        }
        if (masked) {
            val mask = bytes(0x12, 0x34, 0x56, 0x78)
            out.write(mask)
            payload.forEachIndexed { i, b -> out.write(b.toInt() xor mask[i and 3].toInt()) }
        } else {
            out.write(payload)
        }
        return out.toByteArray()
    }

    private fun upgradeRequest(extra: String = "", version: String = "13", key: String = "dGhlIHNhbXBsZSBub25jZQ==") =
        HttpRequest.read(
            ("GET /ws HTTP/1.1\r\nHost: cam:8443\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\n" +
                "Sec-WebSocket-Version: $version\r\nSec-WebSocket-Key: $key\r\n$extra\r\n").toByteArray().inputStream(),
        )!!

    private fun assertCloseCode(expected: Int, vararg frames: ByteArray) {
        val reader = WsReader(frames.reduce { a, b -> a + b }.inputStream(), maxMessageBytes = 1024)
        try {
            while (true) reader.next()
        } catch (e: WsProtocolException) {
            assertEquals(expected, e.closeCode)
            return
        }
        @Suppress("UNREACHABLE_CODE")
        fail("expected protocol error")
    }

    @Test
    fun computesRfcAcceptKey() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsHandshake.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun acceptsValidUpgrade() {
        val request = upgradeRequest()
        assertTrue(WsHandshake.isUpgradeRequest(request))
        assertEquals(101, WsHandshake.respond(request).status)
    }

    @Test
    fun refusesBadUpgrades() {
        assertEquals(426, WsHandshake.respond(upgradeRequest(version = "8")).status)
        assertEquals(400, WsHandshake.respond(upgradeRequest(key = "c2hvcnQ=")).status)
        assertEquals(400, WsHandshake.respond(upgradeRequest(key = "!!!not-base64!!!")).status)
    }

    @Test
    fun comparesOriginWithHost() {
        assertTrue(WsHandshake.originMatchesHost(upgradeRequest("Origin: https://CAM:8443\r\n")))
        assertFalse(WsHandshake.originMatchesHost(upgradeRequest("Origin: https://evil.example\r\n")))
        assertFalse(WsHandshake.originMatchesHost(upgradeRequest("Origin: null\r\n")))
        assertFalse(WsHandshake.originMatchesHost(upgradeRequest()))
    }

    @Test
    fun encodesRfcUnmaskedHello() {
        assertArrayEquals(
            bytes(0x81, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f),
            WsFrameCodec.encode(WsOpcode.TEXT, "Hello".toByteArray()),
        )
    }

    @Test
    fun encodesExtendedLengths() {
        val medium = WsFrameCodec.encode(WsOpcode.BINARY, ByteArray(300), ByteArray(200))
        assertArrayEquals(bytes(0x82, 126, 0x01, 0xF4), medium.copyOf(4))
        assertEquals(504, medium.size)
        val large = WsFrameCodec.encode(WsOpcode.BINARY, ByteArray(70_000))
        assertArrayEquals(bytes(0x82, 127, 0, 0, 0, 0, 0, 0x01, 0x11, 0x70), large.copyOf(10))
        assertEquals(70_010, large.size)
    }

    @Test
    fun decodesRfcMaskedHello() {
        val frame = bytes(0x81, 0x85, 0x37, 0xfa, 0x21, 0x3d, 0x7f, 0x9f, 0x4d, 0x51, 0x58)
        val message = WsReader(frame.inputStream()).next()
        assertEquals(WsOpcode.TEXT, message.opcode)
        assertEquals("Hello", String(message.payload))
    }

    @Test
    fun reassemblesFragmentsAroundControlFrames() {
        val stream = clientFrame(WsOpcode.BINARY, bytes(1, 2), fin = false) +
            clientFrame(WsOpcode.PING, bytes(9)) +
            clientFrame(WsOpcode.CONTINUATION, bytes(3)) +
            clientFrame(WsOpcode.BINARY, ByteArray(500) { 7 })
        val reader = WsReader(stream.inputStream(), maxMessageBytes = 1024)
        assertEquals(WsOpcode.PING, reader.next().opcode)
        val assembled = reader.next()
        assertEquals(WsOpcode.BINARY, assembled.opcode)
        assertArrayEquals(bytes(1, 2, 3), assembled.payload)
        assertEquals(500, reader.next().payload.size)
    }

    @Test
    fun rejectsProtocolViolations() {
        assertCloseCode(1002, clientFrame(WsOpcode.TEXT, bytes(1), masked = false))
        assertCloseCode(1002, bytes(0xC1, 0x80, 0, 0, 0, 0))
        assertCloseCode(1002, clientFrame(WsOpcode.PING, ByteArray(126)))
        assertCloseCode(1002, clientFrame(WsOpcode.PING, bytes(1), fin = false))
        assertCloseCode(1002, clientFrame(WsOpcode.CONTINUATION, bytes(1)))
        assertCloseCode(1002, clientFrame(WsOpcode.TEXT, bytes(1), fin = false), clientFrame(WsOpcode.TEXT, bytes(2)))
        assertCloseCode(1002, clientFrame(0x3, bytes(1)))
    }

    @Test
    fun enforcesMessageSizeLimit() {
        assertCloseCode(1009, clientFrame(WsOpcode.BINARY, ByteArray(1025)))
        assertCloseCode(
            1009,
            clientFrame(WsOpcode.BINARY, ByteArray(600), fin = false),
            clientFrame(WsOpcode.CONTINUATION, ByteArray(600)),
        )
        assertCloseCode(1009, bytes(0x82, 0xFF, 0x80, 0, 0, 0, 0, 0, 0, 0))
    }

    @Test
    fun encodesCloseFrame() {
        assertArrayEquals(bytes(0x88, 0x04, 0x03, 0xE8, 0x6F, 0x6B), WsFrameCodec.encodeClose(1000, "ok"))
    }
}
