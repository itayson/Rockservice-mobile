package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Bridges Android Sparse input to the existing raw liblp parser using only a bounded decoded prefix.
 *
 * The first pass discovers a plausible liblp geometry block. The second pass decodes only enough
 * raw bytes to contain primary metadata slot zero. Final geometry, checksum and table validation are
 * delegated entirely to AndroidSuperMetadataParser.
 */
class AndroidSparseSuperMetadataParser(
    private val maximumDecodedPrefixBytes: Int = DEFAULT_MAXIMUM_DECODED_PREFIX_BYTES,
    private val maximumMetadataSlotBytes: Long = DEFAULT_MAXIMUM_METADATA_SLOT_BYTES,
) {
    private val sparseParser = AndroidSparseImageParser()
    private val prefixDecoder = AndroidSparseRawPrefixDecoder(maximumDecodedPrefixBytes)
    private val rawParser = AndroidSuperMetadataParser(
        maximumMetadataSlotBytes = maximumMetadataSlotBytes,
    )

    init {
        require(maximumDecodedPrefixBytes >= GEOMETRY_DISCOVERY_BYTES) {
            "maximumDecodedPrefixBytes deve ser pelo menos $GEOMETRY_DISCOVERY_BYTES."
        }
        require(maximumMetadataSlotBytes > 0L) {
            "maximumMetadataSlotBytes deve ser maior que zero."
        }
    }

    /**
     * Returns parsed liblp metadata when a sparse image contains a recognizable super geometry.
     * Returns null when the decoded geometry area contains no liblp geometry magic.
     */
    fun parseIfPresent(openSource: () -> InputStream): AndroidSuperMetadata? {
        val sparseMetadata = openSource().use(sparseParser::parse)
        if (sparseMetadata.expandedSizeBytes < GEOMETRY_DISCOVERY_BYTES.toLong()) {
            rejectTruncatedSuperIfGeometryMagicIsPresent(
                openSource = openSource,
                expandedSizeBytes = sparseMetadata.expandedSizeBytes,
            )
            return null
        }

        val discoveryPrefix = openSource().use { source ->
            prefixDecoder.decodeExactly(source, GEOMETRY_DISCOVERY_BYTES)
        }
        val metadataMaxSize = discoverMetadataMaxSize(discoveryPrefix) ?: return null
        require(metadataMaxSize <= maximumMetadataSlotBytes) {
            "Geometria liblp declara metadata_max_size=$metadataMaxSize; limite configurado: " +
                "$maximumMetadataSlotBytes."
        }

        val requiredPrefixBytes = checkedAdd(
            GEOMETRY_DISCOVERY_BYTES.toLong(),
            metadataMaxSize,
            "Prefixo raw necessário para metadata liblp",
        )
        require(requiredPrefixBytes <= maximumDecodedPrefixBytes.toLong()) {
            "Metadata liblp exige prefixo raw de $requiredPrefixBytes bytes; limite configurado: " +
                "$maximumDecodedPrefixBytes."
        }
        require(sparseMetadata.expandedSizeBytes >= requiredPrefixBytes) {
            "Imagem sparse expandida possui ${sparseMetadata.expandedSizeBytes} bytes; metadata liblp " +
                "primária exige pelo menos $requiredPrefixBytes."
        }

        val rawPrefix = if (requiredPrefixBytes == GEOMETRY_DISCOVERY_BYTES.toLong()) {
            discoveryPrefix
        } else {
            openSource().use { source -> prefixDecoder.decodeExactly(source, requiredPrefixBytes.toInt()) }
        }
        return rawParser.parse(ByteArrayInputStream(rawPrefix))
    }

    private fun rejectTruncatedSuperIfGeometryMagicIsPresent(
        openSource: () -> InputStream,
        expandedSizeBytes: Long,
    ) {
        if (expandedSizeBytes < PRIMARY_GEOMETRY_OFFSET + UINT32_BYTES) return

        val availableBytes = expandedSizeBytes.toInt()
        val prefix = openSource().use { source -> prefixDecoder.decodeExactly(source, availableBytes) }
        val containsGeometryMagic =
            hasGeometryMagicAt(prefix, PRIMARY_GEOMETRY_OFFSET) ||
                hasGeometryMagicAt(prefix, BACKUP_GEOMETRY_OFFSET)
        require(!containsGeometryMagic) {
            "Imagem sparse contém assinatura de geometria liblp, mas termina antes dos " +
                "$GEOMETRY_DISCOVERY_BYTES bytes obrigatórios da área de descoberta."
        }
    }

    private fun discoverMetadataMaxSize(prefix: ByteArray): Long? {
        require(prefix.size >= GEOMETRY_DISCOVERY_BYTES) {
            "Prefixo raw insuficiente para descobrir geometria liblp."
        }

        val candidates = listOf(
            geometryCandidate(prefix, PRIMARY_GEOMETRY_OFFSET),
            geometryCandidate(prefix, BACKUP_GEOMETRY_OFFSET),
        )
        val magicCount = candidates.count(GeometryCandidate::hasMagic)
        if (magicCount == 0) return null

        val plausibleSizes = candidates.mapNotNull(GeometryCandidate::metadataMaxSize)
        require(plausibleSizes.isNotEmpty()) {
            "Geometria liblp encontrada, mas checksum/campos de geometria são inválidos."
        }
        return plausibleSizes.max()
    }

    private fun geometryCandidate(bytes: ByteArray, offset: Int): GeometryCandidate {
        val magic = bytes.readUInt32Le(offset)
        if (magic != GEOMETRY_MAGIC) return GeometryCandidate(hasMagic = false, metadataMaxSize = null)

        val structSize = bytes.readUInt32Le(offset + GEOMETRY_STRUCT_SIZE_OFFSET)
        if (structSize != GEOMETRY_STRUCT_SIZE.toLong()) {
            return GeometryCandidate(hasMagic = true, metadataMaxSize = null)
        }

        val struct = bytes.copyOfRange(offset, offset + GEOMETRY_STRUCT_SIZE)
        val expectedChecksum = struct.copyOfRange(
            GEOMETRY_CHECKSUM_OFFSET,
            GEOMETRY_CHECKSUM_OFFSET + SHA256_SIZE,
        )
        struct.fill(
            0,
            fromIndex = GEOMETRY_CHECKSUM_OFFSET,
            toIndex = GEOMETRY_CHECKSUM_OFFSET + SHA256_SIZE,
        )
        val checksumValid = sha256(struct).contentEquals(expectedChecksum)

        val metadataMaxSize = bytes.readUInt32Le(offset + GEOMETRY_METADATA_MAX_SIZE_OFFSET)
        val metadataSlotCount = bytes.readUInt32Le(offset + GEOMETRY_SLOT_COUNT_OFFSET)
        val logicalBlockSize = bytes.readUInt32Le(offset + GEOMETRY_LOGICAL_BLOCK_SIZE_OFFSET)
        val plausible =
            checksumValid &&
                metadataMaxSize > 0L &&
                metadataMaxSize % METADATA_ALIGNMENT_BYTES == 0L &&
                metadataSlotCount in 1L..MAXIMUM_METADATA_SLOTS.toLong() &&
                logicalBlockSize > 0L &&
                logicalBlockSize % 512L == 0L

        return GeometryCandidate(
            hasMagic = true,
            metadataMaxSize = metadataMaxSize.takeIf { plausible },
        )
    }

    private fun hasGeometryMagicAt(bytes: ByteArray, offset: Int): Boolean =
        offset >= 0 && offset + UINT32_BYTES <= bytes.size && bytes.readUInt32Le(offset) == GEOMETRY_MAGIC

    private data class GeometryCandidate(
        val hasMagic: Boolean,
        val metadataMaxSize: Long?,
    )

    private companion object {
        const val LP_PARTITION_RESERVED_BYTES = 4_096
        const val GEOMETRY_BLOCK_SIZE = 4_096
        const val PRIMARY_GEOMETRY_OFFSET = LP_PARTITION_RESERVED_BYTES
        const val BACKUP_GEOMETRY_OFFSET = LP_PARTITION_RESERVED_BYTES + GEOMETRY_BLOCK_SIZE
        const val GEOMETRY_DISCOVERY_BYTES = LP_PARTITION_RESERVED_BYTES + (2 * GEOMETRY_BLOCK_SIZE)
        const val GEOMETRY_MAGIC: Long = 0x616C4467L
        const val GEOMETRY_STRUCT_SIZE = 52
        const val GEOMETRY_STRUCT_SIZE_OFFSET = 4
        const val GEOMETRY_CHECKSUM_OFFSET = 8
        const val GEOMETRY_METADATA_MAX_SIZE_OFFSET = 40
        const val GEOMETRY_SLOT_COUNT_OFFSET = 44
        const val GEOMETRY_LOGICAL_BLOCK_SIZE_OFFSET = 48
        const val SHA256_SIZE = 32
        const val UINT32_BYTES = 4
        const val METADATA_ALIGNMENT_BYTES = 512L
        const val MAXIMUM_METADATA_SLOTS = 8
        const val DEFAULT_MAXIMUM_METADATA_SLOT_BYTES = 64L * 1024 * 1024
        const val DEFAULT_MAXIMUM_DECODED_PREFIX_BYTES = 128 * 1024 * 1024
    }
}

private fun ByteArray.readUInt32Le(offset: Int): Long {
    require(offset >= 0 && offset + 4 <= size) { "Leitura UInt32 fora dos limites do prefixo sparse super." }
    return (this[offset].toLong() and 0xFFL) or
        ((this[offset + 1].toLong() and 0xFFL) shl 8) or
        ((this[offset + 2].toLong() and 0xFFL) shl 16) or
        ((this[offset + 3].toLong() and 0xFFL) shl 24)
}

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private fun checkedAdd(left: Long, right: Long, label: String): Long = try {
    Math.addExact(left, right)
} catch (_: ArithmeticException) {
    throw IllegalArgumentException("$label excede o intervalo suportado.")
}
