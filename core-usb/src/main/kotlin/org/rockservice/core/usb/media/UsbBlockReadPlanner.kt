package org.rockservice.core.usb.media

import java.security.MessageDigest

/** One bounded, read-only block range derived from validated device geometry. */
data class UsbBlockReadChunk(
    val index: Long,
    val startBlock: Long,
    val blockCount: Int,
    val deviceByteOffset: Long,
    val planByteOffset: Long,
    val byteCount: Int,
)

/** Integrity metadata produced after exactly all bytes in a read plan have been accepted. */
data class UsbBlockReadIntegrity(
    val byteCount: Long,
    val sha256: String,
)

/**
 * Immutable read-only plan for a contiguous block range.
 *
 * The plan contains no transport or physical I/O capability. Chunks are generated lazily so a very
 * large but valid device range does not allocate a correspondingly large in-memory list.
 */
class UsbBlockReadPlan internal constructor(
    val startBlock: Long,
    val blockCount: Long,
    val blockSizeBytes: Int,
    val startByteOffset: Long,
    val byteCount: Long,
    val maximumBlocksPerChunk: Int,
) {
    /** Number of chunks that [chunks] will emit. */
    val chunkCount: Long = ((blockCount - 1L) / maximumBlocksPerChunk.toLong()) + 1L

    /**
     * Generates the validated range as bounded read-only chunks without performing physical I/O.
     */
    fun chunks(): Sequence<UsbBlockReadChunk> = sequence {
        var emittedBlocks = 0L
        var index = 0L

        while (emittedBlocks < blockCount) {
            val blocksThisChunk = minOf(
                maximumBlocksPerChunk.toLong(),
                blockCount - emittedBlocks,
            ).toInt()
            val startBlockThisChunk = Math.addExact(startBlock, emittedBlocks)
            val planByteOffset = Math.multiplyExact(emittedBlocks, blockSizeBytes.toLong())
            val deviceByteOffset = Math.addExact(startByteOffset, planByteOffset)
            val chunkByteCount = Math.multiplyExact(blocksThisChunk, blockSizeBytes)

            yield(
                UsbBlockReadChunk(
                    index = index,
                    startBlock = startBlockThisChunk,
                    blockCount = blocksThisChunk,
                    deviceByteOffset = deviceByteOffset,
                    planByteOffset = planByteOffset,
                    byteCount = chunkByteCount,
                ),
            )

            emittedBlocks = Math.addExact(emittedBlocks, blocksThisChunk.toLong())
            index = Math.addExact(index, 1L)
        }
    }

    /** Creates a SHA-256 accumulator bound to the exact byte count of this plan. */
    fun newSha256Accumulator(): UsbBlockReadSha256Accumulator =
        UsbBlockReadSha256Accumulator(expectedByteCount = byteCount)
}

/**
 * Builds read-only block plans from trusted geometry while failing closed on invalid ranges.
 *
 * This planner never opens a USB device and never issues a SCSI command. It validates geometry,
 * arithmetic overflow and the requested range before exposing any chunk description.
 */
