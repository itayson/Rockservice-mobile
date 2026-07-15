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
    fun `parses valid liblp 10_2 metadata and computes partition sizes`() {
        val fixture = superImage()

        val result = parser.parse(ByteArrayInputStream(fixture.bytes))

        assertEquals(AndroidSuperGeometrySource.PRIMARY, result.geometry.source)
        assertEquals(AndroidSuperMetadataCopy.PRIMARY, result.metadataCopy)
        assertEquals(10, result.majorVersion)
        assertEquals(2, result.minorVersion)
        assertEquals(256, result.headerSizeBytes)
        assertEquals(1, result.partitions.size)
        assertEquals("system_a", result.partitions.single().name)
        assertEquals(100L * 512L, result.partitions.single().logicalSizeBytes)
        assertEquals(100L * 512L, result.groups.single().allocatedSizeBytes)
        assertEquals("super", result.blockDevices.single().partitionName)
        assertEquals(fixture.primaryMetadataOffset + fixture.headerSize + fixture.tablesSize, result.bytesConsumed)
    }

    @Test
    fun `parses backup metadata copy for selected slot`() {
        val fixture = superImage(slotCount = 2)

        val result = parser.parse(
            source = ByteArrayInputStream(fixture.bytes),
            slotNumber = 1,
            metadataCopy = AndroidSuperMetadataCopy.BACKUP,
        )

        val expectedOffset = fixture.primaryMetadataOffset + fixture.metadataMaxSize * 3L
        assertEquals(expectedOffset, result.metadataOffsetBytes)
        assertEquals(1, result.slotNumber)
        assertEquals(AndroidSuperMetadataCopy.BACKUP, result.metadataCopy)
    }

    @Test
    fun `falls back to valid backup geometry`() {
        val fixture = superImage()
        fixture.bytes[fixture.primaryGeometryOffset] = 0

        val result = parser.parse(ByteArrayInputStream(fixture.bytes))

        assertEquals(AndroidSuperGeometrySource.BACKUP, result.geometry.source)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects divergent valid geometry copies`() {
        val fixture = superImage()
        putU32(fixture.bytes, fixture.backupGeometryOffset + 40, 8192)
        recomputeGeometryChecksum(fixture.bytes, fixture.backupGeometryOffset)

        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects when both geometry copies are invalid`() {
        val fixture = superImage()
        fixture.bytes[fixture.primaryGeometryOffset + 8] = 1
        fixture.bytes[fixture.backupGeometryOffset + 8] = 1

        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid metadata header checksum`() {
        val fixture = superImage()
        fixture.bytes[fixture.primaryMetadataOffset + 12] =
            (fixture.bytes[fixture.primaryMetadataOffset + 12].toInt() xor 0x01).toByte()

        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid metadata tables checksum`() {
        val fixture = superImage()
        val tableByte = fixture.primaryMetadataOffset + fixture.headerSize
        fixture.bytes[tableByte] = (fixture.bytes[tableByte].toInt() xor 0x01).toByte()

        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unsupported metadata version`() {
        val fixture = superImage()
        putU16(fixture.bytes, fixture.primaryMetadataOffset + 4, 11)
        recomputeHeaderChecksum(fixture.bytes, fixture.primaryMetadataOffset, fixture.headerSize)

        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects header size inconsistent with minor version`() {
        val fixture = superImage()
        putU32(fixture.bytes, fixture.primaryMetadataOffset + 8, 128)
        recomputeHeaderChecksum(fixture.bytes, fixture.primaryMetadataOffset, fixture.headerSize)

        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects noncontiguous table descriptors`() {
        val fixture = superImage()
        putU32(fixture.bytes, fixture.primaryMetadataOffset + 92, 60)
        recomputeHeaderChecksum(fixture.bytes, fixture.primaryMetadataOffset, fixture.headerSize)

        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid partition entry size`() {
        val fixture = superImage()
        putU32(fixture.bytes, fixture.primaryMetadataOffset + 88, 51)
        recomputeHeaderChecksum(fixture.bytes, fixture.primaryMetadataOffset, fixture.headerSize)

        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects new partition attributes in minor version zero`() {
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
            partitions = listOf(PartitionFixture(firstExtent = 1, extentCount = 1)),
            extents = listOf(ExtentFixture()),
        )

        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects partition group reference outside group table`() {
        val fixture = superImage(
            partitions = listOf(PartitionFixture(groupIndex = 1)),
        )

        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unknown extent target type`() {
        val fixture = superImage(extents = listOf(ExtentFixture(targetType = 7)))

        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects zero extent with physical target fields`() {
        val fixture = superImage(
            extents = listOf(
                ExtentFixture(targetType = 1, targetData = 1, targetSource = 0),
            ),
        )

        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects linear extent referencing missing block device`() {
        val fixture = superImage(
            extents = listOf(ExtentFixture(targetSource = 1)),
        )

        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects linear extent before first logical sector`() {
        val fixture = superImage(
            extents = listOf(ExtentFixture(targetData = 1)),
        )

        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects linear extent exceeding block device size`() {
        val fixture = superImage(
            extents = listOf(ExtentFixture(sectors = 1000, targetData = 120_000)),
            blockDevices = listOf(BlockDeviceFixture(sizeBytes = 64L * 1024 * 1024)),
        )

        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects group allocation exceeding maximum size`() {
        val fixture = superImage(
            groups = listOf(GroupFixture(maximumSize = 4096)),
        )

        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects slot number outside geometry slot count`() {
        val fixture = superImage(slotCount = 1)

        parser.parse(ByteArrayInputStream(fixture.bytes), slotNumber = 1)
    }

    @Test
    fun `supports partial reads and zero progress skip`() {
        val fixture = superImage()

        val result = parser.parse(PartialInputStream(fixture.bytes, maximumChunk = 7))

        assertEquals("system_a", result.partitions.single().name)
        assertTrue(result.bytesConsumed > 12_288L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects metadata slot size above configured resource limit`() {
        val fixture = superImage(metadataMaxSize = 4096)
        val restrictive = AndroidSuperMetadataParser(
            maximumInputBytes = 64L * 1024 * 1024,
            maximumMetadataSlotBytes = 2048,
            maximumMetadataSlots = 8,
            maximumTableEntries = 1000,
        )

        restrictive.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects table entry count above configured limit`() {
        val fixture = superImage()
        val restrictive = AndroidSuperMetadataParser(
            maximumInputBytes = 64L * 1024 * 1024,
            maximumMetadataSlotBytes = 1024 * 1024,
            maximumMetadataSlots = 8,
            maximumTableEntries = 0,
        )

        restrictive.parse(ByteArrayInputStream(fixture.bytes))
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
        logicalBlockSize: Int = 4096,
        partitions: List<PartitionFixture> = listOf(PartitionFixture()),
        extents: List<ExtentFixture>? = null,
        groups: List<GroupFixture> = listOf(GroupFixture()),
        blockDevices: List<BlockDeviceFixture>? = null,
    ): SuperFixture {
        val totalMetadataBytes = 4096L + (4096L + metadataMaxSize.toLong() * slotCount) * 2L
        val defaultFirstLogicalSector = totalMetadataBytes / 512L
        val resolvedExtents = extents ?: listOf(ExtentFixture(targetData = defaultFirstLogicalSector))
        val resolvedDevices = blockDevices ?: listOf(
            BlockDeviceFixture(firstLogicalSector = defaultFirstLogicalSector),
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

        val primaryGeometry = geometryBlock(
            metadataMaxSize = metadataMaxSize,
            slotCount = slotCount,
            logicalBlockSize = logicalBlockSize,
        )
        val backupGeometry = primaryGeometry.copyOf()
        val primaryGeometryOffset = 4096
        val backupGeometryOffset = 8192
        val primaryMetadataOffset = 12_288L
        val totalImageSize = primaryMetadataOffset + metadataMaxSize.toLong() * slotCount * 2L
        val image = ByteArray(totalImageSize.toInt())
        primaryGeometry.copyInto(image, primaryGeometryOffset)
        backupGeometry.copyInto(image, backupGeometryOffset)

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

    private fun geometryBlock(
        metadataMaxSize: Int,
        slotCount: Int,
        logicalBlockSize: Int,
    ): ByteArray {
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
        partitions.forEachIndexed { index, partition ->
            val offset = index * 52
            putName(partitionTable, offset, 36, partition.name)
            putU32(partitionTable, offset + 36, partition.attributes)
            putU32(partitionTable, offset + 40, partition.firstExtent.toLong())
            putU32(partitionTable, offset + 44, partition.extentCount.toLong())
            putU32(partitionTable, offset + 48, partition.groupIndex.toLong())
        }

        val extentTable = ByteArray(extents.size * 24)
        extents.forEachIndexed { index, extent ->
            val offset = index * 24
            putU64(extentTable, offset, extent.sectors)
            putU32(extentTable, offset + 8, extent.targetType.toLong())
            putU64(extentTable, offset + 12, extent.targetData)
            putU32(extentTable, offset + 20, extent.targetSource.toLong())
        }

        val groupTable = ByteArray(groups.size * 48)
        groups.forEachIndexed { index, group ->
            val offset = index * 48
            putName(groupTable, offset, 36, group.name)
            putU32(groupTable, offset + 36, group.flags)
            putU64(groupTable, offset + 40, group.maximumSize)
        }

        val blockTable = ByteArray(blockDevices.size * 64)
        blockDevices.forEachIndexed { index, device ->
            val offset = index * 64
            val firstLogical = requireNotNull(device.firstLogicalSector)
            putU64(blockTable, offset, firstLogical)
            putU32(blockTable, offset + 8, device.alignment)
            putU32(blockTable, offset + 12, device.alignmentOffset)
            putU64(blockTable, offset + 16, device.sizeBytes)
            putName(blockTable, offset + 24, 36, device.name)
            putU32(blockTable, offset + 60, device.flags)
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

    private fun putName(target: ByteArray, offset: Int, length: Int, name: String) {
        val bytes = name.encodeToByteArray()
        require(bytes.size <= length)
        bytes.copyInto(target, offset)
    }

    private fun recomputeGeometryChecksum(target: ByteArray, geometryOffset: Int) {
        target.fill(0, geometryOffset + 8, geometryOffset + 40)
        val digest = sha256(target.copyOfRange(geometryOffset, geometryOffset + 52))
        digest.copyInto(target, geometryOffset + 8)
    }

    private fun recomputeHeaderChecksum(target: ByteArray, metadataOffset: Long, headerSize: Int) {
        val offset = metadataOffset.toInt()
        target.fill(0, offset + 12, offset + 44)
        val digest = sha256(target.copyOfRange(offset, offset + headerSize))
        digest.copyInto(target, offset + 12)
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        require(value in 0..0xFFFF)
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Long) {
        require(value in 0..0xFFFF_FFFFL)
        repeat(4) { index ->
            target[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte()
        }
    }

    private fun putU64(target: ByteArray, offset: Int, value: Long) {
        require(value >= 0)
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
