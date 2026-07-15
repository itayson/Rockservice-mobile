package org.rockservice.feature.firmware

import java.io.BufferedInputStream
import java.io.InputStream
import java.security.MessageDigest

enum class FirmwareFormat {
    ZIP,
    ANDROID_SPARSE,
    ELF,
    ISO_9660,
    UNKNOWN,
}

data class FirmwareAnalysis(
    val format: FirmwareFormat,
    val sha256: String,
    val bytesRead: Long,
    val warnings: List<String>,
)

class FirmwareAnalyzer(
    private val maximumBytes: Long = 16L * 1024 * 1024 * 1024,
) {
    fun analyze(source: InputStream): FirmwareAnalysis {
        val input = BufferedInputStream(source)
        input.mark(0x9000)
        val header = ByteArray(0x9000)
        var headerLength = 0
        while (headerLength < header.size) {
            val read = input.read(header, headerLength, header.size - headerLength)
            if (read < 0) break
            if (read == 0) continue
            headerLength += read
        }
        input.reset()

        val format = identify(header, headerLength)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L

        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maximumBytes) {
                "Arquivo excede o limite configurado de $maximumBytes bytes."
            }
            digest.update(buffer, 0, read)
        }

        val warnings = buildList {
            if (format == FirmwareFormat.UNKNOWN) add("Formato não reconhecido por magic bytes.")
            if (total == 0L) add("Arquivo vazio.")
        }
        return FirmwareAnalysis(
            format = format,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            bytesRead = total,
            warnings = warnings,
        )
    }

    internal fun identify(header: ByteArray, length: Int): FirmwareFormat {
        if (length >= 4 && header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04))) {
            return FirmwareFormat.ZIP
        }
        if (length >= 4 && header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x3A, 0xFF.toByte(), 0x26, 0xED.toByte()))) {
            return FirmwareFormat.ANDROID_SPARSE
        }
        if (length >= 4 && header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7F, 0x45, 0x4C, 0x46))) {
            return FirmwareFormat.ELF
        }
        if (length >= 0x8006 &&
            String(header.copyOfRange(0x8001, 0x8006), Charsets.US_ASCII) == "CD001"
        ) {
            return FirmwareFormat.ISO_9660
        }
        return FirmwareFormat.UNKNOWN
    }
}
