package dev.aten.webcam.server

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

class WsProtocolException(val closeCode: Int, message: String) : IOException(message)

class WsMessage(val opcode: Int, val payload: ByteArray)

object WsOpcode {
    const val CONTINUATION = 0x0
    const val TEXT = 0x1
    const val BINARY = 0x2
    const val CLOSE = 0x8
    const val PING = 0x9
    const val PONG = 0xA
}

object WsFrameCodec {
    /** Server-to-client frames are never masked, so one encoded array can be shared by all clients. */
    fun encode(opcode: Int, vararg parts: ByteArray): ByteArray {
        val length = parts.sumOf { it.size }
        val headerSize = when {
            length < 126 -> 2
            length <= 0xFFFF -> 4
            else -> 10
        }
        val frame = ByteArray(headerSize + length)
        frame[0] = (0x80 or opcode).toByte()
        when (headerSize) {
            2 -> frame[1] = length.toByte()
            4 -> {
                frame[1] = 126
                frame[2] = (length ushr 8).toByte()
                frame[3] = length.toByte()
            }
            else -> {
                frame[1] = 127
                for (i in 0 until 8) frame[2 + i] = (length.toLong() ushr (8 * (7 - i))).toByte()
            }
        }
        var offset = headerSize
        for (part in parts) {
            part.copyInto(frame, offset)
            offset += part.size
        }
        return frame
    }

    fun encodeClose(code: Int, reason: String = ""): ByteArray =
        encode(WsOpcode.CLOSE, byteArrayOf((code ushr 8).toByte(), code.toByte()), reason.toByteArray())
}

/** Reads client frames, reassembling fragmented messages and surfacing control frames as they arrive. */
class WsReader(input: InputStream, private val maxMessageBytes: Int = 64 * 1024) {
    private val input = DataInputStream(input)
    private var fragments: ByteArrayOutputStream? = null
    private var fragmentedOpcode = 0

    fun next(): WsMessage {
        while (true) {
            val b0 = input.readUnsignedByte()
            val b1 = input.readUnsignedByte()
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            if (b0 and 0x70 != 0) throw WsProtocolException(1002, "reserved bits set")
            if (b1 and 0x80 == 0) throw WsProtocolException(1002, "client frame not masked")
            val isControl = opcode >= 0x8

            var length = (b1 and 0x7F).toLong()
            if (isControl && (length > 125 || !fin)) throw WsProtocolException(1002, "invalid control frame")
            if (length == 126L) length = input.readUnsignedShort().toLong()
            else if (length == 127L) length = input.readLong()
            val buffered = fragments?.size() ?: 0
            if (length < 0 || length + buffered > maxMessageBytes) throw WsProtocolException(1009, "message too big")

            val mask = ByteArray(4).also { input.readFully(it) }
            val payload = ByteArray(length.toInt()).also { input.readFully(it) }
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()

            when (opcode) {
                WsOpcode.CLOSE, WsOpcode.PING, WsOpcode.PONG -> return WsMessage(opcode, payload)
                WsOpcode.TEXT, WsOpcode.BINARY -> {
                    if (fragments != null) throw WsProtocolException(1002, "expected continuation")
                    if (fin) return WsMessage(opcode, payload)
                    fragmentedOpcode = opcode
                    fragments = ByteArrayOutputStream().also { it.write(payload) }
                }
                WsOpcode.CONTINUATION -> {
                    val pending = fragments ?: throw WsProtocolException(1002, "unexpected continuation")
                    pending.write(payload)
                    if (fin) {
                        fragments = null
                        return WsMessage(fragmentedOpcode, pending.toByteArray())
                    }
                }
                else -> throw WsProtocolException(1002, "unknown opcode")
            }
        }
    }
}
