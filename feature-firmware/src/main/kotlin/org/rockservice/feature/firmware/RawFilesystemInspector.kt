package org.rockservice.feature.firmware

import java.io.InputStream

/** Filesystem types recognized from bounded raw-image structural signatures. */
enum class RawFilesystemType {
    EXT4,
    F2FS,
    EROFS,
    SQUASHFS,
    UNKNOWN,
}

/** Sanitized structural evidence discovered from a bounded raw-image prefix. */
data class RawFilesystemInspection(
    val type: RawFilesystemType,
    val bytesInspected: Int,
    val blockSizeBytes: Long?,
    val detail: String,
)

/**
 * Recognizes common raw filesystems using only a small bounded prefix.
 *
 * This inspector never mounts, traverses or mutates the filesystem image.
 */
class RawFilesystemInspector(
    private val maximumPrefixBytes: Int = DEFAULT_MAXIMUM_PREFIX_BYTES,
) {
    init {
        require(maximumPrefixBytes >= MINIMUM_USEFUL_PREFIX_BYTES) {
            "maximumPrefixBytes deve ser pelo menos $MINIMUM_USEFUL_PREFIX_BYTES."
        }
    }

    /** Reads at most the configured prefix and returns the first structurally valid match. */
    fun inspect(source: InputStream): RawFilesystemInspection {
        val prefix = readPrefix(source)

        detectExt4(prefix)?.let { return it }
        detectF2fs(prefix)?.let { return it }
        detectErofs(prefix)?.let { return it }
        detectSquashFs(prefix)?.let { return it }

        return RawFilesystemInspection(
            type = RawFilesystemType.UNKNOWN,
            bytesInspected = prefix.size,
            blockSizeBytes = null,
            detail = "Nenhuma assinatura de filesystem raw suportado foi validada no prefixo inspecionado.",
        )
    }

    private fun detectExt4(bytes: ByteArray): RawFilesystemInspection? {
        if (bytes.size < EXT4_MAGIC_OFFSET + 2) return null
        if (bytes.readUInt16Le(EXT4_MAGIC_OFFSET) != EXT4_SUPER_MAGIC) return null

        require(bytes.size >= EXT4_LOG_BLOCK_SIZE_OFFSET + 4) {
            "Superblock ext4 truncado antes de log_block_size."
        }
        val logBlockSize = bytes.readUInt32Le(EXT4_LOG_BLOCK_SIZE_OFFSET)
        require(logBlockSize <= MAXIMUM_EXT4_LOG_BLOCK_SIZE) {
            "ext4 log_block_size fora do intervalo suportado: $logBlockSize."
        }
        val blockSize = 1024L shl logBlockSize.toInt()
        require(isValidBlockSize(blockSize, maximum = MAXIMUM_EXT4_BLOCK_SIZE)) {
            "Tamanho de bloco ext4 inválido: $blockSize."
        }

        return RawFilesystemInspection(
            type = RawFilesystemType.EXT4,
            bytesInspected = bytes.size,
            blockSizeBytes = blockSize,
            detail = "Superblock ext4 reconhecido pelo magic 0xEF53.",
        )
    }

    private fun detectF2fs(bytes: ByteArray): RawFilesystemInspection? {
        if (bytes.size < F2FS_SUPERBLOCK_OFFSET + F2FS_LOG_BLOCK_SIZE_RELATIVE_OFFSET + 4) return null
        if (bytes.readUInt32Le(F2FS_SUPERBLOCK_OFFSET) != F2FS_SUPER_MAGIC) return null

        val logBlockSize = bytes.readUInt32Le(F2FS_SUPERBLOCK_OFFSET + F2FS_LOG_BLOCK_SIZE_RELATIVE_OFFSET)
        require(logBlockSize in MINIMUM_BLOCK_SIZE_BITS..MAXIMUM_BLOCK_SIZE_BITS) {
            "F2FS log_blocksize fora do intervalo suportado: $logBlockSize."
        }
        val blockSize = 1L shl logBlockSize.toInt()
        require(isValidBlockSize(blockSize)) { "Tamanho de bloco F2FS inválido: $blockSize." }

        return RawFilesystemInspection(
            type = RawFilesystemType.F2FS,
            bytesInspected = bytes.size,
            blockSizeBytes = blockSize,
            detail = "Superblock F2FS reconhecido pelo magic 0xF2F52010.",
        )
    }

    private fun detectErofs(bytes: ByteArray): RawFilesystemInspection? {
        if (bytes.size <= EROFS_SUPERBLOCK_OFFSET + EROFS_BLOCK_SIZE_BITS_RELATIVE_OFFSET) return null
        if (bytes.readUInt32Le(EROFS_SUPERBLOCK_OFFSET) != EROFS_SUPER_MAGIC) return null

        val blockSizeBits = bytes[EROFS_SUPERBLOCK_OFFSET + EROFS_BLOCK_SIZE_BITS_RELATIVE_OFFSET]
            .toInt() and 0xFF
        require(blockSizeBits in MINIMUM_BLOCK_SIZE_BITS..MAXIMUM_BLOCK_SIZE_BITS) {
            "EROFS blkszbits fora do intervalo suportado: $blockSizeBits."
        }
        val blockSize = 1L shl blockSizeBits
        require(isValidBlockSize(blockSize)) { "Tamanho de bloco EROFS inválido: $blockSize." }

        return RawFilesystemInspection(
            type = RawFilesystemType.EROFS,
            bytesInspected = bytes.size,
            blockSizeBytes = blockSize,
            detail = "Superblock EROFS reconhecido pelo magic 0xE0F5E1E2.",
        )
    }

    private fun detectSquashFs(bytes: ByteArray): RawFilesystemInspection? {
        if (bytes.size < SQUASHFS_BLOCK_SIZE_OFFSET + 4) return null
        if (bytes.readUInt32Le(0) != SQUASHFS_MAGIC) return null

        val blockSize = bytes.readUInt32Le(SQUASHFS_BLOCK_SIZE_OFFSET)
        require(
            blockSize in MINIMUM_SQUASHFS_BLOCK_SIZE..MAXIMUM_SQUASHFS_BLOCK_SIZE &&
                isPowerOfTwo(blockSize)
        ) {
            "Tamanho de bloco SquashFS inválido: $blockSize."
        }

        return RawFilesystemInspection(
            type = RawFilesystemType.SQUASHFS,
            bytesInspected = bytes.size,
            blockSizeBytes = blockSize,
            detail = "Superblock SquashFS reconhecido pelo magic hsqs.",
        )
    }

    private fun readPrefix(source: InputStream): ByteArray {
        val buffer = ByteArray(maximumPrefixBytes)
        var offset = 0
        while (offset < buffer.size) {
            val read = source.read(buffer, offset, buffer.size - offset)
            when {
                read < 0 -> break
                read == 0 -> {
                    val single = source.read()
                    if (single < 0) break
                    buffer[offset++] = single.toByte()
                }
                else -> offset += read
            }
        }
        return buffer.copyOf(offset)
    }

    private fun isValidBlockSize(
        value: Long,
        maximum: Long = MAXIMUM_GENERIC_BLOCK_SIZE,
    ): Boolean = value >= MINIMUM_GENERIC_BLOCK_SIZE && value <= maximum && isPowerOfTwo(value)

    private fun isPowerOfTwo(value: Long): Boolean = value > 0L && (value and (value - 1L)) == 0L

    private companion object {
        const val DEFAULT_MAXIMUM_PREFIX_BYTES = 4096
        const val MINIMUM_USEFUL_PREFIX_BYTES = 2048

        const val EXT4_SUPERBLOCK_OFFSET = 1024
        const val EXT4_LOG_BLOCK_SIZE_OFFSET = EXT4_SUPERBLOCK_OFFSET + 0x18
        const val EXT4_MAGIC_OFFSET = EXT4_SUPERBLOCK_OFFSET + 0x38
        const val EXT4_SUPER_MAGIC = 0xEF53
        const val MAXIMUM_EXT4_LOG_BLOCK_SIZE = 6L
        const val MAXIMUM_EXT4_BLOCK_SIZE = 64L * 1024

        const val F2FS_SUPERBLOCK_OFFSET = 1024
        const val F2FS_LOG_BLOCK_SIZE_RELATIVE_OFFSET = 16
        const val F2FS_SUPER_MAGIC = 0xF2F52010L

        const val EROFS_SUPERBLOCK_OFFSET = 1024
        const val EROFS_BLOCK_SIZE_BITS_RELATIVE_OFFSET = 12
        const val EROFS_SUPER_MAGIC = 0xE0F5E1E2L

        const val SQUASHFS_MAGIC = 0x73717368L
        const val SQUASHFS_BLOCK_SIZE_OFFSET = 12
        const val MINIMUM_SQUASHFS_BLOCK_SIZE = 4096L
        const val MAXIMUM_SQUASHFS_BLOCK_SIZE = 1024L * 1024

        const val MINIMUM_BLOCK_SIZE_BITS = 9L
        const val MAXIMUM_BLOCK_SIZE_BITS = 20L
        const val MINIMUM_GENERIC_BLOCK_SIZE = 512L
        const val MAXIMUM_GENERIC_BLOCK_SIZE = 1024L * 1024
    }
}

private fun ByteArray.readUInt16Le(offset: Int): Int {
    require(offset >= 0 && offset + 2 <= size) { "Leitura UInt16 fora dos limites do prefixo raw." }
    return (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8)
}

private fun ByteArray.readUInt32Le(offset: Int): Long {
    require(offset >= 0 && offset + 4 <= size) { "Leitura UInt32 fora dos limites do prefixo raw." }
    return (this[offset].toLong() and 0xFFL) or
        ((this[offset + 1].toLong() and 0xFFL) shl 8) or
        ((this[offset + 2].toLong() and 0xFFL) shl 16) or
        ((this[offset + 3].toLong() and 0xFFL) shl 24)
}
