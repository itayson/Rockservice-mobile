package org.rockservice.feature.firmware

import java.io.InputStream

/** Android boot image header versions supported by the structural parser. */
enum class AndroidBootHeaderVersion(val wireValue: Int) {
    V0(0),
    V1(1),
    V2(2),
    V3(3),
    V4(4),
    ;

    companion object {
        internal fun fromWireValue(value: Long): AndroidBootHeaderVersion =
            entries.singleOrNull { version -> version.wireValue.toLong() == value }
                ?: throw IllegalArgumentException("Versão de header Android Boot não suportada: $value.")
    }
}

/** Logical sections present in Android boot image layouts. */
enum class AndroidBootSectionType {
    HEADER,
    KERNEL,
    RAMDISK,
    SECOND_STAGE,
    RECOVERY_DTBO,
    DTB,
    BOOT_SIGNATURE,
}

/** Structural position and size of one Android boot image section. */
data class AndroidBootSection(
    val type: AndroidBootSectionType,
    val offsetBytes: Long,
    val sizeBytes: Long,
    val paddedSizeBytes: Long,
)

/** Validated structural metadata for an Android boot image without extracted payloads. */
data class AndroidBootImageMetadata(
    val headerVersion: AndroidBootHeaderVersion,
    val pageSizeBytes: Long,
    val headerSizeBytes: Long,
    val kernelSizeBytes: Long,
    val ramdiskSizeBytes: Long,
    val secondStageSizeBytes: Long,
    val recoveryDtboSizeBytes: Long,
    val recoveryDtboOffsetBytes: Long?,
    val dtbSizeBytes: Long,
    val bootSignatureSizeBytes: Long,
    val osVersionEncoded: Long,
    val sections: List<AndroidBootSection>,
    val minimumImageSizeBytes: Long,
    val bytesConsumed: Long,
)

/**
 * Parses Android boot image headers v0-v4 and validates their declared section layout by streaming.
 *
 * Payload bytes are skipped and never retained. The parser does not extract, modify, mount, sign or
 * write boot images and does not interpret a structurally valid image as compatible with hardware.
 */
