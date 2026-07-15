package org.rockservice.core.usb.rockchip

import android.content.Context
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.rockservice.core.usb.UsbDeviceDescriptor

internal fun interface RockchipReadOnlyTransportOpener {
    fun open(device: UsbDeviceDescriptor): RockchipReadOnlyTransport
}

data class RockchipMetadataProbeEntry(
    val name: String,
    val succeeded: Boolean,
    val value: String,
    val attempted: Boolean = true,
)

data class RockchipMetadataProbeReport(
    val transportMethod: String,
    val entries: List<RockchipMetadataProbeEntry>,
    val requiresReconnect: Boolean = false,
) {
    val hasAnySuccess: Boolean
        get() = entries.any(RockchipMetadataProbeEntry::succeeded)
}

/** Metadata-only facade for the physically validated Android Rockchip USB transport. */
class AndroidRockchipReadOnlyMetadataClient internal constructor(
    private val opener: RockchipReadOnlyTransportOpener,
    private val transportMethod: RockchipUsbIoMethod,
    private val closeTimeoutMillis: Long = DEFAULT_CLOSE_TIMEOUT_MILLIS,
) {
    init {
        require(closeTimeoutMillis > 0L) { "closeTimeoutMillis must be greater than zero." }
    }

    constructor(context: Context) : this(
        opener = createDefaultOpener(context.applicationContext),
        transportMethod = RockchipUsbIoMethod.USB_REQUEST,
    )

    suspend fun probe(device: UsbDeviceDescriptor): RockchipMetadataProbeReport {
        val specs = querySpecs()
        val transport = try {
            opener.open(device)
        } catch (error: SecurityException) {
            return failedOpenReport(specs, error.safeMessage())
        } catch (error: IllegalArgumentException) {
            return failedOpenReport(specs, error.safeMessage())
        } catch (error: IllegalStateException) {
            return failedOpenReport(specs, error.safeMessage())
        }

        val session = RockchipReadOnlySession(transport)
        val entries = mutableListOf<RockchipMetadataProbeEntry>()
        var requiresReconnect = false

        try {
            for ((index, spec) in specs.withIndex()) {
                val outcome = query(session, spec)
                entries += outcome.entry
                if (outcome.stopRemainingQueries) {
                    requiresReconnect = outcome.requiresReconnect
                    val blocker = outcome.entry.name
                    specs.drop(index + 1).forEach { skipped ->
                        entries += skippedEntry(skipped.name, blocker)
                    }
                    break
                }
            }
        } finally {
            if (!closeSessionWithinDeadline(session)) requiresReconnect = true
        }

        return RockchipMetadataProbeReport(
            transportMethod = transportMethod.displayName,
            entries = entries,
            requiresReconnect = requiresReconnect,
        )
    }

    private suspend fun closeSessionWithinDeadline(session: RockchipReadOnlySession): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            if (!CLOSE_WORKER_BUSY.compareAndSet(false, true)) {
                LOGGER.warning("Fechamento Rockchip anterior ainda está bloqueado; reconexão necessária.")
                return@withContext false
            }

            val task = FutureTask {
                try {
                    runBlocking { session.close() }
                } finally {
                    CLOSE_WORKER_BUSY.set(false)
                }
            }
            Thread(task, "RockchipMetadataClose").apply {
                isDaemon = true
                start()
            }

            try {
                task.get(closeTimeoutMillis, TimeUnit.MILLISECONDS)
                true
            } catch (error: TimeoutException) {
                task.cancel(true)
                LOGGER.log(
                    Level.WARNING,
                    "Timeout de $closeTimeoutMillis ms ao fechar a sessão Rockchip; reconexão necessária.",
                    error,
                )
                false
            } catch (error: InterruptedException) {
                task.cancel(true)
                Thread.currentThread().interrupt()
                LOGGER.log(Level.WARNING, "Espera pelo fechamento Rockchip foi interrompida; reconexão necessária.", error)
                false
            } catch (error: ExecutionException) {
                LOGGER.log(Level.WARNING, "Falha ao fechar a sessão Rockchip; reconexão necessária.", error.cause ?: error)
                false
            }
        }

    private suspend fun query(
        session: RockchipReadOnlySession,
        spec: QuerySpec,
    ): QueryOutcome {
        val result = try {
            session.query(spec.operation, timeoutMillis = QUERY_TIMEOUT_MILLIS)
        } catch (timeout: TimeoutCancellationException) {
            LOGGER.log(Level.WARNING, "Metadata query ${spec.name} timed out after $QUERY_TIMEOUT_MILLIS ms.", timeout)
            return transportFailure(
                spec = spec,
                detail = "${transportMethod.displayName}/TIMEOUT: consulta excedeu $QUERY_TIMEOUT_MILLIS ms.",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RockchipUsbTransportException) {
            return transportFailure(spec, error.safeMessage())
        } catch (error: IllegalArgumentException) {
            return transportFailure(spec, error.safeMessage())
        } catch (error: IllegalStateException) {
            return transportFailure(spec, error.safeMessage())
        }

        return try {
            QueryOutcome(
                entry = RockchipMetadataProbeEntry(spec.name, true, spec.render(result.data)),
                stopRemainingQueries = false,
                requiresReconnect = false,
            )
        } catch (error: IllegalArgumentException) {
            QueryOutcome(
                entry = RockchipMetadataProbeEntry(
                    name = spec.name,
                    succeeded = false,
                    value = "Resposta recebida, mas não foi possível interpretar: ${error.safeMessage()}",
                ),
                stopRemainingQueries = false,
                requiresReconnect = false,
            )
        }
    }

    private fun failedOpenReport(
        specs: List<QuerySpec>,
        detail: String,
    ): RockchipMetadataProbeReport {
        val first = specs.first()
        return RockchipMetadataProbeReport(
            transportMethod = transportMethod.displayName,
            entries = buildList {
                add(RockchipMetadataProbeEntry(first.name, false, detail))
                specs.drop(1).forEach { skipped -> add(skippedEntry(skipped.name, first.name)) }
            },
            requiresReconnect = true,
        )
    }

    private fun transportFailure(
        spec: QuerySpec,
        detail: String,
    ): QueryOutcome = QueryOutcome(
        entry = RockchipMetadataProbeEntry(spec.name, false, detail),
        stopRemainingQueries = true,
        requiresReconnect = true,
    )

    private fun skippedEntry(name: String, blocker: String): RockchipMetadataProbeEntry =
        RockchipMetadataProbeEntry(
            name = name,
            succeeded = false,
            value = "Não executado porque o transporte perdeu sincronização durante $blocker.",
            attempted = false,
        )

    private fun querySpecs(): List<QuerySpec> = listOf(
        QuerySpec("TEST_UNIT_READY", RockchipReadOnlyOperation.TEST_UNIT_READY) { "ready" },
        QuerySpec("READ_CHIP_INFO", RockchipReadOnlyOperation.READ_CHIP_INFO) { data ->
            RockchipMetadataParsers.parseChipInfo(data).rawHex
        },
        QuerySpec("READ_FLASH_ID", RockchipReadOnlyOperation.READ_FLASH_ID) { data ->
            RockchipMetadataParsers.parseFlashId(data).rawHex
        },
        QuerySpec("READ_FLASH_INFO", RockchipReadOnlyOperation.READ_FLASH_INFO) { data ->
            val parsed = RockchipMetadataParsers.parseFlashInfo(data)
            "totalSectors=${parsed.totalSectors}, responseBytes=${parsed.rawResponseLength}"
        },
    )

    private fun Throwable.safeMessage(): String =
        message?.take(MAXIMUM_ERROR_LENGTH)?.ifBlank { null } ?: javaClass.simpleName

    private data class QuerySpec(
        val name: String,
        val operation: RockchipReadOnlyOperation,
        val render: (ByteArray) -> String,
    )

    private data class QueryOutcome(
        val entry: RockchipMetadataProbeEntry,
        val stopRemainingQueries: Boolean,
        val requiresReconnect: Boolean,
    )

    private companion object {
        const val QUERY_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_CLOSE_TIMEOUT_MILLIS = 2_000L
        const val MAXIMUM_ERROR_LENGTH = 240
        val LOGGER: Logger = Logger.getLogger("RockchipMetadataClient")
        val CLOSE_WORKER_BUSY = AtomicBoolean(false)

        fun createDefaultOpener(context: Context): RockchipReadOnlyTransportOpener {
            val factory = AndroidRockchipReadOnlyTransportFactory(context)
            return RockchipReadOnlyTransportOpener { device ->
                factory.open(device, RockchipUsbIoMethod.USB_REQUEST)
            }
        }
    }
}
