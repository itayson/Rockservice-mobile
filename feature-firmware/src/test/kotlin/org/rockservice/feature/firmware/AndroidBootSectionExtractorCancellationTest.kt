package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidBootSectionExtractorCancellationTest {
    @Test
    fun `checkpoint aborts long extraction without being swallowed`() {
        val source = ByteArray(4096) { index -> (index and 0xFF).toByte() }
        val metadata = AndroidBootImageMetadata(
            headerVersion = AndroidBootHeaderVersion.V3,
            pageSizeBytes = 4096,
            headerSizeBytes = 1580,
            kernelSizeBytes = 3072,
            ramdiskSizeBytes = 0,
            secondStageSizeBytes = 0,
            recoveryDtboSizeBytes = 0,
            recoveryDtboOffsetBytes = null,
            dtbSizeBytes = 0,
            bootSignatureSizeBytes = 0,
            osVersionEncoded = 0,
            sections = listOf(
                AndroidBootSection(AndroidBootSectionType.HEADER, 0, 1024, 1024),
                AndroidBootSection(AndroidBootSectionType.KERNEL, 1024, 3072, 3072),
            ),
            minimumImageSizeBytes = 4096,
            bytesConsumed = 4096,
        )
        val output = ByteArrayOutputStream()
        var checkpoints = 0

        try {
            AndroidBootSectionExtractor(bufferSizeBytes = 512).extract(
                source = ByteArrayInputStream(source),
                metadata = metadata,
                expectedSourceSha256 = source.sha256(),
                sectionType = AndroidBootSectionType.KERNEL,
                destination = output,
                checkpoint = {
                    checkpoints += 1
                    if (checkpoints == 10) throw TestCancellation()
                },
            )
            fail("Expected TestCancellation")
        } catch (_: TestCancellation) {
            assertTrue(checkpoints >= 10)
            assertTrue(output.size() in 0 until 3072)
        }
    }

    @Test
    fun `checkpoint before first read can abort without output`() {
        val source = ByteArray(16)
        val metadata = AndroidBootImageMetadata(
            headerVersion = AndroidBootHeaderVersion.V3,
            pageSizeBytes = 4096,
            headerSizeBytes = 1580,
            kernelSizeBytes = 4,
            ramdiskSizeBytes = 0,
            secondStageSizeBytes = 0,
            recoveryDtboSizeBytes = 0,
            recoveryDtboOffsetBytes = null,
            dtbSizeBytes = 0,
            bootSignatureSizeBytes = 0,
            osVersionEncoded = 0,
            sections = listOf(
                AndroidBootSection(AndroidBootSectionType.HEADER, 0, 4, 4),
                AndroidBootSection(AndroidBootSectionType.KERNEL, 4, 4, 4),
            ),
            minimumImageSizeBytes = 8,
            bytesConsumed = 8,
        )
        val output = ByteArrayOutputStream()

        try {
            AndroidBootSectionExtractor().extract(
                source = ByteArrayInputStream(source),
                metadata = metadata,
                expectedSourceSha256 = source.sha256(),
                sectionType = AndroidBootSectionType.KERNEL,
                destination = output,
                checkpoint = { throw TestCancellation() },
            )
            fail("Expected TestCancellation")
        } catch (_: TestCancellation) {
            assertTrue(output.size() == 0)
        }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private class TestCancellation : RuntimeException()
}
