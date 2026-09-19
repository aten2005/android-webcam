package dev.aten.webcam.session

/** The camera/microphone/speaker side, driven by [SessionManager]. Calls must not block for long. */
interface MediaPipeline {
    fun start(sink: MediaSink)
    fun stop()
    fun requestKeyFrame()

    /** True while no viewer is displaying video, so the camera can idle while audio continues. */
    fun setVideoPaused(paused: Boolean)

    /** Scales the quality preset's bitrate, 1.0 meaning the full target. */
    fun setBitrateScale(scale: Float)
    fun switchCamera()
    fun setTorch(on: Boolean)
    fun setQuality(preset: String)

    fun startTalkback(sampleRate: Int): Boolean
    fun writeTalkback(pcm: ByteArray, offset: Int, length: Int)
    fun stopTalkback()
}

/** Encoded output and status, pushed from the pipeline's own threads. */
interface MediaSink {
    fun onVideoConfig(config: String)
    fun onVideoFrame(ptsUs: Long, avcc: ByteArray, keyFrame: Boolean)
    fun onAudioConfig(config: String)
    fun onAudioFrame(ptsUs: Long, payload: ByteArray)
    fun onState(state: String)
    fun onNotice(message: String)
}

fun interface Cancellable {
    fun cancel()
}

fun interface Scheduler {
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}
