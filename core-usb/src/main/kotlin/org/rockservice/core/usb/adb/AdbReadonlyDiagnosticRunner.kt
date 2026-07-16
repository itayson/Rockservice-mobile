package org.rockservice.core.usb.adb

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Result classification for one allowlisted ADB diagnostic service. */
enum class AdbDiagnosticSectionStatus {
    SUCCESS,
    TRUNCATED,
    UNSUPPORTED,
    TIMEOUT,
    FAILED,
    SKIPPED_BUDGET,
}

/** One bounded in-memory diagnostic section collected from an explicitly allowlisted ADB service. */
data class AdbDiagnosticSection(
    val service: AdbDiagnosticService,
    val status: AdbDiagnosticSectionStatus,
    val text: String,
    val retainedByteCount: Int,
    val observedByteCount: Long,
    val message: String? = null,
)

/** Immutable bounded snapshot of one read-only ADB diagnostic collection pass. */
data class AdbDiagnosticSnapshot(
    val protocolVersion: Long,
    val maxDataBytes: Long,
    val peerBanner: String,
    val sections: List<AdbDiagnosticSection>,
    val retainedByteCount: Int,
    val budgetExhausted: Boolean,
)

/**
 * Collects a fixed set of read-only diagnostics over an already connected [AdbSessionController].
 *
 * Collection is sequential to keep remote load predictable. Raw output stays in memory, is bounded
 * both per service and globally, and is never logged or persisted by this component.
 */
