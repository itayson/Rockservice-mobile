package org.rockservice.core.usb.media

import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Closed whitelist of SCSI commands that are safe for the read-only USB Mass Storage path.
 *
 * Arbitrary command descriptor blocks are intentionally not accepted by the transport contract.
 */
enum class UsbMassStorageReadCommand(
    val expectedDataInBytes: Int,
) {
    READ_CAPACITY_10(expectedDataInBytes = 8),
}

/** Result of one read-only USB Mass Storage transfer. */
sealed interface UsbMassStorageTransferResult {
    /** Successful transfer containing the bytes returned by the device. */
    data class Success(val data: ByteArray) : UsbMassStorageTransferResult

    /** The target device was detached or the USB connection was reset. */
    data object Disconnected : UsbMassStorageTransferResult

    /** The underlying transport reached its own timeout before completing the transfer. */
    data object TimedOut : UsbMassStorageTransferResult
}

/**
 * Read-only transport boundary for USB Mass Storage SCSI commands.
 *
 * Only commands represented by [UsbMassStorageReadCommand] can be submitted. Implementations must
 * never expose arbitrary CDB execution through this interface and must remain cooperative with
 * coroutine cancellation so detach, reset, and caller cancellation can terminate in-flight work.
 */
fun interface UsbMassStorageCommandTransport {
    /**
     * Executes one whitelisted read-only command.
     *
     * [timeoutMillis] is the maximum duration requested by the caller. The session also enforces
     * this value as a hard timeout, so an implementation cannot block [UsbMassStorageSession]
     * indefinitely even if the underlying USB API stalls.
     */
    suspend fun execute(
        command: UsbMassStorageReadCommand,
        timeoutMillis: Long,
    ): UsbMassStorageTransferResult
}

/** Immutable block geometry reported by a USB Mass Storage device. */
data class UsbBlockDeviceGeometry(
    val blockSizeBytes: Int,
    val blockCount: Long,
    val capacityBytes: Long,
)

/** Raised when the target USB Mass Storage device disconnects or resets during a transfer. */
class UsbMassStorageDisconnectedException(message: String) : IOException(message)

/** Raised when a USB Mass Storage read-only transfer exceeds its allowed duration. */
class UsbMassStorageTimeoutException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Minimal read-only USB Mass Storage session foundation.
 *
 * This class currently exposes only SCSI READ CAPACITY (10). It does not implement destructive
 * commands and does not expose any physical-write capability.
 */
class UsbMassStorageSession(
    private val transport: UsbMassStorageCommandTransport,
) {
    /**
     * Reads the device block size and capacity through the read-only READ CAPACITY (10) command.
     *
     * Caller cancellation is propagated unchanged. A stalled transport is bounded by
     * [timeoutMillis], while detach/reset is surfaced as [UsbMassStorageDisconnectedException].
     */
    suspend fun readGeometry(
        timeoutMillis: Long = DEFAULT_TRANSFER_TIMEOUT_MILLIS,
    ): UsbBlockDeviceGeometry {
        require(timeoutMillis > 0) { "O timeout da transferência deve ser maior que zero." }

        val command = UsbMassStorageReadCommand.READ_CAPACITY_10
        val transferResult = try {
            withTimeout(timeoutMillis) {
                transport.execute(
                    command = command,
                    timeoutMillis = timeoutMillis,
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            throw UsbMassStorageTimeoutException(
                message = "READ CAPACITY (10) excedeu o timeout de $timeoutMillis ms.",
                cause = timeout,
            )
        }

        val response = when (transferResult) {
            is UsbMassStorageTransferResult.Success -> transferResult.data
            UsbMassStorageTransferResult.Disconnected -> {
                throw UsbMassStorageDisconnectedException(
                    "O dispositivo USB Mass Storage foi desconectado ou reiniciado durante READ CAPACITY (10).",
                )
            }
            UsbMassStorageTransferResult.TimedOut -> {
                throw UsbMassStorageTimeoutException(
                    "O transporte USB atingiu o timeout durante READ CAPACITY (10).",
                )
            }
        }

        require(response.size == command.expectedDataInBytes) {
            "READ CAPACITY (10) deve retornar exatamente ${command.expectedDataInBytes} bytes; " +
                "recebido: ${response.size}."
        }

        val lastLogicalBlockAddress = response.readUnsignedIntBigEndian(offset = 0)
        require(lastLogicalBlockAddress != MAX_UNSIGNED_INT) {
            "O dispositivo excede o limite do READ CAPACITY (10); READ CAPACITY (16) é necessário."
        }

        val blockSize = response.readUnsignedIntBigEndian(offset = 4)
        require(blockSize in 1..Int.MAX_VALUE.toLong()) {
            "O tamanho de bloco reportado pelo dispositivo é inválido: $blockSize bytes."
        }

        val blockCount = Math.addExact(lastLogicalBlockAddress, 1L)
        val capacityBytes = Math.multiplyExact(blockCount, blockSize)

        return UsbBlockDeviceGeometry(
            blockSizeBytes = blockSize.toInt(),
            blockCount = blockCount,
            capacityBytes = capacityBytes,
        )
    }

    private fun ByteArray.readUnsignedIntBigEndian(offset: Int): Long =
        ((this[offset].toLong() and 0xffL) shl 24) or
            ((this[offset + 1].toLong() and 0xffL) shl 16) or
            ((this[offset + 2].toLong() and 0xffL) shl 8) or
            (this[offset + 3].toLong() and 0xffL)

    private companion object {
        const val DEFAULT_TRANSFER_TIMEOUT_MILLIS = 5_000L
        const val MAX_UNSIGNED_INT = 0xffff_ffffL
    }
}
