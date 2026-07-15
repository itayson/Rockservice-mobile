package org.rockservice.feature.firmware

import java.io.InputStream
import java.security.MessageDigest

/** Which geometry copy supplied the validated liblp geometry. */
enum class AndroidSuperGeometrySource {
    PRIMARY,
    BACKUP,
}

/** Which metadata copy should be parsed for the requested liblp slot. */
enum class AndroidSuperMetadataCopy {
    PRIMARY,
    BACKUP,
}

/** Validated immutable geometry stored in the raw super partition metadata area. */
data class AndroidSuperGeometry(
    val metadataMaxSizeBytes: Long,
    val metadataSlotCount: Int,
    val logicalBlockSizeBytes: Long,
    val source: AndroidSuperGeometrySource,
)

/** Validated liblp table descriptor. Offsets are relative to the end of the metadata header. */
data class AndroidSuperTableDescriptor(
    val offsetBytes: Long,
    val entryCount: Int,
    val entrySizeBytes: Int,
)

/** One logical partition entry and its computed logical size. */
data class AndroidLogicalPartitionMetadata(
    val name: String,
    val attributes: Long,
    val firstExtentIndex: Int,
    val extentCount: Int,
    val groupIndex: Int,
    val logicalSizeBytes: Long,
)

/** One liblp logical extent. */
data class AndroidLogicalExtentMetadata(
    val sectorCount: Long,
    val targetType: Int,
    val targetData: Long,
    val targetSource: Int,
)

/** One liblp partition group. */
data class AndroidPartitionGroupMetadata(
    val name: String,
    val flags: Long,
    val maximumSizeBytes: Long,
    val allocatedSizeBytes: Long,
)

/** One physical block device referenced by liblp extents. */
data class AndroidSuperBlockDeviceMetadata(
    val firstLogicalSector: Long,
    val alignmentBytes: Long,
    val alignmentOffsetBytes: Long,
    val sizeBytes: Long,
    val partitionName: String,
    val flags: Long,
)

/** Parsed and cross-validated metadata for one raw super metadata slot. */
data class AndroidSuperMetadata(
    val geometry: AndroidSuperGeometry,
    val metadataCopy: AndroidSuperMetadataCopy,
    val slotNumber: Int,
    val metadataOffsetBytes: Long,
    val majorVersion: Int,
    val minorVersion: Int,
    val headerSizeBytes: Int,
    val tablesSizeBytes: Int,
    val headerFlags: Long,
    val partitionsDescriptor: AndroidSuperTableDescriptor,
    val extentsDescriptor: AndroidSuperTableDescriptor,
    val groupsDescriptor: AndroidSuperTableDescriptor,
    val blockDevicesDescriptor: AndroidSuperTableDescriptor,
    val partitions: List<AndroidLogicalPartitionMetadata>,
    val extents: List<AndroidLogicalExtentMetadata>,
    val groups: List<AndroidPartitionGroupMetadata>,
    val blockDevices: List<AndroidSuperBlockDeviceMetadata>,
    val bytesConsumed: Long,
)

/**
 * Parses raw Android dynamic-partition metadata from a super image without mapping or extracting
 * logical partition contents.
 *
 * The parser validates both geometry copies when available, SHA-256 checksums, metadata version,
 * table bounds and overlap, entry sizes, partition references, extent target ranges and group size
 * limits. Sparse super images must be converted or streamed through a separate sparse decoder; this
 * parser intentionally expects the raw super layout.
 */
