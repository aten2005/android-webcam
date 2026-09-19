package dev.aten.webcam.media

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.view.Display
import android.view.Surface

/**
 * Camera2 renders straight into the hardware H.264 encoder's input surface, so no pixel data ever
 * touches the CPU. Every method must be called on [handler]'s thread; callbacks arrive there too.
 */
class VideoPipeline(
    private val context: Context,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onVideoConfig(sets: NalUtils.ParameterSets, width: Int, height: Int, rotation: Int, facing: String)
        fun onVideoFrame(ptsUs: Long, avcc: ByteArray, keyFrame: Boolean)
        fun onVideoError(message: String)

        /** Another app took the camera; [onCameraReturned] follows once it is free again. */
        fun onCameraLost()
        fun onCameraReturned()
    }

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private var choice: CameraChoice? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var torch = false
    private var paused = false
    private var evicted = false
    private var firstPtsUs = -1L
    private var configSent = false

    /** Identifies the current start; callbacks from a previous camera/encoder are ignored. */
    private var generation = 0

    val activeFacing: String? get() = choice?.facing
    val torchAvailable: Boolean get() = choice?.hasFlash == true
    val torchOn: Boolean get() = torch && torchAvailable

    fun availableFacings(): List<String> = try {
        CameraSelector.camerasByFacing(cameraManager).keys.toList()
    } catch (_: CameraAccessException) {
        emptyList()
    }

    @SuppressLint("MissingPermission") // The controller only starts video when CAMERA is granted.
    fun start(facing: String, preset: QualityPreset, bitrate: Int): Boolean {
        stop()
        val currentGeneration = ++generation
        try {
            val encoder = MediaCodec.createEncoderByType(MIME)
            codec = encoder
            val capabilities = encoder.codecInfo.getCapabilitiesForType(MIME).videoCapabilities
            val chosen = capabilities?.let { CameraSelector.choose(cameraManager, facing, preset, it) }
            if (chosen == null) {
                fail("no usable camera")
                return false
            }
            choice = chosen
            if (!chosen.hasFlash) torch = false

            val format = MediaFormat.createVideoFormat(MIME, chosen.size.width, chosen.size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, chosen.fpsRange.upper)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INTERVAL_S)
                // VBR lets a static scene shrink to a trickle, which keeps the radio mostly idle.
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            encoder.setCallback(EncoderCallback(currentGeneration), handler)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()
            cameraManager.openCamera(chosen.id, CameraCallback(currentGeneration), handler)
            return true
        } catch (e: Exception) {
            fail("video start failed: ${e.message}")
            return false
        }
    }

    fun stop() {
        generation++
        handler.removeCallbacks(retryAfterEviction)
        if (evicted) {
            cameraManager.unregisterAvailabilityCallback(availabilityCallback)
            evicted = false
        }
        closeQuietly { session?.close() }
        closeQuietly { camera?.close() }
        closeQuietly { codec?.stop() }
        closeQuietly { codec?.release() }
        closeQuietly { inputSurface?.release() }
        session = null
        camera = null
        codec = null
        inputSurface = null
        choice = null
        firstPtsUs = -1L
        configSent = false
    }

    fun requestKeyFrame() = setParameter(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)

    fun setBitrate(bitsPerSecond: Int) = setParameter(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitsPerSecond)

    fun setTorch(on: Boolean) {
        torch = on && torchAvailable
        if (!paused) startRepeating()
    }

    /** With no viewer watching, the sensor stops streaming; the open camera and encoder resume instantly. */
    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        if (value) closeQuietly { session?.stopRepeating() } else startRepeating()
    }

    private fun setParameter(key: String, value: Int) {
        closeQuietly { codec?.setParameters(Bundle().apply { putInt(key, value) }) }
    }

    private fun startRepeating() {
        val activeSession = session ?: return
        val device = camera ?: return
        val surface = inputSurface ?: return
        val chosen = choice ?: return
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosen.fpsRange)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                // The torch must be part of the request; CameraManager.setTorchMode fails while the camera is open.
                set(
                    CaptureRequest.FLASH_MODE,
                    if (torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
                )
            }
            activeSession.setRepeatingRequest(request.build(), null, handler)
        } catch (e: Exception) {
            fail("camera request failed: ${e.message}")
        }
    }

    private fun rotationDegrees(chosen: CameraChoice): Int {
        // Read once per start instead of tracking the orientation sensor, which would cost power.
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val deviceDegrees = when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (chosen.facing == CameraSelector.FRONT) {
            (chosen.sensorOrientation + deviceDegrees) % 360
        } else {
            (chosen.sensorOrientation - deviceDegrees + 360) % 360
        }
    }

    private fun fail(message: String) {
        stop()
        listener.onVideoError(message)
    }

    private inline fun closeQuietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    private val retryAfterEviction = Runnable { listener.onCameraReturned() }

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (!evicted) return
            // Give the other app a moment to finish releasing before we take the camera back.
            handler.removeCallbacks(retryAfterEviction)
            handler.postDelayed(retryAfterEviction, REOPEN_DELAY_MS)
        }
    }

    private inner class CameraCallback(private val owner: Int) : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            val surface = inputSurface
            if (owner != generation || surface == null) {
                device.close()
                return
            }
            camera = device
            try {
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(surface)),
                        { command -> handler.post(command) },
                        SessionCallback(owner),
                    ),
                )
            } catch (e: Exception) {
                fail("camera session failed: ${e.message}")
            }
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            if (owner != generation) return
            // Another app took the camera. Wait for it to come back rather than fighting over it.
            stop()
            evicted = true
            cameraManager.registerAvailabilityCallback(availabilityCallback, handler)
            listener.onCameraLost()
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            if (owner == generation) fail("camera error $error")
        }
    }

    private inner class SessionCallback(private val owner: Int) : CameraCaptureSession.StateCallback() {
        override fun onConfigured(configured: CameraCaptureSession) {
            if (owner != generation) return
            session = configured
            if (!paused) startRepeating()
        }

        override fun onConfigureFailed(failed: CameraCaptureSession) {
            if (owner == generation) fail("camera session could not be configured")
        }
    }

    private inner class EncoderCallback(private val owner: Int) : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (owner != generation) return
            publishConfig(format.getByteBuffer("csd-0")?.toByteArray(), format.getByteBuffer("csd-1")?.toByteArray())
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (owner != generation) return
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(data)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        publishConfig(data, null)
                    } else {
                        if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                        val keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val avcc = NalUtils.annexBToAvcc(data)
                        if (avcc.isNotEmpty()) {
                            listener.onVideoFrame(info.presentationTimeUs - firstPtsUs, avcc, keyFrame)
                        }
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (_: IllegalStateException) {
                // The codec was stopped while this callback was queued.
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (owner == generation) fail("encoder error: ${e.diagnosticInfo}")
        }

        private fun publishConfig(first: ByteArray?, second: ByteArray?) {
            val chosen = choice ?: return
            if (configSent) return
            val sets = NalUtils.findParameterSets(first, second) ?: return
            configSent = true
            listener.onVideoConfig(sets, chosen.size.width, chosen.size.height, rotationDegrees(chosen), chosen.facing)
        }
    }

    private fun java.nio.ByteBuffer.toByteArray(): ByteArray = ByteArray(remaining()).also { duplicate().get(it) }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val KEY_FRAME_INTERVAL_S = 2
        private const val REOPEN_DELAY_MS = 1_000L
    }
}
