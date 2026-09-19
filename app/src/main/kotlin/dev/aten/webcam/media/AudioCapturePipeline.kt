package dev.aten.webcam.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import kotlin.concurrent.thread

/**
 * Microphone to Opus (AAC when the device has no Opus encoder). A single thread blocks on the
 * microphone, feeding 20 ms chunks straight into the codec's input buffers.
 */
class AudioCapturePipeline(private val listener: Listener) {
    interface Listener {
        /** [description] is the AudioSpecificConfig for AAC, and null for Opus (raw packets need none). */
        fun onAudioConfig(codec: String, sampleRate: Int, channels: Int, description: ByteArray?)
        fun onAudioFrame(ptsUs: Long, payload: ByteArray)
        fun onAudioError(message: String)
    }

    @Volatile
    private var running = false
    private var worker: Thread? = null

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "audio-capture") { captureLoop() }
    }

    fun stop() {
        running = false
        worker?.join(STOP_TIMEOUT_MS)
        worker = null
    }

    @SuppressLint("MissingPermission") // The controller only starts audio when RECORD_AUDIO is granted.
    private fun captureLoop() {
        var record: AudioRecord? = null
        var codec: MediaCodec? = null
        try {
            record = createRecord() ?: throw IllegalStateException("microphone unavailable")
            val opus = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BITRATE)
            }
            val opusEncoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(opus)
            val useOpus = opusEncoder != null
            if (useOpus) {
                codec = MediaCodec.createByCodecName(opusEncoder!!)
                codec.configure(opus, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                listener.onAudioConfig("opus", SAMPLE_RATE, 1, null)
            } else {
                val aac = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec.configure(aac, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            codec.start()
            record.startRecording()

            val info = MediaCodec.BufferInfo()
            var samplesQueued = 0L
            while (running) {
                val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex)!!
                    buffer.clear()
                    val read = record.read(buffer, minOf(CHUNK_BYTES, buffer.capacity()))
                    if (read < 0) throw IllegalStateException("microphone read failed ($read)")
                    val ptsUs = samplesQueued * 1_000_000L / SAMPLE_RATE
                    codec.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
                    samplesQueued += read / BYTES_PER_SAMPLE
                }
                drain(codec, info, useOpus)
            }
        } catch (e: Exception) {
            if (running) listener.onAudioError("audio capture failed: ${e.message}")
        } finally {
            running = false
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            record?.release()
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, isOpus: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!isOpus) {
                    val csd = codec.outputFormat.getByteBuffer("csd-0")
                    val description = csd?.let { ByteArray(it.remaining()).also(it.duplicate()::get) }
                    listener.onAudioConfig("mp4a.40.2", SAMPLE_RATE, 1, description)
                }
                continue
            }
            if (index < 0) return
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!isConfig && info.size > 0) {
                val buffer = codec.getOutputBuffer(index)!!
                val payload = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(payload)
                listener.onAudioFrame(info.presentationTimeUs, payload)
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        // CAMCORDER is tuned for far-field pickup; voice-call sources gate out the room sound a monitor wants.
        for (source in intArrayOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)) {
            val record = try {
                AudioRecord(
                    source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, CHUNK_BYTES * 4),
                )
            } catch (_: IllegalArgumentException) {
                continue
            }
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
        }
        return null
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val BYTES_PER_SAMPLE = 2
        private const val CHUNK_BYTES = SAMPLE_RATE / 50 * BYTES_PER_SAMPLE
        private const val OPUS_BITRATE = 32_000
        private const val AAC_BITRATE = 64_000
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val STOP_TIMEOUT_MS = 1_000L
    }
}
