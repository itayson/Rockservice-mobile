package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class AndroidSparseSuperMetadataParserTest {
    @Test
    fun `parses bounded liblp metadata directly from sparse super image`() {
        val rawSuper = minimalRawSuperImage(metadataMaxSize = 4096)
        val sparseSuper = sparseRawImage(rawSuper, blockSize = 4096)

        val metadata = AndroidSparseSuperMetadataParser().parseIfPresent {
            ByteArrayInputStream(sparseSuper)
        }

        assertNotNull(metadata)
        requireNotNull(metadata)
        assertEquals(4096L, metadata.geometry.metadataMaxSizeBytes)
        assertEquals(1L, metadata.geometry.metadataSlotCount)
        assertEquals(4096L, metadata.geometry.logicalBlockSizeBytes)
        assertEquals(0, metadata.partitions.size)
        assertEquals(0, metadata.extents.size)
        assertEquals(0, metadata.groups.size)
        assertEquals(0, metadata.blockDevices.size)
    }

    @Test
    fun `returns null for sparse image without liblp geometry magic`() {
        val raw = ByteArray(16 * 1024)
        val sparse = sparseRawImage(raw, blockSize = 4096)

        val metadata = AndroidSparseSuperMetadataParser().parseIfPresent {
            ByteArrayInputStream(sparse)
        }

        assertNull(metadata)
    }

    @Test
    fun `rejects sparse super when required decoded prefix exceeds configured limit`() {
        val rawSuper = minimalRawSuperImage(metadataMaxSize = 4096)
        val sparseSuper = sparseRawImage(rawSuper, blockSize = 4096)

        expectIllegalArgument {
            AndroidSparseSuperMetadataParser(
                maximumDecodedPrefixBytes = 13 * 1024,
            ).parseIfPresent {
                ByteArrayInputStream(sparseSuper)
            }
        }
    }

    @Test
    fun `rejects truncated sparse payload while decoding geometry prefix`() {
        val rawSuper = minimalRawSuperImage(metadataMaxSize = 4096)
        val sparseSuper = sparseRawImage(rawSuper, blockSize = 4096).copyOfRange(
            0,
            28 + 12 + 8 * 1024,
        )

        expectIllegalArgument {
            AndroidSparseSuperMetadataParser().parseIfPresent {
                ByteArrayInputStream(sparseSuper)
            }
        }
    }

    private fun minimalRawSuperImage(metadataMaxSize: Int): ByteArray {
        require(metadataMaxSize % 512 == 0)
        val geometry = geometryBlock(metadataMaxSize)
        val metadata = metadataSlot(metadataMaxSize)
        return ByteArrayOutputStream().also { output ->
            output.write(ByteArray(4096))
            output.write(geometry)
            output.write(geometry)
            output.write(metadata)
        }.toByteArray()
    }

    private fun geometryBlock(metadataMaxSize: Int): ByteArray {
        val block = ByteArray(4096)
        val buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 0x616C4467)
        buffer.putInt(4, 52)
        buffer.putInt(40, metadataMaxSize)
        buffer.putInt(44, 1)
        buffer.putInt(48, 4096)

        val struct = block.copyOfRange(0, 52)
        struct.fill(0, fromIndex = 8, toIndex = 40)
        sha256(struct).copyInto(block, destinationOffset = 8)
        return block
    }

    private fun metadataSlot(metadataMaxSize: Int): ByteArray {
        val header = ByteArray(128)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 0x414C5030)
        buffer.putShort(4, 10)
        buffer.putShort(6, 0)
        buffer.putInt(8, 128)
        buffer.putInt(44, 0)
        sha256(ByteArray(0)).copyInto(header, destinationOffset = 48)
        putTableDescriptor(header, offset = 80, entrySize = 52)
        putTableDescriptor(header, offset = 92, entrySize = 24)
        putTableDescriptor(header, offset = 104, entrySize = 48)
        putTableDescriptor(header, offset = 116, entrySize = 64)

        val headerForChecksum = header.copyOf()
        headerForChecksum.fill(0, fromIndex = 12, toIndex = 44)
        sha256(headerForChecksum).copyInto(header, destinationOffset = 12)

        return ByteArray(metadataMaxSize).also { slot -> header.copyInto(slot) }
    }

    private fun putTableDescriptor(bytes: ByteArray, offset: Int, entrySize: Int) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(offset, 0)
        buffer.putInt(offset + 4, 0)
        buffer.putInt(offset + 8, entrySize)
    }

    private fun sparseRawImage(raw: ByteArray, blockSize: Int): ByteArray {
        require(raw.size % blockSize == 0)
        return ByteArrayOutputStream().also { output ->
            output.write(
                ByteBuffer.allocate(28)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0xED26FF3A.toInt())
                    .putShort(1)
                    .putShort(0)
                    .putShort(28)
                    .putShort(12)
                    .putInt(blockSize)
                    .putInt(raw.size / blockSize)
                    .putInt(1)
                    .putInt(0)
                    .array(),
            )
            output.write(
                ByteBuffer.allocate(12)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(0xCAC1.toShort())
                    .putShort(0)
                    .putInt(raw.size / blockSize)
                    .putInt(12 + raw.size)
                    .array(),
            )
            output.write(raw)
        }.toByteArray()
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException = try {
        block()
        fail("Expected IllegalArgumentException")
        error("unreachable")
    } catch (error: IllegalArgumentException) {
        error
    }
}
