package org.rockservice.feature.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidSuperLogicalPartitionMapperTest {
    @Test
    fun `maps mixed linear and zero extents in partition order`() {
        val metadata = metadata(
            partitions = listOf(
                partition(name = "system", firstExtentIndex = 0, extentCount = 3, logicalSizeBytes = 6L * 512L),
            ),
            extents = listOf(
                extent(sectorCount = 2, targetType = 0, targetData = 100, targetSource = 0),
                extent(sectorCount = 1, targetType = 1, targetData = 0, targetSource = 0),
                extent(sectorCount = 3, targetType = 0, targetData = 200, targetSource = 0),
            ),
        )

        val plan = AndroidSuperLogicalPartitionMapper().mapNamed(metadata, "system")

        assertEquals("system", plan.name)
        assertEquals(6L * 512L, plan.sizeBytes)
        assertEquals(3, plan.extents.size)
        assertEquals(
            AndroidSuperLogicalExtentPlan.Linear(
                blockDeviceIndex = 0,
                sourceOffsetBytes = 100L * 512L,
                lengthBytes = 2L * 512L,
            ),
            plan.extents[0],
        )
        assertEquals(AndroidSuperLogicalExtentPlan.Zero(512L), plan.extents[1])
        assertEquals(
            AndroidSuperLogicalExtentPlan.Linear(
                blockDeviceIndex = 0,
                sourceOffsetBytes = 200L * 512L,
                lengthBytes = 3L * 512L,
            ),
            plan.extents[2],
        )
    }

    @Test
    fun `maps empty partition to zero length plan`() {
        val metadata = metadata(
            partitions = listOf(partition(name = "empty", firstExtentIndex = 0, extentCount = 0, logicalSizeBytes = 0)),
            extents = emptyList(),
        )

        val plan = AndroidSuperLogicalPartitionMapper().mapNamed(metadata, "empty")

        assertEquals(0L, plan.sizeBytes)
        assertTrue(plan.extents.isEmpty())
    }

    @Test
    fun `rejects partition extent range outside table`() {
        val metadata = metadata(
            partitions = listOf(partition(name = "bad", firstExtentIndex = 1, extentCount = 2, logicalSizeBytes = 1024)),
            extents = listOf(extent(sectorCount = 1, targetType = 1, targetData = 0, targetSource = 0)),
        )

        expectIllegalArgument {
            AndroidSuperLogicalPartitionMapper().map(metadata)
        }
    }

    @Test
    fun `rejects linear extent with invalid block device source`() {
        val metadata = metadata(
            partitions = listOf(partition(name = "bad", firstExtentIndex = 0, extentCount = 1, logicalSizeBytes = 512)),
            extents = listOf(extent(sectorCount = 1, targetType = 0, targetData = 0, targetSource = 3)),
        )

        expectIllegalArgument {
            AndroidSuperLogicalPartitionMapper().map(metadata)
        }
    }

    @Test
    fun `rejects unsupported extent target with actionable message`() {
        val metadata = metadata(
            partitions = listOf(
                partition(name = "bad-target", firstExtentIndex = 0, extentCount = 1, logicalSizeBytes = 512),
            ),
            extents = listOf(
                extent(sectorCount = 1, targetType = 99, targetData = 0, targetSource = 0),
            ),
        )

        val error = expectIllegalArgument {
            AndroidSuperLogicalPartitionMapper().map(metadata)
        }

        assertTrue(error.message.orEmpty().contains("target não suportado", ignoreCase = true))
        assertTrue(error.message.orEmpty().contains("99"))
    }

    @Test
    fun `rejects sector to byte overflow`() {
        val metadata = metadata(
            partitions = listOf(
                partition(
                    name = "huge",
                    firstExtentIndex = 0,
                    extentCount = 1,
                    logicalSizeBytes = Long.MAX_VALUE,
                ),
            ),
            extents = listOf(
                extent(sectorCount = Long.MAX_VALUE, targetType = 1, targetData = 0, targetSource = 0),
            ),
        )

        expectIllegalArgument {
            AndroidSuperLogicalPartitionMapper().map(metadata)
        }
    }

    @Test
    fun `rejects logical size mismatch`() {
        val metadata = metadata(
            partitions = listOf(partition(name = "bad", firstExtentIndex = 0, extentCount = 1, logicalSizeBytes = 1024)),
            extents = listOf(extent(sectorCount = 1, targetType = 1, targetData = 0, targetSource = 0)),
        )

        expectIllegalArgument {
            AndroidSuperLogicalPartitionMapper().map(metadata)
        }
    }

    @Test
    fun `rejects missing named partition`() {
        val metadata = metadata(
            partitions = listOf(partition(name = "system", firstExtentIndex = 0, extentCount = 0, logicalSizeBytes = 0)),
            extents = emptyList(),
        )

        val error = expectIllegalArgument {
            AndroidSuperLogicalPartitionMapper().mapNamed(metadata, "vendor")
        }

        assertTrue(error.message.orEmpty().contains("vendor"))
    }

    private fun partition(
        name: String,
        firstExtentIndex: Int,
        extentCount: Int,
        logicalSizeBytes: Long,
    ): AndroidLogicalPartitionMetadata = AndroidLogicalPartitionMetadata(
        name = name,
        attributes = 0,
        firstExtentIndex = firstExtentIndex,
        extentCount = extentCount,
        groupIndex = 0,
        logicalSizeBytes = logicalSizeBytes,
    )

    private fun extent(
        sectorCount: Long,
        targetType: Int,
        targetData: Long,
        targetSource: Int,
    ): AndroidLogicalExtentMetadata = AndroidLogicalExtentMetadata(
        sectorCount = sectorCount,
        targetType = targetType,
        targetData = targetData,
        targetSource = targetSource,
    )

    private fun metadata(
        partitions: List<AndroidLogicalPartitionMetadata>,
        extents: List<AndroidLogicalExtentMetadata>,
    ): AndroidSuperMetadata {
        val emptyDescriptor = AndroidSuperTableDescriptor(0, 0, 0)
        return AndroidSuperMetadata(
            geometry = AndroidSuperGeometry(
                metadataMaxSizeBytes = 4096,
                metadataSlotCount = 1,
                logicalBlockSizeBytes = 4096,
                source = AndroidSuperGeometrySource.PRIMARY,
            ),
            metadataCopy = AndroidSuperMetadataCopy.PRIMARY,
            slotNumber = 0,
            metadataOffsetBytes = 12_288,
            majorVersion = 10,
            minorVersion = 2,
            headerSizeBytes = 256,
            tablesSizeBytes = 0,
            headerFlags = 0,
            partitionsDescriptor = emptyDescriptor,
            extentsDescriptor = emptyDescriptor,
            groupsDescriptor = emptyDescriptor,
            blockDevicesDescriptor = emptyDescriptor,
            partitions = partitions,
            extents = extents,
            groups = emptyList(),
            blockDevices = listOf(
                AndroidSuperBlockDeviceMetadata(
                    firstLogicalSector = 0,
                    alignmentBytes = 4096,
                    alignmentOffsetBytes = 0,
                    sizeBytes = 1024L * 1024L,
                    partitionName = "super",
                    flags = 0,
                ),
            ),
            bytesConsumed = 256,
        )
    }

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException = try {
        block()
        fail("Expected IllegalArgumentException")
        error("unreachable")
    } catch (error: IllegalArgumentException) {
        error
    }
}
