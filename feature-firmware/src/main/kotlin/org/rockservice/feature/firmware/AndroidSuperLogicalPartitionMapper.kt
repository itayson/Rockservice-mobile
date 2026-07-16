package org.rockservice.feature.firmware

/** One validated logical extent from liblp metadata, expressed in bytes. */
sealed interface AndroidSuperLogicalExtentPlan {
    val lengthBytes: Long

    data class Linear(
        val blockDeviceIndex: Int,
        val sourceOffsetBytes: Long,
        override val lengthBytes: Long,
    ) : AndroidSuperLogicalExtentPlan

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
 * AndroidSuperMetadata and revalidates every index plus all sector-to-byte arithmetic.
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
        val matches = metadata.partitions.filter { partition -> partition.component1() == partitionName }
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
        partition: AndroidSuperPartition,
    ): AndroidSuperLogicalPartitionPlan {
        val name = partition.component1()
        val firstExtentIndex = partition.component3().toLong()
        val extentCount = partition.component4().toLong()
        require(firstExtentIndex >= 0L) { "Partição '$name' possui first_extent_index negativo." }
        require(extentCount >= 0L) { "Partição '$name' possui quantidade de extents negativa." }

        val endExclusive = checkedAdd(
            firstExtentIndex,
            extentCount,
            "Faixa de extents da partição '$name'",
        )
        require(endExclusive <= metadata.extents.size.toLong()) {
            "Partição '$name' referencia extents [$firstExtentIndex, $endExclusive), mas a tabela possui " +
                "${metadata.extents.size} entradas."
        }

        val plans = ArrayList<AndroidSuperLogicalExtentPlan>(extentCount.toInt())
        var partitionSizeBytes = 0L
        for (index in firstExtentIndex until endExclusive) {
            val extent = metadata.extents[index.toInt()]
            val sectorCount = extent.component1().toLong()
            val targetType = extent.component2()
            val targetData = extent.component3().toLong()
            val targetSource = extent.component4().toLong()

            require(sectorCount >= 0L) {
                "Extent $index da partição '$name' possui quantidade de setores negativa."
            }
            val lengthBytes = checkedMultiply(
                sectorCount,
                LOGICAL_SECTOR_SIZE_BYTES,
                "Tamanho do extent $index da partição '$name'",
            )

            val plan = when (targetType.name) {
                "LINEAR" -> {
                    require(targetSource in metadata.blockDevices.indices.map(Int::toLong)) {
                        "Extent $index da partição '$name' referencia block device $targetSource, mas existem " +
                            "${metadata.blockDevices.size} dispositivos."
                    }
                    require(targetData >= 0L) {
                        "Extent LINEAR $index da partição '$name' possui setor de origem negativo."
                    }
                    AndroidSuperLogicalExtentPlan.Linear(
                        blockDeviceIndex = targetSource.toInt(),
                        sourceOffsetBytes = checkedMultiply(
                            targetData,
                            LOGICAL_SECTOR_SIZE_BYTES,
                            "Offset do extent $index da partição '$name'",
                        ),
                        lengthBytes = lengthBytes,
                    )
                }

                "ZERO" -> AndroidSuperLogicalExtentPlan.Zero(lengthBytes = lengthBytes)

                else -> throw IllegalArgumentException(
                    "Extent $index da partição '$name' usa target não suportado: ${targetType.name}.",
                )
            }
            plans += plan
            partitionSizeBytes = checkedAdd(
                partitionSizeBytes,
                lengthBytes,
                "Tamanho lógico da partição '$name'",
            )
        }

        return AndroidSuperLogicalPartitionPlan(
            name = name,
            sizeBytes = partitionSizeBytes,
            extents = plans.toList(),
        )
    }

    private companion object {
        const val LOGICAL_SECTOR_SIZE_BYTES = 512L
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
