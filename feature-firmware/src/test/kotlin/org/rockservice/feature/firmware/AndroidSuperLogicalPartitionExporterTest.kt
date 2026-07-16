package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidSuperLogicalPartitionExporterTest {
    @Test
    fun `exports mixed linear and zero extents in logical order`() {
        val source = ByteArray(64) { index -> index.toByte() }
        val plan = AndroidSuperLogicalPartitionPlan(
            name = "system_a",
            sizeBytes = 7,
            extents = listOf(
                AndroidSuperLogicalExtentPlan.Linear(0, sourceOffsetBytes = 2, lengthBytes = 3),
                AndroidSuperLogicalExtentPlan.Zero(lengthBytes = 2),
                AndroidSuperLogicalExtentPlan.Linear(0, sourceOffsetBytes = 10, lengthBytes = 2),
            ),
        )
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()

        val report = AndroidSuperLogicalPartitionExporter(bufferSizeBytes = 512).export(
            plan = plan,
            openBlockDevice = { ByteArrayInputStream(source) },
            destination = output,
            onProgress = { written, _ -> progress += written },
        )

        val expected = byteArrayOf(2, 3, 4, 0, 0, 10, 11)
        assertArrayEquals(expected, output.toByteArray())
        assertEquals(7L, report.bytesWritten)
        assertEquals(2, report.linearExtentCount)
        assertEquals(1, report.zeroExtentCount)
        assertEquals(sha256(expected), report.outputSha256)
        assertEquals(0L, progress.first())
        assertEquals(7L, progress.last())
        assertTrue(progress.zipWithNext().all { (left, right) -> right >= left })
    }

    @Test
    fun `opens explicitly addressed block devices for each linear extent`() {
        val devices = mapOf(
            0 to byteArrayOf(1, 2, 3, 4),
            1 to byteArrayOf(10, 11, 12, 13),
        )
        val opened = mutableListOf<Int>()
        val plan = AndroidSuperLogicalPartitionPlan(
            name = "vendor_a",
            sizeBytes = 4,
            extents = listOf(
                AndroidSuperLogicalExtentPlan.Linear(1, 1, 2),
                AndroidSuperLogicalExtentPlan.Linear(0, 0, 2),
            ),
        )
        val output = ByteArrayOutputStream()

        AndroidSuperLogicalPartitionExporter().export(
            plan = plan,
            openBlockDevice = { index ->
                opened += index
                ByteArrayInputStream(requireNotNull(devices[index]))
            },
            destination = output,
        )

        assertEquals(listOf(1, 0), opened)
        assertArrayEquals(byteArrayOf(11, 12, 1, 2), output.toByteArray())
    }

    @Test
    fun `zero only plan does not open a source`() {
        val plan = AndroidSuperLogicalPartitionPlan(
            name = "zero",
            sizeBytes = 1024,
            extents = listOf(AndroidSuperLogicalExtentPlan.Zero(1024)),
        )
        val output = ByteArrayOutputStream()
        var openCalls = 0

        val report = AndroidSuperLogicalPartitionExporter().export(
            plan = plan,
            openBlockDevice = {
                openCalls += 1
                ByteArrayInputStream(ByteArray(0))
            },
            destination = output,
        )

        assertEquals(0, openCalls)
        assertEquals(1024, output.size())
        assertTrue(output.toByteArray().all { byte -> byte == 0.toByte() })
        assertEquals(sha256(ByteArray(1024)), report.outputSha256)
    }

    @Test
    fun `short source fails closed`() {
        val plan = AndroidSuperLogicalPartitionPlan(
            name = "truncated",
            sizeBytes = 5,
            extents = listOf(AndroidSuperLogicalExtentPlan.Linear(0, 3, 5)),
        )

        val error = expectIOException {
            AndroidSuperLogicalPartitionExporter().export(
                plan = plan,
                openBlockDevice = { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)) },
                destination = ByteArrayOutputStream(),
            )
        }

        assertTrue(error.message.orEmpty().contains("truncada"))
    }

    @Test
    fun `checkpoint can abort an active export without being swallowed`() {
        val source = ByteArray(4096) { 7 }
        val plan = AndroidSuperLogicalPartitionPlan(
            name = "cancelled",
            sizeBytes = source.size.toLong(),
            extents = listOf(AndroidSuperLogicalExtentPlan.Linear(0, 0, source.size.toLong())),
        )
        val output = ByteArrayOutputStream()
        var checkpoints = 0

        try {
            AndroidSuperLogicalPartitionExporter(bufferSizeBytes = 512).export(
                plan = plan,
                openBlockDevice = { ByteArrayInputStream(source) },
                destination = output,
                checkpoint = {
                    checkpoints += 1
                    if (checkpoints == 6) throw TestCancellation()
                },
            )
            fail("Expected TestCancellation")
        } catch (_: TestCancellation) {
            assertTrue(output.size() in 1 until source.size)
        }
    }

    @Test
    fun `plan size mismatch is rejected before opening sources`() {
        val plan = AndroidSuperLogicalPartitionPlan(
            name = "invalid",
            sizeBytes = 2,
            extents = listOf(AndroidSuperLogicalExtentPlan.Zero(1)),
        )
        var openCalls = 0

        expectIllegalArgument {
            AndroidSuperLogicalPartitionExporter().export(
                plan = plan,
                openBlockDevice = {
                    openCalls += 1
                    ByteArrayInputStream(ByteArray(0))
                },
                destination = ByteArrayOutputStream(),
            )
        }

        assertEquals(0, openCalls)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun expectIOException(block: () -> Unit): IOException = try {
        block()
        fail("Expected IOException")
        error("unreachable")
    } catch (error: IOException) {
        error
    }

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException = try {
        block()
        fail("Expected IllegalArgumentException")
        error("unreachable")
    } catch (error: IllegalArgumentException) {
        error
    }

    private class TestCancellation : RuntimeException()
}
