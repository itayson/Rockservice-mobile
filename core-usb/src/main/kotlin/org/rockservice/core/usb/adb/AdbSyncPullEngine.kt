package org.rockservice.core.usb.adb

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Metadata produced after one complete bounded ADB Sync pull. */
data class AdbSyncPullResult(
    val byteCount: Long,
    val sha256: String,
)

/** The remote Sync service returned a terminal FAIL response. */
class AdbSyncRemoteFailureException(
    val remoteMessage: String,
) : IllegalStateException(
    if (remoteMessage.isBlank()) "ADB Sync remoto retornou FAIL." else "ADB Sync remoto: $remoteMessage",
)

/** A pull would exceed the caller-approved maximum byte count. */
class AdbSyncPullLimitExceededException(
    val maximumBytes: Long,
    val bytesDelivered: Long,
    val rejectedChunkBytes: Int,
) : IllegalStateException(
    "ADB Sync excederia o limite de $maximumBytes bytes apos $bytesDelivered bytes entregues.",
)

/** The remote stream violated or truncated the expected Sync pull framing. */
class AdbSyncPullProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** The complete pull did not finish inside the caller-approved deadline. */
class AdbSyncPullTimeoutException(
    val timeoutMillis: Long,
) : IllegalStateException("ADB Sync pull excedeu o timeout total de $timeoutMillis ms.")

/**
 * Streams one already-open `sync:` ADB service into a bounded caller sink.
 *
 * The engine owns the supplied stream for the duration of [pull]. On successful DONE it sends QUIT,
 * then closes the ADB stream. On FAIL, timeout, limit overflow, cancellation, or protocol error it
 * closes the stream without attempting another Sync command.
 */
