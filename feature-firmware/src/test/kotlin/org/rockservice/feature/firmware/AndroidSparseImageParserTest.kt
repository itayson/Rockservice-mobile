package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidSparseImageParserTest {
    private val parser = AndroidSparseImageParser(
        maximumChunks = 100,
        maximumInputBytes = 1024 * 1024,
        maximumExpandedBytes = 1024 * 1024,
    )

    @Test
    fun `parses mixed sparse chunks without expanding raw data`() {
        val rawPayload = ByteArray(4096) { index -> (index and 0xFF).toByte() }
        val bytes = sparseImage(
            totalBlocks = 4,
            chunks = listOf(
                ChunkFixture(type = 0xCAC1, blocks = 1, payload = rawPayload),
                ChunkFixture(type = 0xCAC2, blocks = 1, payload = u32(0x11223344L)),
                ChunkFixture(type = 0xCAC3, blocks = 2),
                ChunkFixture(type = 0xCAC4, blocks = 0, payload = u32(0xAABBCCDDL)),
            ),
        )

        val result = parser.parse(ByteArrayInputStream(bytes))

        assertEquals(4L, result.header.totalBlocks)
        assertEquals(4L, result.header.totalChunks)
        assertEquals(16_384L, result.expandedSizeBytes)
        assertEquals(bytes.size.toLong(), result.sparseBytesConsumed)
        assertEquals(AndroidSparseChunkType.RAW, result.chunks[0].type)
        assertEquals(AndroidSparseChunkType.FILL, result.chunks[1].type)
        assertEquals(0x11223344L, result.chunks[1].fillValue)
        assertEquals(AndroidSparseChunkType.DONT_CARE, result.chunks[2].type)
        assertNull(result.chunks[2].fillValue)
        assertEquals(AndroidSparseChunkType.CRC32, result.chunks[3].type)
        assertEquals(0xAABBCCDDL, result.chunks[3].crc32)
        assertEquals(4L, result.chunks[3].outputStartBlock)
    }

    @Test
    fun `accepts higher minor version and extended header sizes`() {
        val bytes = sparseImage(
            minorVersion = 7,
            fileHeaderSize = 32,
            chunkHeaderSize = 16,
            totalBlocks = 1,
            chunks = listOf(ChunkFixture(type = 0xCAC3, blocks = 1)),
        )

        val result = parser.parse(ByteArrayInputStream(bytes))

        assertEquals(7, result.header.minorVersion)
        assertEquals(32, result.header.fileHeaderSize)
        assertEquals(16, result.header.chunkHeaderSize)
        assertEquals(1L, result.header.totalBlocks)
    }

    @Test
    fun `supports partial input stream reads`() {
        val bytes = sparseImage(
            totalBlocks = 1,
            chunks = listOf(ChunkFixture(type = 0xCAC3, blocks = 1)),
        )

        val result = parser.parse(PartialInputStream(bytes, maximumChunk = 3))

        assertEquals(1L, result.header.totalBlocks)
        assertEquals(bytes.size.toLong(), result.sparseBytesConsumed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid magic`() {
        val bytes = sparseImage(
            magic = 0x12345678L,
            totalBlocks = 1,
            chunks = listOf(ChunkFixture(type = 0xCAC3, blocks = 1)),
        )
        parser.parse(ByteArrayInputStream(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unsupported major version`() {
        val bytes = sparseImage(
            majorVersion = 2,
            totalBlocks = 1,
            chunks = listOf(ChunkFixture(type = 0xCAC3, blocks = 1)),
        )
        parser.parse(ByteArrayInputStream(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects sparse header smaller than baseline`() {
        val bytes = sparseImage(
            fileHeaderSize = 27,
            totalBlocks = 0,
            chunks = emptyList(),
        )
        parser.parse(ByteArrayInputStream(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects chunk header smaller than baseline`() {
        val bytes = sparseImage(
            chunkHeaderSize = 11,
            totalBlocks = 0,
            chunks = emptyList(),
        )
        parser.parse(ByteArrayInputStream(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid block size`() {
        val bytes = sparseImage(
            blockSize = 4097,
            totalBlocks = 1,
            chunks = listOf(ChunkFixture(type = 0xCAC3, blocks = 1)),
        )
        parser.parse(ByteArrayInputStream(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects raw payload size mismatch`() {
        val bytes = sparseImage(
            totalBlocks = 1,
            chunks = listOf(
                ChunkFixture(type = 0xCAC1, blocks = 1, payload = ByteArray(8)),
            ),
        )
        parser.parse(ByteArrayInputStream(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects fill payload not exactly four bytes`() {
        val bytes = sparseImage(
            totalBlocks = 1,
            chunks = listOf(
                ChunkFixture(type = 0xCAC2, blocks = 1, payload = ByteArray(3)),
            ),
        )
        parser.parse(ByteArrayInputStream(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects dont care chunk carrying payload`() {
        val bytes = sparseImage(
            totalBlocks = 1,
            chunks = listOf(
                ChunkFixture(type = 0xCAC3, blocks = 1, payload = byteArrayOf(1)),
            ),
        )
        parser.parse(ByteArrayInputStream(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unknown chunk type`() {
        val bytes = sparseImage(
            totalBlocks = 1,
            chunks = listOf(ChunkFixture(type = 0xCAFE, blocks = 1)),
        )
        parser.parse(ByteArrayInputStream(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects truncated raw payload`() {
        val complete = sparseImage(
            totalBlocks = 1,
            chunks = listOf(
                ChunkFixture(type = 0xCAC1, blocks = 1, payload = ByteArray(4096)),
            ),
        )
        parser.parse(ByteArrayInputStream(complete.copyOf(complete.size - 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects output block count different from header`() {
        val bytes = sparseImage(
            totalBlocks = 2,
            chunks = listOf(ChunkFixture(type = 0xCAC3, blocks = 1)),
        )
        parser.parse(ByteArrayInputStream(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects declared chunk count above configured limit`() {
        val bytes = sparseImage(
            totalBlocks = 2,
            chunks = listOf(
                ChunkFixture(type = 0xCAC3, blocks = 1),
                ChunkFixture(type = 0xCAC3, blocks = 1),
            ),
        )
        AndroidSparseImageParser(
            maximumChunks = 1,
            maximumInputBytes = 1024 * 1024,
            maximumExpandedBytes = 1024 * 1024,
        ).parse(ByteArrayInputStream(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects expanded image above configured limit`() {
        val bytes = sparseImage(
            totalBlocks = 2,
            chunks = listOf(ChunkFixture(type = 0xCAC3, blocks = 2)),
        )
        AndroidSparseImageParser(
            maximumChunks = 10,
            maximumInputBytes = 1024 * 1024,
            maximumExpandedBytes = 4096,
        ).parse(ByteArrayInputStream(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects input consumption above configured limit`() {
        val bytes = sparseImage(
            totalBlocks = 1,
            chunks = listOf(ChunkFixture(type = 0xCAC3, blocks = 1)),
        )
        AndroidSparseImageParser(
            maximumChunks = 10,
            maximumInputBytes = 28,
            maximumExpandedBytes = 1024 * 1024,
        ).parse(ByteArrayInputStream(bytes))
    }

    private data class ChunkFixture(
        val type: Int,
        val blocks: Long,
        val payload: ByteArray = ByteArray(0),
        val totalSizeOverride: Long? = null,
    )

    private fun sparseImage(
        magic: Long = 0xED26FF3AL,
        majorVersion: Int = 1,
        minorVersion: Int = 0,
        fileHeaderSize: Int = 28,
        chunkHeaderSize: Int = 12,
        blockSize: Long = 4096,
        totalBlocks: Long,
        chunks: List<ChunkFixture>,
        imageChecksum: Long = 0,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(u32(magic))
        output.write(u16(majorVersion))
        output.write(u16(minorVersion))
        output.write(u16(fileHeaderSize))
        output.write(u16(chunkHeaderSize))
        output.write(u32(blockSize))
        output.write(u32(totalBlocks))
        output.write(u32(chunks.size.toLong()))
        output.write(u32(imageChecksum))
        repeat(maxOf(0, fileHeaderSize - 28)) { output.write(0) }

        chunks.forEach { chunk ->
            output.write(u16(chunk.type))
            output.write(u16(0))
            output.write(u32(chunk.blocks))
            val totalSize = chunk.totalSizeOverride
                ?: (chunkHeaderSize.toLong() + chunk.payload.size.toLong())
            output.write(u32(totalSize))
            repeat(maxOf(0, chunkHeaderSize - 12)) { output.write(0) }
            output.write(chunk.payload)
        }
        return output.toByteArray()
    }

    private fun u16(value: Int): ByteArray {
        require(value in 0..0xFFFF)
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
        )
    }

    private fun u32(value: Long): ByteArray {
        require(value in 0..0xFFFF_FFFFL)
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte(),
        )
    }

    private class PartialInputStream(
        private val bytes: ByteArray,
        private val maximumChunk: Int,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int =
            if (position >= bytes.size) -1 else bytes[position++].toInt() and 0xFF

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, maximumChunk, bytes.size - position)
            bytes.copyInto(target, destinationOffset = offset, startIndex = position, endIndex = position + count)
            position += count
            return count
        }

        override fun skip(byteCount: Long): Long = 0
    }
}
