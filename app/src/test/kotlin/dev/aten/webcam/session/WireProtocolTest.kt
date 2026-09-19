package dev.aten.webcam.session

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class WireProtocolTest {
    @Test
    fun framesMediaWithTypeAndBigEndianTimestamp() {
        val frame = WireProtocol.mediaFrame(WireProtocol.VIDEO_KEY, 0x0102030405060708, byteArrayOf(9, 8))
        val expected = byteArrayOf(0x82.toByte(), 11, 1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 8)
        assertArrayEquals(expected, frame)
    }

    @Test
    fun describesVideoAndAudioConfig() {
        val video = JSONObject(WireProtocol.videoConfig("avc1.42c01f", byteArrayOf(1, 2, 3), 1280, 720, 90, "back"))
        assertEquals("videoConfig", video.getString("type"))
        assertArrayEquals(byteArrayOf(1, 2, 3), Base64.getDecoder().decode(video.getString("description")))
        assertEquals(90, video.getInt("rotation"))

        val opus = JSONObject(WireProtocol.audioConfig("opus", 48000, 1, null))
        assertFalse(opus.has("description"))
        assertEquals(48000, opus.getInt("sampleRate"))
    }

    @Test
    fun describesPipelineState() {
        val state = PipelineState(listOf("back", "front"), "back", torchAvailable = true, torch = false, "medium", true)
        val json = JSONObject(WireProtocol.state(state))
        assertEquals(2, json.getJSONArray("cameras").length())
        assertEquals("medium", json.getString("quality"))
    }

    @Test
    fun describesHeartbeat() = assertEquals("heartbeat", JSONObject(WireProtocol.heartbeat()).getString("type"))

    @Test
    fun parsesValidControlMessages() {
        assertEquals(ControlMessage.SwitchCamera, ControlMessage.parse("""{"type":"switchCamera"}"""))
        assertEquals(ControlMessage.NeedKeyFrame, ControlMessage.parse("""{"type":"needKey"}"""))
        assertEquals(ControlMessage.Torch(true), ControlMessage.parse("""{"type":"torch","on":true}"""))
        assertEquals(ControlMessage.Quality("low"), ControlMessage.parse("""{"type":"quality","preset":"low"}"""))
        assertEquals(ControlMessage.PauseVideo(true), ControlMessage.parse("""{"type":"pauseVideo","paused":true}"""))
        assertEquals(ControlMessage.TalkStart(16000), ControlMessage.parse("""{"type":"talkStart","sampleRate":16000}"""))
        assertEquals(ControlMessage.TalkStop, ControlMessage.parse("""{"type":"talkStop"}"""))
    }

    @Test
    fun ignoresInvalidControlMessages() {
        assertNull(ControlMessage.parse("not json"))
        assertNull(ControlMessage.parse("""{"no":"type"}"""))
        assertNull(ControlMessage.parse("""{"type":"reboot"}"""))
        assertNull(ControlMessage.parse("""{"type":"torch"}"""))
        assertNull(ControlMessage.parse("""{"type":"quality","preset":"ultra"}"""))
        assertNull(ControlMessage.parse("""{"type":"talkStart","sampleRate":1000000}"""))
        assertNull(ControlMessage.parse("""{"type":"needKey","pad":"${"x".repeat(2000)}"}"""))
    }
}
