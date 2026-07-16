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
                AndroidSuperPartition("system", 0, 0, 3, 0),
            ),
            extents = listOf(
                AndroidSuperExtent(2, AndroidSuperExtentTargetType.LINEAR, 100, 0),
                AndroidSuperExtent(1, AndroidSuperExtentTargetType.ZERO, 0, 0),
                AndroidSuperExtent(3, AndroidSuperExtentTargetType.LINEAR, 200, 0),
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
        assertEquals(AndroidSuperLogicalExtentPlan.Zero(512), plan.extents[1])
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
            partitions = listOf(AndroidSuperPartition("empty", 0, 0, 0, 0)),
            extents = emptyList(),
        )

        val plan = AndroidSuperLogicalPartitionMapper().mapNamed(metadata, "empty")

        assertEquals(0L, plan.sizeBytes)
        assertTrue(plan.extents.isEmpty())
    }

    @Test
    fun `rejects partition extent range outside table`() {
        val metadata = metadata(
            partitions = listOf(AndroidSuperPartition("bad", 0, 1, 2, 0)),
            extents = listOf(AndroidSuperExtent(1, AndroidSuperExtentTargetType.ZERO, 0, 0)),
        )

        expectIllegalArgument {
            AndroidSuperLogicalPartitionMapper().map(metadata)
        }
    }

    @Test
    fun `rejects linear extent with invalid block device source`() {
        val metadata = metadata(
            partitions = listOf(AndroidSuperPartition("bad", 0, 0, 1, 0)),
            extents = listOf(AndroidSuperExtent(1, AndroidSuperExtentTargetType.LINEAR, 0, 3)),
        )

        expectIllegalArgument {
            AndroidSuperLogicalPartitionMapper().map(metadata)
        }
    }

    @Test
    fun `rejects sector to byte overflow`() {
        val metadata = metadata(
            partitions = listOf(AndroidSuperPartition("huge", 0, 0, 1, 0)),
            extents = listOf(
                AndroidSuperExtent(Long.MAX_VALUE, AndroidSuperExtentTargetType.ZERO, 0, 0),
            ),
        )

        expectIllegalArgument {
            AndroidSuperLogicalPartitionMapper().map(metadata)
        }
    }

    @Test
    fun `rejects missing named partition`() {
        val metadata = metadata(
            partitions = listOf(AndroidSuperPartition("system", 0, 0, 0, 0)),
            extents = emptyList(),
        )

        val error = expectIllegalArgument {
            AndroidSuperLogicalPartitionMapper().mapNamed(metadata, "vendor")
        }

        assertTrue(error.message.orEmpty().contains("vendor"))
    }

    private fun metadata(
        partitions: List<AndroidSuperPartition>,
        extents: List<AndroidSuperExtent>,
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
                AndroidSuperBlockDevice(
                    firstLogicalSector = 0,
                    alignmentBytes = 4096,
                    alignmentOffsetBytes = 0,
                    sizeBytes = 1024 * 1024,
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