class AndroidBootImageParser(
    private val maximumInputBytes: Long = DEFAULT_MAXIMUM_INPUT_BYTES,
    private val maximumImageBytes: Long = DEFAULT_MAXIMUM_IMAGE_BYTES,
    private val maximumLegacyPageSizeBytes: Long = DEFAULT_MAXIMUM_LEGACY_PAGE_SIZE_BYTES,
) {
    init {
        require(maximumInputBytes > 0) { "maximumInputBytes deve ser maior que zero." }
        require(maximumImageBytes > 0) { "maximumImageBytes deve ser maior que zero." }
        require(maximumLegacyPageSizeBytes > 0) {
            "maximumLegacyPageSizeBytes deve ser maior que zero."
        }
    }

    /** Parses one complete declared Android boot image layout and verifies that it is not truncated. */
    fun parse(source: InputStream): AndroidBootImageMetadata {
        val input = LimitedInput(source, maximumInputBytes)
        val prefix = input.readExactly(VERSION_DETECTION_PREFIX_SIZE, "prefixo do header Android Boot")
        require(prefix.copyOfRange(0, BOOT_MAGIC_SIZE).contentEquals(BOOT_MAGIC)) {
            "Magic Android Boot inválida."
        }

        val version = AndroidBootHeaderVersion.fromWireValue(prefix.readUInt32Le(HEADER_VERSION_OFFSET))
        val structSize = version.structSizeBytes
        val headerBytes = ByteArray(structSize)
        prefix.copyInto(headerBytes)
        if (structSize > prefix.size) {
            input.readExactly(
                target = headerBytes,
                offset = prefix.size,
                byteCount = structSize - prefix.size,
                label = "header Android Boot ${version.name}",
            )
        }

        val parsed = parseHeader(version, headerBytes)
        require(parsed.pageSizeBytes <= maximumLegacyPageSizeBytes || version >= AndroidBootHeaderVersion.V3) {
            "Page size Android Boot ${parsed.pageSizeBytes} excede o limite configurado de " +
                "$maximumLegacyPageSizeBytes bytes."
        }
        require(parsed.headerSizeBytes <= parsed.pageSizeBytes) {
            "Header Android Boot declara ${parsed.headerSizeBytes} bytes, maior que a página de " +
                "${parsed.pageSizeBytes} bytes."
        }
        require(structSize.toLong() <= parsed.pageSizeBytes) {
            "Página Android Boot de ${parsed.pageSizeBytes} bytes não comporta o header ${version.name} " +
                "de $structSize bytes."
        }

        val sections = buildSections(parsed)
        val minimumImageSize = sections.maxOf { section ->
            checkedAdd(
                left = section.offsetBytes,
                right = section.paddedSizeBytes,
                label = "Fim da seção ${section.type}",
            )
        }
        require(minimumImageSize <= maximumImageBytes) {
            "Imagem Android Boot declara layout mínimo de $minimumImageSize bytes; limite configurado: " +
                "$maximumImageBytes bytes."
        }
        require(minimumImageSize <= maximumInputBytes) {
            "Imagem Android Boot declara layout mínimo de $minimumImageSize bytes; limite de leitura: " +
                "$maximumInputBytes bytes."
        }

        validateRecoveryDtboOffset(parsed, sections)

        input.skipExactly(
            byteCount = parsed.pageSizeBytes - structSize.toLong(),
            label = "restante da página de header Android Boot",
        )
        sections.asSequence()
            .filter { section -> section.type != AndroidBootSectionType.HEADER }
            .forEach { section ->
                input.skipExactly(
                    byteCount = section.paddedSizeBytes,
                    label = "seção ${section.type} Android Boot",
                )
            }

        require(input.bytesConsumed == minimumImageSize) {
            "Contabilidade interna do layout Android Boot divergiu: consumidos ${input.bytesConsumed}, " +
                "esperados $minimumImageSize bytes."
        }

        return AndroidBootImageMetadata(
            headerVersion = version,
            pageSizeBytes = parsed.pageSizeBytes,
            headerSizeBytes = parsed.headerSizeBytes,
            kernelSizeBytes = parsed.kernelSizeBytes,
            ramdiskSizeBytes = parsed.ramdiskSizeBytes,
            secondStageSizeBytes = parsed.secondStageSizeBytes,
            recoveryDtboSizeBytes = parsed.recoveryDtboSizeBytes,
            recoveryDtboOffsetBytes = parsed.recoveryDtboOffsetBytes,
            dtbSizeBytes = parsed.dtbSizeBytes,
            bootSignatureSizeBytes = parsed.bootSignatureSizeBytes,
            osVersionEncoded = parsed.osVersionEncoded,
            sections = sections,
            minimumImageSizeBytes = minimumImageSize,
            bytesConsumed = input.bytesConsumed,
        )
    }

    private fun parseHeader(
        version: AndroidBootHeaderVersion,
        bytes: ByteArray,
    ): ParsedHeader =
        when (version) {
            AndroidBootHeaderVersion.V0,
            AndroidBootHeaderVersion.V1,
            AndroidBootHeaderVersion.V2,
            -> parseLegacyHeader(version, bytes)

            AndroidBootHeaderVersion.V3,
            AndroidBootHeaderVersion.V4,
            -> parseModernHeader(version, bytes)
        }

    private fun parseLegacyHeader(
        version: AndroidBootHeaderVersion,
        bytes: ByteArray,
    ): ParsedHeader {
        val pageSize = bytes.readUInt32Le(LEGACY_PAGE_SIZE_OFFSET)
        require(pageSize > 0) { "Page size Android Boot legacy deve ser maior que zero." }

        val declaredHeaderSize = when (version) {
            AndroidBootHeaderVersion.V0 -> V0_STRUCT_SIZE.toLong()
            AndroidBootHeaderVersion.V1,
            AndroidBootHeaderVersion.V2,
            -> bytes.readUInt32Le(V1_HEADER_SIZE_OFFSET)

            else -> error("Versão não legacy: $version")
        }
        require(declaredHeaderSize >= version.structSizeBytes.toLong()) {
            "Header Android Boot ${version.name} declara $declaredHeaderSize bytes; mínimo esperado: " +
                "${version.structSizeBytes}."
        }

        return ParsedHeader(
            version = version,
            pageSizeBytes = pageSize,
            headerSizeBytes = declaredHeaderSize,
            kernelSizeBytes = bytes.readUInt32Le(LEGACY_KERNEL_SIZE_OFFSET),
            ramdiskSizeBytes = bytes.readUInt32Le(LEGACY_RAMDISK_SIZE_OFFSET),
            secondStageSizeBytes = bytes.readUInt32Le(LEGACY_SECOND_SIZE_OFFSET),
            recoveryDtboSizeBytes = if (version >= AndroidBootHeaderVersion.V1) {
                bytes.readUInt32Le(V1_RECOVERY_DTBO_SIZE_OFFSET)
            } else {
                0L
            },
            recoveryDtboOffsetBytes = if (version >= AndroidBootHeaderVersion.V1) {
                bytes.readUInt64LeAsLong(V1_RECOVERY_DTBO_OFFSET_OFFSET)
            } else {
                null
            },
            dtbSizeBytes = if (version >= AndroidBootHeaderVersion.V2) {
                bytes.readUInt32Le(V2_DTB_SIZE_OFFSET)
            } else {
                0L
            },
            bootSignatureSizeBytes = 0L,
            osVersionEncoded = bytes.readUInt32Le(LEGACY_OS_VERSION_OFFSET),
        )
    }

    private fun parseModernHeader(
        version: AndroidBootHeaderVersion,
        bytes: ByteArray,
    ): ParsedHeader {
        val headerSize = bytes.readUInt32Le(MODERN_HEADER_SIZE_OFFSET)
        require(headerSize >= version.structSizeBytes.toLong()) {
            "Header Android Boot ${version.name} declara $headerSize bytes; mínimo esperado: " +
                "${version.structSizeBytes}."
        }
        require(headerSize <= MODERN_PAGE_SIZE) {
            "Header Android Boot ${version.name} declara $headerSize bytes; página fixa: " +
                "$MODERN_PAGE_SIZE bytes."
        }

        return ParsedHeader(
            version = version,
            pageSizeBytes = MODERN_PAGE_SIZE,
            headerSizeBytes = headerSize,
            kernelSizeBytes = bytes.readUInt32Le(MODERN_KERNEL_SIZE_OFFSET),
            ramdiskSizeBytes = bytes.readUInt32Le(MODERN_RAMDISK_SIZE_OFFSET),
            secondStageSizeBytes = 0L,
            recoveryDtboSizeBytes = 0L,
            recoveryDtboOffsetBytes = null,
            dtbSizeBytes = 0L,
            bootSignatureSizeBytes = if (version >= AndroidBootHeaderVersion.V4) {
                bytes.readUInt32Le(V4_SIGNATURE_SIZE_OFFSET)
            } else {
                0L
            },
            osVersionEncoded = bytes.readUInt32Le(MODERN_OS_VERSION_OFFSET),
        )
    }

    private fun buildSections(header: ParsedHeader): List<AndroidBootSection> {
        val sections = mutableListOf(
            AndroidBootSection(
                type = AndroidBootSectionType.HEADER,
                offsetBytes = 0L,
                sizeBytes = header.headerSizeBytes,
                paddedSizeBytes = header.pageSizeBytes,
            ),
        )
        var cursor = header.pageSizeBytes

        fun append(type: AndroidBootSectionType, sizeBytes: Long) {
            if (sizeBytes == 0L) return
            val paddedSize = alignUp(sizeBytes, header.pageSizeBytes, "Seção $type")
            sections += AndroidBootSection(
                type = type,
                offsetBytes = cursor,
                sizeBytes = sizeBytes,
                paddedSizeBytes = paddedSize,
            )
            cursor = checkedAdd(cursor, paddedSize, "Layout Android Boot")
        }

        append(AndroidBootSectionType.KERNEL, header.kernelSizeBytes)
        append(AndroidBootSectionType.RAMDISK, header.ramdiskSizeBytes)
        append(AndroidBootSectionType.SECOND_STAGE, header.secondStageSizeBytes)
        append(AndroidBootSectionType.RECOVERY_DTBO, header.recoveryDtboSizeBytes)
        append(AndroidBootSectionType.DTB, header.dtbSizeBytes)
        append(AndroidBootSectionType.BOOT_SIGNATURE, header.bootSignatureSizeBytes)

        return sections
    }

    private fun validateRecoveryDtboOffset(
        header: ParsedHeader,
        sections: List<AndroidBootSection>,
    ) {
        if (header.recoveryDtboSizeBytes == 0L) return
        val declared = requireNotNull(header.recoveryDtboOffsetBytes) {
            "Imagem Android Boot com recovery_dtbo não declarou offset."
        }
        val computed = sections.single { section ->
            section.type == AndroidBootSectionType.RECOVERY_DTBO
        }.offsetBytes
        require(declared == computed) {
            "Offset recovery_dtbo declarado ($declared) diverge do layout calculado ($computed)."
        }
    }

    private data class ParsedHeader(
        val version: AndroidBootHeaderVersion,
        val pageSizeBytes: Long,
        val headerSizeBytes: Long,
        val kernelSizeBytes: Long,
        val ramdiskSizeBytes: Long,
        val secondStageSizeBytes: Long,
        val recoveryDtboSizeBytes: Long,
        val recoveryDtboOffsetBytes: Long?,
        val dtbSizeBytes: Long,
        val bootSignatureSizeBytes: Long,
        val osVersionEncoded: Long,
    )

    private class LimitedInput(
        private val source: InputStream,
        private val maximumBytes: Long,
    ) {
        private val skipBuffer = ByteArray(DEFAULT_SKIP_BUFFER_SIZE)
        var bytesConsumed: Long = 0L
            private set

        fun readExactly(byteCount: Int, label: String): ByteArray =
            ByteArray(byteCount).also { target ->
                readExactly(target, offset = 0, byteCount = byteCount, label = label)
            }

        fun readExactly(
            target: ByteArray,
            offset: Int,
            byteCount: Int,
            label: String,
        ) {
            require(offset >= 0 && byteCount >= 0 && offset + byteCount <= target.size) {
                "Faixa de leitura inválida para $label."
            }
            ensureWithinLimit(byteCount.toLong(), label)
            var currentOffset = offset
            val endOffset = offset + byteCount

            while (currentOffset < endOffset) {
                val read = source.read(target, currentOffset, endOffset - currentOffset)
                when {
                    read < 0 -> throw IllegalArgumentException("Imagem truncada ao ler $label.")
                    read == 0 -> {
                        val single = source.read()
                        if (single < 0) {
                            throw IllegalArgumentException("Imagem truncada ao ler $label.")
                        }
                        target[currentOffset] = single.toByte()
                        currentOffset += 1
                        bytesConsumed += 1
                    }
                    else -> {
                        currentOffset += read
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

            while (remaining > 0L) {
                val skipped = source.skip(remaining)
                if (skipped > 0L) {
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
                        if (single < 0) {
                            throw IllegalArgumentException("Imagem truncada ao ler $label.")
                        }
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
        val BOOT_MAGIC: ByteArray = "ANDROID!".encodeToByteArray()
        const val BOOT_MAGIC_SIZE = 8
        const val HEADER_VERSION_OFFSET = 40
        const val VERSION_DETECTION_PREFIX_SIZE = 44

        const val V0_STRUCT_SIZE = 1632
        const val V1_STRUCT_SIZE = 1648
        const val V2_STRUCT_SIZE = 1660
        const val V3_STRUCT_SIZE = 1580
        const val V4_STRUCT_SIZE = 1584

        const val LEGACY_KERNEL_SIZE_OFFSET = 8
        const val LEGACY_RAMDISK_SIZE_OFFSET = 16
        const val LEGACY_SECOND_SIZE_OFFSET = 24
        const val LEGACY_PAGE_SIZE_OFFSET = 36
        const val LEGACY_OS_VERSION_OFFSET = 44
        const val V1_RECOVERY_DTBO_SIZE_OFFSET = 1632
        const val V1_RECOVERY_DTBO_OFFSET_OFFSET = 1636
        const val V1_HEADER_SIZE_OFFSET = 1644
        const val V2_DTB_SIZE_OFFSET = 1648

        const val MODERN_KERNEL_SIZE_OFFSET = 8
        const val MODERN_RAMDISK_SIZE_OFFSET = 12
        const val MODERN_OS_VERSION_OFFSET = 16
        const val MODERN_HEADER_SIZE_OFFSET = 20
        const val V4_SIGNATURE_SIZE_OFFSET = 1580
        const val MODERN_PAGE_SIZE = 4096L

        const val DEFAULT_SKIP_BUFFER_SIZE = 8192
        const val DEFAULT_MAXIMUM_INPUT_BYTES = 64L * 1024 * 1024 * 1024
        const val DEFAULT_MAXIMUM_IMAGE_BYTES = 64L * 1024 * 1024 * 1024
        const val DEFAULT_MAXIMUM_LEGACY_PAGE_SIZE_BYTES = 1024L * 1024
    }
}

private val AndroidBootHeaderVersion.structSizeBytes: Int
    get() = when (this) {
        AndroidBootHeaderVersion.V0 -> 1632
        AndroidBootHeaderVersion.V1 -> 1648
        AndroidBootHeaderVersion.V2 -> 1660
        AndroidBootHeaderVersion.V3 -> 1580
        AndroidBootHeaderVersion.V4 -> 1584
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
    require(value <= Long.MAX_VALUE.toULong()) {
        "Offset UInt64 excede o intervalo suportado pelo parser."
    }
    return value.toLong()
}

private fun alignUp(value: Long, alignment: Long, label: String): Long {
    require(value >= 0) { "$label não aceita tamanho negativo." }
    require(alignment > 0) { "$label exige alinhamento positivo." }
    if (value == 0L) return 0L
    val remainder = value % alignment
    if (remainder == 0L) return value
    return checkedAdd(value, alignment - remainder, "$label alinhada")
}

private fun checkedAdd(left: Long, right: Long, label: String): Long {
    require(left >= 0 && right >= 0) { "$label não aceita valores negativos." }
    require(right <= Long.MAX_VALUE - left) { "$label excede Long.MAX_VALUE." }
    return left + right
}
