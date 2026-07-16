package org.rockservice.mobile

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class RockchipBackupManifest(
    val createdAtEpochMillis: Long,
    val vendorId: Int,
    val productId: Int,
    val startSector: Long,
    val sectorCount: Long,
    val byteCount: Long,
    val sha256: String,
) {
    init {
        require(vendorId in 0..0xFFFF)
        require(productId in 0..0xFFFF)
        require(startSector >= 0L)
        require(sectorCount > 0L)
        require(byteCount == Math.multiplyExact(sectorCount, 512L))
        require(SHA256_REGEX.matches(sha256))
    }

    fun toJson(): String = buildString {
        append("{\n")
        append("  \"schema\": \"rockservice.backup-manifest.v1\",\n")
        append("  \"createdAtEpochMillis\": ").append(createdAtEpochMillis).append(",\n")
        append("  \"vendorId\": ").append(vendorId).append(",\n")
        append("  \"productId\": ").append(productId).append(",\n")
        append("  \"startSector\": ").append(startSector).append(",\n")
        append("  \"sectorCount\": ").append(sectorCount).append(",\n")
        append("  \"byteCount\": ").append(byteCount).append(",\n")
        append("  \"sha256\": \"").append(sha256).append("\"\n")
        append("}\n")
    }

    companion object {
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")
    }
}

data class RockchipBackupVerification(
    val verified: Boolean,
    val actualByteCount: Long,
    val actualSha256: String,
    val detail: String,
)

/** Independently re-reads a local SAF backup before any future restore can be planned. */
object RockchipBackupIntegrityVerifier {
    suspend fun verify(
        contentResolver: ContentResolver,
        source: Uri,
        manifest: RockchipBackupManifest,
    ): RockchipBackupVerification = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesRead = 0L
        val input = contentResolver.openInputStream(source)
            ?: throw IOException("O arquivo de backup selecionado não pode ser aberto.")
        input.buffered().use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                bytesRead = Math.addExact(bytesRead, count.toLong())
                require(bytesRead <= manifest.byteCount) {
                    "O arquivo excede o tamanho registrado no manifesto."
                }
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().toLowerHex()
        val verified = bytesRead == manifest.byteCount && hash == manifest.sha256
        RockchipBackupVerification(
            verified = verified,
            actualByteCount = bytesRead,
            actualSha256 = hash,
            detail = if (verified) {
                "Tamanho e SHA-256 correspondem ao manifesto local."
            } else {
                "O arquivo não corresponde integralmente ao manifesto local."
            },
        )
    }

    private const val BUFFER_SIZE = 64 * 1024
}

data class RockchipRestoreDryRunPlan(
    val allowed: Boolean,
    val targetStartSector: Long,
    val sectorCount: Long,
    val byteCount: Long,
    val expectedSha256: String,
    val blockers: List<String>,
)

/**
 * Produces a non-executable restore plan. No write opcode or transport primitive is represented here.
 */
object RockchipRestoreDryRunPlanner {
    fun plan(
        manifest: RockchipBackupManifest,
        verification: RockchipBackupVerification,
        currentVendorId: Int,
        currentProductId: Int,
        physicallyValidatedWritePath: Boolean,
        recoveryPlanAvailable: Boolean,
    ): RockchipRestoreDryRunPlan {
        val blockers = buildList {
            if (!verification.verified) add("Backup local não passou na revalidação independente de integridade.")
            if (currentVendorId != manifest.vendorId || currentProductId != manifest.productId) {
                add("VID/PID do alvo atual não correspondem ao alvo registrado no manifesto.")
            }
            if (!physicallyValidatedWritePath) add("Caminho de escrita ainda não possui validação física explícita.")
            if (!recoveryPlanAvailable) add("Não existe plano de recuperação validado para esta combinação de hardware.")
        }
        return RockchipRestoreDryRunPlan(
            allowed = blockers.isEmpty(),
            targetStartSector = manifest.startSector,
            sectorCount = manifest.sectorCount,
            byteCount = manifest.byteCount,
            expectedSha256 = manifest.sha256,
            blockers = blockers,
        )
    }
}

private fun ByteArray.toLowerHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        this@toLowerHex.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(digits[value ushr 4])
            append(digits[value and 0x0F])
        }
    }
}
