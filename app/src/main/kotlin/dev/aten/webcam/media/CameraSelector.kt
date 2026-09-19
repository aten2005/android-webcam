package dev.aten.webcam.media

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.util.Range
import android.util.Size
import kotlin.math.abs

class QualityPreset(val name: String, val width: Int, val height: Int, val fps: Int, val bitrate: Int) {
    companion object {
        val ALL = listOf(
            QualityPreset("low", 640, 480, 15, 500_000),
            QualityPreset("medium", 1280, 720, 24, 1_500_000),
            QualityPreset("high", 1920, 1080, 30, 4_000_000),
        )

        fun byName(name: String): QualityPreset = ALL.firstOrNull { it.name == name } ?: ALL[1]
    }
}

class CameraChoice(
    val id: String,
    val facing: String,
    val sensorOrientation: Int,
    val hasFlash: Boolean,
    val size: Size,
    val fpsRange: Range<Int>,
)

object CameraSelector {
    const val BACK = "back"
    const val FRONT = "front"
    private const val EXTERNAL = "external"

    /** One camera id per facing; phones expose extra lenses as separate ids we do not need. */
    fun camerasByFacing(manager: CameraManager): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (id in manager.cameraIdList) {
            val facing = when (manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> BACK
                CameraCharacteristics.LENS_FACING_FRONT -> FRONT
                else -> EXTERNAL
            }
            result.putIfAbsent(facing, id)
        }
        return result
    }

    fun choose(
        manager: CameraManager,
        preferredFacing: String,
        preset: QualityPreset,
        encoder: MediaCodecInfo.VideoCapabilities,
    ): CameraChoice? {
        val cameras = camerasByFacing(manager)
        val facing = if (preferredFacing in cameras) preferredFacing else cameras.keys.firstOrNull() ?: return null
        val id = cameras.getValue(facing)
        val characteristics = manager.getCameraCharacteristics(id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(MediaCodec::class.java).orEmpty()
            .filter { encoder.isSizeSupported(it.width, it.height) }
        val size = chooseSize(sizes, preset) ?: return null
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        return CameraChoice(
            id = id,
            facing = facing,
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            size = size,
            fpsRange = chooseFpsRange(ranges, preset.fps),
        )
    }

    private fun chooseSize(sizes: List<Size>, preset: QualityPreset): Size? {
        sizes.firstOrNull { it.width == preset.width && it.height == preset.height }?.let { return it }
        val budget = preset.width.toLong() * preset.height
        return sizes.filter { it.width.toLong() * it.height <= budget }.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
    }

    /** The camera, not the encoder, sets the real frame rate; fixed ranges avoid exposure-driven fps swings. */
    private fun chooseFpsRange(ranges: Array<out Range<Int>>, target: Int): Range<Int> =
        ranges.minByOrNull { abs(it.upper - target) * 10 + (it.upper - it.lower) } ?: Range(target, target)
}
