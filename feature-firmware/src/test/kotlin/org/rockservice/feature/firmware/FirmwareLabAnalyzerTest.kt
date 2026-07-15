package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FirmwareLabAnalyzerTest {
    @Test
    fun `unknown image remains reportable with known sha256 vector`() {
        val bytes = "abc".toByteArray(Charsets.US_ASCII)
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
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            report.sha256,
        )
        assertTrue(report.warnings.isNotEmpty())
        assertTrue(report.sections.any { section -> section.title == "Analise estrutural especializada" })
        assertTrue(report.toPlainText().contains("mystery.bin"))
    }

    @Test
    fun `zip is identified without a specialized extraction pass`() {
        val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        var openCount = 0

        val report = FirmwareLabAnalyzer().analyze(
            displayName = "archive.zip",
            declaredSizeBytes = bytes.size.toLong(),
        ) {
            openCount += 1
            ByteArrayInputStream(bytes)
        }

        assertEquals(1, openCount)
        assertEquals(FirmwareFormat.ZIP, report.detectedFormat)
        assertTrue(report.sections.any { section -> section.title == "Analise estrutural especializada" })
    }

    @Test
    fun `truncated boot image fails with actionable structural error`() {
        val error = expectIllegalArgument {
            FirmwareLabAnalyzer().analyze(
                displayName = "boot.img",
                declaredSizeBytes = 8,
            ) {
                ByteArrayInputStream("ANDROID!".toByteArray(Charsets.US_ASCII))
            }
        }

        assertTrue(error.message.orEmpty().contains("truncada", ignoreCase = true))
    }

    @Test
    fun `malformed super reports failure of primary and backup metadata copies`() {
        val bytes = ByteArray(4100)
        byteArrayOf(0x67, 0x44, 0x6C, 0x61).copyInto(bytes, destinationOffset = 4096)

        val error = expectIllegalArgument {
            FirmwareLabAnalyzer().analyze(
                displayName = "super.img",
                declaredSizeBytes = bytes.size.toLong(),
            ) {
                ByteArrayInputStream(bytes)
            }
        }

        assertTrue(error.message.orEmpty().contains("primaria e backup", ignoreCase = true))
    }

    @Test
    fun `super analysis falls back from primary metadata to backup`() {
        val requestedCopies = mutableListOf<AndroidSuperMetadataCopy>()
        val operations = FirmwareLabParserOperations(
            analyzeFirmware = {
                FirmwareAnalysis(
                    format = FirmwareFormat.ANDROID_SUPER_RAW,
                    sha256 = "0".repeat(64),
                    bytesRead = 1,
                    warnings = emptyList(),
                )
            },
            parseSparse = { error("Sparse parser must not be called") },
            parseBoot = { error("Boot parser must not be called") },
            parseSuper = { _, copy ->
                requestedCopies += copy
                if (copy == AndroidSuperMetadataCopy.PRIMARY) {
                    throw IllegalArgumentException("primary invalid")
                }
                fakeSuperMetadata(copy)
            },
        )
        val analyzer = FirmwareLabAnalyzer(parserOperations = operations)

        val report = analyzer.analyze(
            displayName = "super.img",
            declaredSizeBytes = 1,
        ) {
            ByteArrayInputStream(byteArrayOf(0))
        }

        assertEquals(
            listOf(AndroidSuperMetadataCopy.PRIMARY, AndroidSuperMetadataCopy.BACKUP),
            requestedCopies,
        )
        assertTrue(
            report.sections
                .flatMap { section -> section.lines }
                .any { line -> line.contains("BACKUP") },
        )
    }

    @Test
    fun `report filename removes path traversal components`() {
        val report = FirmwareLabAnalyzer().analyze(
            displayName = "../../firmware.bin",
            declaredSizeBytes = 4,
        ) {
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        }

        assertEquals("firmware-rockservice-report.txt", report.suggestedReportFileName)
        assertFalse(report.suggestedReportFileName.contains('/'))
        assertFalse(report.suggestedReportFileName.contains('\\'))
    }

    @Test
    fun `configured input limit rejects oversized zip before any specialized processing`() {
        val analyzer = FirmwareLabAnalyzer(
            firmwareAnalyzer = FirmwareAnalyzer(maximumBytes = 3),
        )

        expectIllegalArgument {
            analyzer.analyze(
                displayName = "large.zip",
                declaredSizeBytes = 4,
            ) {
                ByteArrayInputStream(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            }
        }
    }

    private fun fakeSuperMetadata(copy: AndroidSuperMetadataCopy): AndroidSuperMetadata {
        val emptyDescriptor = AndroidSuperTableDescriptor(
            offsetBytes = 0,
            entryCount = 0,
            entrySizeBytes = 0,
        )
        return AndroidSuperMetadata(
            geometry = AndroidSuperGeometry(
                metadataMaxSizeBytes = 4096,
                metadataSlotCount = 1,
                logicalBlockSizeBytes = 4096,
                source = AndroidSuperGeometrySource.PRIMARY,
            ),
            metadataCopy = copy,
            slotNumber = 0,
            metadataOffsetBytes = 12_288,
            majorVersion = 10,
            minorVersion = 2,
            headerSizeBytes = 256,
            tablesSizeBytes = 0,
            headerFlags = 0,
            partitionsDescriptor = emptyDescriptor,
            extentsDescriptor = emptyDescriptor,
            groupsDescriptor = emptyDescriptor,
            blockDevicesDescriptor = emptyDescriptor,
            partitions = emptyList(),
            extents = emptyList(),
            groups = emptyList(),
            blockDevices = emptyList(),
            bytesConsumed = 256,
        )
    }

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            return error
        }
        throw AssertionError("Unreachable")
    }
}