class AdbReadonlyDiagnosticRunner internal constructor(
    private val peer: AdbConnectedPeer,
    private val openStream: suspend (AdbDiagnosticService, Long) -> AdbDiagnosticReadableStream,
    private val serviceTimeoutMillis: Long = DEFAULT_SERVICE_TIMEOUT_MILLIS,
    private val maximumBytesPerService: Int = DEFAULT_MAXIMUM_BYTES_PER_SERVICE,
    private val maximumTotalBytes: Int = DEFAULT_MAXIMUM_TOTAL_BYTES,
) {
    constructor(
        session: AdbSessionController,
        serviceTimeoutMillis: Long = DEFAULT_SERVICE_TIMEOUT_MILLIS,
        maximumBytesPerService: Int = DEFAULT_MAXIMUM_BYTES_PER_SERVICE,
        maximumTotalBytes: Int = DEFAULT_MAXIMUM_TOTAL_BYTES,
    ) : this(
        peer = session.peer,
        openStream = { service, timeoutMillis ->
            SessionDiagnosticReadableStream(session.open(service, timeoutMillis))
        },
        serviceTimeoutMillis = serviceTimeoutMillis,
        maximumBytesPerService = maximumBytesPerService,
        maximumTotalBytes = maximumTotalBytes,
    )

    init {
        require(serviceTimeoutMillis > 0L) { "Timeout de diagnostico ADB deve ser positivo." }
        require(maximumBytesPerService in 1..MAXIMUM_ALLOWED_BYTES_PER_SERVICE) {
            "Limite por servico ADB deve estar entre 1 e $MAXIMUM_ALLOWED_BYTES_PER_SERVICE bytes."
        }
        require(maximumTotalBytes in 1..MAXIMUM_ALLOWED_TOTAL_BYTES) {
            "Limite total ADB deve estar entre 1 e $MAXIMUM_ALLOWED_TOTAL_BYTES bytes."
        }
    }

    /** Collects each requested service at most once, preserving the caller's first-seen order. */
    suspend fun collect(
        services: List<AdbDiagnosticService> = DEFAULT_SERVICES,
    ): AdbDiagnosticSnapshot {
        val requested = services.distinct()
        val sections = ArrayList<AdbDiagnosticSection>(requested.size)
        var remainingBudget = maximumTotalBytes

        requested.forEach { service ->
            if (remainingBudget <= 0) {
                sections += AdbDiagnosticSection(
                    service = service,
                    status = AdbDiagnosticSectionStatus.SKIPPED_BUDGET,
                    text = "",
                    retainedByteCount = 0,
                    observedByteCount = 0,
                    message = "Limite total de diagnostico ADB esgotado antes deste servico.",
                )
                return@forEach
            }

            val sectionLimit = minOf(maximumBytesPerService, remainingBudget)
            val section = collectService(service, sectionLimit)
            sections += section
            remainingBudget -= section.retainedByteCount
        }

        return AdbDiagnosticSnapshot(
            protocolVersion = peer.protocolVersion,
            maxDataBytes = peer.maxDataBytes,
            peerBanner = peer.banner,
            sections = sections,
            retainedByteCount = maximumTotalBytes - remainingBudget,
            budgetExhausted = remainingBudget == 0 && requested.size > sections.count {
                it.status != AdbDiagnosticSectionStatus.SKIPPED_BUDGET
            },
        )
    }

    private suspend fun collectService(
        service: AdbDiagnosticService,
        byteLimit: Int,
    ): AdbDiagnosticSection {
        var stream: AdbDiagnosticReadableStream? = null
        return try {
            withTimeout(serviceTimeoutMillis) {
                stream = openStream(service, serviceTimeoutMillis)
                val output = ByteArrayOutputStream(minOf(byteLimit, INITIAL_OUTPUT_CAPACITY))
                var observedBytes = 0L
                var truncated = false

                while (true) {
                    val chunk = stream?.read() ?: break
                    observedBytes += chunk.size.toLong()
                    val remaining = byteLimit - output.size()
                    if (remaining <= 0) {
                        truncated = true
                        break
                    }
                    if (chunk.size > remaining) {
                        output.write(chunk, 0, remaining)
                        truncated = true
                        break
                    }
                    output.write(chunk)
                }

                if (truncated) safeClose(stream)
                val retained = output.toByteArray()
                AdbDiagnosticSection(
                    service = service,
                    status = if (truncated) {
                        AdbDiagnosticSectionStatus.TRUNCATED
                    } else {
                        AdbDiagnosticSectionStatus.SUCCESS
                    },
                    text = retained.toDiagnosticText(),
                    retainedByteCount = retained.size,
                    observedByteCount = observedBytes,
                    message = if (truncated) {
                        "Saida limitada a $byteLimit bytes; o stream remoto foi fechado antecipadamente."
                    } else {
                        null
                    },
                )
            }
        } catch (error: AdbServiceRejectedException) {
            AdbDiagnosticSection(
                service = service,
                status = AdbDiagnosticSectionStatus.UNSUPPORTED,
                text = "",
                retainedByteCount = 0,
                observedByteCount = 0,
                message = error.message ?: "Servico ADB rejeitado pelo peer.",
            )
        } catch (error: TimeoutCancellationException) {
            safeClose(stream)
            AdbDiagnosticSection(
                service = service,
                status = AdbDiagnosticSectionStatus.TIMEOUT,
                text = "",
                retainedByteCount = 0,
                observedByteCount = 0,
                message = "Servico ADB excedeu o timeout de $serviceTimeoutMillis ms.",
            )
        } catch (cancelled: CancellationException) {
            safeClose(stream)
            throw cancelled
        } catch (error: Throwable) {
            safeClose(stream)
            AdbDiagnosticSection(
                service = service,
                status = AdbDiagnosticSectionStatus.FAILED,
                text = "",
                retainedByteCount = 0,
                observedByteCount = 0,
                message = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private suspend fun safeClose(stream: AdbDiagnosticReadableStream?) {
        if (stream == null) return
        withContext(NonCancellable) {
            try {
                stream.close(CLEANUP_CLOSE_TIMEOUT_MILLIS)
            } catch (_: Throwable) {
                // Best-effort cleanup: the session controller remains responsible for terminal state.
            }
        }
    }

    companion object {
        const val DEFAULT_SERVICE_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_MAXIMUM_BYTES_PER_SERVICE = 128 * 1024
        const val DEFAULT_MAXIMUM_TOTAL_BYTES = 512 * 1024
        val DEFAULT_SERVICES: List<AdbDiagnosticService> = AdbDiagnosticService.entries.toList()

        private const val INITIAL_OUTPUT_CAPACITY = 8 * 1024
        private const val CLEANUP_CLOSE_TIMEOUT_MILLIS = 1_000L
        private const val MAXIMUM_ALLOWED_BYTES_PER_SERVICE = 1024 * 1024
        private const val MAXIMUM_ALLOWED_TOTAL_BYTES = 4 * 1024 * 1024
    }
}

internal interface AdbDiagnosticReadableStream {
    suspend fun read(): ByteArray?
    suspend fun close(timeoutMillis: Long)
}

private class SessionDiagnosticReadableStream(
    private val stream: AdbSessionStream,
) : AdbDiagnosticReadableStream {
    override suspend fun read(): ByteArray? = stream.read()

    override suspend fun close(timeoutMillis: Long) {
        stream.close(timeoutMillis)
    }
}

private fun ByteArray.toDiagnosticText(): String =
    toString(Charsets.UTF_8).replace('\u0000', '\uFFFD')
