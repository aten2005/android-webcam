package dev.aten.webcam.session

import dev.aten.webcam.server.WsFrameCodec
import dev.aten.webcam.server.WsOpcode
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Media travels as binary WebSocket messages: `[type:1][pts:8, big-endian microseconds][payload]`.
 * Talkback audio from the browser is `[TALKBACK_PCM:1][16-bit little-endian PCM]`.
 * Everything else is JSON text.
 */
object WireProtocol {
    const val VIDEO_KEY = 1
    const val VIDEO_DELTA = 2
    const val AUDIO = 3
    const val TALKBACK_PCM = 16

    /** Returns a complete WebSocket frame, ready to be shared between every viewer's queue. */
    fun mediaFrame(type: Int, ptsUs: Long, payload: ByteArray): ByteArray {
        val header = ByteArray(9)
        header[0] = type.toByte()
        for (i in 0 until 8) header[1 + i] = (ptsUs ushr (8 * (7 - i))).toByte()
        return WsFrameCodec.encode(WsOpcode.BINARY, header, payload)
    }

    fun videoConfig(codec: String, description: ByteArray, width: Int, height: Int, rotation: Int, facing: String): String =
        JSONObject()
            .put("type", "videoConfig")
            .put("codec", codec)
            .put("description", Base64.getEncoder().encodeToString(description))
            .put("width", width)
            .put("height", height)
            .put("rotation", rotation)
            .put("facing", facing)
            .toString()

    fun audioConfig(codec: String, sampleRate: Int, channels: Int, description: ByteArray?): String =
        JSONObject()
            .put("type", "audioConfig")
            .put("codec", codec)
            .put("sampleRate", sampleRate)
            .put("channels", channels)
            .apply { description?.let { put("description", Base64.getEncoder().encodeToString(it)) } }
            .toString()

    fun state(state: PipelineState): String =
        JSONObject()
            .put("type", "state")
            .put("cameras", JSONArray(state.cameras))
            .put("facing", state.facing)
            .put("torchAvailable", state.torchAvailable)
            .put("torch", state.torch)
            .put("quality", state.quality)
            .put("audio", state.audioAvailable)
            .toString()

    fun notice(message: String): String = JSONObject().put("type", "notice").put("message", message).toString()

    fun talk(granted: Boolean, reason: String? = null): String =
        JSONObject().put("type", "talk").put("granted", granted).apply { reason?.let { put("reason", it) } }.toString()
}

class PipelineState(
    val cameras: List<String>,
    val facing: String,
    val torchAvailable: Boolean,
    val torch: Boolean,
    val quality: String,
    val audioAvailable: Boolean,
)
