package org.rockservice.feature.firmware

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidSuperLogicalPartitionMapperDefensiveTest {
    @Test
    fun `rejects linear extent whose byte range overflows`() {
        val targetSector = Long.MAX_VALUE / 512L
        val metadata = metadata(
            partition = AndroidLogicalPartitionMetadata(
                name = "overflow",
                attributes = 0,
                firstExtentIndex = 0,
                extentCount = 1,
                groupIndex = 0,
                logicalSizeBytes = 1024,
            ),
            extent = AndroidLogicalExtentMetadata(
                sectorCount = 2,
                targetType = 0,
                targetData = targetSector,
                targetSource = 0,
            ),
            blockDeviceSizeBytes = targetSector * 512L,
        )

        val error = expectIllegalArgument {
            AndroidSuperLogicalPartitionMapper().map(metadata)
        }

        assertTrue(error.message.orEmpty().contains("Fim do extent"))
    }

    @Test
    fun `rejects unsupported target type with actionable message`() {
        val metadata = metadata(
            partition = AndroidLogicalPartitionMetadata(
                name = "unsupported",
                attributes = 0,
                firstExtentIndex = 0,
                extentCount = 1,
                groupIndex = 0,
                logicalSizeBytes = 512,
            ),
            extent = AndroidLogicalExtentMetadata(
                sectorCount = 1,
                targetType = 7,
                targetData = 0,
                targetSource = 0,
            ),
        )

        val error = expectIllegalArgument {
            AndroidSuperLogicalPartitionMapper().map(metadata)
        }

        assertTrue(error.message.orEmpty().contains("target não suportado"))
        assertTrue(error.message.orEmpty().contains("7"))
    }

    private fun metadata(
        partition: AndroidLogicalPartitionMetadata,
        extent: AndroidLogicalExtentMetadata,
        blockDeviceSizeBytes: Long = 1024L * 1024L,
    ): AndroidSuperMetadata {
        val emptyDescriptor = AndroidSuperTableDescriptor(0, 0, 0)
        return AndroidSuperMetadata(
            geometry = AndroidSuperGeometry(4096, 1, 4096, AndroidSuperGeometrySource.PRIMARY),
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
            partitions = listOf(partition),
            extents = listOf(extent),
            groups = emptyList(),
            blockDevices = listOf(
                AndroidSuperBlockDeviceMetadata(
                    firstLogicalSector = 0,
                    alignmentBytes = 4096,
                    alignmentOffsetBytes = 0,
                    sizeBytes = blockDeviceSizeBytes,
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
