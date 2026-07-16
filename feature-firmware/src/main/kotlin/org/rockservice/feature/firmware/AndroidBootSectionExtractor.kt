package org.rockservice.feature.firmware

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Evidence produced after extracting one validated Android Boot payload section. */
data class AndroidBootSectionExtractionReport(
    val sectionType: AndroidBootSectionType,
    val offsetBytes: Long,
    val extractedBytes: Long,
    val sourceBytesRead: Long,
    val sourceSha256: String,
    val sectionSha256: String,
)

/**
 * Streams one payload section from an Android Boot image while verifying the complete source hash.
 *
 * The metadata must come from the defensive AndroidBootImageParser. The caller owns both streams;
 * this extractor never closes them. A destination may contain partial data when any late validation
 * fails, so callers must expose that state explicitly.
 */
class AndroidBootSectionExtractor(
    private val maximumInputBytes: Long = DEFAULT_MAXIMUM_INPUT_BYTES,
    private val maximumSectionBytes: Long = DEFAULT_MAXIMUM_SECTION_BYTES,
    private val bufferSizeBytes: Int = DEFAULT_BUFFER_SIZE_BYTES,
) {
    init {
        require(maximumInputBytes > 0L) { "maximumInputBytes deve ser maior que zero." }
        require(maximumSectionBytes > 0L) { "maximumSectionBytes deve ser maior que zero." }
        require(bufferSizeBytes > 0) { "bufferSizeBytes deve ser maior que zero." }
    }

    /** Extracts exactly one non-header section and verifies that the source still matches its SHA-256. */
    fun extract(
        source: InputStream,
        metadata: AndroidBootImageMetadata,
        expectedSourceSha256: String,
        sectionType: AndroidBootSectionType,
        destination: OutputStream,
    ): AndroidBootSectionExtractionReport {
        require(sectionType != AndroidBootSectionType.HEADER) {
            "A extração controlada não expõe a seção HEADER neste gate."
        }
        val normalizedExpectedHash = expectedSourceSha256.lowercase()
        require(normalizedExpectedHash.matches(SHA256_PATTERN)) {
            "SHA-256 esperado da imagem Android Boot deve conter 64 caracteres hexadecimais."
        }

        val matchingSections = metadata.sections.filter { section -> section.type == sectionType }
        require(matchingSections.size == 1) {
            if (matchingSections.isEmpty()) {
                "A imagem Android Boot não contém a seção $sectionType."
            } else {
                "Metadata Android Boot contém múltiplas seções $sectionType."
            }
        }
        val section = matchingSections.single()
        require(section.sizeBytes > 0L) { "A seção $sectionType não possui payload para extrair." }
        require(section.offsetBytes >= 0L) { "Offset da seção $sectionType não pode ser negativo." }
        require(section.sizeBytes <= maximumSectionBytes) {
            "A seção $sectionType possui ${section.sizeBytes} bytes; limite configurado: $maximumSectionBytes."
        }
        val sectionEnd = checkedAdd(section.offsetBytes, section.sizeBytes, "Fim da seção $sectionType")
        require(sectionEnd <= metadata.minimumImageSizeBytes) {
            "A seção $sectionType ultrapassa o layout mínimo validado da imagem Android Boot."
        }
        require(metadata.minimumImageSizeBytes <= maximumInputBytes) {
            "O layout mínimo Android Boot possui ${metadata.minimumImageSizeBytes} bytes; limite de leitura: " +
                "$maximumInputBytes."
        }

        val sourceDigest = MessageDigest.getInstance("SHA-256")
        val sectionDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSizeBytes)
        var sourceOffset = 0L
        var extractedBytes = 0L

        while (true) {
            val remainingAllowance = maximumInputBytes - sourceOffset
            if (remainingAllowance == 0L) {
                val overflowByte = source.read()
                require(overflowByte < 0) {
                    "A imagem Android Boot excede o limite de leitura de $maximumInputBytes bytes."
                }
                break
            }

            val request = minOf(buffer.size.toLong(), remainingAllowance).toInt()
            val read = source.read(buffer, 0, request)
            when {
                read < 0 -> break
                read == 0 -> {
                    val single = source.read()
                    if (single < 0) break
                    buffer[0] = single.toByte()
                    processBufferRange(
                        buffer = buffer,
                        byteCount = 1,
                        sourceOffset = sourceOffset,
                        section = section,
                        destination = destination,
                        sourceDigest = sourceDigest,
                        sectionDigest = sectionDigest,
                    ).also { extractedBytes += it }
                    sourceOffset += 1L
                }
                else -> {
                    processBufferRange(
                        buffer = buffer,
                        byteCount = read,
                        sourceOffset = sourceOffset,
                        section = section,
                        destination = destination,
                        sourceDigest = sourceDigest,
                        sectionDigest = sectionDigest,
                    ).also { extractedBytes += it }
                    sourceOffset += read.toLong()
                }
            }
        }

        require(sourceOffset >= metadata.minimumImageSizeBytes) {
            "Imagem Android Boot truncada: lidos $sourceOffset bytes; layout mínimo exige " +
                "${metadata.minimumImageSizeBytes}."
        }
        require(extractedBytes == section.sizeBytes) {
            "Extração da seção $sectionType produziu $extractedBytes bytes; esperado: ${section.sizeBytes}."
        }

        val actualSourceSha256 = sourceDigest.digest().toHex()
        require(actualSourceSha256 == normalizedExpectedHash) {
            "A imagem Android Boot mudou desde a análise: SHA-256 atual não corresponde ao esperado."
        }

        return AndroidBootSectionExtractionReport(
            sectionType = sectionType,
            offsetBytes = section.offsetBytes,
            extractedBytes = extractedBytes,
            sourceBytesRead = sourceOffset,
            sourceSha256 = actualSourceSha256,
            sectionSha256 = sectionDigest.digest().toHex(),
        )
    }

    private fun processBufferRange(
        buffer: ByteArray,
        byteCount: Int,
        sourceOffset: Long,
        section: AndroidBootSection,
        destination: OutputStream,
        sourceDigest: MessageDigest,
        sectionDigest: MessageDigest,
    ): Long {
        sourceDigest.update(buffer, 0, byteCount)

        val bufferEnd = checkedAdd(sourceOffset, byteCount.toLong(), "Fim do buffer Android Boot")
        val sectionEnd = checkedAdd(section.offsetBytes, section.sizeBytes, "Fim da seção ${section.type}")
        val overlapStart = maxOf(sourceOffset, section.offsetBytes)
        val overlapEnd = minOf(bufferEnd, sectionEnd)
        if (overlapStart >= overlapEnd) return 0L

        val startInBuffer = (overlapStart - sourceOffset).toInt()
        val overlapLength = (overlapEnd - overlapStart).toInt()
        destination.write(buffer, startInBuffer, overlapLength)
        sectionDigest.update(buffer, startInBuffer, overlapLength)
        return overlapLength.toLong()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val DEFAULT_BUFFER_SIZE_BYTES = 64 * 1024
        const val DEFAULT_MAXIMUM_INPUT_BYTES = 64L * 1024 * 1024 * 1024
        const val DEFAULT_MAXIMUM_SECTION_BYTES = 16L * 1024 * 1024 * 1024
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
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
