package dev.aten.webcam.session

import dev.aten.webcam.server.WsChannel
import dev.aten.webcam.server.WsConnection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {
    private var now = 0L

    private class FakeChannel(override val remoteAddress: String) : WsChannel {
        override val userAgent = "test"
        override var queuedBytes = 0L
        var accepting = true
        var aborted = false
        var closeCode: Int? = null
        val texts = ArrayList<String>()
        val frames = ArrayList<ByteArray>()

        override fun offer(frame: ByteArray): Boolean {
            if (accepting) frames.add(frame)
            return accepting
        }

        override fun sendText(text: String) {
            texts.add(text)
        }

        override fun close(code: Int, reason: String) {
            closeCode = code
        }

        override fun abort() {
            aborted = true
        }

        fun videoTypes() = frames.map { it[it.size - PAYLOAD.size - 9].toInt() }
        fun lastJson() = JSONObject(texts.last())
    }

    private class FakePipeline : MediaPipeline {
        val calls = ArrayList<String>()
        var sink: MediaSink? = null
        var talkbackAvailable = true
        var talkbackBytes = 0

        override fun start(sink: MediaSink) {
            this.sink = sink
            calls.add("start")
        }

        override fun stop() {
            sink = null
            calls.add("stop")
        }

        override fun requestKeyFrame() { calls.add("key") }
        override fun setVideoPaused(paused: Boolean) { calls.add("paused=$paused") }
        override fun setBitrateScale(scale: Float) { calls.add("scale=%.2f".format(scale)) }
        override fun switchCamera() { calls.add("switch") }
        override fun setTorch(on: Boolean) { calls.add("torch=$on") }
        override fun setQuality(preset: String) { calls.add("quality=$preset") }
        override fun startTalkback(sampleRate: Int): Boolean {
            calls.add("talkStart=$sampleRate")
            return talkbackAvailable
        }

        override fun writeTalkback(pcm: ByteArray, offset: Int, length: Int) {
            talkbackBytes += length
        }

        override fun stopTalkback() { calls.add("talkStop") }

        fun count(call: String) = calls.count { it == call }
    }

    private class FakeScheduler : Scheduler {
        val tasks = ArrayList<Pair<Long, () -> Unit>>()
        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val entry = delayMs to task
            tasks.add(entry)
            return Cancellable { tasks.remove(entry) }
        }

        fun runAll() = tasks.toList().also { tasks.clear() }.forEach { it.second() }
    }

    private val pipeline = FakePipeline()
    private val scheduler = FakeScheduler()
    private val sessionEvents = ArrayList<Pair<Boolean, List<String>>>()
    private val manager = SessionManager(pipeline, scheduler, { now }) { streaming, viewers ->
        sessionEvents.add(streaming to viewers)
    }

    private fun connect(address: String = "10.0.0.2"): Pair<FakeChannel, WsConnection.Listener> {
        val channel = FakeChannel(address)
        return channel to manager.open(channel)!!
    }

    private fun video(key: Boolean) = manager.onVideoFrame(now * 1000, PAYLOAD, key)

    @Test
    fun startsPipelineForFirstViewerAndStopsAfterLinger() {
        val (_, first) = connect("10.0.0.2")
        val (_, second) = connect("10.0.0.3")
        assertEquals(1, pipeline.count("start"))
        assertEquals(true to listOf("10.0.0.2", "10.0.0.3"), sessionEvents.last())

        first.onClosed()
        assertTrue(scheduler.tasks.isEmpty())
        second.onClosed()
        assertEquals(0, pipeline.count("stop"))
        assertEquals(SessionManager.LINGER_MS, scheduler.tasks.single().first)

        scheduler.runAll()
        assertEquals(1, pipeline.count("stop"))
        assertEquals(false to emptyList<String>(), sessionEvents.last())
    }

    @Test
    fun reconnectDuringLingerKeepsPipelineRunning() {
        val (_, listener) = connect()
        listener.onClosed()
        connect()
        assertTrue(scheduler.tasks.isEmpty())
        assertEquals(1, pipeline.count("start"))
        assertEquals(0, pipeline.count("stop"))
    }

    @Test
    fun restartsPipelineForViewerAfterStandby() {
        val (_, listener) = connect()
        listener.onClosed()
        scheduler.runAll()
        connect()
        assertEquals(2, pipeline.count("start"))
    }

    @Test
    fun refusesViewersBeyondLimit() {
        repeat(SessionManager.MAX_VIEWERS) { connect("10.0.0.$it") }
        assertNull(manager.open(FakeChannel("10.0.0.99")))
    }

    @Test
    fun withholdsDeltasUntilKeyFrame() {
        val (channel, _) = connect()
        video(key = false)
        assertTrue(channel.frames.isEmpty())
        video(key = true)
        video(key = false)
        assertEquals(listOf(WireProtocol.VIDEO_KEY, WireProtocol.VIDEO_DELTA), channel.videoTypes())
    }

    @Test
    fun lateJoinerGetsCachedConfigAndTriggersKeyFrame() {
        connect()
        manager.onVideoConfig("""{"type":"videoConfig"}""")
        manager.onAudioConfig("""{"type":"audioConfig"}""")
        manager.onState("""{"type":"state"}""")
        now += 5000
        val (late, _) = connect("10.0.0.9")
        assertEquals(listOf("videoConfig", "audioConfig", "state"), late.texts.map { JSONObject(it).getString("type") })
        assertEquals(1, pipeline.count("key"))
    }

    @Test
    fun keyFrameRequestsAreRateLimited() {
        val (_, listener) = connect()
        now += 5000
        repeat(5) { listener.onText("""{"type":"needKey"}""") }
        assertEquals(1, pipeline.count("key"))
        now += SessionManager.KEY_REQUEST_INTERVAL_MS
        listener.onText("""{"type":"needKey"}""")
        assertEquals(2, pipeline.count("key"))
    }

    @Test
    fun newVideoConfigMakesViewersWaitForKeyFrame() {
        val (channel, _) = connect()
        video(key = true)
        manager.onVideoConfig("""{"type":"videoConfig"}""")
        video(key = false)
        assertEquals(1, channel.frames.size)
    }

    @Test
    fun congestedViewerDropsFramesLowersBitrateAndRecovers() {
        val (slow, _) = connect("10.0.0.2")
        val (fast, _) = connect("10.0.0.3")
        video(key = true)
        slow.queuedBytes = ClientSink.MAX_BACKLOG_BYTES + 1
        now += 100
        video(key = false)
        assertEquals(1, slow.frames.size)
        assertEquals(2, fast.frames.size)
        assertEquals(1, pipeline.count("scale=0.70"))

        slow.queuedBytes = ClientSink.MAX_BACKLOG_BYTES / 2
        now += 100
        video(key = true)
        assertEquals("hysteresis keeps the viewer paused until the backlog drains", 1, slow.frames.size)

        slow.queuedBytes = 0
        now += 100
        video(key = false)
        assertEquals(1, slow.frames.size)
        now += 100
        video(key = true)
        assertEquals(2, slow.frames.size)

        now += BitrateController.INCREASE_INTERVAL_MS
        video(key = false)
        val scales = pipeline.calls.filter { it.startsWith("scale=") }.map { it.substringAfter('=').toFloat() }
        assertEquals(2, scales.size)
        assertTrue(scales[1] > scales[0] && scales[1] < 1f)
    }

    @Test
    fun stalledViewerIsAborted() {
        val (channel, _) = connect()
        channel.queuedBytes = ClientSink.MAX_BACKLOG_BYTES + 1
        video(key = true)
        now += ClientSink.STALL_TIMEOUT_MS
        video(key = true)
        assertFalse(channel.aborted)
        now += 1
        video(key = true)
        assertTrue(channel.aborted)
        assertTrue(channel.frames.isEmpty())
    }

    @Test
    fun pausesCameraOnlyWhenEveryViewerPaused() {
        val (first, firstListener) = connect("10.0.0.2")
        val (_, secondListener) = connect("10.0.0.3")
        firstListener.onText("""{"type":"pauseVideo","paused":true}""")
        assertEquals("paused=false", pipeline.calls.last { it.startsWith("paused") })
        secondListener.onText("""{"type":"pauseVideo","paused":true}""")
        assertEquals("paused=true", pipeline.calls.last { it.startsWith("paused") })

        video(key = true)
        assertTrue(first.frames.isEmpty())
        manager.onAudioFrame(0, PAYLOAD)
        assertEquals(1, first.frames.size)

        now += 5000
        val keyRequestsBefore = pipeline.count("key")
        firstListener.onText("""{"type":"pauseVideo","paused":false}""")
        assertEquals("paused=false", pipeline.calls.last { it.startsWith("paused") })
        assertEquals(keyRequestsBefore + 1, pipeline.count("key"))
    }

    @Test
    fun forwardsCameraControls() {
        val (_, listener) = connect()
        listener.onText("""{"type":"switchCamera"}""")
        listener.onText("""{"type":"torch","on":true}""")
        listener.onText("""{"type":"quality","preset":"high"}""")
        listener.onText("""{"type":"bogus"}""")
        assertEquals(listOf("switch", "torch=true", "quality=high"), pipeline.calls.takeLast(3))
    }

    @Test
    fun grantsTalkbackToOneViewerAtATime() {
        val (first, firstListener) = connect("10.0.0.2")
        val (second, secondListener) = connect("10.0.0.3")
        firstListener.onText("""{"type":"talkStart","sampleRate":16000}""")
        assertTrue(first.lastJson().getBoolean("granted"))
        secondListener.onText("""{"type":"talkStart","sampleRate":16000}""")
        assertFalse(second.lastJson().getBoolean("granted"))

        val pcm = ByteArray(321).also { it[0] = WireProtocol.TALKBACK_PCM.toByte() }
        secondListener.onBinary(pcm)
        assertEquals(0, pipeline.talkbackBytes)
        firstListener.onBinary(pcm)
        assertEquals(320, pipeline.talkbackBytes)

        firstListener.onClosed()
        assertEquals(1, pipeline.count("talkStop"))
        secondListener.onText("""{"type":"talkStart","sampleRate":16000}""")
        assertTrue(second.lastJson().getBoolean("granted"))
    }

    @Test
    fun reportsUnavailableSpeaker() {
        pipeline.talkbackAvailable = false
        val (channel, listener) = connect()
        listener.onText("""{"type":"talkStart","sampleRate":16000}""")
        assertFalse(channel.lastJson().getBoolean("granted"))
        assertEquals(0, pipeline.count("talkStop"))
    }

    @Test
    fun capsTalkbackAtTwiceRealTime() {
        val (_, listener) = connect()
        listener.onText("""{"type":"talkStart","sampleRate":16000}""")
        val chunk = ByteArray(16_001).also { it[0] = WireProtocol.TALKBACK_PCM.toByte() }
        repeat(10) { listener.onBinary(chunk) }
        // One second of budget is granted up front: 16 kHz * 2 bytes * 2x = 64000 bytes.
        assertEquals(64_000, pipeline.talkbackBytes)
        now += 1000
        repeat(10) { listener.onBinary(chunk) }
        assertEquals(128_000, pipeline.talkbackBytes)
    }

    @Test
    fun shutdownClosesViewersAndStopsImmediately() {
        val (channel, _) = connect()
        manager.shutdown()
        assertEquals(1001, channel.closeCode)
        assertEquals(1, pipeline.count("stop"))
        assertNotNull(sessionEvents.lastOrNull { !it.first })
    }

    @Test
    fun bitrateControllerStaysWithinBounds() {
        val controller = BitrateController { now }
        repeat(20) {
            controller.onCongestion()
            now += BitrateController.DECREASE_INTERVAL_MS
        }
        assertEquals(BitrateController.MIN_SCALE, controller.scale)
        repeat(30) {
            now += BitrateController.INCREASE_INTERVAL_MS
            controller.onFrameDelivered()
        }
        assertEquals(1f, controller.scale)
        assertNull(controller.onFrameDelivered())
    }

    private companion object {
        val PAYLOAD = ByteArray(200) { 1 }
    }
}
