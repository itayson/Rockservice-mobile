package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareLabAnalyzerTest {
    @Test
    fun `unknown image remains reportable without specialized parsing`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        var openCount = 0

        val report = FirmwareLabAnalyzer().analyze(
            displayName = "mystery.bin",
            declaredSizeBytes = null,
        ) {
            openCount += 1
            ByteArrayInputStream(bytes)
        }

        assertEquals(1, openCount)
        assertEquals(FirmwareFormat.UNKNOWN, report.detectedFormat)
        assertTrue(report.warnings.isNotEmpty())
        assertTrue(report.sections.any { section -> section.title == "Analise estrutural especializada" })
        assertTrue(report.toPlainText().contains("mystery.bin"))
    }

    @Test
    fun `report filename is sanitized`() {
        val report = FirmwareLabAnalyzer().analyze(
            displayName = "firmware test.bin",
            declaredSizeBytes = 4,
        ) {
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        }

        assertEquals("firmware_test-rockservice-report.txt", report.suggestedReportFileName)
    }
}
