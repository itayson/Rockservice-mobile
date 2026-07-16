package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareLabRawFilesystemIntegrationTest {
    @Test
    fun `unknown container with ext4 evidence gains raw filesystem report section`() {
        val bytes = ByteArray(4096)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(1024 + 0x18, 2)
        buffer.putShort(1024 + 0x38, 0xEF53.toShort())
        buffer.putInt(1024 + 0x60, 0x40)
        var openCount = 0

        val report = FirmwareLabAnalyzer().analyze(
            displayName = "system.img",
            declaredSizeBytes = bytes.size.toLong(),
        ) {
            openCount += 1
            ByteArrayInputStream(bytes)
        }

        assertEquals(FirmwareFormat.UNKNOWN, report.detectedFormat)
        assertEquals(2, openCount)
        val rawSection = report.sections.single { section -> section.title == "Filesystem raw detectado" }
        assertTrue(rawSection.lines.any { line -> line.contains("EXT4") })
        assertTrue(rawSection.lines.any { line -> line.contains("SHA-256 do prefixo") })
        assertTrue(rawSection.lines.any { line -> line.contains("SHA-256 integral") })
    }

    @Test
    fun `large unknown image without supported filesystem keeps generic section`() {
        val bytes = ByteArray(4096)
        var openCount = 0

        val report = FirmwareLabAnalyzer().analyze(
            displayName = "raw.bin",
            declaredSizeBytes = bytes.size.toLong(),
        ) {
            openCount += 1
            ByteArrayInputStream(bytes)
        }

        assertEquals(2, openCount)
        assertTrue(report.sections.any { section -> section.title == "Analise estrutural especializada" })
        assertTrue(report.sections.none { section -> section.title == "Filesystem raw detectado" })
    }

    @Test
    fun `tiny unknown input skips raw filesystem second pass`() {
        val bytes = "abc".toByteArray()
        var openCount = 0

        val report = FirmwareLabAnalyzer().analyze(
            displayName = "tiny.bin",
            declaredSizeBytes = bytes.size.toLong(),
        ) {
            openCount += 1
            ByteArrayInputStream(bytes)
        }

        assertEquals(1, openCount)
        assertEquals(FirmwareFormat.UNKNOWN, report.detectedFormat)
        assertTrue(report.sections.any { section -> section.title == "Analise estrutural especializada" })
    }

    @Test
    fun `known container does not invoke raw filesystem inspector`() {
        var rawInspectionCalls = 0
        val operations = FirmwareLabParserOperations(
            analyzeFirmware = {
                FirmwareAnalysis(
                    format = FirmwareFormat.ZIP,
                    sha256 = "0".repeat(64),
                    bytesRead = 4096,
                    warnings = emptyList(),
                )
            },
            parseSparse = { error("Sparse parser must not be called") },
            parseBoot = { error("Boot parser must not be called") },
            parseSuper = { _, _ -> error("Super parser must not be called") },
            parseSparseSuper = { error("Sparse-super parser must not be called") },
            inspectRawFilesystem = {
                rawInspectionCalls += 1
                error("Raw filesystem inspector must not be called")
            },
        )

        val report = FirmwareLabAnalyzer(parserOperations = operations).analyze(
            displayName = "archive.zip",
            declaredSizeBytes = 4096,
        ) {
            ByteArrayInputStream(ByteArray(4096))
        }

        assertEquals(0, rawInspectionCalls)
        assertEquals(FirmwareFormat.ZIP, report.detectedFormat)
    }
}
