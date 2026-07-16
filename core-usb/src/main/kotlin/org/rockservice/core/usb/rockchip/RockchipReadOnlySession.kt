package org.rockservice.core.usb.rockchip

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal data class RockchipRawExchange(
    val data: ByteArray,
    val statusBytes: ByteArray,
)

/**
 * Internal transport boundary for the metadata-only Rockchip session.
 *
 * No Android implementation exists in the bootstrap. Keeping this boundary internal prevents the
 * application layer from sending arbitrary raw protocol commands.
 */
internal interface RockchipReadOnlyTransport {
    suspend fun exchange(
        command: ByteArray,
        responseLengthRange: IntRange,
        timeoutMillis: Long,
    ): RockchipRawExchange

    suspend fun close()
}

internal class RockchipReadOnlySession(
    private val transport: RockchipReadOnlyTransport,
    initialTag: Int = 1,
) {
    private val nextTag = AtomicInteger(initialTag)
    private val operationMutex = Mutex()
    @Volatile
    private var closed = false

    suspend fun query(
        operation: RockchipReadOnlyOperation,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        testUnitSubCode: RockchipReadOnlyTestUnitSubCode = RockchipReadOnlyTestUnitSubCode.NONE,
    ): RockchipReadOnlyExchangeResult {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero." }

        return withTimeout(timeoutMillis) {
            operationMutex.withLock {
                check(!closed) { "Rockchip read-only session is closed." }
                currentCoroutineContext().ensureActive()

                val tag = nextTag.getAndIncrement()
                val command = RockchipReadOnlyProtocolCodec.encodeCommand(
                    operation = operation,
                    tag = tag,
                    testUnitSubCode = testUnitSubCode,
                )
                val raw = transport.exchange(
                    command = command,
                    responseLengthRange = operation.minResponseLength..operation.maxResponseLength,
                    timeoutMillis = timeoutMillis,
                )
                currentCoroutineContext().ensureActive()

                val result = RockchipReadOnlyProtocolCodec.decodeExchange(
                    operation = operation,
                    data = raw.data,
                    statusBytes = raw.statusBytes,
                    expectedTag = tag,
                )
                check(result.status.status == RockchipCommandStatus.PASSED) {
                    "Rockchip metadata command ${operation.name} failed with ${result.status.status}."
                }
                result
            }
        }
    }

    suspend fun readLba(
        startSector: Long,
        sectorCount: Int,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): RockchipReadOnlyLbaExchangeResult {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero." }

        return withTimeout(timeoutMillis) {
            operationMutex.withLock {
                check(!closed) { "Rockchip read-only session is closed." }
                currentCoroutineContext().ensureActive()

                val tag = nextTag.getAndIncrement()
                val command = RockchipReadOnlyProtocolCodec.encodeReadLbaCommand(
                    tag = tag,
                    startSector = startSector,
                    sectorCount = sectorCount,
                )
                val expectedBytes = Math.multiplyExact(
                    sectorCount,
                    RockchipReadOnlyProtocolCodec.LOGICAL_SECTOR_SIZE,
                )
                val raw = transport.exchange(
                    command = command,
                    responseLengthRange = expectedBytes..expectedBytes,
                    timeoutMillis = timeoutMillis,
                )
                currentCoroutineContext().ensureActive()

                val result = RockchipReadOnlyProtocolCodec.decodeReadLbaExchange(
                    startSector = startSector,
                    sectorCount = sectorCount,
                    data = raw.data,
                    statusBytes = raw.statusBytes,
                    expectedTag = tag,
                )
                check(result.status.status == RockchipCommandStatus.PASSED) {
                    "Rockchip READ_LBA failed with ${result.status.status}."
                }
                result
            }
        }
    }

    suspend fun close() {
        operationMutex.withLock {
            if (closed) return
            closed = true
            transport.close()
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}
