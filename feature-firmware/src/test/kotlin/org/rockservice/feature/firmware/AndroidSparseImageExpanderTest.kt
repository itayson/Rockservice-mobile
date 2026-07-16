package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSparseImageExpanderTest {
    @Test
    fun `expands raw fill and dont care chunks and validates crc`() {
        val raw = byteArrayOf(1, 2, 3, 4)
        val fillPattern = byteArrayOf(0x44, 0x33, 0x22, 0x11)
        val expected = raw + fillPattern + fillPattern + ByteArray(8)
        val expectedCrc = expected.crc32()
        val sparse = sparseImage(
            blockSize = 4,
            totalBlocks = 5,
            imageChecksum = expectedCrc,
            chunks = listOf(
                rawChunk(blocks = 1, payload = raw),
                fillChunk(blocks = 2, fillPattern = fillPattern),
                dontCareChunk(blocks = 2),
                crc32Chunk(expectedCrc),
            ),
        )
        val output = ByteArrayOutputStream()

        val report = AndroidSparseImageExpander(bufferSizeBytes = 8).expand(
            ByteArrayInputStream(sparse),
            output,
        )

        assertArrayEquals(expected, output.toByteArray())
        assertEquals(expected.size.toLong(), report.expandedSizeBytes)
        assertEquals(sparse.size.toLong(), report.sparseBytesConsumed)
        assertEquals(4L, report.chunkCount)
        assertEquals(expected.sha256(), report.outputSha256)
        assertEquals(expectedCrc, report.outputCrc32)
        assertEquals(1, report.validatedCrc32Chunks)
        assertTrue(report.headerChecksumValidated)
    }

    @Test
    fun `expands image without optional header checksum`() {
        val expected = byteArrayOf(9, 8, 7, 6)
        val sparse = sparseImage(
            blockSize = 4,
            totalBlocks = 1,
            imageChecksum = 0,
            chunks = listOf(rawChunk(blocks = 1, payload = expected)),
        )
        val output = ByteArrayOutputStream()

        val report = AndroidSparseImageExpander().expand(ByteArrayInputStream(sparse), output)

        assertArrayEquals(expected, output.toByteArray())
        assertFalse(report.headerChecksumValidated)
        assertEquals(0, report.validatedCrc32Chunks)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid crc32 chunk`() {
        val raw = byteArrayOf(1, 2, 3, 4)
        val sparse = sparseImage(
            blockSize = 4,
            totalBlocks = 1,
            imageChecksum = 0,
            chunks = listOf(
                rawChunk(blocks = 1, payload = raw),
                crc32Chunk(expectedCrc = 0xDEADBEEFL),
            ),
        )

        AndroidSparseImageExpander().expand(ByteArrayInputStream(sparse), ByteArrayOutputStream())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects truncated raw payload`() {
        val sparse = sparseImage(
            blockSize = 4,
            totalBlocks = 1,
            imageChecksum = 0,
            chunks = listOf(rawChunk(blocks = 1, payload = byteArrayOf(1, 2, 3))),
            declaredRawPayloadSize = 4,
        )

        AndroidSparseImageExpander().expand(ByteArrayInputStream(sparse), ByteArrayOutputStream())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects declared expansion above configured limit before writing output`() {
        val sparse = sparseImage(
            blockSize = 4096,
            totalBlocks = 2,
            imageChecksum = 0,
            chunks = listOf(dontCareChunk(blocks = 2)),
        )
        val output = ByteArrayOutputStream()

        try {
            AndroidSparseImageExpander(maximumExpandedBytes = 4096).expand(
                ByteArrayInputStream(sparse),
                output,
            )
        } finally {
            assertEquals(0, output.size())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects chunks that exceed declared output block count`() {
        val sparse = sparseImage(
            blockSize = 4,
            totalBlocks = 1,
            imageChecksum = 0,
            chunks = listOf(dontCareChunk(blocks = 2)),
        )

        AndroidSparseImageExpander().expand(ByteArrayInputStream(sparse), ByteArrayOutputStream())
    }

    private fun sparseImage(
        blockSize: Int,
        totalBlocks: Int,
        imageChecksum: Long,
        chunks: List<Chunk>,
        declaredRawPayloadSize: Int? = null,
    ): ByteArray = ByteArrayOutputStream().also { output ->
        output.write(
            ByteBuffer.allocate(28)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0xED26FF3A.toInt())
                .putShort(1)
                .putShort(0)
                .putShort(28)
                .putShort(12)
                .putInt(blockSize)
                .putInt(totalBlocks)
                .putInt(chunks.size)
                .putInt(imageChecksum.toInt())
                .array(),
        )
        chunks.forEach { chunk ->
            val payloadSize = when {
                declaredRawPayloadSize != null && chunk.type == 0xCAC1 -> declaredRawPayloadSize
                else -> chunk.payload.size
            }
            output.write(
                ByteBuffer.allocate(12)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(chunk.type.toShort())
                    .putShort(0)
                    .putInt(chunk.blocks)
                    .putInt(12 + payloadSize)
                    .array(),
            )
            output.write(chunk.payload)
        }
    }.toByteArray()

    private fun rawChunk(blocks: Int, payload: ByteArray): Chunk = Chunk(0xCAC1, blocks, payload)

    private fun fillChunk(blocks: Int, fillPattern: ByteArray): Chunk {
        require(fillPattern.size == 4)
        return Chunk(0xCAC2, blocks, fillPattern)
    }

    private fun dontCareChunk(blocks: Int): Chunk = Chunk(0xCAC3, blocks, ByteArray(0))

    private fun crc32Chunk(expectedCrc: Long): Chunk = Chunk(
        type = 0xCAC4,
        blocks = 0,
        payload = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(expectedCrc.toInt())
            .array(),
    )

    private fun ByteArray.crc32(): Long = CRC32().also { it.update(this) }.value

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }

    private data class Chunk(
        val type: Int,
        val blocks: Int,
        val payload: ByteArray,
    )
}
