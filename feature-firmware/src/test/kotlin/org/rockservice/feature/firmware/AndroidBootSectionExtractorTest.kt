package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AndroidBootSectionExtractorTest {
    @Test
    fun `extracts kernel payload without page padding and validates whole source hash`() {
        val source = ByteArray(20) { index -> index.toByte() }
        val metadata = metadata(
            minimumImageSize = 16,
            sections = listOf(
                section(AndroidBootSectionType.HEADER, offset = 0, size = 4, padded = 4),
                section(AndroidBootSectionType.KERNEL, offset = 4, size = 3, padded = 8),
                section(AndroidBootSectionType.RAMDISK, offset = 12, size = 4, padded = 4),
            ),
        )
        val output = ByteArrayOutputStream()

        val report = AndroidBootSectionExtractor(bufferSizeBytes = 5).extract(
            source = ByteArrayInputStream(source),
            metadata = metadata,
            expectedSourceSha256 = source.sha256(),
            sectionType = AndroidBootSectionType.KERNEL,
            destination = output,
        )

        assertArrayEquals(source.copyOfRange(4, 7), output.toByteArray())
        assertEquals(3L, report.extractedBytes)
        assertEquals(4L, report.offsetBytes)
        assertEquals(20L, report.sourceBytesRead)
        assertEquals(source.sha256(), report.sourceSha256)
        assertEquals(source.copyOfRange(4, 7).sha256(), report.sectionSha256)
    }

    @Test
    fun `extracts ramdisk from validated section`() {
        val source = ByteArray(16) { index -> (index + 10).toByte() }
        val metadata = metadata(
            minimumImageSize = 16,
            sections = listOf(
                section(AndroidBootSectionType.HEADER, 0, 4, 4),
                section(AndroidBootSectionType.KERNEL, 4, 4, 4),
                section(AndroidBootSectionType.RAMDISK, 8, 4, 8),
            ),
        )
        val output = ByteArrayOutputStream()

        AndroidBootSectionExtractor().extract(
            ByteArrayInputStream(source),
            metadata,
            source.sha256().uppercase(),
            AndroidBootSectionType.RAMDISK,
            output,
        )

        assertArrayEquals(source.copyOfRange(8, 12), output.toByteArray())
    }

    @Test
    fun `rejects header extraction before reading or writing`() {
        val source = ByteArray(8)
        val output = ByteArrayOutputStream()

        expectIllegalArgument {
            AndroidBootSectionExtractor().extract(
                ByteArrayInputStream(source),
                metadata(
                    minimumImageSize = 8,
                    sections = listOf(section(AndroidBootSectionType.HEADER, 0, 8, 8)),
                ),
                source.sha256(),
                AndroidBootSectionType.HEADER,
                output,
            )
        }

        assertEquals(0, output.size())
    }

    @Test
    fun `rejects missing section before writing`() {
        val source = ByteArray(8)
        val output = ByteArrayOutputStream()

        expectIllegalArgument {
            AndroidBootSectionExtractor().extract(
                ByteArrayInputStream(source),
                metadata(
                    minimumImageSize = 8,
                    sections = listOf(section(AndroidBootSectionType.HEADER, 0, 8, 8)),
                ),
                source.sha256(),
                AndroidBootSectionType.KERNEL,
                output,
            )
        }

        assertEquals(0, output.size())
    }

    @Test
    fun `detects source changed since analysis after extracting candidate bytes`() {
        val analyzedSource = ByteArray(12) { index -> index.toByte() }
        val changedSource = analyzedSource.copyOf().also { bytes -> bytes[10] = 99 }
        val metadata = metadata(
            minimumImageSize = 12,
            sections = listOf(
                section(AndroidBootSectionType.HEADER, 0, 4, 4),
                section(AndroidBootSectionType.KERNEL, 4, 4, 8),
            ),
        )
        val output = ByteArrayOutputStream()

        expectIllegalArgument {
            AndroidBootSectionExtractor().extract(
                ByteArrayInputStream(changedSource),
                metadata,
                analyzedSource.sha256(),
                AndroidBootSectionType.KERNEL,
                output,
            )
        }

        assertArrayEquals(changedSource.copyOfRange(4, 8), output.toByteArray())
    }

    @Test
    fun `rejects truncated source`() {
        val source = ByteArray(10) { index -> index.toByte() }
        val metadata = metadata(
            minimumImageSize = 12,
            sections = listOf(
                section(AndroidBootSectionType.HEADER, 0, 4, 4),
                section(AndroidBootSectionType.KERNEL, 4, 4, 8),
            ),
        )

        expectIllegalArgument {
            AndroidBootSectionExtractor().extract(
                ByteArrayInputStream(source),
                metadata,
                source.sha256(),
                AndroidBootSectionType.KERNEL,
                ByteArrayOutputStream(),
            )
        }
    }

    @Test
    fun `rejects section above configured limit before writing`() {
        val source = ByteArray(12)
        val output = ByteArrayOutputStream()
        val metadata = metadata(
            minimumImageSize = 12,
            sections = listOf(
                section(AndroidBootSectionType.HEADER, 0, 4, 4),
                section(AndroidBootSectionType.KERNEL, 4, 5, 8),
            ),
        )

        expectIllegalArgument {
            AndroidBootSectionExtractor(maximumSectionBytes = 4).extract(
                ByteArrayInputStream(source),
                metadata,
                source.sha256(),
                AndroidBootSectionType.KERNEL,
                output,
            )
        }

        assertEquals(0, output.size())
    }

    @Test
    fun `rejects source above input limit`() {
        val source = ByteArray(11) { index -> index.toByte() }
        val metadata = metadata(
            minimumImageSize = 8,
            sections = listOf(
                section(AndroidBootSectionType.HEADER, 0, 4, 4),
                section(AndroidBootSectionType.KERNEL, 4, 4, 4),
            ),
        )

        expectIllegalArgument {
            AndroidBootSectionExtractor(maximumInputBytes = 10).extract(
                ByteArrayInputStream(source),
                metadata,
                source.sha256(),
                AndroidBootSectionType.KERNEL,
                ByteArrayOutputStream(),
            )
        }
    }

    @Test
    fun `rejects malformed expected sha before writing`() {
        val source = ByteArray(8)
        val output = ByteArrayOutputStream()

        expectIllegalArgument {
            AndroidBootSectionExtractor().extract(
                ByteArrayInputStream(source),
                metadata(
                    minimumImageSize = 8,
                    sections = listOf(
                        section(AndroidBootSectionType.HEADER, 0, 4, 4),
                        section(AndroidBootSectionType.KERNEL, 4, 4, 4),
                    ),
                ),
                "not-a-sha256",
                AndroidBootSectionType.KERNEL,
                output,
            )
        }

        assertEquals(0, output.size())
    }

    private fun metadata(
        minimumImageSize: Long,
        sections: List<AndroidBootSection>,
    ): AndroidBootImageMetadata = AndroidBootImageMetadata(
        headerVersion = AndroidBootHeaderVersion.V3,
        pageSizeBytes = 4096,
        headerSizeBytes = 1580,
        kernelSizeBytes = sections.firstOrNull { it.type == AndroidBootSectionType.KERNEL }?.sizeBytes ?: 0,
        ramdiskSizeBytes = sections.firstOrNull { it.type == AndroidBootSectionType.RAMDISK }?.sizeBytes ?: 0,
        secondStageSizeBytes = sections.firstOrNull { it.type == AndroidBootSectionType.SECOND_STAGE }?.sizeBytes ?: 0,
        recoveryDtboSizeBytes = sections.firstOrNull { it.type == AndroidBootSectionType.RECOVERY_DTBO }?.sizeBytes ?: 0,
        recoveryDtboOffsetBytes = sections.firstOrNull { it.type == AndroidBootSectionType.RECOVERY_DTBO }?.offsetBytes,
        dtbSizeBytes = sections.firstOrNull { it.type == AndroidBootSectionType.DTB }?.sizeBytes ?: 0,
        bootSignatureSizeBytes = sections.firstOrNull { it.type == AndroidBootSectionType.BOOT_SIGNATURE }?.sizeBytes ?: 0,
        osVersionEncoded = 0,
        sections = sections,
        minimumImageSizeBytes = minimumImageSize,
        bytesConsumed = minimumImageSize,
    )

    private fun section(
        type: AndroidBootSectionType,
        offset: Long,
        size: Long,
        padded: Long,
    ): AndroidBootSection = AndroidBootSection(
        type = type,
        offsetBytes = offset,
        sizeBytes = size,
        paddedSizeBytes = padded,
    )

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException = try {
        block()
        fail("Expected IllegalArgumentException")
        error("unreachable")
    } catch (error: IllegalArgumentException) {
        error
    }
}
