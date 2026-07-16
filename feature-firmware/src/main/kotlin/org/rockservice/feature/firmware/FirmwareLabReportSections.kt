package org.rockservice.feature.firmware

import java.util.Locale

internal object FirmwareLabReportSections {
    fun general(
        displayName: String,
        declaredSizeBytes: Long?,
        analysis: FirmwareAnalysis,
    ): FirmwareLabSection {
        val lines = buildList {
            add("Arquivo: $displayName")
            add("Formato detectado: ${analysis.format.displayLabel}")
            add("SHA-256: ${analysis.sha256}")
            add("Bytes lidos para hash: ${analysis.bytesRead} (${formatBytes(analysis.bytesRead)})")
            declaredSizeBytes?.let { size ->
                add("Tamanho informado pelo provedor: $size (${formatBytes(size)})")
                if (size != analysis.bytesRead) {
                    add("Aviso: o tamanho informado pelo provedor difere da quantidade efetivamente lida.")
                }
            }
            analysis.warnings.forEach { warning -> add("Aviso: $warning") }
        }
        return FirmwareLabSection("Resumo", lines)
    }

    fun sparse(
        metadata: AndroidSparseImageMetadata,
        maximumListedEntries: Int,
    ): List<FirmwareLabSection> {
        val counts = AndroidSparseChunkType.entries.associateWith { type ->
            metadata.chunks.count { chunk -> chunk.type == type }
        }
        val summary = FirmwareLabSection(
            title = "Android Sparse",
            lines = listOf(
                "Versao: ${metadata.header.majorVersion}.${metadata.header.minorVersion}",
                "Tamanho de bloco: ${metadata.header.blockSizeBytes} bytes",
                "Blocos declarados: ${metadata.header.totalBlocks}",
                "Chunks declarados: ${metadata.header.totalChunks}",
                "Tamanho expandido estimado: ${metadata.expandedSizeBytes} (${formatBytes(metadata.expandedSizeBytes)})",
                "Bytes sparse consumidos: ${metadata.sparseBytesConsumed}",
                "Chunks RAW: ${counts.getValue(AndroidSparseChunkType.RAW)}",
                "Chunks FILL: ${counts.getValue(AndroidSparseChunkType.FILL)}",
                "Chunks DONT_CARE: ${counts.getValue(AndroidSparseChunkType.DONT_CARE)}",
                "Chunks CRC32: ${counts.getValue(AndroidSparseChunkType.CRC32)}",
            ),
        )
        val chunks = boundedLines(metadata.chunks, maximumListedEntries) { chunk ->
            "#${chunk.index} ${chunk.type}: saida bloco ${chunk.outputStartBlock}, " +
                "${chunk.outputBlockCount} blocos, payload ${chunk.inputPayloadSizeBytes} bytes"
        }
        return listOf(summary, FirmwareLabSection("Chunks", chunks))
    }

    fun boot(metadata: AndroidBootImageMetadata): List<FirmwareLabSection> {
        val summary = FirmwareLabSection(
            title = "Android Boot Image",
            lines = listOf(
                "Header: ${metadata.headerVersion}",
                "Page size: ${metadata.pageSizeBytes} bytes",
                "Header size: ${metadata.headerSizeBytes} bytes",
                "Kernel: ${metadata.kernelSizeBytes} (${formatBytes(metadata.kernelSizeBytes)})",
                "Ramdisk: ${metadata.ramdiskSizeBytes} (${formatBytes(metadata.ramdiskSizeBytes)})",
                "Second stage: ${metadata.secondStageSizeBytes} bytes",
                "Recovery DTBO: ${metadata.recoveryDtboSizeBytes} bytes",
                "DTB: ${metadata.dtbSizeBytes} bytes",
                "Boot signature: ${metadata.bootSignatureSizeBytes} bytes",
                "Layout minimo: ${metadata.minimumImageSizeBytes} (${formatBytes(metadata.minimumImageSizeBytes)})",
            ),
        )
        val layout = metadata.sections.map { section ->
            "${section.type}: offset ${section.offsetBytes}, tamanho ${section.sizeBytes}, " +
                "tamanho alinhado ${section.paddedSizeBytes}"
        }
        return listOf(summary, FirmwareLabSection("Layout de secoes", layout))
    }

