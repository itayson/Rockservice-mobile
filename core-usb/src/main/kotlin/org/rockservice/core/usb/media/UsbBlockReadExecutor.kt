package org.rockservice.core.usb.media

/**
 * Read-only transport boundary for one validated block chunk.
 *
 * Implementations must not expose arbitrary SCSI commands or any write capability. The caller
 * supplies chunks produced by [UsbBlockReadPlanner], and implementations are responsible for
 * applying platform-level I/O deadlines when the underlying USB API can block.
 */
fun interface UsbBlockReadTransport {
    suspend fun readBlocks(
        startBlock: Long,
        blockCount: Int,
        blockSizeBytes: Int,
        timeoutMillis: Long,
    ): UsbMassStorageTransferResult
}

/** One successfully received block chunk and its validated plan metadata. */
data class UsbBlockReadData(
    val chunk: UsbBlockReadChunk,
    val data: ByteArray,
)

/**
 * Executes an immutable [UsbBlockReadPlan] through a strictly read-only transport.
 *
 * Every transfer is checked against the exact byte count declared by its chunk before data is
 * exposed to the consumer or accepted by the SHA-256 accumulator. No retry is attempted after a
 * disconnect, timeout, malformed length or consumer failure, so callers can fail closed and decide
 * explicitly how to handle a partial destination.
 */
class UsbBlockReadExecutor(
    private val transport: UsbBlockReadTransport,
) {
    suspend fun execute(
        plan: UsbBlockReadPlan,
        timeoutMillis: Long = DEFAULT_TRANSFER_TIMEOUT_MILLIS,
        onChunk: suspend (UsbBlockReadData) -> Unit,
    ): UsbBlockReadIntegrity {
        require(timeoutMillis > 0L) { "timeoutMillis must be greater than zero." }

        val accumulator = plan.newSha256Accumulator()
        for (chunk in plan.chunks()) {
            require(chunk.byteCount <= Int.MAX_VALUE.toLong()) {
                "Planned chunk is too large for an in-memory USB transfer: ${chunk.byteCount} bytes."
            }

            val transfer = transport.readBlocks(
                startBlock = chunk.startBlock,
                blockCount = chunk.blockCount,
                blockSizeBytes = plan.blockSizeBytes,
                timeoutMillis = timeoutMillis,
            )
            val data = when (transfer) {
                is UsbMassStorageTransferResult.Success -> transfer.data
                UsbMassStorageTransferResult.Disconnected -> {
                    throw UsbMassStorageDisconnectedException(
                        "USB Mass Storage device disconnected or reset while reading block ${chunk.startBlock}.",
                    )
                }
                UsbMassStorageTransferResult.TimedOut -> {
                    throw UsbMassStorageTimeoutException(
                        "USB Mass Storage transport timed out while reading block ${chunk.startBlock}.",
                    )
                }
            }

            require(data.size.toLong() == chunk.byteCount) {
                "USB block read returned an unexpected byte count for chunk ${chunk.index}: " +
                    "expected=${chunk.byteCount}, received=${data.size}."
            }

            onChunk(UsbBlockReadData(chunk = chunk, data = data))
            accumulator.update(data)
        }

        return accumulator.finish()
    }

    private companion object {
        const val DEFAULT_TRANSFER_TIMEOUT_MILLIS = 5_000L
    }
}
