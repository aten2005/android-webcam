package dev.aten.webcam.session

import org.json.JSONException
import org.json.JSONObject

/** Commands sent by the web client. Anything that does not validate is ignored. */
sealed class ControlMessage {
    data object SwitchCamera : ControlMessage()
    data object NeedKeyFrame : ControlMessage()
    data object TalkStop : ControlMessage()
    data class Torch(val on: Boolean) : ControlMessage()
    data class Quality(val preset: String) : ControlMessage()
    data class PauseVideo(val paused: Boolean) : ControlMessage()
    data class TalkStart(val sampleRate: Int) : ControlMessage()

    companion object {
        const val MAX_LENGTH = 1024
        val QUALITY_PRESETS = listOf("low", "medium", "high")
        val TALK_SAMPLE_RATES = 8000..48000

        fun parse(text: String): ControlMessage? {
            if (text.length > MAX_LENGTH) return null
            return try {
                val json = JSONObject(text)
                when (json.getString("type")) {
                    "switchCamera" -> SwitchCamera
                    "needKey" -> NeedKeyFrame
                    "talkStop" -> TalkStop
                    "torch" -> Torch(json.getBoolean("on"))
                    "pauseVideo" -> PauseVideo(json.getBoolean("paused"))
                    "quality" -> json.getString("preset").takeIf { it in QUALITY_PRESETS }?.let(::Quality)
                    "talkStart" -> json.getInt("sampleRate").takeIf { it in TALK_SAMPLE_RATES }?.let(::TalkStart)
                    else -> null
                }
            } catch (_: JSONException) {
                null
            }
        }
    }
}
