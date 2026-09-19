package dev.aten.webcam.server

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * One upgraded WebSocket. All writes go through a dedicated writer thread fed by a queue, so a
 * slow or stalled viewer can never block the encoder threads or other viewers.
 */
class WsConnection(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    val remoteAddress: String,
    val userAgent: String,
    private val maxQueuedBytes: Long = 4L * 1024 * 1024,
) {
    interface Listener {
        fun onText(text: String) {}
        fun onBinary(payload: ByteArray) {}
        fun onClosed() {}
    }

    private val queue = LinkedBlockingDeque<ByteArray>()
    private val queuedByteCount = AtomicLong()
    private val closed = AtomicBoolean()

    val queuedBytes: Long get() = queuedByteCount.get()
    val isClosed: Boolean get() = closed.get()

    /** Queues a droppable frame. Returns false, without queueing, when the viewer is too far behind. */
    fun offer(frame: ByteArray): Boolean {
        if (closed.get() || queuedByteCount.get() + frame.size > maxQueuedBytes) return false
        enqueue(frame)
        return true
    }

    fun sendText(text: String) = enqueue(WsFrameCodec.encode(WsOpcode.TEXT, text.toByteArray()))

    fun close(code: Int = 1000, reason: String = "") {
        if (closed.get()) return
        enqueue(WsFrameCodec.encodeClose(code, reason))
        enqueue(POISON)
    }

    /** Hard close, for peers that stopped reading: closing the socket unblocks a stuck writer. */
    fun abort() {
        if (closed.compareAndSet(false, true)) {
            queue.clear()
            queue.offer(POISON)
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    /** Runs the read loop on the calling thread until the connection ends. */
    fun run(listener: Listener) {
        val writer = thread(name = "ws-writer", isDaemon = true) { writeLoop() }
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val reader = WsReader(input)
            while (!closed.get()) {
                val message = reader.next()
                when (message.opcode) {
                    WsOpcode.TEXT -> listener.onText(String(message.payload, Charsets.UTF_8))
                    WsOpcode.BINARY -> listener.onBinary(message.payload)
                    WsOpcode.PING -> enqueue(WsFrameCodec.encode(WsOpcode.PONG, message.payload))
                    WsOpcode.CLOSE -> {
                        close()
                        break
                    }
                }
            }
        } catch (e: WsProtocolException) {
            close(e.closeCode)
        } catch (_: IOException) {
        } finally {
            // Lets the writer flush a pending close frame, but never wait out its ping interval.
            queue.offer(POISON)
            writer.join(CLOSE_GRACE_MS)
            abort()
            listener.onClosed()
        }
    }

    private fun enqueue(frame: ByteArray) {
        if (closed.get()) return
        queuedByteCount.addAndGet(frame.size.toLong())
        queue.offer(frame)
    }

    private fun writeLoop() {
        var lastPingAt = System.nanoTime()
        try {
            while (true) {
                val frame = queue.poll(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
                if (frame === POISON) break
                if (frame != null) {
                    queuedByteCount.addAndGet(-frame.size.toLong())
                    output.write(frame)
                }
                // Pings ride along with media too: the reader's timeout relies on the pongs.
                if (System.nanoTime() - lastPingAt >= PING_INTERVAL_MS * 1_000_000L) {
                    output.write(PING_FRAME)
                    lastPingAt = System.nanoTime()
                }
                if (queue.isEmpty()) output.flush()
            }
            output.flush()
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            abort()
        }
    }

    companion object {
        const val PING_INTERVAL_MS = 20_000L
        const val READ_TIMEOUT_MS = 60_000
        private const val CLOSE_GRACE_MS = 2_000L
        private val POISON = ByteArray(0)
        private val PING_FRAME = WsFrameCodec.encode(WsOpcode.PING)
    }
}
