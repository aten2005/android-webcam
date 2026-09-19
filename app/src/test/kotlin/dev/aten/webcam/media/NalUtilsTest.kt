package dev.aten.webcam.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NalUtilsTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val sps = bytes(0x67, 0x42, 0xC0, 0x1F, 0xDA, 0x01, 0x40)
    private val pps = bytes(0x68, 0xCE, 0x3C, 0x80)
    private val idr = bytes(0x65, 0x88, 0x84, 0x21)
    private val slice = bytes(0x41, 0x9A, 0x02)
    private val start4 = bytes(0, 0, 0, 1)
    private val start3 = bytes(0, 0, 1)

    @Test
    fun splitsMixedStartCodes() {
        val nals = NalUtils.splitAnnexB(start4 + sps + start4 + pps + start3 + idr)
        assertEquals(3, nals.size)
        assertArrayEquals(sps, nals[0])
        assertArrayEquals(pps, nals[1])
        assertArrayEquals(idr, nals[2])
    }

    @Test
    fun splitsWithinOffsetAndLength() {
        val data = bytes(9, 9) + start4 + slice + bytes(9)
        val nals = NalUtils.splitAnnexB(data, 2, start4.size + slice.size)
        assertEquals(1, nals.size)
        assertArrayEquals(slice, nals[0])
    }

    @Test
    fun returnsNothingWithoutStartCode() {
        assertEquals(0, NalUtils.splitAnnexB(bytes(1, 2, 3, 4)).size)
        assertEquals(0, NalUtils.splitAnnexB(ByteArray(0)).size)
    }

    @Test
    fun findsParameterSetsAcrossBuffers() {
        val combined = NalUtils.findParameterSets(start4 + sps + start4 + pps)!!
        assertArrayEquals(sps, combined.sps)
        assertArrayEquals(pps, combined.pps)
        val separate = NalUtils.findParameterSets(start4 + sps, null, start4 + pps)!!
        assertArrayEquals(pps, separate.pps)
        assertNull(NalUtils.findParameterSets(start4 + sps))
    }

    @Test
    fun convertsToLengthPrefixedAndStripsParameterSets() {
        val aud = bytes(0x09, 0xF0)
        val avcc = NalUtils.annexBToAvcc(start4 + aud + start4 + sps + start4 + pps + start4 + idr + start3 + slice)
        assertArrayEquals(bytes(0, 0, 0, 4) + idr + bytes(0, 0, 0, 3) + slice, avcc)
    }

    @Test
    fun buildsBaselineAvcC() {
        val avcC = NalUtils.buildAvcC(NalUtils.ParameterSets(sps, pps))
        val expected = bytes(1, 0x42, 0xC0, 0x1F, 0xFF, 0xE1, 0, sps.size) + sps + bytes(1, 0, pps.size) + pps
        assertArrayEquals(expected, avcC)
    }

    @Test
    fun appendsChromaInfoForHighProfile() {
        val highSps = bytes(0x67, 0x64, 0x00, 0x28, 0xAC)
        val avcC = NalUtils.buildAvcC(NalUtils.ParameterSets(highSps, pps))
        assertArrayEquals(bytes(0xFD, 0xF8, 0xF8, 0), avcC.copyOfRange(avcC.size - 4, avcC.size))
        assertEquals("avc1.640028", NalUtils.codecString(highSps))
    }

    @Test
    fun formatsCodecString() = assertEquals("avc1.42c01f", NalUtils.codecString(sps))
}
