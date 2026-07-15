package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareAnalyzerTest {
    private val analyzer = FirmwareAnalyzer(maximumBytes = 1024)

    @Test
    fun `identifies zip by magic bytes and calculates sha256`() {
        val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)
        val result = analyzer.analyze(ByteArrayInputStream(bytes))
        assertEquals(FirmwareFormat.ZIP, result.format)
        assertEquals(bytes.size.toLong(), result.bytesRead)
        assertEquals(64, result.sha256.length)
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
}
