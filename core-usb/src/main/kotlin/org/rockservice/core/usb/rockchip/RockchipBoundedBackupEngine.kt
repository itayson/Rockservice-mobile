package org.rockservice.core.usb.rockchip

import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Integrity metadata returned after one complete bounded Rockchip read-only backup. */
data class RockchipBoundedBackupResult(
    val startSector: Long,
    val sectorCount: Long,
    val byteCount: Long,
    val sha256: String,
)

/** Progress snapshot emitted only after a complete chunk has been accepted by the caller sink. */
data class RockchipBoundedBackupProgress(
    val completedSectors: Long,
    val totalSectors: Long,
    val completedBytes: Long,
    val totalBytes: Long,
)

/**
 * Streams a caller-approved contiguous LBA range through the existing read-only Rockchip session.
 *
 * This engine performs no USB discovery, opens no device and creates no file. The Android layer must
 * select/revalidate the physical target and provide an explicit local destination separately.
 */
internal class RockchipBoundedBackupEngine(
    private val maximumSectorCount: Long = DEFAULT_MAXIMUM_SECTOR_COUNT,
    private val chunkSectorCount: Int = RockchipReadOnlyProtocolCodec.MAX_BOUNDED_LBA_SECTORS,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    init {
        require(maximumSectorCount in 1L..HARD_MAXIMUM_SECTOR_COUNT) {
            "maximumSectorCount must be between 1 and $HARD_MAXIMUM_SECTOR_COUNT sectors."
        }
        require(chunkSectorCount in 1..RockchipReadOnlyProtocolCodec.MAX_BOUNDED_LBA_SECTORS) {
            "chunkSectorCount must be between 1 and ${RockchipReadOnlyProtocolCodec.MAX_BOUNDED_LBA_SECTORS}."
        }
    }

    suspend fun backup(
        session: RockchipReadOnlySession,
        startSector: Long,
        sectorCount: Long,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onData: suspend (ByteArray) -> Unit,
        onProgress: suspend (RockchipBoundedBackupProgress) -> Unit = {},
    ): RockchipBoundedBackupResult = backup(
        reader = SessionLbaReader(session),
        startSector = startSector,
        sectorCount = sectorCount,
        timeoutMillis = timeoutMillis,
        onData = onData,
        onProgress = onProgress,
    )

    internal suspend fun backup(
        reader: RockchipBoundedLbaReader,
        startSector: Long,
        sectorCount: Long,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onData: suspend (ByteArray) -> Unit,
        onProgress: suspend (RockchipBoundedBackupProgress) -> Unit = {},
    ): RockchipBoundedBackupResult {
        validateRange(startSector, sectorCount)
        require(timeoutMillis in 1L..MAXIMUM_TIMEOUT_MILLIS) {
            "timeoutMillis must be between 1 and $MAXIMUM_TIMEOUT_MILLIS."
        }

        val totalBytes = Math.multiplyExact(sectorCount, LOGICAL_SECTOR_SIZE.toLong())
        val digest = MessageDigest.getInstance("SHA-256")
        val deadlineNanos = deadlineAfter(timeoutMillis)
        var completedSectors = 0L

        return withTimeout(timeoutMillis) {
            while (completedSectors < sectorCount) {
                currentCoroutineContext().ensureActive()
                val currentSector = Math.addExact(startSector, completedSectors)
                val sectorsThisChunk = minOf(
                    chunkSectorCount.toLong(),
                    sectorCount - completedSectors,
                ).toInt()
                val expectedBytes = Math.multiplyExact(sectorsThisChunk, LOGICAL_SECTOR_SIZE)
                val chunk = reader.readLba(
                    startSector = currentSector,
                    sectorCount = sectorsThisChunk,
                    timeoutMillis = remainingTimeoutMillis(deadlineNanos),
                )
                require(chunk.size == expectedBytes) {
                    "Rockchip backup chunk returned ${chunk.size} bytes; expected $expectedBytes."
                }

                digest.update(chunk)
                onData(chunk)
                completedSectors += sectorsThisChunk.toLong()
                onProgress(
                    RockchipBoundedBackupProgress(
                        completedSectors = completedSectors,
                        totalSectors = sectorCount,
                        completedBytes = Math.multiplyExact(completedSectors, LOGICAL_SECTOR_SIZE.toLong()),
                        totalBytes = totalBytes,
                    ),
                )
            }

            RockchipBoundedBackupResult(
                startSector = startSector,
                sectorCount = sectorCount,
                byteCount = totalBytes,
                sha256 = digest.digest().toLowerHex(),
            )
        }
    }

    private fun validateRange(startSector: Long, sectorCount: Long) {
        require(startSector in 0L..MAX_UNSIGNED_INT) {
            "startSector must fit in an unsigned 32-bit LBA."
        }
        require(sectorCount in 1L..maximumSectorCount) {
            "sectorCount must be between 1 and $maximumSectorCount."
        }
        val lastSector = Math.addExact(startSector, sectorCount - 1L)
        require(lastSector <= MAX_UNSIGNED_INT) {
            "Requested Rockchip backup range exceeds the unsigned 32-bit LBA address space."
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        return Math.addExact(monotonicNanos(), timeoutNanos)
    }

    private fun remainingTimeoutMillis(deadlineNanos: Long): Long {
        val remainingNanos = deadlineNanos - monotonicNanos()
        check(remainingNanos > 0L) { "Rockchip backup deadline expired before the next READ_LBA chunk." }
        return ((remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND)
            .coerceAtLeast(1L)
            .coerceAtMost(MAXIMUM_TIMEOUT_MILLIS)
    }

    companion object {
        const val DEFAULT_MAXIMUM_SECTOR_COUNT = 131_072L // 64 MiB at 512 bytes/sector.
        const val HARD_MAXIMUM_SECTOR_COUNT = 2_097_152L // 1 GiB hard cap for this first engine.
        const val DEFAULT_TIMEOUT_MILLIS = 10L * 60L * 1000L
        const val MAXIMUM_TIMEOUT_MILLIS = 30L * 60L * 1000L

        private const val LOGICAL_SECTOR_SIZE = 512
        private const val MAX_UNSIGNED_INT = 0xFFFF_FFFFL
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal fun interface RockchipBoundedLbaReader {
    suspend fun readLba(startSector: Long, sectorCount: Int, timeoutMillis: Long): ByteArray
}

private class SessionLbaReader(
    private val session: RockchipReadOnlySession,
) : RockchipBoundedLbaReader {
    override suspend fun readLba(startSector: Long, sectorCount: Int, timeoutMillis: Long): ByteArray =
        session.readLba(startSector, sectorCount, timeoutMillis).data
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