class UsbBlockReadPlanner(
    private val maximumBlocksPerChunk: Int = DEFAULT_MAXIMUM_BLOCKS_PER_CHUNK,
) {
    init {
        require(maximumBlocksPerChunk in 1..HARD_MAXIMUM_BLOCKS_PER_CHUNK) {
            "maximumBlocksPerChunk must be between 1 and $HARD_MAXIMUM_BLOCKS_PER_CHUNK."
        }
    }

    /**
     * Plans a contiguous range that must fit entirely inside [geometry].
     *
     * Both block-address and byte-address calculations are overflow checked. Inconsistent geometry
     * is rejected before the requested range is evaluated.
     */
    fun plan(
        geometry: UsbBlockDeviceGeometry,
        startBlock: Long,
        blockCount: Long,
    ): UsbBlockReadPlan {
        validateGeometry(geometry)
        require(startBlock >= 0L) { "startBlock must be non-negative." }
        require(blockCount > 0L) { "blockCount must be greater than zero." }

        val endBlockExclusive = exactAdd(
            startBlock,
            blockCount,
            "Requested block range overflows the signed 64-bit address space.",
        )
        require(endBlockExclusive <= geometry.blockCount) {
            "Requested block range exceeds device geometry: end=$endBlockExclusive, " +
                "available=${geometry.blockCount}."
        }

        val blockSize = geometry.blockSizeBytes.toLong()
        val startByteOffset = exactMultiply(
            startBlock,
            blockSize,
            "Requested start block overflows the byte address space.",
        )
        val byteCount = exactMultiply(
            blockCount,
            blockSize,
            "Requested block count overflows the byte length.",
        )
        val endByteExclusive = exactAdd(
            startByteOffset,
            byteCount,
            "Requested byte range overflows the signed 64-bit address space.",
        )
        require(endByteExclusive <= geometry.capacityBytes) {
            "Requested byte range exceeds device capacity: end=$endByteExclusive, " +
                "capacity=${geometry.capacityBytes}."
        }

        return UsbBlockReadPlan(
            startBlock = startBlock,
            blockCount = blockCount,
            blockSizeBytes = geometry.blockSizeBytes,
            startByteOffset = startByteOffset,
            byteCount = byteCount,
            maximumBlocksPerChunk = maximumBlocksPerChunk,
        )
    }

    private fun validateGeometry(geometry: UsbBlockDeviceGeometry) {
        require(geometry.blockSizeBytes > 0) { "Device block size must be greater than zero." }
        require(geometry.blockCount > 0L) { "Device block count must be greater than zero." }
        require(geometry.capacityBytes > 0L) { "Device capacity must be greater than zero." }

        val expectedCapacity = exactMultiply(
            geometry.blockCount,
            geometry.blockSizeBytes.toLong(),
            "Device geometry overflows the supported byte address space.",
        )
        require(expectedCapacity == geometry.capacityBytes) {
            "Device geometry is inconsistent: blockCount * blockSizeBytes=$expectedCapacity, " +
                "capacityBytes=${geometry.capacityBytes}."
        }
    }

    private fun exactAdd(left: Long, right: Long, message: String): Long =
        try {
            Math.addExact(left, right)
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException(message, error)
        }

    private fun exactMultiply(left: Long, right: Long, message: String): Long =
        try {
            Math.multiplyExact(left, right)
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException(message, error)
        }

    companion object {
        const val DEFAULT_MAXIMUM_BLOCKS_PER_CHUNK = 128
        const val HARD_MAXIMUM_BLOCKS_PER_CHUNK = 65_535
    }
}

/**
 * Incrementally computes SHA-256 while enforcing the exact byte length declared by a read plan.
 */
class UsbBlockReadSha256Accumulator internal constructor(
    private val expectedByteCount: Long,
) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var finished = false

    /** Number of bytes accepted so far. */
    var acceptedByteCount: Long = 0L
        private set

    /**
     * Adds one non-empty read chunk and rejects any update that would exceed the planned byte count.
     */
    fun update(data: ByteArray) {
        check(!finished) { "SHA-256 accumulator has already been finished." }
        require(data.isNotEmpty()) { "Read chunks must not be empty." }

        val nextByteCount = try {
            Math.addExact(acceptedByteCount, data.size.toLong())
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("Accepted byte count overflowed.", error)
        }
        require(nextByteCount <= expectedByteCount) {
            "Read data exceeds the planned byte count: next=$nextByteCount, expected=$expectedByteCount."
        }

        digest.update(data)
        acceptedByteCount = nextByteCount
    }

    /**
     * Finalizes SHA-256 only after exactly the planned number of bytes has been accepted.
     */
    fun finish(): UsbBlockReadIntegrity {
        check(!finished) { "SHA-256 accumulator has already been finished." }
        check(acceptedByteCount == expectedByteCount) {
            "Cannot finish an incomplete read: accepted=$acceptedByteCount, expected=$expectedByteCount."
        }

        finished = true
        return UsbBlockReadIntegrity(
            byteCount = acceptedByteCount,
            sha256 = digest.digest().toLowerHex(),
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
