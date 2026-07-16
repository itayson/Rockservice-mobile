package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(1, metadata.geometry.metadataSlotCount)
        assertEquals(4096L, metadata.geometry.logicalBlockSizeBytes)
        assertEquals(0, metadata.partitions.size)
        assertEquals(0, metadata.extents.size)
        assertEquals(0, metadata.groups.size)
        assertEquals(1, metadata.blockDevices.size)
        assertEquals("super", metadata.blockDevices.single().partitionName)
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
    fun `rejects truncated sparse super when primary geometry magic is present`() {
        val raw = ByteArrayOutputStream().also { output ->
            output.write(ByteArray(4096))
            output.write(geometryBlock(metadataMaxSize = 4096))
        }.toByteArray()
        val sparse = sparseRawImage(raw, blockSize = 4096)

        val error = expectIllegalArgument {
            AndroidSparseSuperMetadataParser().parseIfPresent {
                ByteArrayInputStream(sparse)
            }
        }

        assertTrue(error.message.orEmpty().contains("área de descoberta", ignoreCase = true))
    }

    @Test
    fun `rejects truncated sparse super when only backup geometry magic is present`() {
        val raw = ByteArray(8196)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(8192, 0x616C4467)
        val sparse = sparseRawImage(raw, blockSize = 4)

        val error = expectIllegalArgument {
            AndroidSparseSuperMetadataParser().parseIfPresent {
                ByteArrayInputStream(sparse)
            }
        }

        assertTrue(error.message.orEmpty().contains("área de descoberta", ignoreCase = true))
    }

    @Test
    fun `ignores corrupt geometry copy when planning bounded metadata prefix`() {
        val corruptPrimary = geometryBlock(metadataMaxSize = 8192).also { geometry ->
            geometry[8] = (geometry[8].toInt() xor 0x01).toByte()
        }
        val validBackup = geometryBlock(metadataMaxSize = 4096)
        val rawSuper = minimalRawSuperImage(
            metadataMaxSize = 4096,
            primaryGeometry = corruptPrimary,
            backupGeometry = validBackup,
        )
        val sparseSuper = sparseRawImage(rawSuper, blockSize = 4096)

        val metadata = AndroidSparseSuperMetadataParser(
            maximumDecodedPrefixBytes = 16 * 1024,
        ).parseIfPresent {
            ByteArrayInputStream(sparseSuper)
        }

        assertNotNull(metadata)
        requireNotNull(metadata)
        assertEquals(4096L, metadata.geometry.metadataMaxSizeBytes)
        assertEquals(AndroidSuperGeometrySource.BACKUP, metadata.geometry.source)
    }

    @Test
    fun `ignores malformed primary geometry struct size and uses valid backup`() {
        val malformedPrimary = geometryBlock(metadataMaxSize = 8192).also { geometry ->
            ByteBuffer.wrap(geometry).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 48)
        }
        val validBackup = geometryBlock(metadataMaxSize = 4096)
        val rawSuper = minimalRawSuperImage(
            metadataMaxSize = 4096,
            primaryGeometry = malformedPrimary,
            backupGeometry = validBackup,
        )
        val sparseSuper = sparseRawImage(rawSuper, blockSize = 4096)

        val metadata = AndroidSparseSuperMetadataParser(
            maximumDecodedPrefixBytes = 16 * 1024,
        ).parseIfPresent {
            ByteArrayInputStream(sparseSuper)
        }

        assertNotNull(metadata)
        requireNotNull(metadata)
        assertEquals(4096L, metadata.geometry.metadataMaxSizeBytes)
        assertEquals(AndroidSuperGeometrySource.BACKUP, metadata.geometry.source)
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

    private fun minimalRawSuperImage(
        metadataMaxSize: Int,
        primaryGeometry: ByteArray? = null,
        backupGeometry: ByteArray? = null,
    ): ByteArray {
        require(metadataMaxSize >= 192) {
            "metadataMaxSize deve comportar o cabeçalho e a tabela de block devices."
        }
        require(metadataMaxSize % 512 == 0)
        val primary = primaryGeometry ?: geometryBlock(metadataMaxSize)
        val backup = backupGeometry ?: geometryBlock(metadataMaxSize)
        val metadata = metadataSlot(metadataMaxSize)
        return ByteArrayOutputStream().also { output ->
            output.write(ByteArray(4096))
            output.write(primary)
            output.write(backup)
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
        val tables = blockDeviceTable(metadataMaxSize)
        val header = ByteArray(128)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 0x414C5030)
        buffer.putShort(4, 10)
        buffer.putShort(6, 0)
        buffer.putInt(8, 128)
        buffer.putInt(44, tables.size)
        sha256(tables).copyInto(header, destinationOffset = 48)
        putTableDescriptor(header, offset = 80, entryCount = 0, entrySize = 52)
        putTableDescriptor(header, offset = 92, entryCount = 0, entrySize = 24)
        putTableDescriptor(header, offset = 104, entryCount = 0, entrySize = 48)
        putTableDescriptor(header, offset = 116, entryCount = 1, entrySize = 64)

        val headerForChecksum = header.copyOf()
        headerForChecksum.fill(0, fromIndex = 12, toIndex = 44)
        sha256(headerForChecksum).copyInto(header, destinationOffset = 12)

        return ByteArray(metadataMaxSize).also { slot ->
            header.copyInto(slot)
            tables.copyInto(slot, destinationOffset = header.size)
        }
    }

    private fun blockDeviceTable(metadataMaxSize: Int): ByteArray {
        val table = ByteArray(64)
        val buffer = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        val metadataAreaBytes = 4096L + (2L * 4096L) + (2L * metadataMaxSize)
        buffer.putLong(0, metadataAreaBytes / 512L)
        buffer.putInt(8, 4096)
        buffer.putInt(12, 0)
        buffer.putLong(16, 1024L * 1024L)
        "super".toByteArray(Charsets.US_ASCII).copyInto(table, destinationOffset = 24)
        buffer.putInt(60, 0)
        return table
    }

    private fun putTableDescriptor(
        bytes: ByteArray,
        offset: Int,
        entryCount: Int,
        entrySize: Int,
    ) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(offset, 0)
        buffer.putInt(offset + 4, entryCount)
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
