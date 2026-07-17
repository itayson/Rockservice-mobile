package org.rockservice.core.usb.media

/** Immutable plan for a future raw image operation. */
data class RawImageWritePlan(
    val imageSizeBytes: Long,
    val expectedSha256: String,
    val targetCapacityBytes: Long,
    val blockSizeBytes: Int,
    val requiredBlocks: Long,
    val trailingCapacityBytes: Long,
)

/**
 * Validates immutable source metadata against a target block geometry without physical I/O.
 *
 * The expected SHA-256 binds later execution to the exact source that was validated here.
 */
object RawImageWritePlanner {
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

    /** Builds a bounded plan only when source identity and target geometry are valid. */
    fun plan(
        imageSizeBytes: Long,
        expectedSha256: String,
        targetCapacityBytes: Long,
        blockSizeBytes: Int,
    ): RawImageWritePlan {
        require(imageSizeBytes > 0L) { "A imagem deve conter pelo menos 1 byte." }
        require(SHA256_PATTERN.matches(expectedSha256)) {
            "O SHA-256 esperado deve conter exatamente 64 caracteres hexadecimais minúsculos."
        }
        require(targetCapacityBytes > 0L) { "A capacidade do destino deve ser positiva." }
        require(blockSizeBytes > 0) { "O tamanho de bloco deve ser positivo." }

        val blockSize = blockSizeBytes.toLong()
        require(targetCapacityBytes % blockSize == 0L) {
            "A capacidade do destino deve ser alinhada ao tamanho de bloco informado."
        }
        require(imageSizeBytes <= targetCapacityBytes) {
            "A imagem ($imageSizeBytes bytes) é maior que o destino ($targetCapacityBytes bytes)."
        }

        val requiredBlocks = Math.addExact(imageSizeBytes, blockSize - 1L) / blockSize
        val coveredBytes = Math.multiplyExact(requiredBlocks, blockSize)
        require(coveredBytes <= targetCapacityBytes) {
            "O último bloco da imagem ultrapassaria a capacidade física do destino."
        }

        return RawImageWritePlan(
            imageSizeBytes = imageSizeBytes,
            expectedSha256 = expectedSha256,
            targetCapacityBytes = targetCapacityBytes,
            blockSizeBytes = blockSizeBytes,
            requiredBlocks = requiredBlocks,
            trailingCapacityBytes = targetCapacityBytes - coveredBytes,
        )
    }
}
