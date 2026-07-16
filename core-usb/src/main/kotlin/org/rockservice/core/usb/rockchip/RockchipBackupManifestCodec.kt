package org.rockservice.core.usb.rockchip

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Encodes and decodes the small, versioned sidecar manifest used for local backups. */
object RockchipBackupManifestCodec {
    private const val HEADER = "ROCKSERVICE-ROCKCHIP-BACKUP-MANIFEST"
    private const val CURRENT_VERSION = 1
    private const val DEFAULT_MAXIMUM_MANIFEST_BYTES = 16 * 1024
    private const val MAXIMUM_ALLOWED_MANIFEST_BYTES = 64 * 1024
    private const val READ_BUFFER_BYTES = 4 * 1024
    private val sha256Regex = Regex("[0-9a-f]{64}")

    private val requiredKeys = setOf(
        "version",
        "startSector",
        "sectorCount",
        "byteCount",
        "sha256",
    )

    fun encode(manifest: RockchipBackupManifest): String = buildString {
        val sha256 = requireSha256(manifest.sha256)
        appendLine(HEADER)
        appendLine("version=$CURRENT_VERSION")
        appendLine("startSector=${manifest.startSector}")
        appendLine("sectorCount=${manifest.sectorCount}")
        appendLine("byteCount=${manifest.byteCount}")
        appendLine("sha256=$sha256")
    }

    /** Decodes one manifest stream without allowing unbounded local input into memory. */
    fun decode(
        source: InputStream,
        maximumBytes: Int = DEFAULT_MAXIMUM_MANIFEST_BYTES,
    ): RockchipBackupManifest {
        require(maximumBytes in 256..MAXIMUM_ALLOWED_MANIFEST_BYTES) {
            "maximumBytes must be between 256 and $MAXIMUM_ALLOWED_MANIFEST_BYTES."
        }

        val output = ByteArrayOutputStream(minOf(maximumBytes, READ_BUFFER_BYTES))
        val buffer = ByteArray(minOf(maximumBytes, READ_BUFFER_BYTES))
        var totalBytes = 0
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            totalBytes = Math.addExact(totalBytes, read)
            require(totalBytes <= maximumBytes) {
                "Manifest exceeds the maximum allowed size of $maximumBytes bytes."
            }
            output.write(buffer, 0, read)
        }

        return decode(decodeUtf8Strict(output.toByteArray()))
    }

    fun decode(text: String): RockchipBackupManifest {
        val lines = text.lineSequence()
            .map { line -> line.removeSuffix("\r") }
            .filter { line -> line.isNotBlank() }
            .toList()

        require(lines.firstOrNull() == HEADER) { "Manifest header is missing or unsupported." }

        val fields = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "Manifest contains a malformed field." }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            require(key in requiredKeys) { "Manifest contains unsupported field '$key'." }
            require(fields.put(key, value) == null) { "Manifest contains duplicate field '$key'." }
        }

        val missing = requiredKeys - fields.keys
        require(missing.isEmpty()) { "Manifest is missing required field(s): ${missing.sorted().joinToString()}." }
        require(fields.getValue("version").toIntOrNull() == CURRENT_VERSION) {
            "Manifest version is unsupported."
        }

        return RockchipBackupManifest(
            startSector = fields.getValue("startSector").toLongOrNull()
                ?: throw IllegalArgumentException("Manifest startSector is invalid."),
            sectorCount = fields.getValue("sectorCount").toLongOrNull()
                ?: throw IllegalArgumentException("Manifest sectorCount is invalid."),
            byteCount = fields.getValue("byteCount").toLongOrNull()
                ?: throw IllegalArgumentException("Manifest byteCount is invalid."),
            sha256 = requireSha256(fields.getValue("sha256")),
        )
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("Manifest must contain valid UTF-8 text.", error)
    }

    private fun requireSha256(value: String): String {
        require(sha256Regex.matches(value)) {
            "Manifest sha256 must be a lowercase 64-character hexadecimal digest."
        }
        return value
    }
}

/** Converts a successfully completed bounded result into its immutable sidecar manifest. */
fun RockchipBoundedBackupResult.toBackupManifest(): RockchipBackupManifest = RockchipBackupManifest(
    startSector = startSector,
    sectorCount = sectorCount,
    byteCount = byteCount,
    sha256 = sha256,
)
