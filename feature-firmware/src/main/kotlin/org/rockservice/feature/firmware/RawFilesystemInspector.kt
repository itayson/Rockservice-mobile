package org.rockservice.feature.firmware

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

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
    val prefixSha256: String,
    val detail: String,
)

/**
 * Recognizes common raw filesystems using only a small bounded prefix.
 *
 * This inspector never mounts, traverses or mutates the filesystem image. The returned SHA-256 is
 * only evidence for the inspected prefix; it is not an authenticity claim for the complete image.
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
        val prefixSha256 = sha256Hex(prefix)

        detectExt4(prefix, prefixSha256)?.let { return it }
        detectF2fs(prefix, prefixSha256)?.let { return it }
        detectErofs(prefix, prefixSha256)?.let { return it }
        detectSquashFs(prefix, prefixSha256)?.let { return it }

        return RawFilesystemInspection(
            type = RawFilesystemType.UNKNOWN,
            bytesInspected = prefix.size,
            blockSizeBytes = null,
            prefixSha256 = prefixSha256,
            detail = "Nenhuma assinatura de filesystem raw suportado foi validada no prefixo inspecionado.",
        )
    }

    private fun detectExt4(bytes: ByteArray, prefixSha256: String): RawFilesystemInspection? {
        if (bytes.size < EXT4_FEATURE_INCOMPAT_OFFSET + 4) return null
        if (bytes.readUInt16Le(EXT4_MAGIC_OFFSET) != EXT4_SUPER_MAGIC) return null

        val logBlockSize = bytes.readUInt32Le(EXT4_LOG_BLOCK_SIZE_OFFSET)
        require(logBlockSize <= MAXIMUM_EXT4_LOG_BLOCK_SIZE) {
            "ext4 log_block_size fora do intervalo suportado: $logBlockSize."
        }
        val blockSize = 1024L shl logBlockSize.toInt()
        require(isValidBlockSize(blockSize, maximum = MAXIMUM_EXT4_BLOCK_SIZE)) {
            "Tamanho de bloco ext4 inválido: $blockSize."
        }

        val incompatFeatures = bytes.readUInt32Le(EXT4_FEATURE_INCOMPAT_OFFSET)
        if (incompatFeatures and EXT4_SPECIFIC_INCOMPAT_MASK == 0L) return null

        return RawFilesystemInspection(
            type = RawFilesystemType.EXT4,
            bytesInspected = bytes.size,
            blockSizeBytes = blockSize,
            prefixSha256 = prefixSha256,
            detail = "Superblock ext4 reconhecido por magic, block size e feature incompatível específica de ext4.",
        )
    }

    private fun detectF2fs(bytes: ByteArray, prefixSha256: String): RawFilesystemInspection? {
        if (bytes.size < F2FS_REQUIRED_PREFIX_BYTES) return null
        if (bytes.readUInt32Le(F2FS_SUPERBLOCK_OFFSET) != F2FS_SUPER_MAGIC) return null

        val logSectorSize = bytes.readUInt32Le(F2FS_SUPERBLOCK_OFFSET + F2FS_LOG_SECTOR_SIZE_RELATIVE_OFFSET)
        val logSectorsPerBlock = bytes.readUInt32Le(
            F2FS_SUPERBLOCK_OFFSET + F2FS_LOG_SECTORS_PER_BLOCK_RELATIVE_OFFSET,
        )
        val logBlockSize = bytes.readUInt32Le(F2FS_SUPERBLOCK_OFFSET + F2FS_LOG_BLOCK_SIZE_RELATIVE_OFFSET)
        val logBlocksPerSegment = bytes.readUInt32Le(
            F2FS_SUPERBLOCK_OFFSET + F2FS_LOG_BLOCKS_PER_SEG_RELATIVE_OFFSET,
        )
        val segmentsPerSection = bytes.readUInt32Le(
            F2FS_SUPERBLOCK_OFFSET + F2FS_SEGMENTS_PER_SECTION_RELATIVE_OFFSET,
        )
        val sectionsPerZone = bytes.readUInt32Le(
            F2FS_SUPERBLOCK_OFFSET + F2FS_SECTIONS_PER_ZONE_RELATIVE_OFFSET,
        )

        if (logBlockSize !in F2FS_MINIMUM_LOG_BLOCK_SIZE..F2FS_MAXIMUM_LOG_BLOCK_SIZE ||
            logBlocksPerSegment != F2FS_REQUIRED_LOG_BLOCKS_PER_SEGMENT ||
            logSectorSize !in F2FS_MINIMUM_LOG_SECTOR_SIZE..logBlockSize ||
            logSectorSize + logSectorsPerBlock != logBlockSize ||
            segmentsPerSection == 0L ||
            sectionsPerZone == 0L
        ) {
            return null
        }

        return RawFilesystemInspection(
            type = RawFilesystemType.F2FS,
            bytesInspected = bytes.size,
            blockSizeBytes = 1L shl logBlockSize.toInt(),
            prefixSha256 = prefixSha256,
            detail = "Superblock F2FS reconhecido por magic e geometria coerente de bloco, segmento, seção e zona.",
        )
    }

    private fun detectErofs(bytes: ByteArray, prefixSha256: String): RawFilesystemInspection? {
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
            prefixSha256 = prefixSha256,
            detail = "Superblock EROFS reconhecido pelo magic e blkszbits validado.",
        )
    }

    private fun detectSquashFs(bytes: ByteArray, prefixSha256: String): RawFilesystemInspection? {
        if (bytes.size < SQUASHFS_BLOCK_SIZE_OFFSET + 4) return null
        if (bytes.readUInt32Le(0) != SQUASHFS_MAGIC) return null

        val blockSize = bytes.readUInt32Le(SQUASHFS_BLOCK_SIZE_OFFSET)
        require(
            blockSize in MINIMUM_SQUASHFS_BLOCK_SIZE..MAXIMUM_SQUASHFS_BLOCK_SIZE &&
                isPowerOfTwo(blockSize),
        ) {
            "Tamanho de bloco SquashFS inválido: $blockSize."
        }

        return RawFilesystemInspection(
            type = RawFilesystemType.SQUASHFS,
            bytesInspected = bytes.size,
            blockSizeBytes = blockSize,
            prefixSha256 = prefixSha256,
            detail = "Superblock SquashFS reconhecido pelo magic hsqs e block size validado.",
        )
    }

    private fun readPrefix(source: InputStream): ByteArray {
        val buffer = ByteArray(maximumPrefixBytes)
        var offset = 0
        try {
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
        } catch (error: IOException) {
            throw IOException(
                "Falha ao inspecionar prefixo raw: limite=$maximumPrefixBytes bytes, offset=$offset.",
                error,
            )
        }
        return buffer.copyOf(offset)
    }

    private fun isValidBlockSize(
        value: Long,
        maximum: Long = MAXIMUM_GENERIC_BLOCK_SIZE,
    ): Boolean = value >= MINIMUM_GENERIC_BLOCK_SIZE && value <= maximum && isPowerOfTwo(value)

    private fun isPowerOfTwo(value: Long): Boolean = value > 0L && (value and (value - 1L)) == 0L

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val DEFAULT_MAXIMUM_PREFIX_BYTES = 4096
        const val MINIMUM_USEFUL_PREFIX_BYTES = 2048

        const val EXT4_SUPERBLOCK_OFFSET = 1024
        const val EXT4_LOG_BLOCK_SIZE_OFFSET = EXT4_SUPERBLOCK_OFFSET + 0x18
        const val EXT4_MAGIC_OFFSET = EXT4_SUPERBLOCK_OFFSET + 0x38
        const val EXT4_FEATURE_INCOMPAT_OFFSET = EXT4_SUPERBLOCK_OFFSET + 0x60
        const val EXT4_SUPER_MAGIC = 0xEF53
        const val EXT4_FEATURE_INCOMPAT_EXTENTS = 0x40L
        const val EXT4_FEATURE_INCOMPAT_64BIT = 0x80L
        const val EXT4_FEATURE_INCOMPAT_FLEX_BG = 0x200L
        const val EXT4_FEATURE_INCOMPAT_EA_INODE = 0x400L
        const val EXT4_FEATURE_INCOMPAT_CSUM_SEED = 0x2000L
        const val EXT4_FEATURE_INCOMPAT_LARGEDIR = 0x4000L
        const val EXT4_FEATURE_INCOMPAT_INLINE_DATA = 0x8000L
        const val EXT4_FEATURE_INCOMPAT_ENCRYPT = 0x10000L
        const val EXT4_FEATURE_INCOMPAT_CASEFOLD = 0x20000L
        const val EXT4_SPECIFIC_INCOMPAT_MASK =
            EXT4_FEATURE_INCOMPAT_EXTENTS or
                EXT4_FEATURE_INCOMPAT_64BIT or
                EXT4_FEATURE_INCOMPAT_FLEX_BG or
                EXT4_FEATURE_INCOMPAT_EA_INODE or
                EXT4_FEATURE_INCOMPAT_CSUM_SEED or
                EXT4_FEATURE_INCOMPAT_LARGEDIR or
                EXT4_FEATURE_INCOMPAT_INLINE_DATA or
                EXT4_FEATURE_INCOMPAT_ENCRYPT or
                EXT4_FEATURE_INCOMPAT_CASEFOLD
        const val MAXIMUM_EXT4_LOG_BLOCK_SIZE = 6L
        const val MAXIMUM_EXT4_BLOCK_SIZE = 64L * 1024

        const val F2FS_SUPERBLOCK_OFFSET = 1024
        const val F2FS_LOG_SECTOR_SIZE_RELATIVE_OFFSET = 8
        const val F2FS_LOG_SECTORS_PER_BLOCK_RELATIVE_OFFSET = 12
        const val F2FS_LOG_BLOCK_SIZE_RELATIVE_OFFSET = 16
        const val F2FS_LOG_BLOCKS_PER_SEG_RELATIVE_OFFSET = 20
        const val F2FS_SEGMENTS_PER_SECTION_RELATIVE_OFFSET = 24
        const val F2FS_SECTIONS_PER_ZONE_RELATIVE_OFFSET = 28
        const val F2FS_REQUIRED_PREFIX_BYTES = F2FS_SUPERBLOCK_OFFSET + F2FS_SECTIONS_PER_ZONE_RELATIVE_OFFSET + 4
        const val F2FS_SUPER_MAGIC = 0xF2F52010L
        const val F2FS_MINIMUM_LOG_SECTOR_SIZE = 9L
        const val F2FS_MINIMUM_LOG_BLOCK_SIZE = 12L
        const val F2FS_MAXIMUM_LOG_BLOCK_SIZE = 16L
        const val F2FS_REQUIRED_LOG_BLOCKS_PER_SEGMENT = 9L

        const val EROFS_SUPERBLOCK_OFFSET = 1024
        const val EROFS_BLOCK_SIZE_BITS_RELATIVE_OFFSET = 12
        const val EROFS_SUPER_MAGIC = 0xE0F5E1E2L

        const val SQUASHFS_MAGIC = 0x73717368L
        const val SQUASHFS_BLOCK_SIZE_OFFSET = 12
        const val MINIMUM_SQUASHFS_BLOCK_SIZE = 4096L
        const val MAXIMUM_SQUASHFS_BLOCK_SIZE = 1024L * 1024

        const val MINIMUM_BLOCK_SIZE_BITS = 9
        const val MAXIMUM_BLOCK_SIZE_BITS = 16
        const val MINIMUM_GENERIC_BLOCK_SIZE = 512L
        const val MAXIMUM_GENERIC_BLOCK_SIZE = 64L * 1024
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
