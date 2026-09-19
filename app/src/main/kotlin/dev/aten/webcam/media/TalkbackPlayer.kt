package dev.aten.webcam.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * Plays the viewer's microphone on the device speaker. The media stream is used rather than the
 * voice-call one: it is louder, follows the media volume, and does not switch the audio mode,
 * which would degrade the far-field microphone pickup.
 *
 * [releaseLater] runs a block after a delay, so a stopped track can play out what it still holds.
 */
class TalkbackPlayer(private val releaseLater: (delayMs: Long, release: () -> Unit) -> Unit) {
    private var track: AudioTrack? = null
    private var writeFailed = false

    @Synchronized
    fun start(sampleRate: Int): Boolean {
        stop()
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) return false
            val prerollFrames = sampleRate * PREROLL_MS / 1000
            // A track only starts playing once its start threshold is buffered, which defaults to the
            // whole buffer. Before API 31 the threshold is fixed, so the buffer itself has to be short.
            val bufferMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BUFFER_MS else PREROLL_MS * 2
            val created = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * bufferMs / 1000 * BYTES_PER_SAMPLE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) created.setStartThresholdInFrames(prerollFrames)
            created.play()
            track = created
            writeFailed = false
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not open the talkback track at $sampleRate Hz", e)
            false
        }
    }

    /** Never blocks the network thread: when the speaker buffer is full the excess audio is dropped. */
    @Synchronized
    fun write(pcm: ByteArray, offset: Int, length: Int) {
        val current = track ?: return
        val evenLength = length and 1.inv()
        if (evenLength <= 0) return
        val written = current.write(pcm, offset, evenLength, AudioTrack.WRITE_NON_BLOCKING)
        if (written < 0 && !writeFailed) {
            writeFailed = true
            Log.w(TAG, "talkback write failed: $written")
        }
    }

    @Synchronized
    fun stop() {
        val current = track ?: return
        track = null
        try {
            // Unlike pause() and flush(), stop() plays out the buffered end of the sentence.
            current.stop()
            Log.d(TAG, "talkback stopped after ${current.underrunCount} underruns")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "talkback track was already unusable", e)
        }
        releaseLater(DRAIN_MS) { current.release() }
    }

    private companion object {
        const val TAG = "TalkbackPlayer"
        const val BYTES_PER_SAMPLE = 2
        const val BUFFER_MS = 500
        const val PREROLL_MS = 120
        const val DRAIN_MS = BUFFER_MS + 100L
    }
}
