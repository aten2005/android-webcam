package dev.aten.webcam.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import dev.aten.webcam.session.MediaPipeline
import dev.aten.webcam.session.MediaSink
import dev.aten.webcam.session.PipelineState
import dev.aten.webcam.session.WireProtocol

/**
 * Android implementation of the pipeline the session layer drives. Calls return immediately; the
 * work is serialized on one handler thread, which sits parked (costing nothing) during standby.
 */
class MediaController(
    private val context: Context,
    private val defaultQuality: () -> String,
) : MediaPipeline, VideoPipeline.Listener, AudioCapturePipeline.Listener {
    private val thread = HandlerThread("media").apply { start() }
    private val handler = Handler(thread.looper)
    private val video = VideoPipeline(context, handler, this)
    private val audio = AudioCapturePipeline(this)

    // Written on the handler thread, read from the audio thread as well.
    @Volatile
    private var sink: MediaSink? = null
    private var preset = QualityPreset.byName(defaultQuality())
    private var facing = CameraSelector.BACK
    private var bitrateScale = 1f
    private var videoPaused = false

    override fun start(sink: MediaSink) = post {
        this.sink = sink
        preset = QualityPreset.byName(defaultQuality())
        bitrateScale = 1f
        videoPaused = false
        startVideo()
        if (granted(Manifest.permission.RECORD_AUDIO)) audio.start()
        publishState()
    }

    override fun stop() = post {
        video.stop()
        audio.stop()
        sink = null
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    override fun requestKeyFrame() = post { video.requestKeyFrame() }

    override fun setVideoPaused(paused: Boolean) = post {
        videoPaused = paused
        video.setPaused(paused)
    }

    override fun setBitrateScale(scale: Float) = post {
        bitrateScale = scale
        video.setBitrate(scaledBitrate())
    }

    override fun switchCamera() = post {
        val facings = video.availableFacings()
        if (facings.size < 2) return@post
        facing = facings[(facings.indexOf(video.activeFacing ?: facing) + 1) % facings.size]
        startVideo()
        publishState()
    }

    override fun setTorch(on: Boolean) = post {
        video.setTorch(on)
        publishState()
    }

    override fun setQuality(preset: String) = post {
        this.preset = QualityPreset.byName(preset)
        bitrateScale = 1f
        startVideo()
        publishState()
    }

    override fun startTalkback(sampleRate: Int): Boolean = false

    override fun writeTalkback(pcm: ByteArray, offset: Int, length: Int) = Unit

    override fun stopTalkback() = Unit

    override fun onVideoConfig(sets: NalUtils.ParameterSets, width: Int, height: Int, rotation: Int, facing: String) {
        val config = WireProtocol.videoConfig(
            NalUtils.codecString(sets.sps), NalUtils.buildAvcC(sets), width, height, rotation, facing,
        )
        sink?.onVideoConfig(config)
    }

    override fun onVideoFrame(ptsUs: Long, avcc: ByteArray, keyFrame: Boolean) {
        sink?.onVideoFrame(ptsUs, avcc, keyFrame)
    }

    override fun onVideoError(message: String) {
        sink?.onNotice(message)
        publishState()
    }

    override fun onCameraLost() {
        sink?.onNotice("Camera is in use by another app. Video resumes when it is released.")
    }

    override fun onCameraReturned() {
        if (sink == null) return
        startVideo()
        publishState()
    }

    override fun onAudioConfig(codec: String, sampleRate: Int, channels: Int, description: ByteArray?) {
        sink?.onAudioConfig(WireProtocol.audioConfig(codec, sampleRate, channels, description))
    }

    override fun onAudioFrame(ptsUs: Long, payload: ByteArray) {
        sink?.onAudioFrame(ptsUs, payload)
    }

    override fun onAudioError(message: String) = post {
        sink?.onNotice(message)
        publishState()
    }

    private fun startVideo() {
        if (!granted(Manifest.permission.CAMERA)) {
            sink?.onNotice("Camera permission has not been granted on the device.")
            return
        }
        if (video.start(facing, preset, scaledBitrate())) video.setPaused(videoPaused)
    }

    private fun scaledBitrate() = (preset.bitrate * bitrateScale).toInt()

    private fun publishState() {
        val state = PipelineState(
            cameras = video.availableFacings(),
            facing = video.activeFacing ?: facing,
            torchAvailable = video.torchAvailable,
            torch = video.torchOn,
            quality = preset.name,
            audioAvailable = audio.isRunning,
        )
        sink?.onState(WireProtocol.state(state))
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun post(block: () -> Unit) {
        handler.post(block)
    }
}
