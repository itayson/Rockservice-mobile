package org.rockservice.feature.firmware

import java.io.InputStream

/** Parsed fixed fields from an Android sparse image header. */
data class AndroidSparseHeader(
    val majorVersion: Int,
    val minorVersion: Int,
    val fileHeaderSize: Int,
    val chunkHeaderSize: Int,
    val blockSizeBytes: Long,
    val totalBlocks: Long,
    val totalChunks: Long,
    val imageChecksum: Long,
)

/** Android sparse chunk types defined by the AOSP sparse format. */
enum class AndroidSparseChunkType(val wireValue: Int) {
    RAW(0xCAC1),
    FILL(0xCAC2),
    DONT_CARE(0xCAC3),
    CRC32(0xCAC4),
    ;

    companion object {
        internal fun fromWireValue(value: Int): AndroidSparseChunkType =
            entries.singleOrNull { type -> type.wireValue == value }
                ?: throw IllegalArgumentException(
                    "Tipo de chunk Android Sparse desconhecido: 0x${value.toString(16).uppercase()}.",
                )
    }
}

/** Structural metadata for one sparse input chunk without expanding its payload. */
data class AndroidSparseChunk(
    val index: Int,
    val type: AndroidSparseChunkType,
    val outputStartBlock: Long,
    val outputBlockCount: Long,
    val inputPayloadSizeBytes: Long,
    val fillValue: Long? = null,
    val crc32: Long? = null,
)

/** Validated structural description of an Android sparse image. */
data class AndroidSparseImageMetadata(
    val header: AndroidSparseHeader,
    val chunks: List<AndroidSparseChunk>,
    val expandedSizeBytes: Long,
    val sparseBytesConsumed: Long,
)

/**
 * Parses Android sparse image structure defensively without expanding, extracting or writing data.
 *
 * The parser validates header versions and sizes, chunk payload lengths, output block accounting,
 * configured resource limits and truncated input. Raw chunk payloads are skipped rather than loaded
 * into memory.
 */
