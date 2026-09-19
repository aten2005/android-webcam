package dev.aten.webcam.media

import java.io.ByteArrayOutputStream

/**
 * MediaCodec emits H.264 in Annex-B form (start-code delimited). Browsers' WebCodecs decoders are
 * only uniformly reliable with the MP4-style "AVCC" form: length-prefixed NAL units plus an avcC
 * configuration record, so everything is converted before it goes on the wire.
 */
object NalUtils {
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    private const val NAL_AUD = 9
    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134)

    class ParameterSets(val sps: ByteArray, val pps: ByteArray)

    fun nalType(nal: ByteArray): Int = nal[0].toInt() and 0x1F

    /** Splits an Annex-B buffer into NAL units, without their 3- or 4-byte start codes. */
    fun splitAnnexB(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<ByteArray> {
        val end = offset + length
        val nals = ArrayList<ByteArray>(4)
        var nalStart = -1
        var i = offset
        while (i + 3 <= end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                if (nalStart >= 0) addTrimmed(nals, data, nalStart, i)
                nalStart = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (nalStart >= 0) addTrimmed(nals, data, nalStart, end)
        return nals
    }

    // A 4-byte start code shows up as a trailing zero on the preceding NAL; NAL payloads never end in 0x00.
    private fun addTrimmed(nals: MutableList<ByteArray>, data: ByteArray, start: Int, endExclusive: Int) {
        var end = endExclusive
        while (end > start && data[end - 1].toInt() == 0) end--
        if (end > start) nals.add(data.copyOfRange(start, end))
    }

    fun findParameterSets(vararg buffers: ByteArray?): ParameterSets? {
        val nals = buffers.filterNotNull().flatMap { splitAnnexB(it) }
        val sps = nals.firstOrNull { nalType(it) == NAL_SPS } ?: return null
        val pps = nals.firstOrNull { nalType(it) == NAL_PPS } ?: return null
        return ParameterSets(sps, pps)
    }

    /** Length-prefixes the frame's NAL units. Parameter sets travel in the avcC record instead. */
    fun annexBToAvcc(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
        val nals = splitAnnexB(data, offset, length).filter { nalType(it) !in setOf(NAL_SPS, NAL_PPS, NAL_AUD) }
        val out = ByteArray(nals.sumOf { 4 + it.size })
        var position = 0
        for (nal in nals) {
            out[position] = (nal.size ushr 24).toByte()
            out[position + 1] = (nal.size ushr 16).toByte()
            out[position + 2] = (nal.size ushr 8).toByte()
            out[position + 3] = nal.size.toByte()
            nal.copyInto(out, position + 4)
            position += 4 + nal.size
        }
        return out
    }

    /** Builds the AVCDecoderConfigurationRecord passed to the browser as VideoDecoder `description`. */
    fun buildAvcC(sets: ParameterSets): ByteArray {
        val sps = sets.sps
        val pps = sets.pps
        require(sps.size >= 4) { "SPS too short" }
        val out = ByteArrayOutputStream(16 + sps.size + pps.size)
        out.write(1)
        out.write(sps[1].toInt())
        out.write(sps[2].toInt())
        out.write(sps[3].toInt())
        out.write(0xFF) // 4-byte NAL lengths
        out.write(0xE1) // one SPS
        out.write(sps.size ushr 8)
        out.write(sps.size)
        out.write(sps)
        out.write(1)
        out.write(pps.size ushr 8)
        out.write(pps.size)
        out.write(pps)
        if ((sps[1].toInt() and 0xFF) in HIGH_PROFILES) {
            // Hardware encoders only produce 4:2:0 8-bit, which is what these fields declare.
            out.write(0xFD)
            out.write(0xF8)
            out.write(0xF8)
            out.write(0)
        }
        return out.toByteArray()
    }

    /** WebCodecs codec string, e.g. "avc1.42c01f": profile, constraint flags and level from the SPS. */
    fun codecString(sps: ByteArray): String =
        "avc1.%02x%02x%02x".format(sps[1].toInt() and 0xFF, sps[2].toInt() and 0xFF, sps[3].toInt() and 0xFF)
}