    fun superImage(
        metadata: AndroidSuperMetadata,
        maximumListedEntries: Int,
    ): List<FirmwareLabSection> {
        val summary = FirmwareLabSection(
            title = "Android Super / liblp",
            lines = listOf(
                "Versao liblp: ${metadata.majorVersion}.${metadata.minorVersion}",
                "Geometria usada: ${metadata.geometry.source}",
                "Copia de metadata usada: ${metadata.metadataCopy}",
                "Slot de metadata: ${metadata.slotNumber}",
                "Slots declarados: ${metadata.geometry.metadataSlotCount}",
                "metadata_max_size: ${metadata.geometry.metadataMaxSizeBytes} bytes",
                "Bloco logico: ${metadata.geometry.logicalBlockSizeBytes} bytes",
                "Particoes: ${metadata.partitions.size}",
                "Extents: ${metadata.extents.size}",
                "Grupos: ${metadata.groups.size}",
                "Block devices: ${metadata.blockDevices.size}",
            ),
        )
        val partitions = boundedLines(metadata.partitions, maximumListedEntries) { partition ->
            "${partition.name}: ${partition.logicalSizeBytes} bytes (${formatBytes(partition.logicalSizeBytes)}), " +
                "extents=${partition.extentCount}, grupo=${partition.groupIndex}, atributos=0x" +
                partition.attributes.toString(16).uppercase(Locale.US)
        }
        val groups = boundedLines(metadata.groups, maximumListedEntries) { group ->
            "${group.name}: alocado=${group.allocatedSizeBytes} bytes, maximo=${group.maximumSizeBytes} bytes, " +
                "flags=0x${group.flags.toString(16).uppercase(Locale.US)}"
        }
        val devices = boundedLines(metadata.blockDevices, maximumListedEntries) { device ->
            "${device.partitionName}: tamanho=${device.sizeBytes} bytes (${formatBytes(device.sizeBytes)}), " +
                "primeiro setor logico=${device.firstLogicalSector}, alinhamento=${device.alignmentBytes}"
        }
        return listOf(
            summary,
            FirmwareLabSection("Particoes logicas", partitions),
            FirmwareLabSection("Grupos", groups),
            FirmwareLabSection("Block devices", devices),
        )
    }

    fun rawFilesystem(inspection: RawFilesystemInspection): FirmwareLabSection = FirmwareLabSection(
        title = "Filesystem raw detectado",
        lines = buildList {
            add("Tipo: ${inspection.type}")
            add("Bytes do prefixo inspecionados: ${inspection.bytesInspected}")
            inspection.blockSizeBytes?.let { blockSize -> add("Tamanho de bloco validado: $blockSize bytes") }
            add("SHA-256 do prefixo inspecionado: ${inspection.prefixSha256}")
            add("Evidencia estrutural: ${inspection.detail}")
            add("Observacao: o SHA-256 acima cobre somente o prefixo; o SHA-256 integral da imagem permanece no Resumo.")
            add("Nenhum filesystem foi montado e nenhum arquivo interno foi extraido.")
        },
    )

    private fun <T> boundedLines(
        values: List<T>,
        maximumListedEntries: Int,
        render: (T) -> String,
    ): List<String> = buildList {
        values.take(maximumListedEntries).forEach { value -> add(render(value)) }
        if (values.size > maximumListedEntries) {
            add("... ${values.size - maximumListedEntries} entradas adicionais omitidas por limite de relatorio.")
        }
        if (values.isEmpty()) add("Nenhuma entrada.")
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return String.format(Locale.US, "%.2f %s", value, units[unitIndex])
    }

    private val FirmwareFormat.displayLabel: String
        get() = when (this) {
            FirmwareFormat.ZIP -> "ZIP"
            FirmwareFormat.ANDROID_SPARSE -> "Android Sparse"
            FirmwareFormat.ANDROID_BOOT_IMAGE -> "Android Boot Image"
            FirmwareFormat.ANDROID_SUPER_RAW -> "Android Super raw / liblp"
            FirmwareFormat.ELF -> "ELF"
            FirmwareFormat.ISO_9660 -> "ISO 9660"
            FirmwareFormat.UNKNOWN -> "Desconhecido"
        }
}