class AndroidSuperMetadataParser(
    private val maximumInputBytes: Long = DEFAULT_MAXIMUM_INPUT_BYTES,
    private val maximumMetadataSlotBytes: Long = DEFAULT_MAXIMUM_METADATA_SLOT_BYTES,
    private val maximumMetadataSlots: Int = DEFAULT_MAXIMUM_METADATA_SLOTS,
    private val maximumTableEntries: Int = DEFAULT_MAXIMUM_TABLE_ENTRIES,
) {
    init {
        require(maximumInputBytes > 0) { "maximumInputBytes deve ser maior que zero." }
        require(maximumMetadataSlotBytes > 0) { "maximumMetadataSlotBytes deve ser maior que zero." }
        require(maximumMetadataSlots > 0) { "maximumMetadataSlots deve ser maior que zero." }
        require(maximumTableEntries > 0) { "maximumTableEntries deve ser maior que zero." }
    }

    /** Parses one selected metadata slot from a raw super image. */
    fun parse(
        source: InputStream,
        slotNumber: Int = 0,
        metadataCopy: AndroidSuperMetadataCopy = AndroidSuperMetadataCopy.PRIMARY,
    ): AndroidSuperMetadata {
        require(slotNumber >= 0) { "slotNumber deve ser não negativo." }

        val input = LimitedInput(source, maximumInputBytes)
        input.skipExactly(LP_PARTITION_RESERVED_BYTES, "área reservada inicial da super")
        val primaryGeometryBlock = input.readExactly(LP_METADATA_GEOMETRY_SIZE, "geometria primária")
        val backupGeometryBlock = input.readExactly(LP_METADATA_GEOMETRY_SIZE, "geometria de backup")
        val geometry = chooseGeometry(primaryGeometryBlock, backupGeometryBlock)

        require(slotNumber < geometry.metadataSlotCount) {
            "Slot de metadata $slotNumber fora do intervalo 0..${geometry.metadataSlotCount - 1}."
        }

        val metadataOffset = metadataOffset(
            geometry = geometry,
            slotNumber = slotNumber,
            metadataCopy = metadataCopy,
        )
        require(metadataOffset >= input.bytesConsumed) {
            "Offset de metadata calculado retrocede no fluxo de entrada."
        }
        input.skipExactly(
            byteCount = metadataOffset - input.bytesConsumed,
            label = "espaço até metadata $metadataCopy slot $slotNumber",
        )

        val baseHeader = input.readExactly(METADATA_HEADER_V1_0_SIZE, "header base liblp")
        val magic = baseHeader.readUInt32Le(HEADER_MAGIC_OFFSET)
        require(magic == LP_METADATA_HEADER_MAGIC) { "Magic do header liblp inválida." }

        val majorVersion = baseHeader.readUInt16Le(HEADER_MAJOR_VERSION_OFFSET)
        val minorVersion = baseHeader.readUInt16Le(HEADER_MINOR_VERSION_OFFSET)
        require(majorVersion == LP_METADATA_MAJOR_VERSION) {
            "Versão major liblp incompatível: $majorVersion."
        }
        require(minorVersion in LP_METADATA_MINOR_VERSION_MIN..LP_METADATA_MINOR_VERSION_MAX) {
            "Versão minor liblp incompatível: $minorVersion."
        }

        val expectedHeaderSize = if (minorVersion < LP_METADATA_VERSION_FOR_EXPANDED_HEADER) {
            METADATA_HEADER_V1_0_SIZE
        } else {
            METADATA_HEADER_V1_2_SIZE
        }
        val declaredHeaderSize = baseHeader.readUInt32Le(HEADER_SIZE_OFFSET).toIntExact("header_size")
        require(declaredHeaderSize == expectedHeaderSize) {
            "Header liblp declara $declaredHeaderSize bytes; esperado para 10.$minorVersion: " +
                "$expectedHeaderSize."
        }

        val header = ByteArray(expectedHeaderSize)
        baseHeader.copyInto(header)
        if (expectedHeaderSize > baseHeader.size) {
            input.readExactly(
                target = header,
                offset = baseHeader.size,
                byteCount = expectedHeaderSize - baseHeader.size,
                label = "extensão do header liblp",
            )
        }
        validateSha256WithZeroedRange(
            bytes = header,
            checksumOffset = HEADER_CHECKSUM_OFFSET,
            checksumLength = SHA256_SIZE,
            expectedChecksum = header.copyOfRange(
                HEADER_CHECKSUM_OFFSET,
                HEADER_CHECKSUM_OFFSET + SHA256_SIZE,
            ),
            label = "header liblp",
        )

        val tablesSize = header.readUInt32Le(TABLES_SIZE_OFFSET).toIntExact("tables_size")
        require(tablesSize.toLong() <= geometry.metadataMaxSizeBytes) {
            "Tabelas liblp usam $tablesSize bytes; metadata_max_size: " +
                "${geometry.metadataMaxSizeBytes}."
        }
        require(expectedHeaderSize.toLong() + tablesSize.toLong() <= geometry.metadataMaxSizeBytes) {
            "Header e tabelas liblp excedem metadata_max_size."
        }

        val partitionsDescriptor = header.readDescriptor(PARTITIONS_DESCRIPTOR_OFFSET, "partitions")
        val extentsDescriptor = header.readDescriptor(EXTENTS_DESCRIPTOR_OFFSET, "extents")
        val groupsDescriptor = header.readDescriptor(GROUPS_DESCRIPTOR_OFFSET, "groups")
        val blockDevicesDescriptor = header.readDescriptor(BLOCK_DEVICES_DESCRIPTOR_OFFSET, "block_devices")

        validateDescriptorEntrySize(partitionsDescriptor, PARTITION_ENTRY_SIZE, "partitions")
        validateDescriptorEntrySize(extentsDescriptor, EXTENT_ENTRY_SIZE, "extents")
        validateDescriptorEntrySize(groupsDescriptor, GROUP_ENTRY_SIZE, "groups")
        validateDescriptorEntrySize(blockDevicesDescriptor, BLOCK_DEVICE_ENTRY_SIZE, "block_devices")

        val descriptors = listOf(
            "partitions" to partitionsDescriptor,
            "extents" to extentsDescriptor,
            "groups" to groupsDescriptor,
            "block_devices" to blockDevicesDescriptor,
        )
        descriptors.forEach { (name, descriptor) ->
            validateDescriptorBounds(descriptor, tablesSize, name)
            require(descriptor.entryCount <= maximumTableEntries) {
                "Tabela $name declara ${descriptor.entryCount} entradas; limite: $maximumTableEntries."
            }
        }
        validateNonOverlappingTables(descriptors)

        val tables = input.readExactly(tablesSize, "tabelas liblp")
        val expectedTablesChecksum = header.copyOfRange(
            TABLES_CHECKSUM_OFFSET,
            TABLES_CHECKSUM_OFFSET + SHA256_SIZE,
        )
        require(sha256(tables).contentEquals(expectedTablesChecksum)) {
            "Checksum SHA-256 das tabelas liblp inválido."
        }

        val rawExtents = parseExtents(tables, extentsDescriptor)
        val rawGroups = parseGroups(tables, groupsDescriptor)
        val blockDevices = parseBlockDevices(tables, blockDevicesDescriptor)
        require(blockDevices.isNotEmpty()) { "Metadata liblp não contém block device principal." }

        validateExtents(rawExtents, blockDevices)
        val rawPartitions = parsePartitions(
            tables = tables,
            descriptor = partitionsDescriptor,
            minorVersion = minorVersion,
            extentCount = rawExtents.size,
            groupCount = rawGroups.size,
        )
        validateUniquePartitionNames(rawPartitions)

        val partitionSizes = rawPartitions.map { partition ->
            computePartitionSize(partition, rawExtents)
        }
        val groupAllocatedSizes = LongArray(rawGroups.size)
        rawPartitions.forEachIndexed { index, partition ->
            groupAllocatedSizes[partition.groupIndex] = checkedAdd(
                left = groupAllocatedSizes[partition.groupIndex],
                right = partitionSizes[index],
                label = "Tamanho alocado do grupo ${rawGroups[partition.groupIndex].name}",
            )
        }
        val groups = rawGroups.mapIndexed { index, group ->
            val allocated = groupAllocatedSizes[index]
            require(group.maximumSizeBytes == 0L || allocated <= group.maximumSizeBytes) {
                "Grupo ${group.name} aloca $allocated bytes; máximo declarado: " +
                    "${group.maximumSizeBytes}."
            }
            group.copy(allocatedSizeBytes = allocated)
        }
        val partitions = rawPartitions.mapIndexed { index, partition ->
            partition.copy(logicalSizeBytes = partitionSizes[index])
        }

        return AndroidSuperMetadata(
            geometry = geometry,
            metadataCopy = metadataCopy,
            slotNumber = slotNumber,
            metadataOffsetBytes = metadataOffset,
            majorVersion = majorVersion,
            minorVersion = minorVersion,
            headerSizeBytes = expectedHeaderSize,
            tablesSizeBytes = tablesSize,
            headerFlags = if (minorVersion >= LP_METADATA_VERSION_FOR_EXPANDED_HEADER) {
                header.readUInt32Le(HEADER_FLAGS_OFFSET)
            } else {
                0L
            },
            partitionsDescriptor = partitionsDescriptor,
            extentsDescriptor = extentsDescriptor,
            groupsDescriptor = groupsDescriptor,
            blockDevicesDescriptor = blockDevicesDescriptor,
            partitions = partitions,
            extents = rawExtents,
            groups = groups,
            blockDevices = blockDevices,
            bytesConsumed = input.bytesConsumed,
        )
    }

    private fun chooseGeometry(
        primaryBlock: ByteArray,
        backupBlock: ByteArray,
    ): AndroidSuperGeometry {
        val primary = try {
            Result.success(parseGeometry(primaryBlock, AndroidSuperGeometrySource.PRIMARY))
        } catch (error: IllegalArgumentException) {
            Result.failure(error)
        }
        val backup = try {
            Result.success(parseGeometry(backupBlock, AndroidSuperGeometrySource.BACKUP))
        } catch (error: IllegalArgumentException) {
            Result.failure(error)
        }

        if (primary.isSuccess && backup.isSuccess) {
            val primaryGeometry = primary.getOrThrow()
            val backupGeometry = backup.getOrThrow()
            require(primaryGeometry.copy(source = AndroidSuperGeometrySource.PRIMARY) ==
                backupGeometry.copy(source = AndroidSuperGeometrySource.PRIMARY)) {
                "Geometrias primária e backup são válidas, mas divergem."
            }
            return primaryGeometry
        }
        if (primary.isSuccess) return primary.getOrThrow()
        if (backup.isSuccess) return backup.getOrThrow()

        throw IllegalArgumentException(
            "Geometrias liblp primária e backup inválidas. Primária: " +
                "${primary.exceptionOrNull()?.message}; backup: ${backup.exceptionOrNull()?.message}",
        )
    }

    private fun parseGeometry(
        block: ByteArray,
        source: AndroidSuperGeometrySource,
    ): AndroidSuperGeometry {
        require(block.size == LP_METADATA_GEOMETRY_SIZE) { "Bloco de geometria liblp truncado." }
        val struct = block.copyOfRange(0, GEOMETRY_STRUCT_SIZE)
        require(struct.readUInt32Le(GEOMETRY_MAGIC_OFFSET) == LP_METADATA_GEOMETRY_MAGIC) {
            "Magic da geometria liblp inválida."
        }
        val structSize = struct.readUInt32Le(GEOMETRY_STRUCT_SIZE_OFFSET).toIntExact("geometry.struct_size")
        require(structSize == GEOMETRY_STRUCT_SIZE) {
            "Geometria liblp declara $structSize bytes; esperado: $GEOMETRY_STRUCT_SIZE."
        }
        validateSha256WithZeroedRange(
            bytes = struct,
            checksumOffset = GEOMETRY_CHECKSUM_OFFSET,
            checksumLength = SHA256_SIZE,
            expectedChecksum = struct.copyOfRange(
                GEOMETRY_CHECKSUM_OFFSET,
                GEOMETRY_CHECKSUM_OFFSET + SHA256_SIZE,
            ),
            label = "geometria liblp",
        )

        val metadataMaxSize = struct.readUInt32Le(GEOMETRY_METADATA_MAX_SIZE_OFFSET)
        val slotCount = struct.readUInt32Le(GEOMETRY_SLOT_COUNT_OFFSET).toIntExact("metadata_slot_count")
        val logicalBlockSize = struct.readUInt32Le(GEOMETRY_LOGICAL_BLOCK_SIZE_OFFSET)

        require(metadataMaxSize > 0 && metadataMaxSize % LP_SECTOR_SIZE == 0L) {
            "metadata_max_size deve ser positivo e alinhado a $LP_SECTOR_SIZE bytes."
        }
        require(metadataMaxSize <= maximumMetadataSlotBytes) {
            "metadata_max_size $metadataMaxSize excede o limite configurado de " +
                "$maximumMetadataSlotBytes bytes."
        }
        require(slotCount in 1..maximumMetadataSlots) {
            "metadata_slot_count $slotCount fora do limite 1..$maximumMetadataSlots."
        }
        require(logicalBlockSize > 0 && logicalBlockSize % LP_SECTOR_SIZE == 0L) {
            "logical_block_size deve ser positivo e múltiplo de $LP_SECTOR_SIZE bytes."
        }

        return AndroidSuperGeometry(
            metadataMaxSizeBytes = metadataMaxSize,
            metadataSlotCount = slotCount,
            logicalBlockSizeBytes = logicalBlockSize,
            source = source,
        )
    }

    private fun metadataOffset(
        geometry: AndroidSuperGeometry,
        slotNumber: Int,
        metadataCopy: AndroidSuperMetadataCopy,
    ): Long {
        val metadataAreaStart = LP_PARTITION_RESERVED_BYTES + LP_METADATA_GEOMETRY_SIZE * 2L
        val slotOffset = checkedMultiply(
            left = geometry.metadataMaxSizeBytes,
            right = slotNumber.toLong(),
            label = "Offset do slot liblp",
        )
        return when (metadataCopy) {
            AndroidSuperMetadataCopy.PRIMARY -> checkedAdd(
                metadataAreaStart,
                slotOffset,
                "Offset de metadata primária",
            )

            AndroidSuperMetadataCopy.BACKUP -> {
                val primaryAreaSize = checkedMultiply(
                    left = geometry.metadataMaxSizeBytes,
                    right = geometry.metadataSlotCount.toLong(),
                    label = "Área de metadata primária",
                )
                checkedAdd(
                    checkedAdd(metadataAreaStart, primaryAreaSize, "Início de metadata backup"),
                    slotOffset,
                    "Offset de metadata backup",
                )
            }
        }
    }

    private fun parsePartitions(
        tables: ByteArray,
        descriptor: AndroidSuperTableDescriptor,
        minorVersion: Int,
        extentCount: Int,
        groupCount: Int,
    ): List<AndroidLogicalPartitionMetadata> {
        val validAttributes = if (minorVersion >= LP_METADATA_VERSION_FOR_UPDATED_ATTR) {
            LP_PARTITION_ATTRIBUTE_MASK
        } else {
            LP_PARTITION_ATTRIBUTE_MASK_V0
        }

        return List(descriptor.entryCount) { index ->
            val offset = descriptor.entryOffset(index)
            val name = tables.readFixedName(offset, PARTITION_NAME_SIZE, strictPartitionName = true)
            val attributes = tables.readUInt32Le(offset + 36)
            require(attributes and validAttributes.inv() == 0L) {
                "Partição $name possui atributos não suportados: 0x${attributes.toString(16)}."
            }
            val firstExtent = tables.readUInt32Le(offset + 40).toIntExact("first_extent_index")
            val numExtents = tables.readUInt32Le(offset + 44).toIntExact("num_extents")
            val groupIndex = tables.readUInt32Le(offset + 48).toIntExact("group_index")
            require(firstExtent <= extentCount && numExtents <= extentCount - firstExtent) {
                "Partição $name referencia extents fora da tabela."
            }
            require(groupIndex < groupCount) { "Partição $name referencia grupo inexistente $groupIndex." }

            AndroidLogicalPartitionMetadata(
                name = name,
                attributes = attributes,
                firstExtentIndex = firstExtent,
                extentCount = numExtents,
                groupIndex = groupIndex,
                logicalSizeBytes = 0L,
            )
        }
    }

    private fun parseExtents(
        tables: ByteArray,
        descriptor: AndroidSuperTableDescriptor,
    ): List<AndroidLogicalExtentMetadata> =
        List(descriptor.entryCount) { index ->
            val offset = descriptor.entryOffset(index)
            AndroidLogicalExtentMetadata(
                sectorCount = tables.readUInt64LeAsLong(offset),
                targetType = tables.readUInt32Le(offset + 8).toIntExact("extent.target_type"),
                targetData = tables.readUInt64LeAsLong(offset + 12),
                targetSource = tables.readUInt32Le(offset + 20).toIntExact("extent.target_source"),
            )
        }

    private fun parseGroups(
        tables: ByteArray,
        descriptor: AndroidSuperTableDescriptor,
    ): List<AndroidPartitionGroupMetadata> =
        List(descriptor.entryCount) { index ->
            val offset = descriptor.entryOffset(index)
            AndroidPartitionGroupMetadata(
                name = tables.readFixedName(offset, GROUP_NAME_SIZE, strictPartitionName = false),
                flags = tables.readUInt32Le(offset + 36),
                maximumSizeBytes = tables.readUInt64LeAsLong(offset + 40),
                allocatedSizeBytes = 0L,
            )
        }

    private fun parseBlockDevices(
        tables: ByteArray,
        descriptor: AndroidSuperTableDescriptor,
    ): List<AndroidSuperBlockDeviceMetadata> =
        List(descriptor.entryCount) { index ->
            val offset = descriptor.entryOffset(index)
            val device = AndroidSuperBlockDeviceMetadata(
                firstLogicalSector = tables.readUInt64LeAsLong(offset),
                alignmentBytes = tables.readUInt32Le(offset + 8),
                alignmentOffsetBytes = tables.readUInt32Le(offset + 12),
                sizeBytes = tables.readUInt64LeAsLong(offset + 16),
                partitionName = tables.readFixedName(
                    offset + 24,
                    BLOCK_DEVICE_NAME_SIZE,
                    strictPartitionName = false,
                ),
                flags = tables.readUInt32Le(offset + 60),
            )
            require(device.sizeBytes > 0 && device.sizeBytes % LP_SECTOR_SIZE == 0L) {
                "Block device ${device.partitionName} possui tamanho inválido ${device.sizeBytes}."
            }
            require(device.firstLogicalSector <= device.sizeBytes / LP_SECTOR_SIZE) {
                "Block device ${device.partitionName} possui first_logical_sector fora do dispositivo."
            }
            device
        }

    private fun validateExtents(
        extents: List<AndroidLogicalExtentMetadata>,
        blockDevices: List<AndroidSuperBlockDeviceMetadata>,
    ) {
        extents.forEachIndexed { index, extent ->
            when (extent.targetType) {
                LP_TARGET_TYPE_LINEAR -> {
                    require(extent.targetSource in blockDevices.indices) {
                        "Extent #$index referencia block device inexistente ${extent.targetSource}."
                    }
                    val device = blockDevices[extent.targetSource]
                    require(extent.targetData >= device.firstLogicalSector) {
                        "Extent linear #$index inicia antes do first_logical_sector do block device."
                    }
                    val endSector = checkedAdd(
                        extent.targetData,
                        extent.sectorCount,
                        "Fim físico do extent #$index",
                    )
                    require(endSector <= device.sizeBytes / LP_SECTOR_SIZE) {
                        "Extent linear #$index excede o tamanho de ${device.partitionName}."
                    }
                }

                LP_TARGET_TYPE_ZERO -> {
                    require(extent.targetData == 0L && extent.targetSource == 0) {
                        "Extent zero #$index deve ter target_data e target_source iguais a zero."
                    }
                }

                else -> throw IllegalArgumentException(
                    "Extent #$index possui target_type desconhecido ${extent.targetType}.",
                )
            }
        }
    }

    private fun computePartitionSize(
        partition: AndroidLogicalPartitionMetadata,
        extents: List<AndroidLogicalExtentMetadata>,
    ): Long {
        var totalSectors = 0L
        repeat(partition.extentCount) { relativeIndex ->
            totalSectors = checkedAdd(
                totalSectors,
                extents[partition.firstExtentIndex + relativeIndex].sectorCount,
                "Tamanho lógico da partição ${partition.name}",
            )
        }
        return checkedMultiply(totalSectors, LP_SECTOR_SIZE, "Tamanho da partição ${partition.name}")
    }

    private fun validateUniquePartitionNames(partitions: List<AndroidLogicalPartitionMetadata>) {
        val seen = mutableSetOf<String>()
        partitions.forEach { partition ->
            require(seen.add(partition.name)) { "Nome de partição duplicado: ${partition.name}." }
        }
    }

    private fun validateDescriptorEntrySize(
        descriptor: AndroidSuperTableDescriptor,
        expectedSize: Int,
        label: String,
    ) {
        require(descriptor.entrySizeBytes == expectedSize) {
            "Tabela $label usa entries de ${descriptor.entrySizeBytes} bytes; esperado: $expectedSize."
        }
    }

    private fun validateDescriptorBounds(
        descriptor: AndroidSuperTableDescriptor,
        tablesSize: Int,
        label: String,
    ) {
        require(descriptor.offsetBytes <= tablesSize.toLong()) {
            "Tabela $label inicia fora de tables_size."
        }
        val tableSize = checkedMultiply(
            descriptor.entryCount.toLong(),
            descriptor.entrySizeBytes.toLong(),
            "Tamanho da tabela $label",
        )
        require(tableSize <= tablesSize.toLong() - descriptor.offsetBytes) {
            "Tabela $label excede tables_size."
        }
    }

    private fun validateNonOverlappingTables(
        descriptors: List<Pair<String, AndroidSuperTableDescriptor>>,
    ) {
        val ranges = descriptors.map { (name, descriptor) ->
            val size = checkedMultiply(
                descriptor.entryCount.toLong(),
                descriptor.entrySizeBytes.toLong(),
                "Tamanho da tabela $name",
            )
            Triple(name, descriptor.offsetBytes, size)
        }.filter { (_, _, size) -> size > 0L }
            .sortedBy { (_, offset, _) -> offset }

        var previousEnd = 0L
        ranges.forEach { (name, offset, size) ->
            require(offset >= previousEnd) {
                "Tabela $name sobrepõe uma tabela liblp anterior."
            }
            previousEnd = checkedAdd(offset, size, "Fim da tabela $name")
        }
    }

    private fun validateSha256WithZeroedRange(
        bytes: ByteArray,
        checksumOffset: Int,
        checksumLength: Int,
        expectedChecksum: ByteArray,
        label: String,
    ) {
        val copy = bytes.copyOf()
        copy.fill(0, checksumOffset, checksumOffset + checksumLength)
        require(sha256(copy).contentEquals(expectedChecksum)) {
            "Checksum SHA-256 de $label inválido."
        }
    }

    private fun ByteArray.readDescriptor(offset: Int, label: String): AndroidSuperTableDescriptor =
        AndroidSuperTableDescriptor(
            offsetBytes = readUInt32Le(offset),
            entryCount = readUInt32Le(offset + 4).toIntExact("$label.num_entries"),
            entrySizeBytes = readUInt32Le(offset + 8).toIntExact("$label.entry_size"),
        )

    private fun AndroidSuperTableDescriptor.entryOffset(index: Int): Int {
        require(index in 0 until entryCount) { "Índice de tabela fora dos limites." }
        val offset = checkedAdd(
            offsetBytes,
            checkedMultiply(index.toLong(), entrySizeBytes.toLong(), "Offset de entry liblp"),
            "Offset de entry liblp",
        )
        return offset.toIntExact("offset de entry liblp")
    }

    private class LimitedInput(
        private val source: InputStream,
        private val maximumBytes: Long,
    ) {
        private val skipBuffer = ByteArray(DEFAULT_SKIP_BUFFER_SIZE)
        var bytesConsumed: Long = 0L
            private set

        fun readExactly(byteCount: Int, label: String): ByteArray =
            ByteArray(byteCount).also { target ->
                readExactly(target, 0, byteCount, label)
            }

        fun readExactly(target: ByteArray, offset: Int, byteCount: Int, label: String) {
            require(offset >= 0 && byteCount >= 0 && offset + byteCount <= target.size) {
                "Faixa de leitura inválida para $label."
            }
            ensureWithinLimit(byteCount.toLong(), label)
            var cursor = offset
            val end = offset + byteCount
            while (cursor < end) {
                val read = source.read(target, cursor, end - cursor)
                when {
                    read < 0 -> throw IllegalArgumentException("Imagem truncada ao ler $label.")
                    read == 0 -> {
                        val single = source.read()
                        if (single < 0) throw IllegalArgumentException("Imagem truncada ao ler $label.")
                        target[cursor++] = single.toByte()
                        bytesConsumed += 1
                    }
                    else -> {
                        cursor += read
                        bytesConsumed += read.toLong()
                    }
                }
            }
        }

        fun skipExactly(byteCount: Long, label: String) {
            require(byteCount >= 0) { "byteCount deve ser não negativo." }
            if (byteCount == 0L) return
            ensureWithinLimit(byteCount, label)
            var remaining = byteCount
            while (remaining > 0) {
                val skipped = source.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    bytesConsumed += skipped
                    continue
                }
                val request = minOf(remaining, skipBuffer.size.toLong()).toInt()
                val read = source.read(skipBuffer, 0, request)
                when {
                    read < 0 -> throw IllegalArgumentException("Imagem truncada ao ler $label.")
                    read == 0 -> {
                        val single = source.read()
                        if (single < 0) throw IllegalArgumentException("Imagem truncada ao ler $label.")
                        remaining -= 1
                        bytesConsumed += 1
                    }
                    else -> {
                        remaining -= read.toLong()
                        bytesConsumed += read.toLong()
                    }
                }
            }
        }

        private fun ensureWithinLimit(byteCount: Long, label: String) {
            require(byteCount <= maximumBytes - bytesConsumed) {
                "Leitura de $label excede o limite configurado de $maximumBytes bytes."
            }
        }
    }

    private companion object {
        const val LP_METADATA_GEOMETRY_MAGIC = 0x616C4467L
        const val LP_METADATA_HEADER_MAGIC = 0x414C5030L
        const val LP_METADATA_MAJOR_VERSION = 10
        const val LP_METADATA_MINOR_VERSION_MIN = 0
        const val LP_METADATA_MINOR_VERSION_MAX = 2
        const val LP_METADATA_VERSION_FOR_UPDATED_ATTR = 1
        const val LP_METADATA_VERSION_FOR_EXPANDED_HEADER = 2

        const val LP_SECTOR_SIZE = 512L
        const val LP_PARTITION_RESERVED_BYTES = 4096L
        const val LP_METADATA_GEOMETRY_SIZE = 4096
        const val GEOMETRY_STRUCT_SIZE = 52
        const val METADATA_HEADER_V1_0_SIZE = 128
        const val METADATA_HEADER_V1_2_SIZE = 256
        const val SHA256_SIZE = 32

        const val GEOMETRY_MAGIC_OFFSET = 0
        const val GEOMETRY_STRUCT_SIZE_OFFSET = 4
        const val GEOMETRY_CHECKSUM_OFFSET = 8
        const val GEOMETRY_METADATA_MAX_SIZE_OFFSET = 40
        const val GEOMETRY_SLOT_COUNT_OFFSET = 44
        const val GEOMETRY_LOGICAL_BLOCK_SIZE_OFFSET = 48

        const val HEADER_MAGIC_OFFSET = 0
        const val HEADER_MAJOR_VERSION_OFFSET = 4
        const val HEADER_MINOR_VERSION_OFFSET = 6
        const val HEADER_SIZE_OFFSET = 8
        const val HEADER_CHECKSUM_OFFSET = 12
        const val TABLES_SIZE_OFFSET = 44
        const val TABLES_CHECKSUM_OFFSET = 48
        const val PARTITIONS_DESCRIPTOR_OFFSET = 80
        const val EXTENTS_DESCRIPTOR_OFFSET = 92
        const val GROUPS_DESCRIPTOR_OFFSET = 104
        const val BLOCK_DEVICES_DESCRIPTOR_OFFSET = 116
        const val HEADER_FLAGS_OFFSET = 128

        const val PARTITION_ENTRY_SIZE = 52
        const val EXTENT_ENTRY_SIZE = 24
        const val GROUP_ENTRY_SIZE = 48
        const val BLOCK_DEVICE_ENTRY_SIZE = 64
        const val PARTITION_NAME_SIZE = 36
        const val GROUP_NAME_SIZE = 36
        const val BLOCK_DEVICE_NAME_SIZE = 36

        const val LP_PARTITION_ATTRIBUTE_MASK_V0 = 0x3L
        const val LP_PARTITION_ATTRIBUTE_MASK = 0xFL
        const val LP_TARGET_TYPE_LINEAR = 0
        const val LP_TARGET_TYPE_ZERO = 1

        const val DEFAULT_SKIP_BUFFER_SIZE = 8192
        const val DEFAULT_MAXIMUM_INPUT_BYTES = 64L * 1024 * 1024 * 1024
        const val DEFAULT_MAXIMUM_METADATA_SLOT_BYTES = 16L * 1024 * 1024
        const val DEFAULT_MAXIMUM_METADATA_SLOTS = 32
        const val DEFAULT_MAXIMUM_TABLE_ENTRIES = 100_000
    }
}