class AndroidSparseImageParser(
    private val maximumChunks: Int = DEFAULT_MAXIMUM_CHUNKS,
    private val maximumInputBytes: Long = DEFAULT_MAXIMUM_INPUT_BYTES,
    private val maximumExpandedBytes: Long = DEFAULT_MAXIMUM_EXPANDED_BYTES,
) {
    init {
        require(maximumChunks > 0) { "maximumChunks deve ser maior que zero." }
        require(maximumInputBytes > 0) { "maximumInputBytes deve ser maior que zero." }
        require(maximumExpandedBytes > 0) { "maximumExpandedBytes deve ser maior que zero." }
    }

    /** Parses and validates one sparse image stream while keeping memory use independent of raw data size. */
    fun parse(source: InputStream): AndroidSparseImageMetadata {
        val input = LimitedInput(source, maximumInputBytes)
        val fixedHeader = input.readExactly(SPARSE_HEADER_SIZE, "header Android Sparse")

        val magic = fixedHeader.readUInt32Le(offset = 0)
        require(magic == SPARSE_HEADER_MAGIC) {
            "Magic Android Sparse inválida: 0x${magic.toString(16).uppercase()}."
        }

        val majorVersion = fixedHeader.readUInt16Le(offset = 4)
        val minorVersion = fixedHeader.readUInt16Le(offset = 6)
        val fileHeaderSize = fixedHeader.readUInt16Le(offset = 8)
        val chunkHeaderSize = fixedHeader.readUInt16Le(offset = 10)
        val blockSize = fixedHeader.readUInt32Le(offset = 12)
        val totalBlocks = fixedHeader.readUInt32Le(offset = 16)
        val totalChunks = fixedHeader.readUInt32Le(offset = 20)
        val imageChecksum = fixedHeader.readUInt32Le(offset = 24)

        require(majorVersion == SUPPORTED_MAJOR_VERSION) {
            "Versão major Android Sparse não suportada: $majorVersion."
        }
        require(fileHeaderSize >= SPARSE_HEADER_SIZE) {
            "Header Android Sparse menor que $SPARSE_HEADER_SIZE bytes."
        }
        require(chunkHeaderSize >= CHUNK_HEADER_SIZE) {
            "Header de chunk Android Sparse menor que $CHUNK_HEADER_SIZE bytes."
        }
        require(blockSize > 0 && blockSize % 4L == 0L) {
            "Tamanho de bloco Android Sparse deve ser positivo e múltiplo de 4 bytes."
        }
        require(totalChunks <= maximumChunks.toLong()) {
            "Imagem Android Sparse declara $totalChunks chunks; limite configurado: $maximumChunks."
        }

        val expandedSize = checkedMultiply(
            left = totalBlocks,
            right = blockSize,
            label = "Tamanho expandido Android Sparse",
        )
        require(expandedSize <= maximumExpandedBytes) {
            "Imagem Android Sparse expandiria para $expandedSize bytes; limite configurado: $maximumExpandedBytes."
        }

        input.skipExactly(
            byteCount = (fileHeaderSize - SPARSE_HEADER_SIZE).toLong(),
            label = "extensão do header Android Sparse",
        )

        val header = AndroidSparseHeader(
            majorVersion = majorVersion,
            minorVersion = minorVersion,
            fileHeaderSize = fileHeaderSize,
            chunkHeaderSize = chunkHeaderSize,
            blockSizeBytes = blockSize,
            totalBlocks = totalBlocks,
            totalChunks = totalChunks,
            imageChecksum = imageChecksum,
        )
        val chunks = ArrayList<AndroidSparseChunk>(minOf(totalChunks.toInt(), INITIAL_CHUNK_CAPACITY))
        var currentOutputBlock = 0L

        repeat(totalChunks.toInt()) { index ->
            val fixedChunkHeader = input.readExactly(
                byteCount = CHUNK_HEADER_SIZE,
                label = "header do chunk Android Sparse #$index",
            )
            val type = AndroidSparseChunkType.fromWireValue(fixedChunkHeader.readUInt16Le(offset = 0))
            val outputBlockCount = fixedChunkHeader.readUInt32Le(offset = 4)
            val totalChunkSize = fixedChunkHeader.readUInt32Le(offset = 8)

            require(totalChunkSize >= chunkHeaderSize.toLong()) {
                "Chunk Android Sparse #$index declara total_sz menor que chunk_hdr_sz."
            }

            input.skipExactly(
                byteCount = (chunkHeaderSize - CHUNK_HEADER_SIZE).toLong(),
                label = "extensão do header do chunk Android Sparse #$index",
            )

            val inputPayloadSize = totalChunkSize - chunkHeaderSize.toLong()
            val outputStartBlock = currentOutputBlock
            var fillValue: Long? = null
            var crc32: Long? = null

            when (type) {
                AndroidSparseChunkType.RAW -> {
                    val expectedPayloadSize = checkedMultiply(
                        left = outputBlockCount,
                        right = blockSize,
                        label = "Payload RAW do chunk Android Sparse #$index",
                    )
                    require(inputPayloadSize == expectedPayloadSize) {
                        "Chunk RAW #$index possui $inputPayloadSize bytes; esperado: $expectedPayloadSize."
                    }
                    input.skipExactly(
                        byteCount = inputPayloadSize,
                        label = "payload RAW do chunk Android Sparse #$index",
                    )
                    currentOutputBlock = checkedAdd(
                        left = currentOutputBlock,
                        right = outputBlockCount,
                        label = "Contagem de blocos Android Sparse",
                    )
                }

                AndroidSparseChunkType.FILL -> {
                    require(inputPayloadSize == UINT32_SIZE.toLong()) {
                        "Chunk FILL #$index deve conter exatamente $UINT32_SIZE bytes de payload."
                    }
                    fillValue = input.readExactly(UINT32_SIZE, "payload FILL do chunk #$index")
                        .readUInt32Le(offset = 0)
                    currentOutputBlock = checkedAdd(
                        left = currentOutputBlock,
                        right = outputBlockCount,
                        label = "Contagem de blocos Android Sparse",
                    )
                }

                AndroidSparseChunkType.DONT_CARE -> {
                    require(inputPayloadSize == 0L) {
                        "Chunk DONT_CARE #$index não pode conter payload."
                    }
                    currentOutputBlock = checkedAdd(
                        left = currentOutputBlock,
                        right = outputBlockCount,
                        label = "Contagem de blocos Android Sparse",
                    )
                }

                AndroidSparseChunkType.CRC32 -> {
                    require(inputPayloadSize == UINT32_SIZE.toLong()) {
                        "Chunk CRC32 #$index deve conter exatamente $UINT32_SIZE bytes de payload."
                    }
                    crc32 = input.readExactly(UINT32_SIZE, "payload CRC32 do chunk #$index")
                        .readUInt32Le(offset = 0)
                }
            }

            require(currentOutputBlock <= totalBlocks) {
                "Chunks Android Sparse excedem a contagem declarada de $totalBlocks blocos."
            }

            chunks += AndroidSparseChunk(
                index = index,
                type = type,
                outputStartBlock = outputStartBlock,
                outputBlockCount = outputBlockCount,
                inputPayloadSizeBytes = inputPayloadSize,
                fillValue = fillValue,
                crc32 = crc32,
            )
        }

        require(currentOutputBlock == totalBlocks) {
            "Chunks Android Sparse descrevem $currentOutputBlock blocos; header declara $totalBlocks."
        }

        return AndroidSparseImageMetadata(
            header = header,
            chunks = chunks,
            expandedSizeBytes = expandedSize,
            sparseBytesConsumed = input.bytesConsumed,
        )
    }

    private class LimitedInput(
        private val source: InputStream,
        private val maximumBytes: Long,
    ) {
        private val skipBuffer = ByteArray(DEFAULT_SKIP_BUFFER_SIZE)
        var bytesConsumed: Long = 0L
            private set

        fun readExactly(byteCount: Int, label: String): ByteArray {
            require(byteCount >= 0) { "byteCount deve ser não negativo." }
            ensureWithinLimit(byteCount.toLong(), label)
            val target = ByteArray(byteCount)
            var offset = 0

            while (offset < byteCount) {
                val read = source.read(target, offset, byteCount - offset)
                when {
                    read < 0 -> throw IllegalArgumentException("Imagem truncada ao ler $label.")
                    read == 0 -> {
                        val single = source.read()
                        if (single < 0) {
                            throw IllegalArgumentException("Imagem truncada ao ler $label.")
                        }
                        target[offset] = single.toByte()
                        offset += 1
                        bytesConsumed += 1
                    }
                    else -> {
                        offset += read
                        bytesConsumed += read.toLong()
                    }
                }
            }
            return target
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
        const val SPARSE_HEADER_MAGIC: Long = 0xED26FF3AL
        const val SUPPORTED_MAJOR_VERSION: Int = 1
        const val SPARSE_HEADER_SIZE: Int = 28
        const val CHUNK_HEADER_SIZE: Int = 12
        const val UINT32_SIZE: Int = 4
        const val INITIAL_CHUNK_CAPACITY: Int = 4096
        const val DEFAULT_MAXIMUM_CHUNKS: Int = 100_000
        const val DEFAULT_SKIP_BUFFER_SIZE: Int = 8192
        const val DEFAULT_MAXIMUM_INPUT_BYTES: Long = 64L * 1024 * 1024 * 1024
        const val DEFAULT_MAXIMUM_EXPANDED_BYTES: Long = 16L * 1024 * 1024 * 1024 * 1024
    }
}

private fun ByteArray.readUInt16Le(offset: Int): Int {
    require(offset >= 0 && offset + 2 <= size) { "Leitura UInt16 fora dos limites." }
    return (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8)
}

private fun ByteArray.readUInt32Le(offset: Int): Long {
    require(offset >= 0 && offset + 4 <= size) { "Leitura UInt32 fora dos limites." }
    return (this[offset].toLong() and 0xFFL) or
        ((this[offset + 1].toLong() and 0xFFL) shl 8) or
        ((this[offset + 2].toLong() and 0xFFL) shl 16) or
        ((this[offset + 3].toLong() and 0xFFL) shl 24)
}

private fun checkedMultiply(left: Long, right: Long, label: String): Long {
    require(left >= 0 && right >= 0) { "$label não aceita valores negativos." }
    require(left == 0L || right <= Long.MAX_VALUE / left) { "$label excede Long.MAX_VALUE." }
    return left * right
}

private fun checkedAdd(left: Long, right: Long, label: String): Long {
    require(left >= 0 && right >= 0) { "$label não aceita valores negativos." }
    require(right <= Long.MAX_VALUE - left) { "$label excede Long.MAX_VALUE." }
    return left + right
}
