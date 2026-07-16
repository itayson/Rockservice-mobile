package org.rockservice.core.usb.adb

import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
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
) : IllegalStateException(remoteFailureDisplayMessage(remoteMessage))

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

/** QUIT or stream close failed after the engine had already assumed ownership of the Sync stream. */
class AdbSyncPullCleanupException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

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

    /**
     * Pulls one remote path through an already-open ADB session stream and delivers bounded chunks.
     * The supplied stream is consumed and closed by this call regardless of success or failure.
     */
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
        var primaryFailure: Throwable? = null

        try {
            return withTimeout(timeoutMillis) {
                // Use the same deadline as the outer operation; do not create a shorter implicit
                // timeout for the initial RECV request.
                stream.write(request, timeoutMillis)

                var result: AdbSyncPullResult? = null
                pullLoop@ while (result == null) {
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
                                // Hash before invoking caller code so sink-side mutation cannot alter
                                // the integrity result for bytes received from the peer.
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
                                result = AdbSyncPullResult(
                                    byteCount = deliveredBytes,
                                    sha256 = digest.digest().toLowerHex(),
                                )
                                break@pullLoop
                            }

                            is AdbSyncPullResponse.Fail -> {
                                throw AdbSyncRemoteFailureException(response.message)
                            }
                        }
                    }
                }
                checkNotNull(result)
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            val cleanupFailure = withContext(NonCancellable) {
                cleanupOwnedStream(
                    stream = stream,
                    completedSuccessfully = completedSuccessfully,
                )
            }
            if (cleanupFailure != null) {
                val primary = primaryFailure
                if (primary != null) {
                    primary.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    private suspend fun cleanupOwnedStream(
        stream: AdbSyncPullStream,
        completedSuccessfully: Boolean,
    ): AdbSyncPullCleanupException? {
        val failures = mutableListOf<Throwable>()
        if (completedSuccessfully) {
            try {
                stream.write(
                    AdbSyncPullCodec.encodeQuitRequest(),
                    CLEANUP_TIMEOUT_MILLIS,
                )
            } catch (error: Throwable) {
                failures += IllegalStateException(
                    "Falha ao enviar QUIT para o servico ADB Sync apos DONE.",
                    error,
                )
            }
        }

        try {
            stream.close(CLEANUP_TIMEOUT_MILLIS)
        } catch (error: Throwable) {
            failures += IllegalStateException(
                "Falha ao fechar o stream ADB usado pelo pull Sync.",
                error,
            )
        }

        if (failures.isEmpty()) return null
        val cleanup = AdbSyncPullCleanupException(
            message = if (completedSuccessfully) {
                "ADB Sync concluiu o conteudo, mas o cleanup do stream falhou."
            } else {
                "ADB Sync falhou e o cleanup do stream tambem nao foi concluido."
            },
            cause = failures.first(),
        )
        failures.drop(1).forEach(cleanup::addSuppressed)
        return cleanup
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

    companion object {
        const val DEFAULT_MAXIMUM_BYTES = 64L * 1024L * 1024L
        const val DEFAULT_TIMEOUT_MILLIS = 2L * 60L * 1000L
        const val MAXIMUM_ALLOWED_BYTES = 8L * 1024L * 1024L * 1024L
        const val MAXIMUM_ALLOWED_TIMEOUT_MILLIS = 30L * 60L * 1000L

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

private fun remoteFailureDisplayMessage(remoteMessage: String): String {
    if (remoteMessage.isBlank()) return "ADB Sync remoto retornou FAIL."
    val sanitized = buildString(minOf(remoteMessage.length, MAXIMUM_REMOTE_FAILURE_DISPLAY_CHARS + 1)) {
        remoteMessage.forEach { character ->
            when {
                character == '\n' || character == '\r' || character == '\t' -> append(character)
                character.isISOControl() -> append('\uFFFD')
                else -> append(character)
            }
            if (length > MAXIMUM_REMOTE_FAILURE_DISPLAY_CHARS) return@buildString
        }
    }
    val preview = if (sanitized.length > MAXIMUM_REMOTE_FAILURE_DISPLAY_CHARS) {
        sanitized.take(MAXIMUM_REMOTE_FAILURE_DISPLAY_CHARS - 1) + "…"
    } else {
        sanitized
    }
    return "ADB Sync remoto: $preview"
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

private const val MAXIMUM_REMOTE_FAILURE_DISPLAY_CHARS = 512
