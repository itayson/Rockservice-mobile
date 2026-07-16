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
import org.junit.Assert.fail
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
    fun `supports extended image and chunk headers`() {
        val expected = byteArrayOf(5, 6, 7, 8)
        val sparse = sparseImage(
            blockSize = 4,
            totalBlocks = 1,
            imageChecksum = expected.crc32(),
            fileHeaderSize = 32,
            chunkHeaderSize = 16,
            chunks = listOf(rawChunk(blocks = 1, payload = expected)),
        )
        val output = ByteArrayOutputStream()

        val report = AndroidSparseImageExpander().expand(ByteArrayInputStream(sparse), output)

        assertArrayEquals(expected, output.toByteArray())
        assertEquals(sparse.size.toLong(), report.sparseBytesConsumed)
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

    @Test
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

        expectIllegalArgument {
            AndroidSparseImageExpander().expand(ByteArrayInputStream(sparse), ByteArrayOutputStream())
        }
    }

    @Test
    fun `rejects crc32 chunk that declares output blocks without writing output`() {
        val sparse = sparseImage(
            blockSize = 4,
            totalBlocks = 0,
            imageChecksum = 0,
            chunks = listOf(crc32Chunk(expectedCrc = 0, blocks = 1)),
        )

        assertRejectedWithoutOutput(sparse)
    }

    @Test
    fun `rejects truncated fixed image header without writing output`() {
        assertRejectedWithoutOutput(ByteArray(27))
    }

    @Test
    fun `rejects truncated extended image header without writing output`() {
        val sparse = sparseHeader(
            blockSize = 4,
            totalBlocks = 0,
            totalChunks = 0,
            imageChecksum = 0,
            fileHeaderSize = 32,
            chunkHeaderSize = 12,
        ) + byteArrayOf(1, 2)

        assertRejectedWithoutOutput(sparse)
    }

    @Test
    fun `rejects image header size below fixed header without writing output`() {
        val sparse = sparseHeader(
            blockSize = 4,
            totalBlocks = 0,
            totalChunks = 0,
            imageChecksum = 0,
            fileHeaderSize = 27,
            chunkHeaderSize = 12,
        )

        assertRejectedWithoutOutput(sparse)
    }

    @Test
    fun `rejects chunk header size below fixed header without writing output`() {
        val sparse = sparseHeader(
            blockSize = 4,
            totalBlocks = 0,
            totalChunks = 0,
            imageChecksum = 0,
            fileHeaderSize = 28,
            chunkHeaderSize = 11,
        )

        assertRejectedWithoutOutput(sparse)
    }

    @Test
    fun `rejects truncated fixed chunk header without writing output`() {
        val sparse = sparseHeader(
            blockSize = 4,
            totalBlocks = 0,
            totalChunks = 1,
            imageChecksum = 0,
            fileHeaderSize = 28,
            chunkHeaderSize = 12,
        ) + ByteArray(11)

        assertRejectedWithoutOutput(sparse)
    }

    @Test
    fun `rejects truncated extended chunk header without writing output`() {
        val sparse = sparseHeader(
            blockSize = 4,
            totalBlocks = 0,
            totalChunks = 1,
            imageChecksum = 0,
            fileHeaderSize = 28,
            chunkHeaderSize = 16,
        ) + chunkHeader(
            type = 0xCAC3,
            blocks = 0,
            totalSize = 16,
        ) + byteArrayOf(1, 2)

        assertRejectedWithoutOutput(sparse)
    }

    @Test
    fun `rejects maximum input limit before reading full fixed header`() {
        val sparse = sparseImage(
            blockSize = 4,
            totalBlocks = 0,
            imageChecksum = 0,
            chunks = emptyList(),
        )
        val output = ByteArrayOutputStream()

        expectIllegalArgument {
            AndroidSparseImageExpander(maximumInputBytes = 27).expand(
                ByteArrayInputStream(sparse),
                output,
            )
        }
        assertEquals(0, output.size())
    }

    @Test
    fun `rejects declared chunk count above configured limit without writing output`() {
        val sparse = sparseHeader(
            blockSize = 4,
            totalBlocks = 0,
            totalChunks = 2,
            imageChecksum = 0,
            fileHeaderSize = 28,
            chunkHeaderSize = 12,
        )
        val output = ByteArrayOutputStream()

        expectIllegalArgument {
            AndroidSparseImageExpander(maximumChunks = 1).expand(ByteArrayInputStream(sparse), output)
        }
        assertEquals(0, output.size())
    }

    @Test
    fun `rejects unknown chunk type without writing output`() {
        val sparse = sparseImage(
            blockSize = 4,
            totalBlocks = 0,
            imageChecksum = 0,
            chunks = listOf(Chunk(type = 0xCAFF, blocks = 0, payload = ByteArray(0))),
        )

        assertRejectedWithoutOutput(sparse)
    }

    @Test
    fun `rejects mismatched non zero header checksum after producing deterministic output`() {
        val raw = byteArrayOf(1, 2, 3, 4)
        val sparse = sparseImage(
            blockSize = 4,
            totalBlocks = 1,
            imageChecksum = 1,
            chunks = listOf(rawChunk(blocks = 1, payload = raw)),
        )
        val output = ByteArrayOutputStream()

        expectIllegalArgument {
            AndroidSparseImageExpander().expand(ByteArrayInputStream(sparse), output)
        }
        assertArrayEquals(raw, output.toByteArray())
    }

    @Test
    fun `rejects truncated raw payload`() {
        val sparse = sparseImage(
            blockSize = 4,
            totalBlocks = 1,
            imageChecksum = 0,
            chunks = listOf(rawChunk(blocks = 1, payload = byteArrayOf(1, 2, 3))),
            declaredRawPayloadSize = 4,
        )

        expectIllegalArgument {
            AndroidSparseImageExpander().expand(ByteArrayInputStream(sparse), ByteArrayOutputStream())
        }
    }

    @Test
    fun `rejects declared expansion above configured limit before writing output`() {
        val sparse = sparseImage(
            blockSize = 4096,
            totalBlocks = 2,
            imageChecksum = 0,
            chunks = listOf(dontCareChunk(blocks = 2)),
        )
        val output = ByteArrayOutputStream()

        expectIllegalArgument {
            AndroidSparseImageExpander(maximumExpandedBytes = 4096).expand(
                ByteArrayInputStream(sparse),
                output,
            )
        }
        assertEquals(0, output.size())
    }

    @Test
    fun `rejects chunks that exceed declared output block count without writing output`() {
        val sparse = sparseImage(
            blockSize = 4,
            totalBlocks = 1,
            imageChecksum = 0,
            chunks = listOf(dontCareChunk(blocks = 2)),
        )

        assertRejectedWithoutOutput(sparse)
    }

    private fun assertRejectedWithoutOutput(sparse: ByteArray) {
        val output = ByteArrayOutputStream()
        expectIllegalArgument {
            AndroidSparseImageExpander().expand(ByteArrayInputStream(sparse), output)
        }
        assertEquals(0, output.size())
    }

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException = try {
        block()
        fail("Expected IllegalArgumentException")
        error("unreachable")
    } catch (error: IllegalArgumentException) {
        error
    }

    private fun sparseImage(
        blockSize: Int,
        totalBlocks: Int,
        imageChecksum: Long,
        chunks: List<Chunk>,
        fileHeaderSize: Int = 28,
        chunkHeaderSize: Int = 12,
        declaredRawPayloadSize: Int? = null,
    ): ByteArray = ByteArrayOutputStream().also { output ->
        output.write(
            sparseHeader(
                blockSize = blockSize,
                totalBlocks = totalBlocks,
                totalChunks = chunks.size,
                imageChecksum = imageChecksum,
                fileHeaderSize = fileHeaderSize,
                chunkHeaderSize = chunkHeaderSize,
            ),
        )
        repeat((fileHeaderSize - 28).coerceAtLeast(0)) { output.write(0) }
        chunks.forEach { chunk ->
            val payloadSize = when {
                declaredRawPayloadSize != null && chunk.type == 0xCAC1 -> declaredRawPayloadSize
                else -> chunk.payload.size
            }
            output.write(
                chunkHeader(
                    type = chunk.type,
                    blocks = chunk.blocks,
                    totalSize = chunkHeaderSize + payloadSize,
                ),
            )
            repeat((chunkHeaderSize - 12).coerceAtLeast(0)) { output.write(0) }
            output.write(chunk.payload)
        }
    }.toByteArray()

    private fun sparseHeader(
        blockSize: Int,
        totalBlocks: Int,
        totalChunks: Int,
        imageChecksum: Long,
        fileHeaderSize: Int,
        chunkHeaderSize: Int,
    ): ByteArray = ByteBuffer.allocate(28)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0xED26FF3A.toInt())
        .putShort(1)
        .putShort(0)
        .putShort(fileHeaderSize.toShort())
        .putShort(chunkHeaderSize.toShort())
        .putInt(blockSize)
        .putInt(totalBlocks)
        .putInt(totalChunks)
        .putInt(imageChecksum.toInt())
        .array()

    private fun chunkHeader(type: Int, blocks: Int, totalSize: Int): ByteArray = ByteBuffer.allocate(12)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(type.toShort())
        .putShort(0)
        .putInt(blocks)
        .putInt(totalSize)
        .array()

    private fun rawChunk(blocks: Int, payload: ByteArray): Chunk = Chunk(0xCAC1, blocks, payload)

    private fun fillChunk(blocks: Int, fillPattern: ByteArray): Chunk {
        require(fillPattern.size == 4)
        return Chunk(0xCAC2, blocks, fillPattern)
    }

    private fun dontCareChunk(blocks: Int): Chunk = Chunk(0xCAC3, blocks, ByteArray(0))

    private fun crc32Chunk(expectedCrc: Long, blocks: Int = 0): Chunk = Chunk(
        type = 0xCAC4,
        blocks = blocks,
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
