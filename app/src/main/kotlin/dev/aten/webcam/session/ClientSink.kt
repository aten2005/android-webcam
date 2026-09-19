package dev.aten.webcam.session

import dev.aten.webcam.server.WsChannel

/** Per-viewer delivery state: decides which frames a viewer can use and sheds load when it lags. */
class ClientSink(val channel: WsChannel, private val clock: () -> Long) {
    enum class Result { SENT, SKIPPED, NEED_KEY, CONGESTED, DEAD }

    @Volatile
    var videoPaused = false
        set(value) {
            field = value
            if (!value) awaitingKey = true
        }

    // A decoder can only start on a key frame, so deltas are withheld until one has been delivered.
    @Volatile
    private var awaitingKey = true
    private var congestedSince = NOT_CONGESTED

    fun expectKeyFrame() {
        awaitingKey = true
    }

    @Synchronized
    fun sendVideo(frame: ByteArray, keyFrame: Boolean): Result {
        if (videoPaused) return Result.SKIPPED
        val now = clock()
        val backlog = channel.queuedBytes
        if (congestedSince != NOT_CONGESTED && backlog <= RESUME_BACKLOG_BYTES) congestedSince = NOT_CONGESTED
        if (congestedSince != NOT_CONGESTED || backlog > MAX_BACKLOG_BYTES) return congested(now)

        if (awaitingKey && !keyFrame) return Result.NEED_KEY
        if (!channel.offer(frame)) return congested(now)
        awaitingKey = false
        return Result.SENT
    }

    fun sendAudio(frame: ByteArray) {
        if (channel.queuedBytes <= MAX_BACKLOG_BYTES) channel.offer(frame)
    }

    private fun congested(now: Long): Result {
        // Frames were skipped, so the viewer's decoder has to restart from a key frame.
        awaitingKey = true
        if (congestedSince == NOT_CONGESTED) congestedSince = now
        if (now - congestedSince > STALL_TIMEOUT_MS) {
            channel.abort()
            return Result.DEAD
        }
        return Result.CONGESTED
    }

    companion object {
        /** A few seconds of video at the default bitrate; beyond this the stream is no longer "live". */
        const val MAX_BACKLOG_BYTES = 768L * 1024
        const val RESUME_BACKLOG_BYTES = MAX_BACKLOG_BYTES / 4
        const val STALL_TIMEOUT_MS = 10_000L
        private const val NOT_CONGESTED = -1L
    }
}
