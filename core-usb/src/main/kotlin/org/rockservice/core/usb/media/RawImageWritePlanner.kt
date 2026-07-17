package org.rockservice.core.usb.media

/** Immutable plan for a future raw image write operation. */
data class RawImageWritePlan(
    val imageSizeBytes: Long,
    val targetCapacityBytes: Long,
    val blockSizeBytes: Int,
    val requiredBlocks: Long,
    val trailingCapacityBytes: Long,
)

/**
 * Validates a raw image against a target block device without performing any physical I/O.
 *
 * This planner is intentionally pure. Real USB writes remain gated by the application-level
 * REAL_USB_WRITE_ENABLED feature flag and require a separate transport implementation.
 */
object RawImageWritePlanner {
    fun plan(
        imageSizeBytes: Long,
        targetCapacityBytes: Long,
        blockSizeBytes: Int,
    ): RawImageWritePlan {
        require(imageSizeBytes > 0L) { "A imagem deve conter pelo menos 1 byte." }
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
            targetCapacityBytes = targetCapacityBytes,
            blockSizeBytes = blockSizeBytes,
            requiredBlocks = requiredBlocks,
            trailingCapacityBytes = targetCapacityBytes - imageSizeBytes,
        )
    }
}