private fun ByteArray.readUInt16Le(offset: Int): Int {
    require(offset >= 0 && offset + 2 <= size) { "Leitura UInt16 fora dos limites." }
    return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
}

private fun ByteArray.readUInt32Le(offset: Int): Long {
    require(offset >= 0 && offset + 4 <= size) { "Leitura UInt32 fora dos limites." }
    return (this[offset].toLong() and 0xFFL) or
        ((this[offset + 1].toLong() and 0xFFL) shl 8) or
        ((this[offset + 2].toLong() and 0xFFL) shl 16) or
        ((this[offset + 3].toLong() and 0xFFL) shl 24)
}

private fun ByteArray.readUInt64LeAsLong(offset: Int): Long {
    require(offset >= 0 && offset + 8 <= size) { "Leitura UInt64 fora dos limites." }
    var value = 0uL
    repeat(8) { index ->
        value = value or ((this[offset + index].toULong() and 0xFFuL) shl (index * 8))
    }
    require(value <= Long.MAX_VALUE.toULong()) { "UInt64 excede o intervalo suportado pelo parser." }
    return value.toLong()
}

private fun ByteArray.readFixedName(
    offset: Int,
    length: Int,
    strictPartitionName: Boolean,
): String {
    require(offset >= 0 && offset + length <= size) { "Nome fixo fora dos limites." }
    val end = (offset until offset + length).firstOrNull { index -> this[index] == 0.toByte() }
        ?: (offset + length)
    require((end until offset + length).all { index -> this[index] == 0.toByte() }) {
        "Campo de nome contém bytes não nulos após o terminador."
    }
    val raw = copyOfRange(offset, end)
    require(raw.isNotEmpty()) { "Campo de nome liblp vazio." }
    require(raw.all { byte -> (byte.toInt() and 0xFF) in 0x20..0x7E }) {
        "Campo de nome liblp contém caracteres não ASCII imprimíveis."
    }
    val name = raw.decodeToString()
    if (strictPartitionName) {
        require(name.all { character -> character.isLetterOrDigit() || character == '_' }) {
            "Nome de partição liblp inválido: $name."
        }
    }
    return name
}

private fun Long.toIntExact(label: String): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) { "$label excede Int.MAX_VALUE." }
    return toInt()
}

private fun checkedAdd(left: Long, right: Long, label: String): Long {
    require(left >= 0 && right >= 0) { "$label não aceita valores negativos." }
    require(right <= Long.MAX_VALUE - left) { "$label excede Long.MAX_VALUE." }
    return left + right
}

private fun checkedMultiply(left: Long, right: Long, label: String): Long {
    require(left >= 0 && right >= 0) { "$label não aceita valores negativos." }
    require(left == 0L || right <= Long.MAX_VALUE / left) { "$label excede Long.MAX_VALUE." }
    return left * right
}

private fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)
