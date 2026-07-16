package org.rockservice.feature.firmware

/** One validated logical extent from liblp metadata, expressed in bytes. */
sealed interface AndroidSuperLogicalExtentPlan {
    val lengthBytes: Long

    /** Copies bytes from one validated physical block-device range. */
    data class Linear(
        val blockDeviceIndex: Int,
        val sourceOffsetBytes: Long,
        override val lengthBytes: Long,
    ) : AndroidSuperLogicalExtentPlan

    /** Materializes a logical zero-filled range without reading source bytes. */
    data class Zero(
        override val lengthBytes: Long,
    ) : AndroidSuperLogicalExtentPlan
}

/** Immutable read plan for one logical partition described by validated liblp metadata. */
data class AndroidSuperLogicalPartitionPlan(
    val name: String,
    val sizeBytes: Long,
    val extents: List<AndroidSuperLogicalExtentPlan>,
)

/**
 * Converts validated liblp tables into immutable logical-partition read plans.
 *
 * This mapper performs no file I/O. It accepts only table relationships already parsed into
 * [AndroidSuperMetadata] and revalidates every index plus all sector-to-byte arithmetic.
 */
class AndroidSuperLogicalPartitionMapper {
    /** Maps every partition in table order. */
    fun map(metadata: AndroidSuperMetadata): List<AndroidSuperLogicalPartitionPlan> =
        metadata.partitions.map { partition -> mapPartition(metadata, partition) }

    /** Maps one named partition and rejects missing or ambiguous names. */
    fun mapNamed(
        metadata: AndroidSuperMetadata,
        partitionName: String,
    ): AndroidSuperLogicalPartitionPlan {
        require(partitionName.isNotBlank()) { "O nome da partição lógica não pode ser vazio." }
        val matches = metadata.partitions.filter { partition -> partition.name == partitionName }
        require(matches.size == 1) {
            if (matches.isEmpty()) {
                "Partição lógica '$partitionName' não encontrada na metadata liblp."
            } else {
                "Metadata liblp contém múltiplas partições chamadas '$partitionName'."
            }
        }
        return mapPartition(metadata, matches.single())
    }

    private fun mapPartition(
        metadata: AndroidSuperMetadata,
        partition: AndroidLogicalPartitionMetadata,
    ): AndroidSuperLogicalPartitionPlan {
        val firstExtentIndex = partition.firstExtentIndex.toLong()
        val extentCount = partition.extentCount.toLong()
        val endExclusive = checkedAdd(
            firstExtentIndex,
            extentCount,
            "Faixa de extents da partição '${partition.name}'",
        )
        require(firstExtentIndex <= metadata.extents.size.toLong() && endExclusive <= metadata.extents.size.toLong()) {
            "Partição '${partition.name}' referencia extents [$firstExtentIndex, $endExclusive), mas a tabela possui " +
                "${metadata.extents.size} entradas."
        }

        val plans = ArrayList<AndroidSuperLogicalExtentPlan>(partition.extentCount)
        var partitionSizeBytes = 0L
        for (index in partition.firstExtentIndex until endExclusive.toInt()) {
            val extent = metadata.extents[index]
            val lengthBytes = checkedMultiply(
                extent.sectorCount,
                LOGICAL_SECTOR_SIZE_BYTES,
                "Tamanho do extent $index da partição '${partition.name}'",
            )

            val plan = when (extent.targetType) {
                TARGET_TYPE_LINEAR -> mapLinearExtent(
                    metadata = metadata,
                    partitionName = partition.name,
                    extentIndex = index,
                    extent = extent,
                    lengthBytes = lengthBytes,
                )

                TARGET_TYPE_ZERO -> {
                    require(extent.targetData == 0L && extent.targetSource == 0) {
                        "Extent ZERO $index da partição '${partition.name}' deve ter target_data e target_source iguais a zero."
                    }
                    AndroidSuperLogicalExtentPlan.Zero(lengthBytes = lengthBytes)
                }

                else -> throw IllegalArgumentException(
                    "Extent $index da partição '${partition.name}' usa target não suportado: ${extent.targetType}.",
                )
            }
            plans += plan
            partitionSizeBytes = checkedAdd(
                partitionSizeBytes,
                lengthBytes,
                "Tamanho lógico da partição '${partition.name}'",
            )
        }

        require(partitionSizeBytes == partition.logicalSizeBytes) {
            "Plano da partição '${partition.name}' totaliza $partitionSizeBytes bytes, mas a metadata validada declara " +
                "${partition.logicalSizeBytes} bytes."
        }

        return AndroidSuperLogicalPartitionPlan(
            name = partition.name,
            sizeBytes = partitionSizeBytes,
            extents = plans.toList(),
        )
    }

    private fun mapLinearExtent(
        metadata: AndroidSuperMetadata,
        partitionName: String,
        extentIndex: Int,
        extent: AndroidLogicalExtentMetadata,
        lengthBytes: Long,
    ): AndroidSuperLogicalExtentPlan.Linear {
        require(extent.targetSource in metadata.blockDevices.indices) {
            "Extent $extentIndex da partição '$partitionName' referencia block device ${extent.targetSource}, mas existem " +
                "${metadata.blockDevices.size} dispositivos."
        }
        val device = metadata.blockDevices[extent.targetSource]
        require(extent.targetData >= device.firstLogicalSector) {
            "Extent LINEAR $extentIndex da partição '$partitionName' inicia antes do primeiro setor lógico de " +
                "${device.partitionName}."
        }
        val sourceOffsetBytes = checkedMultiply(
            extent.targetData,
            LOGICAL_SECTOR_SIZE_BYTES,
            "Offset do extent $extentIndex da partição '$partitionName'",
        )
        val sourceEndBytes = checkedAdd(
            sourceOffsetBytes,
            lengthBytes,
            "Fim do extent $extentIndex da partição '$partitionName'",
        )
        require(sourceEndBytes <= device.sizeBytes) {
            "Extent LINEAR $extentIndex da partição '$partitionName' excede o tamanho do block device " +
                "${device.partitionName}."
        }
        return AndroidSuperLogicalExtentPlan.Linear(
            blockDeviceIndex = extent.targetSource,
            sourceOffsetBytes = sourceOffsetBytes,
            lengthBytes = lengthBytes,
        )
    }

    private companion object {
        const val LOGICAL_SECTOR_SIZE_BYTES = 512L
        const val TARGET_TYPE_LINEAR = 0
        const val TARGET_TYPE_ZERO = 1
    }
}

private fun checkedMultiply(left: Long, right: Long, label: String): Long {
    require(left >= 0L && right >= 0L) { "$label não aceita valores negativos." }
    return try {
        Math.multiplyExact(left, right)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("$label excede o intervalo suportado.")
    }
}

private fun checkedAdd(left: Long, right: Long, label: String): Long {
    require(left >= 0L && right >= 0L) { "$label não aceita valores negativos." }
    return try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("$label excede o intervalo suportado.")
    }
}