class AdbSyncPullEngine(
    private val defaultMaximumBytes: Long = DEFAULT_MAXIMUM_BYTES,
    private val defaultTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    init {
        validateMaximumBytes(defaultMaximumBytes)
        validateTimeout(defaultTimeoutMillis)
    }

    suspend fun pull(
        stream: AdbSessionStream,
        remotePath: String,
        maximumBytes: Long = defaultMaximumBytes,
        timeoutMillis: Long = defaultTimeoutMillis,
        onData: suspend (ByteArray) -> Unit,
    ): AdbSyncPullResult = pull(
        stream = SessionSyncPullStream(stream),
        remotePath = remotePath,
        maximumBytes = maximumBytes,
        timeoutMillis = timeoutMillis,
        onData = onData,
    )

    internal suspend fun pull(
        stream: AdbSyncPullStream,
        remotePath: String,
        maximumBytes: Long = defaultMaximumBytes,
        timeoutMillis: Long = defaultTimeoutMillis,
        onData: suspend (ByteArray) -> Unit,
    ): AdbSyncPullResult {
        validateMaximumBytes(maximumBytes)
        validateTimeout(timeoutMillis)
        val request = AdbSyncPullCodec.encodeReceiveRequest(remotePath)
        val decoder = AdbSyncPullDecoder()
        val digest = MessageDigest.getInstance("SHA-256")
        var deliveredBytes = 0L
        var completedSuccessfully = false

        try {
            return withTimeout(timeoutMillis) {
                stream.write(request, boundedStreamTimeout(timeoutMillis))

                while (true) {
                    val transportChunk = stream.read()
                        ?: throw protocolErrorFromPrematureClose(decoder)
                    val responses = try {
                        decoder.feed(transportChunk)
                    } catch (error: IllegalArgumentException) {
                        throw AdbSyncPullProtocolException(
                            "Resposta ADB Sync invalida durante RECV.",
                            error,
                        )
                    } catch (error: IllegalStateException) {
                        throw AdbSyncPullProtocolException(
                            "ADB Sync enviou dados depois do estado terminal.",
                            error,
                        )
                    }

                    for (response in responses) {
                        when (response) {
                            is AdbSyncPullResponse.Data -> {
                                val payload = response.bytes
                                if (payload.size.toLong() > maximumBytes - deliveredBytes) {
                                    throw AdbSyncPullLimitExceededException(
                                        maximumBytes = maximumBytes,
                                        bytesDelivered = deliveredBytes,
                                        rejectedChunkBytes = payload.size,
                                    )
                                }
                                digest.update(payload)
                                onData(payload)
                                deliveredBytes += payload.size.toLong()
                            }

                            AdbSyncPullResponse.Done -> {
                                try {
                                    decoder.finish()
                                } catch (error: IllegalArgumentException) {
                                    throw AdbSyncPullProtocolException(
                                        "ADB Sync terminou com framing inconsistente.",
                                        error,
                                    )
                                }
                                completedSuccessfully = true
                                return@withTimeout AdbSyncPullResult(
                                    byteCount = deliveredBytes,
                                    sha256 = digest.digest().toLowerHex(),
                                )
                            }

                            is AdbSyncPullResponse.Fail -> {
                                throw AdbSyncRemoteFailureException(response.message)
                            }
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AdbSyncPullTimeoutException(timeoutMillis)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                if (completedSuccessfully) {
                    runCatching {
                        stream.write(
                            AdbSyncPullCodec.encodeQuitRequest(),
                            CLEANUP_TIMEOUT_MILLIS,
                        )
                    }
                }
                runCatching { stream.close(CLEANUP_TIMEOUT_MILLIS) }
            }
        }
    }

    private fun protocolErrorFromPrematureClose(decoder: AdbSyncPullDecoder): AdbSyncPullProtocolException =
        try {
            decoder.finish()
            AdbSyncPullProtocolException("Stream ADB Sync fechou sem resultado de pull.")
        } catch (error: IllegalArgumentException) {
            AdbSyncPullProtocolException(
                "Stream ADB Sync fechou antes de uma resposta terminal completa.",
                error,
            )
        }

    private fun validateMaximumBytes(maximumBytes: Long) {
        require(maximumBytes in 1L..MAXIMUM_ALLOWED_BYTES) {
            "Limite de ADB Sync pull deve estar entre 1 e $MAXIMUM_ALLOWED_BYTES bytes."
        }
    }

    private fun validateTimeout(timeoutMillis: Long) {
        require(timeoutMillis in 1L..MAXIMUM_ALLOWED_TIMEOUT_MILLIS) {
            "Timeout de ADB Sync pull deve estar entre 1 e $MAXIMUM_ALLOWED_TIMEOUT_MILLIS ms."
        }
    }

    private fun boundedStreamTimeout(totalTimeoutMillis: Long): Long =
        minOf(totalTimeoutMillis, DEFAULT_STREAM_WRITE_TIMEOUT_MILLIS)

    companion object {
        const val DEFAULT_MAXIMUM_BYTES = 64L * 1024L * 1024L
        const val DEFAULT_TIMEOUT_MILLIS = 2L * 60L * 1000L
        const val MAXIMUM_ALLOWED_BYTES = 8L * 1024L * 1024L * 1024L
        const val MAXIMUM_ALLOWED_TIMEOUT_MILLIS = 30L * 60L * 1000L

        private const val DEFAULT_STREAM_WRITE_TIMEOUT_MILLIS = 5_000L
        private const val CLEANUP_TIMEOUT_MILLIS = 1_000L
    }
}

internal interface AdbSyncPullStream {
    suspend fun write(bytes: ByteArray, timeoutMillis: Long)
    suspend fun read(): ByteArray?
    suspend fun close(timeoutMillis: Long)
}

private class SessionSyncPullStream(
    private val stream: AdbSessionStream,
) : AdbSyncPullStream {
    override suspend fun write(bytes: ByteArray, timeoutMillis: Long) {
        stream.write(bytes, timeoutMillis)
    }

    override suspend fun read(): ByteArray? = stream.read()

    override suspend fun close(timeoutMillis: Long) {
        stream.close(timeoutMillis)
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
