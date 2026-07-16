package org.rockservice.feature.firmware

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/** Result of one bounded streaming Android Sparse expansion. */
data class AndroidSparseExpansionReport(
    val expandedSizeBytes: Long,
    val sparseBytesConsumed: Long,
    val chunkCount: Long,
    val outputSha256: String,
    val outputCrc32: Long,
    val validatedCrc32Chunks: Int,
    val headerChecksumValidated: Boolean,
)

/**
 * Expands Android Sparse images to deterministic raw bytes with bounded working memory.
 *
 * The caller owns both streams. This class never closes them. DONT_CARE chunks are materialized as
 * zero bytes so expansion behaves consistently for arbitrary OutputStream implementations.
 */
class AndroidSparseImageExpander(
    private val maximumChunks: Int = DEFAULT_MAXIMUM_CHUNKS,
    private val maximumInputBytes: Long = DEFAULT_MAXIMUM_INPUT_BYTES,
    private val maximumExpandedBytes: Long = DEFAULT_MAXIMUM_EXPANDED_BYTES,
    private val bufferSizeBytes: Int = DEFAULT_BUFFER_SIZE_BYTES,
) {
    init {
        require(maximumChunks > 0) { "maximumChunks deve ser maior que zero." }
        require(maximumInputBytes > 0) { "maximumInputBytes deve ser maior que zero." }
        require(maximumExpandedBytes > 0) { "maximumExpandedBytes deve ser maior que zero." }
        require(bufferSizeBytes >= UINT32_SIZE) { "bufferSizeBytes deve ser pelo menos $UINT32_SIZE." }
    }

    /** Expands one sparse stream while validating structure, CRC chunks and configured limits. */
    fun expand(source: InputStream, destination: OutputStream): AndroidSparseExpansionReport {
        val input = LimitedInput(source, maximumInputBytes, bufferSizeBytes)
        val fixedHeader = input.readExactly(SPARSE_HEADER_SIZE, "header Android Sparse")

        val magic = fixedHeader.readUInt32Le(offset = 0)
        require(magic == SPARSE_HEADER_MAGIC) {
            "Magic Android Sparse inválida: 0x${magic.toString(16).uppercase()}."
        }

        val majorVersion = fixedHeader.readUInt16Le(offset = 4)
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
        require(blockSize > 0 && blockSize % UINT32_SIZE == 0L) {
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

        val sha256 = MessageDigest.getInstance("SHA-256")
        val crc32 = CRC32()
        val zeroBuffer = ByteArray(bufferSizeBytes)
        var currentOutputBlock = 0L
        var bytesWritten = 0L
        var validatedCrc32Chunks = 0

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
                    ensureOutputRange(currentOutputBlock, outputBlockCount, totalBlocks, index)
                    input.copyExactly(
                        byteCount = inputPayloadSize,
                        destination = destination,
                        sha256 = sha256,
                        crc32 = crc32,
                        label = "payload RAW do chunk Android Sparse #$index",
                    )
                    currentOutputBlock = checkedAdd(
                        currentOutputBlock,
                        outputBlockCount,
                        "Contagem de blocos Android Sparse",
                    )
                    bytesWritten = checkedAdd(bytesWritten, expectedPayloadSize, "Bytes expandidos Android Sparse")
                }

                AndroidSparseChunkType.FILL -> {
                    require(inputPayloadSize == UINT32_SIZE.toLong()) {
                        "Chunk FILL #$index deve conter exatamente $UINT32_SIZE bytes de payload."
                    }
                    ensureOutputRange(currentOutputBlock, outputBlockCount, totalBlocks, index)
                    val pattern = input.readExactly(UINT32_SIZE, "payload FILL do chunk #$index")
                    val outputBytes = checkedMultiply(
                        outputBlockCount,
                        blockSize,
                        "Saída FILL do chunk Android Sparse #$index",
                    )
                    writeRepeatedPattern(
                        destination = destination,
                        pattern = pattern,
                        byteCount = outputBytes,
                        sha256 = sha256,
                        crc32 = crc32,
                    )
                    currentOutputBlock = checkedAdd(
                        currentOutputBlock,
                        outputBlockCount,
                        "Contagem de blocos Android Sparse",
                    )
                    bytesWritten = checkedAdd(bytesWritten, outputBytes, "Bytes expandidos Android Sparse")
                }

                AndroidSparseChunkType.DONT_CARE -> {
                    require(inputPayloadSize == 0L) {
                        "Chunk DONT_CARE #$index não pode conter payload."
                    }
                    ensureOutputRange(currentOutputBlock, outputBlockCount, totalBlocks, index)
                    val outputBytes = checkedMultiply(
                        outputBlockCount,
                        blockSize,
                        "Saída DONT_CARE do chunk Android Sparse #$index",
                    )
                    writeRepeatedBuffer(
                        destination = destination,
                        buffer = zeroBuffer,
                        byteCount = outputBytes,
                        sha256 = sha256,
                        crc32 = crc32,
                    )
                    currentOutputBlock = checkedAdd(
                        currentOutputBlock,
                        outputBlockCount,
                        "Contagem de blocos Android Sparse",
                    )
                    bytesWritten = checkedAdd(bytesWritten, outputBytes, "Bytes expandidos Android Sparse")
                }

                AndroidSparseChunkType.CRC32 -> {
                    require(inputPayloadSize == UINT32_SIZE.toLong()) {
                        "Chunk CRC32 #$index deve conter exatamente $UINT32_SIZE bytes de payload."
                    }
                    val expectedCrc32 = input.readExactly(UINT32_SIZE, "payload CRC32 do chunk #$index")
                        .readUInt32Le(offset = 0)
                    val actualCrc32 = crc32.value
                    require(expectedCrc32 == actualCrc32) {
                        "CRC32 do chunk #$index divergente: esperado 0x${expectedCrc32.toString(16).uppercase()}, " +
                            "calculado 0x${actualCrc32.toString(16).uppercase()}."
                    }
                    validatedCrc32Chunks += 1
                }
            }
        }

        require(currentOutputBlock == totalBlocks) {
            "Chunks Android Sparse descrevem $currentOutputBlock blocos; header declara $totalBlocks."
        }
        require(bytesWritten == expandedSize) {
            "Expansão Android Sparse produziu $bytesWritten bytes; esperado: $expandedSize."
        }

        val outputCrc32 = crc32.value
        val headerChecksumValidated = imageChecksum != 0L
        if (headerChecksumValidated) {
            require(outputCrc32 == imageChecksum) {
                "Checksum CRC32 do header Android Sparse divergente: esperado " +
                    "0x${imageChecksum.toString(16).uppercase()}, calculado " +
                    "0x${outputCrc32.toString(16).uppercase()}."
            }
        }

        return AndroidSparseExpansionReport(
            expandedSizeBytes = bytesWritten,
            sparseBytesConsumed = input.bytesConsumed,
            chunkCount = totalChunks,
            outputSha256 = sha256.digest().joinToString("") { byte -> "%02x".format(byte) },
            outputCrc32 = outputCrc32,
            validatedCrc32Chunks = validatedCrc32Chunks,
            headerChecksumValidated = headerChecksumValidated,
        )
    }

    private fun ensureOutputRange(
        currentOutputBlock: Long,
        outputBlockCount: Long,
        totalBlocks: Long,
        chunkIndex: Int,
    ) {
        val nextBlock = checkedAdd(
            currentOutputBlock,
            outputBlockCount,
            "Contagem de blocos Android Sparse",
        )
        require(nextBlock <= totalBlocks) {
            "Chunk Android Sparse #$chunkIndex excede a contagem declarada de $totalBlocks blocos."
        }
    }

    private fun writeRepeatedPattern(
        destination: OutputStream,
        pattern: ByteArray,
        byteCount: Long,
        sha256: MessageDigest,
        crc32: CRC32,
    ) {
        require(pattern.isNotEmpty()) { "Padrão de preenchimento não pode ser vazio." }
        val bufferLength = bufferSizeBytes - (bufferSizeBytes % pattern.size)
        require(bufferLength > 0) { "Buffer de expansão é menor que o padrão FILL." }
        val buffer = ByteArray(bufferLength)
        var offset = 0
        while (offset < buffer.size) {
            pattern.copyInto(buffer, destinationOffset = offset)
            offset += pattern.size
        }
        writeRepeatedBuffer(destination, buffer, byteCount, sha256, crc32)
    }

    private fun writeRepeatedBuffer(
        destination: OutputStream,
        buffer: ByteArray,
        byteCount: Long,
        sha256: MessageDigest,
        crc32: CRC32,
    ) {
        var remaining = byteCount
        while (remaining > 0L) {
            val writeSize = minOf(remaining, buffer.size.toLong()).toInt()
            destination.write(buffer, 0, writeSize)
            sha256.update(buffer, 0, writeSize)
            crc32.update(buffer, 0, writeSize)
            remaining -= writeSize.toLong()
        }
    }

    private class LimitedInput(
        private val source: InputStream,
        private val maximumBytes: Long,
        bufferSizeBytes: Int,
    ) {
        private val transferBuffer = ByteArray(bufferSizeBytes)
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
                    read == 0 -> readSingleByte(target, offset, label).also { offset += 1 }
                    else -> {
                        offset += read
                        bytesConsumed += read.toLong()
                    }
                }
            }
            return target
        }

        fun copyExactly(
            byteCount: Long,
            destination: OutputStream,
            sha256: MessageDigest,
            crc32: CRC32,
            label: String,
        ) {
            require(byteCount >= 0L) { "byteCount deve ser não negativo." }
            ensureWithinLimit(byteCount, label)
            var remaining = byteCount
            while (remaining > 0L) {
                val request = minOf(remaining, transferBuffer.size.toLong()).toInt()
                val read = source.read(transferBuffer, 0, request)
                when {
                    read < 0 -> throw IllegalArgumentException("Imagem truncada ao ler $label.")
                    read == 0 -> {
                        val single = source.read()
                        if (single < 0) throw IllegalArgumentException("Imagem truncada ao ler $label.")
                        transferBuffer[0] = single.toByte()
                        destination.write(transferBuffer, 0, 1)
                        sha256.update(transferBuffer, 0, 1)
                        crc32.update(transferBuffer, 0, 1)
                        remaining -= 1L
                        bytesConsumed += 1L
                    }
                    else -> {
                        destination.write(transferBuffer, 0, read)
                        sha256.update(transferBuffer, 0, read)
                        crc32.update(transferBuffer, 0, read)
                        remaining -= read.toLong()
                        bytesConsumed += read.toLong()
                    }
                }
            }
        }

        fun skipExactly(byteCount: Long, label: String) {
            require(byteCount >= 0L) { "byteCount deve ser não negativo." }
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
                val request = minOf(remaining, transferBuffer.size.toLong()).toInt()
                val read = source.read(transferBuffer, 0, request)
                when {
                    read < 0 -> throw IllegalArgumentException("Imagem truncada ao ler $label.")
                    read == 0 -> {
                        val single = source.read()
                        if (single < 0) throw IllegalArgumentException("Imagem truncada ao ler $label.")
                        remaining -= 1L
                        bytesConsumed += 1L
                    }
                    else -> {
                        remaining -= read.toLong()
                        bytesConsumed += read.toLong()
                    }
                }
            }
        }

        private fun readSingleByte(target: ByteArray, offset: Int, label: String) {
            val single = source.read()
            if (single < 0) throw IllegalArgumentException("Imagem truncada ao ler $label.")
            target[offset] = single.toByte()
            bytesConsumed += 1L
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
        const val DEFAULT_MAXIMUM_CHUNKS: Int = 100_000
        const val DEFAULT_BUFFER_SIZE_BYTES: Int = 64 * 1024
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
