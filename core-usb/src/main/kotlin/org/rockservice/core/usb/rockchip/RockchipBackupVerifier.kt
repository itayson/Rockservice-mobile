package org.rockservice.core.usb.rockchip

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Immutable metadata describing one completed local read-only backup. */
data class RockchipBackupManifest(
    val startSector: Long,
    val sectorCount: Long,
    val byteCount: Long,
    val sha256: String,
) {
    init {
        require(startSector >= 0L) { "startSector must not be negative." }
        require(sectorCount > 0L) { "sectorCount must be greater than zero." }
        require(sectorCount <= Long.MAX_VALUE / LOGICAL_SECTOR_SIZE.toLong()) {
            "sectorCount is too large to represent the backup size in bytes."
        }
        val expectedByteCount = sectorCount * LOGICAL_SECTOR_SIZE.toLong()
        require(byteCount == expectedByteCount) {
            "byteCount must match sectorCount * $LOGICAL_SECTOR_SIZE."
        }
        require(SHA256_REGEX.matches(sha256)) { "sha256 must be a lowercase 64-character hexadecimal digest." }
    }

    private companion object {
        const val LOGICAL_SECTOR_SIZE = 512
        val SHA256_REGEX = Regex("[0-9a-f]{64}")
    }
}

/** Result of independently checking a local backup against its immutable manifest. */
data class RockchipBackupVerificationResult(
    val expectedByteCount: Long,
    val actualByteCount: Long,
    val expectedSha256: String,
    val actualSha256: String,
) {
    val sizeMatches: Boolean get() = actualByteCount == expectedByteCount
    val sha256Matches: Boolean get() = actualSha256 == expectedSha256
    val verified: Boolean get() = sizeMatches && sha256Matches
}

/**
 * Revalidates a local backup independently from the original USB read operation.
 *
 * The verifier performs no USB access and exposes no write capability.
 */
object RockchipBackupVerifier {
    /** Reads the supplied local stream once and compares its byte count and SHA-256 with [manifest]. */
    fun verify(
        manifest: RockchipBackupManifest,
        source: InputStream,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
    ): RockchipBackupVerificationResult {
        require(bufferSize in MINIMUM_BUFFER_SIZE..MAXIMUM_BUFFER_SIZE) {
            "bufferSize must be between $MINIMUM_BUFFER_SIZE and $MAXIMUM_BUFFER_SIZE bytes."
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSize)
        var total = 0L

        try {
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total = Math.addExact(total, read.toLong())
                digest.update(buffer, 0, read)
            }
        } catch (error: IOException) {
            throw IOException(
                "Failed to read the local backup while verifying its size and SHA-256.",
                error,
            )
        }

        return RockchipBackupVerificationResult(
            expectedByteCount = manifest.byteCount,
            actualByteCount = total,
            expectedSha256 = manifest.sha256,
            actualSha256 = digest.digest().toLowerHex(),
        )
    }

    private const val DEFAULT_BUFFER_SIZE = 64 * 1024
    private const val MINIMUM_BUFFER_SIZE = 512
    private const val MAXIMUM_BUFFER_SIZE = 1024 * 1024
}

private fun ByteArray.toLowerHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toLowerHex) {
            val value = byte.toInt() and 0xFF
            append(digits[value ushr 4])
            append(digits[value and 0x0F])
        }
    }
}
