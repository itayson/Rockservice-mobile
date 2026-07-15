package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareAnalyzerTest {
    private val analyzer = FirmwareAnalyzer(maximumBytes = 1024 * 1024)

    @Test
    fun `identifies zip by magic bytes and calculates sha256`() {
        val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)
        val result = analyzer.analyze(ByteArrayInputStream(bytes))
        assertEquals(FirmwareFormat.ZIP, result.format)
        assertEquals(bytes.size.toLong(), result.bytesRead)
        assertEquals(64, result.sha256.length)
    }

    @Test
    fun `identifies android sparse image at exact four byte signature`() {
        val bytes = byteArrayOf(0x3A, 0xFF.toByte(), 0x26, 0xED.toByte())
        assertEquals(FirmwareFormat.ANDROID_SPARSE, analyzer.analyze(ByteArrayInputStream(bytes)).format)
    }

    @Test
    fun `rejects partial four byte sparse signature`() {
        assertUnknown(byteArrayOf(0x3A, 0xFF.toByte(), 0x26))
    }

    @Test
    fun `identifies android boot image at exact eight byte signature`() {
        val bytes = "ANDROID!".toByteArray(Charsets.US_ASCII)
        assertEquals(FirmwareFormat.ANDROID_BOOT_IMAGE, analyzer.analyze(ByteArrayInputStream(bytes)).format)
    }

    @Test
    fun `rejects truncated android boot signature`() {
        assertUnknown("ANDROID".toByteArray(Charsets.US_ASCII))
    }

    @Test
    fun `identifies raw android super by primary geometry magic`() {
        val bytes = ByteArray(4100)
        byteArrayOf(0x67, 0x44, 0x6C, 0x61).copyInto(bytes, destinationOffset = 4096)
        assertEquals(FirmwareFormat.ANDROID_SUPER_RAW, analyzer.analyze(ByteArrayInputStream(bytes)).format)
    }

    @Test
    fun `identifies raw android super by backup geometry magic`() {
        val bytes = ByteArray(8196)
        byteArrayOf(0x67, 0x44, 0x6C, 0x61).copyInto(bytes, destinationOffset = 8192)
        assertEquals(FirmwareFormat.ANDROID_SUPER_RAW, analyzer.analyze(ByteArrayInputStream(bytes)).format)
    }

    @Test
    fun `rejects incomplete super magic at backup boundary`() {
        val bytes = ByteArray(8195)
        byteArrayOf(0x67, 0x44, 0x6C).copyInto(bytes, destinationOffset = 8192)
        assertUnknown(bytes)
    }

    @Test
    fun `identifies elf image`() {
        val bytes = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)
        assertEquals(FirmwareFormat.ELF, analyzer.analyze(ByteArrayInputStream(bytes)).format)
    }

    @Test
    fun `identifies iso image from partial reads`() {
        val bytes = ByteArray(0x8006)
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(bytes, destinationOffset = 0x8001)
        val result = analyzer.analyze(PartialInputStream(bytes, 7))
        assertEquals(FirmwareFormat.ISO_9660, result.format)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects files above configured limit`() {
        FirmwareAnalyzer(maximumBytes = 3).analyze(ByteArrayInputStream(ByteArray(4)))
    }

    @Test
    fun `truncated header remains unknown without crashing`() {
        val result = analyzer.analyze(ByteArrayInputStream(byteArrayOf(0x50)))
        assertEquals(FirmwareFormat.UNKNOWN, result.format)
        assertTrue(result.warnings.isNotEmpty())
    }

    private fun assertUnknown(bytes: ByteArray) {
        assertEquals(FirmwareFormat.UNKNOWN, analyzer.analyze(ByteArrayInputStream(bytes)).format)
    }

    private class PartialInputStream(
        private val bytes: ByteArray,
        private val maximumChunk: Int,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position >= bytes.size) -1 else bytes[position++].toInt() and 0xff

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, maximumChunk, bytes.size - position)
            bytes.copyInto(target, offset, position, position + count)
            position += count
            return count
        }

        override fun available(): Int = 0
    }
}
