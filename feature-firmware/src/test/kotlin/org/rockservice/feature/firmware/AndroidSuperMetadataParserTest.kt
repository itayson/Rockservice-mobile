package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSuperMetadataParserTest {
    private val parser = AndroidSuperMetadataParser(
        maximumInputBytes = 64L * 1024 * 1024,
        maximumMetadataSlotBytes = 1024 * 1024,
        maximumMetadataSlots = 8,
        maximumTableEntries = 1000,
    )

    @Test
    fun `parses valid liblp metadata and computes logical sizes`() {
        val fixture = superImage()

        val result = parser.parse(ByteArrayInputStream(fixture.bytes))

        assertEquals(AndroidSuperGeometrySource.PRIMARY, result.geometry.source)
        assertEquals(10, result.majorVersion)
        assertEquals(2, result.minorVersion)
        assertEquals("system_a", result.partitions.single().name)
        assertEquals(51_200L, result.partitions.single().logicalSizeBytes)
        assertEquals(51_200L, result.groups.single().allocatedSizeBytes)
        assertEquals("super", result.blockDevices.single().partitionName)
        assertEquals(
            fixture.primaryMetadataOffset + fixture.headerSize + fixture.tablesSize,
            result.bytesConsumed,
        )
    }

    @Test
    fun `parses selected backup metadata slot`() {
        val fixture = superImage(slotCount = 2)

        val result = parser.parse(
            source = ByteArrayInputStream(fixture.bytes),
            slotNumber = 1,
            metadataCopy = AndroidSuperMetadataCopy.BACKUP,
        )

        assertEquals(
            fixture.primaryMetadataOffset + fixture.metadataMaxSize * 3L,
            result.metadataOffsetBytes,
        )
        assertEquals(AndroidSuperMetadataCopy.BACKUP, result.metadataCopy)
        assertEquals(1, result.slotNumber)
    }

    @Test
    fun `uses backup geometry when primary geometry is corrupt`() {
        val fixture = superImage()
        fixture.bytes[fixture.primaryGeometryOffset] = 0

        val result = parser.parse(ByteArrayInputStream(fixture.bytes))

        assertEquals(AndroidSuperGeometrySource.BACKUP, result.geometry.source)
    }

    @Test
    fun `supports partial reads and zero progress skip`() {
        val fixture = superImage()

        val result = parser.parse(PartialInputStream(fixture.bytes, maximumChunk = 7))

        assertEquals("system_a", result.partitions.single().name)
        assertTrue(result.bytesConsumed > 12_288L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects divergent valid geometry copies`() {
        val fixture = superImage()
        putU32(fixture.bytes, fixture.backupGeometryOffset + 40, 8192)
        recomputeGeometryChecksum(fixture.bytes, fixture.backupGeometryOffset)
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects two corrupt geometry copies`() {
        val fixture = superImage()
        fixture.bytes[fixture.primaryGeometryOffset + 8] =
            (fixture.bytes[fixture.primaryGeometryOffset + 8].toInt() xor 1).toByte()
        fixture.bytes[fixture.backupGeometryOffset + 8] =
            (fixture.bytes[fixture.backupGeometryOffset + 8].toInt() xor 1).toByte()
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid header checksum`() {
        val fixture = superImage()
        val offset = fixture.primaryMetadataOffset.toInt() + 12
        fixture.bytes[offset] = (fixture.bytes[offset].toInt() xor 1).toByte()
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid tables checksum`() {
        val fixture = superImage()
        val offset = fixture.primaryMetadataOffset.toInt() + fixture.headerSize
        fixture.bytes[offset] = (fixture.bytes[offset].toInt() xor 1).toByte()
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unsupported metadata major version`() {
        val fixture = superImage()
        putU16(fixture.bytes, fixture.primaryMetadataOffset.toInt() + 4, 11)
        recomputeHeaderChecksum(fixture)
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects header size inconsistent with minor version`() {
        val fixture = superImage()
        putU32(fixture.bytes, fixture.primaryMetadataOffset.toInt() + 8, 128)
        recomputeHeaderChecksum(fixture)
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects noncontiguous tables`() {
        val fixture = superImage()
        putU32(fixture.bytes, fixture.primaryMetadataOffset.toInt() + 92, 60)
        recomputeHeaderChecksum(fixture)
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects wrong partition entry size`() {
        val fixture = superImage()
        putU32(fixture.bytes, fixture.primaryMetadataOffset.toInt() + 88, 51)
        recomputeHeaderChecksum(fixture)
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unsupported partition attributes for minor zero`() {
        val fixture = superImage(
            minorVersion = 0,
            partitions = listOf(PartitionFixture(attributes = 0x4)),
        )
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects duplicate partition names`() {
        val fixture = superImage(
            partitions = listOf(
                PartitionFixture(name = "system_a", firstExtent = 0),
                PartitionFixture(name = "system_a", firstExtent = 1),
            ),
            extents = listOf(
                ExtentFixture(targetData = 40),
                ExtentFixture(targetData = 140),
            ),
        )
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects partition extent range outside extent table`() {
        val fixture = superImage(
            partitions = listOf(PartitionFixture(firstExtent = 1)),
            extents = listOf(ExtentFixture()),
        )
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects partition group outside group table`() {
        val fixture = superImage(partitions = listOf(PartitionFixture(groupIndex = 1)))
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unknown extent target type`() {
        val fixture = superImage(extents = listOf(ExtentFixture(targetType = 7)))
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects zero extent with physical target data`() {
        val fixture = superImage(
            extents = listOf(ExtentFixture(targetType = 1, targetData = 1)),
        )
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects linear extent referencing missing block device`() {
        val fixture = superImage(extents = listOf(ExtentFixture(targetSource = 1)))
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects linear extent before first logical sector`() {
        val fixture = superImage(extents = listOf(ExtentFixture(targetData = 1)))
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects linear extent that exceeds block device size`() {
        val fixture = superImage(
            extents = listOf(ExtentFixture(sectors = 1000, targetData = 131_000)),
            blockDevices = listOf(
                BlockDeviceFixture(
                    firstLogicalSector = 40,
                    sizeBytes = 64L * 1024 * 1024,
                ),
            ),
        )
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects group allocation above maximum`() {
        val fixture = superImage(groups = listOf(GroupFixture(maximumSize = 4096)))
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects slot number outside geometry`() {
        val fixture = superImage(slotCount = 1)
        parser.parse(ByteArrayInputStream(fixture.bytes), slotNumber = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects metadata max size above configured resource limit`() {
        val fixture = superImage(metadataMaxSize = 4096)
        AndroidSuperMetadataParser(
            maximumInputBytes = 64L * 1024 * 1024,
            maximumMetadataSlotBytes = 2048,
            maximumMetadataSlots = 8,
            maximumTableEntries = 1000,
        ).parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects table entry count above configured limit`() {
        val fixture = superImage(
            partitions = listOf(
                PartitionFixture(name = "system_a", firstExtent = 0),
                PartitionFixture(name = "vendor_a", firstExtent = 1),
            ),
            extents = listOf(
                ExtentFixture(targetData = 40),
                ExtentFixture(targetData = 140),
            ),
        )
        AndroidSuperMetadataParser(
            maximumInputBytes = 64L * 1024 * 1024,
            maximumMetadataSlotBytes = 1024 * 1024,
            maximumMetadataSlots = 8,
            maximumTableEntries = 1,
        ).parse(ByteArrayInputStream(fixture.bytes))
    }

    private data class PartitionFixture(
        val name: String = "system_a",
        val attributes: Long = 1,
        val firstExtent: Int = 0,
        val extentCount: Int = 1,
        val groupIndex: Int = 0,
    )

    private data class ExtentFixture(
        val sectors: Long = 100,
        val targetType: Int = 0,
        val targetData: Long = 40,
        val targetSource: Int = 0,
    )

    private data class GroupFixture(
        val name: String = "default",
        val flags: Long = 0,
        val maximumSize: Long = 0,
    )

    private data class BlockDeviceFixture(
        val firstLogicalSector: Long? = null,
        val alignment: Long = 1024 * 1024,
        val alignmentOffset: Long = 0,
        val sizeBytes: Long = 64L * 1024 * 1024,
        val name: String = "super",
        val flags: Long = 0,
    )

    private data class SuperFixture(
        val bytes: ByteArray,
        val primaryGeometryOffset: Int,
        val backupGeometryOffset: Int,
        val primaryMetadataOffset: Long,
        val metadataMaxSize: Int,
        val headerSize: Int,
        val tablesSize: Int,
    )

    private fun superImage(
        minorVersion: Int = 2,
        metadataMaxSize: Int = 4096,
        slotCount: Int = 1,
        partitions: List<PartitionFixture> = listOf(PartitionFixture()),
        extents: List<ExtentFixture>? = null,
        groups: List<GroupFixture> = listOf(GroupFixture()),
        blockDevices: List<BlockDeviceFixture>? = null,
    ): SuperFixture {
        val totalMetadataBytes = 4096L + (4096L + metadataMaxSize.toLong() * slotCount) * 2L
        val firstLogicalSector = totalMetadataBytes / 512L
        val resolvedExtents = extents ?: listOf(ExtentFixture(targetData = firstLogicalSector))
        val resolvedDevices = blockDevices ?: listOf(
            BlockDeviceFixture(firstLogicalSector = firstLogicalSector),
        )
        val headerSize = if (minorVersion < 2) 128 else 256
        val metadata = metadataSlot(
            minorVersion = minorVersion,
            headerSize = headerSize,
            metadataMaxSize = metadataMaxSize,
            partitions = partitions,
            extents = resolvedExtents,
            groups = groups,
            blockDevices = resolvedDevices,
        )
        val tablesSize = readU32(metadata, 44).toInt()
        val geometry = geometryBlock(metadataMaxSize, slotCount, logicalBlockSize = 4096)
        val primaryGeometryOffset = 4096
        val backupGeometryOffset = 8192
        val primaryMetadataOffset = 12_288L
        val imageSize = primaryMetadataOffset + metadataMaxSize.toLong() * slotCount * 2L
        val image = ByteArray(imageSize.toInt())
        geometry.copyInto(image, primaryGeometryOffset)
        geometry.copyInto(image, backupGeometryOffset)

        repeat(slotCount) { slot ->
            metadata.copyInto(image, (primaryMetadataOffset + metadataMaxSize.toLong() * slot).toInt())
            metadata.copyInto(
                image,
                (primaryMetadataOffset + metadataMaxSize.toLong() * slotCount +
                    metadataMaxSize.toLong() * slot).toInt(),
            )
        }

        return SuperFixture(
            bytes = image,
            primaryGeometryOffset = primaryGeometryOffset,
            backupGeometryOffset = backupGeometryOffset,
            primaryMetadataOffset = primaryMetadataOffset,
            metadataMaxSize = metadataMaxSize,
            headerSize = headerSize,
            tablesSize = tablesSize,
        )
    }

    private fun geometryBlock(metadataMaxSize: Int, slotCount: Int, logicalBlockSize: Int): ByteArray {
        val block = ByteArray(4096)
        putU32(block, 0, 0x616C4467)
        putU32(block, 4, 52)
        putU32(block, 40, metadataMaxSize.toLong())
        putU32(block, 44, slotCount.toLong())
        putU32(block, 48, logicalBlockSize.toLong())
        recomputeGeometryChecksum(block, 0)
        return block
    }

    private fun metadataSlot(
        minorVersion: Int,
        headerSize: Int,
        metadataMaxSize: Int,
        partitions: List<PartitionFixture>,
        extents: List<ExtentFixture>,
        groups: List<GroupFixture>,
        blockDevices: List<BlockDeviceFixture>,
    ): ByteArray {
        val partitionTable = ByteArray(partitions.size * 52)
        partitions.forEachIndexed { index, value ->
            val offset = index * 52
            putName(partitionTable, offset, value.name)
            putU32(partitionTable, offset + 36, value.attributes)
            putU32(partitionTable, offset + 40, value.firstExtent.toLong())
            putU32(partitionTable, offset + 44, value.extentCount.toLong())
            putU32(partitionTable, offset + 48, value.groupIndex.toLong())
        }

        val extentTable = ByteArray(extents.size * 24)
        extents.forEachIndexed { index, value ->
            val offset = index * 24
            putU64(extentTable, offset, value.sectors)
            putU32(extentTable, offset + 8, value.targetType.toLong())
            putU64(extentTable, offset + 12, value.targetData)
            putU32(extentTable, offset + 20, value.targetSource.toLong())
        }

        val groupTable = ByteArray(groups.size * 48)
        groups.forEachIndexed { index, value ->
            val offset = index * 48
            putName(groupTable, offset, value.name)
            putU32(groupTable, offset + 36, value.flags)
            putU64(groupTable, offset + 40, value.maximumSize)
        }

        val blockTable = ByteArray(blockDevices.size * 64)
        blockDevices.forEachIndexed { index, value ->
            val offset = index * 64
            putU64(blockTable, offset, requireNotNull(value.firstLogicalSector))
            putU32(blockTable, offset + 8, value.alignment)
            putU32(blockTable, offset + 12, value.alignmentOffset)
            putU64(blockTable, offset + 16, value.sizeBytes)
            putName(blockTable, offset + 24, value.name)
            putU32(blockTable, offset + 60, value.flags)
        }

        val tables = partitionTable + extentTable + groupTable + blockTable
        require(headerSize + tables.size <= metadataMaxSize)
        val metadata = ByteArray(metadataMaxSize)
        putU32(metadata, 0, 0x414C5030)
        putU16(metadata, 4, 10)
        putU16(metadata, 6, minorVersion)
        putU32(metadata, 8, headerSize.toLong())
        putU32(metadata, 44, tables.size.toLong())
        sha256(tables).copyInto(metadata, 48)

        var tableOffset = 0
        putDescriptor(metadata, 80, tableOffset, partitions.size, 52)
        tableOffset += partitionTable.size
        putDescriptor(metadata, 92, tableOffset, extents.size, 24)
        tableOffset += extentTable.size
        putDescriptor(metadata, 104, tableOffset, groups.size, 48)
        tableOffset += groupTable.size
        putDescriptor(metadata, 116, tableOffset, blockDevices.size, 64)
        tables.copyInto(metadata, headerSize)
        recomputeHeaderChecksum(metadata, 0, headerSize)
        return metadata
    }

    private fun putDescriptor(target: ByteArray, offset: Int, tableOffset: Int, count: Int, size: Int) {
        putU32(target, offset, tableOffset.toLong())
        putU32(target, offset + 4, count.toLong())
        putU32(target, offset + 8, size.toLong())
    }

    private fun putName(target: ByteArray, offset: Int, value: String) {
        value.encodeToByteArray().copyInto(target, offset)
    }

    private fun recomputeGeometryChecksum(target: ByteArray, offset: Int) {
        target.fill(0, offset + 8, offset + 40)
        sha256(target.copyOfRange(offset, offset + 52)).copyInto(target, offset + 8)
    }

    private fun recomputeHeaderChecksum(fixture: SuperFixture) {
        recomputeHeaderChecksum(
            target = fixture.bytes,
            metadataOffset = fixture.primaryMetadataOffset.toInt(),
            headerSize = fixture.headerSize,
        )
    }

    private fun recomputeHeaderChecksum(target: ByteArray, metadataOffset: Int, headerSize: Int) {
        target.fill(0, metadataOffset + 12, metadataOffset + 44)
        sha256(target.copyOfRange(metadataOffset, metadataOffset + headerSize))
            .copyInto(target, metadataOffset + 12)
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Long) {
        repeat(4) { index ->
            target[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte()
        }
    }

    private fun putU64(target: ByteArray, offset: Int, value: Long) {
        repeat(8) { index ->
            target[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte()
        }
    }

    private fun readU32(target: ByteArray, offset: Int): Long =
        (target[offset].toLong() and 0xFFL) or
            ((target[offset + 1].toLong() and 0xFFL) shl 8) or
            ((target[offset + 2].toLong() and 0xFFL) shl 16) or
            ((target[offset + 3].toLong() and 0xFFL) shl 24)

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

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
            bytes.copyInto(target, offset, position, position + count)
            position += count
            return count
        }

        override fun skip(byteCount: Long): Long = 0
    }
}
