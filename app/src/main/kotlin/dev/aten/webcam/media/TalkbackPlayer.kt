package dev.aten.webcam.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Plays the viewer's microphone on the device speaker. The media stream is used rather than the
 * voice-call one: it is louder, follows the media volume, and does not switch the audio mode,
 * which would degrade the far-field microphone pickup.
 */
class TalkbackPlayer {
    private var track: AudioTrack? = null

    @Synchronized
    fun start(sampleRate: Int): Boolean {
        stop()
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) return false
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
                .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * BYTES_PER_SAMPLE / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                return false
            }
            created.play()
            track = created
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Never blocks the network thread: when the speaker buffer is full the excess audio is dropped. */
    @Synchronized
    fun write(pcm: ByteArray, offset: Int, length: Int) {
        val evenLength = length and 1.inv()
        if (evenLength > 0) track?.write(pcm, offset, evenLength, AudioTrack.WRITE_NON_BLOCKING)
    }

    @Synchronized
    fun stop() {
        val current = track ?: return
        track = null
        try {
            current.pause()
            current.flush()
        } catch (_: IllegalStateException) {
        }
        current.release()
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
    }
}
