package dev.aten.webcam.session

import dev.aten.webcam.server.WsAdmission
import dev.aten.webcam.server.WsChannel
import dev.aten.webcam.server.WsConnection
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the standby/streaming state machine. The pipeline (camera, microphone, encoders) runs only
 * while at least one authenticated viewer is connected, and is released shortly after the last
 * one leaves so the device drops back to its idle listener.
 */
class SessionManager(
    private val pipeline: MediaPipeline,
    private val scheduler: Scheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val observer: Observer = Observer { _, _ -> },
) : MediaSink {
    fun interface Observer {
        fun onSessionChanged(streaming: Boolean, viewers: List<String>)
    }

    private val clients = CopyOnWriteArrayList<ClientSink>()
    private val bitrate = BitrateController(clock)
    private var streaming = false
    private var pendingStop: Cancellable? = null
    private var lastKeyRequestAt = Long.MIN_VALUE / 2

    @Volatile private var videoConfig: String? = null
    @Volatile private var audioConfig: String? = null
    @Volatile private var pipelineState: String? = null

    private var talker: ClientSink? = null
    private var talkSampleRate = 0
    private var talkStartedAt = 0L
    private var talkBytes = 0L

    /** Consulted before admitting a viewer; lets the host refuse streaming, e.g. on low battery. */
    @Volatile
    var admission: () -> WsAdmission.Refused? = { null }

    /**
     * Admits a viewer, waking the pipeline for the first one. A page reconnecting after a network drop
     * takes over its old session, which may not have noticed the drop yet and would otherwise hold a
     * viewer slot until it times out.
     */
    fun open(channel: WsChannel): WsAdmission {
        var replaced: ClientSink? = null
        val admission = synchronized(this) {
            admission()?.let { return it }
            replaced = channel.clientId?.let { id -> clients.firstOrNull { it.channel.clientId == id } }
            replaced?.let(::evict)
            admit(channel)
        }
        // Closing a socket whose writer is stuck on a dead network can block, so it happens outside the lock.
        replaced?.channel?.abort()
        return admission
    }

    private fun evict(client: ClientSink) {
        clients.remove(client)
        if (talker === client) endTalk()
    }

    private fun admit(channel: WsChannel): WsAdmission {
        if (clients.size >= MAX_VIEWERS) return WsAdmission.Refused(CLOSE_TRY_AGAIN_LATER, "viewer limit reached")
        val client = ClientSink(channel, clock)
        pendingStop?.cancel()
        pendingStop = null

        val sentVideo = videoConfig?.also(channel::sendText)
        val sentAudio = audioConfig?.also(channel::sendText)
        pipelineState?.let(channel::sendText)
        clients.add(client)
        // A config published between the sends above and joining the list would otherwise be missed.
        videoConfig?.takeIf { it !== sentVideo }?.let(channel::sendText)
        audioConfig?.takeIf { it !== sentAudio }?.let(channel::sendText)

        if (!streaming) {
            streaming = true
            bitrate.reset()
            pipeline.start(this)
        } else {
            requestKeyFrame()
        }
        updateVideoPaused()
        notifyObserver()
        return WsAdmission.Accepted(ClientListener(client))
    }

    /** Disconnects everyone and releases the pipeline immediately, for when the service stops. */
    fun shutdown() = disconnectAll(1001, "server stopping")

    /** [code] tells the web client why; codes in the 4000 range stop it from reconnecting on its own. */
    @Synchronized
    fun disconnectAll(code: Int, reason: String) {
        pendingStop?.cancel()
        pendingStop = null
        clients.forEach { it.channel.close(code, reason) }
        clients.clear()
        stopStreaming()
    }

    @Synchronized
    private fun close(client: ClientSink) {
        if (!clients.remove(client)) return
        if (talker === client) endTalk()
        if (clients.isEmpty()) {
            // Reloading the page reconnects within moments; lingering avoids power-cycling the camera.
            pendingStop = scheduler.schedule(LINGER_MS) { stopIfIdle() }
        } else {
            updateVideoPaused()
        }
        notifyObserver()
    }

    @Synchronized
    private fun stopIfIdle() {
        pendingStop = null
        if (clients.isEmpty()) stopStreaming()
    }

    private fun stopStreaming() {
        if (!streaming) return
        streaming = false
        endTalk()
        pipeline.stop()
        videoConfig = null
        audioConfig = null
        pipelineState = null
        notifyObserver()
    }

    @Synchronized
    private fun handle(client: ClientSink, message: ControlMessage) {
        if (client !in clients) return
        when (message) {
            ControlMessage.SwitchCamera -> pipeline.switchCamera()
            ControlMessage.NeedKeyFrame -> {
                client.expectKeyFrame()
                requestKeyFrame()
            }
            is ControlMessage.Torch -> pipeline.setTorch(message.on)
            is ControlMessage.Quality -> {
                bitrate.reset()
                pipeline.setQuality(message.preset)
            }
            is ControlMessage.PauseVideo -> {
                client.videoPaused = message.paused
                updateVideoPaused()
                if (!message.paused) requestKeyFrame()
            }
            is ControlMessage.TalkStart -> startTalk(client, message.sampleRate)
            ControlMessage.TalkStop -> if (talker === client) endTalk()
        }
    }

    private fun startTalk(client: ClientSink, sampleRate: Int) {
        if (talker != null && talker !== client) {
            client.channel.sendText(WireProtocol.talk(false, "someone else is talking"))
            return
        }
        if (talker === client) endTalk()
        if (!pipeline.startTalkback(sampleRate)) {
            client.channel.sendText(WireProtocol.talk(false, "speaker unavailable"))
            return
        }
        talker = client
        talkSampleRate = sampleRate
        talkStartedAt = clock()
        talkBytes = 0
        client.channel.sendText(WireProtocol.talk(true))
    }

    private fun endTalk() {
        if (talker == null) return
        talker = null
        pipeline.stopTalkback()
    }

    @Synchronized
    private fun talkback(client: ClientSink, payload: ByteArray) {
        if (talker !== client || payload.size < 3) return
        // Caps the uplink at twice real time so a client cannot flood the speaker buffer.
        val elapsedMs = clock() - talkStartedAt + 1000
        val allowedBytes = elapsedMs * talkSampleRate * BYTES_PER_SAMPLE / 1000 * 2
        talkBytes += payload.size - 1
        if (talkBytes > allowedBytes) {
            talkBytes -= payload.size - 1
            return
        }
        pipeline.writeTalkback(payload, 1, payload.size - 1)
    }

    private fun updateVideoPaused() {
        pipeline.setVideoPaused(clients.isNotEmpty() && clients.all { it.videoPaused })
    }

    @Synchronized
    private fun requestKeyFrame() {
        val now = clock()
        if (!streaming || now - lastKeyRequestAt < KEY_REQUEST_INTERVAL_MS) return
        lastKeyRequestAt = now
        pipeline.requestKeyFrame()
    }

    private fun notifyObserver() = observer.onSessionChanged(streaming, clients.map { it.channel.remoteAddress })

    override fun onVideoConfig(config: String) {
        videoConfig = config
        for (client in clients) {
            client.expectKeyFrame()
            client.channel.sendText(config)
        }
    }

    override fun onVideoFrame(ptsUs: Long, avcc: ByteArray, keyFrame: Boolean) {
        if (clients.isEmpty()) return
        val type = if (keyFrame) WireProtocol.VIDEO_KEY else WireProtocol.VIDEO_DELTA
        val frame = WireProtocol.mediaFrame(type, ptsUs, avcc)
        var needKey = false
        var congested = false
        var delivered = false
        for (client in clients) {
            when (client.sendVideo(frame, keyFrame)) {
                ClientSink.Result.SENT -> delivered = true
                ClientSink.Result.NEED_KEY -> needKey = true
                ClientSink.Result.CONGESTED -> congested = true
                ClientSink.Result.SKIPPED, ClientSink.Result.DEAD -> Unit
            }
        }
        if (needKey) requestKeyFrame()
        val newScale = synchronized(bitrate) {
            if (congested) bitrate.onCongestion() else if (delivered) bitrate.onFrameDelivered() else null
        }
        newScale?.let(pipeline::setBitrateScale)
    }

    override fun onAudioConfig(config: String) {
        audioConfig = config
        clients.forEach { it.channel.sendText(config) }
    }

    override fun onAudioFrame(ptsUs: Long, payload: ByteArray) {
        if (clients.isEmpty()) return
        val frame = WireProtocol.mediaFrame(WireProtocol.AUDIO, ptsUs, payload)
        clients.forEach { it.sendAudio(frame) }
    }

    override fun onState(state: String) {
        pipelineState = state
        clients.forEach { it.channel.sendText(state) }
    }

    override fun onNotice(message: String) {
        val notice = WireProtocol.notice(message)
        clients.forEach { it.channel.sendText(notice) }
    }

    private inner class ClientListener(private val client: ClientSink) : WsConnection.Listener {
        override fun onText(text: String) {
            ControlMessage.parse(text)?.let { handle(client, it) }
        }

        override fun onBinary(payload: ByteArray) {
            if (payload.isNotEmpty() && payload[0].toInt() == WireProtocol.TALKBACK_PCM) talkback(client, payload)
        }

        override fun onClosed() = close(client)
    }

    companion object {
        const val MAX_VIEWERS = 4
        const val LINGER_MS = 2_000L
        const val KEY_REQUEST_INTERVAL_MS = 1_000L
        const val CLOSE_BATTERY_LOW = 4001
        const val CLOSE_TRY_AGAIN_LATER = 1013
        private const val BYTES_PER_SAMPLE = 2L
    }
}
