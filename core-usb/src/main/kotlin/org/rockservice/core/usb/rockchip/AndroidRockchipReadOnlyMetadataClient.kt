package org.rockservice.core.usb.rockchip

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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

/**
 * Public metadata-only facade for the physically validated Android Rockchip USB transport.
 *
 * A probe keeps one validated transport session while transactions are healthy. Any USB/protocol
 * failure stops the remaining commands because the device phase can no longer be assumed to be
 * synchronized. The caller can then require a physical reconnect before another active probe.
 */
class AndroidRockchipReadOnlyMetadataClient internal constructor(
    private val opener: RockchipReadOnlyTransportOpener,
    private val transportMethod: RockchipUsbIoMethod,
) {
    constructor(context: Context) : this(
        opener = createDefaultOpener(context.applicationContext),
        transportMethod = RockchipUsbIoMethod.USB_REQUEST,
    )

    suspend fun probe(device: UsbDeviceDescriptor): RockchipMetadataProbeReport {
        val specs = querySpecs()
        val transport = try {
            opener.open(device)
        } catch (error: RuntimeException) {
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
            try {
                session.close()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Failed to close Rockchip metadata probe session.", error)
            }
        }

        return RockchipMetadataProbeReport(
            transportMethod = transportMethod.displayName,
            entries = entries,
            requiresReconnect = requiresReconnect,
        )
    }

    private suspend fun query(
        session: RockchipReadOnlySession,
        spec: QuerySpec,
    ): QueryOutcome {
        val result = try {
            session.query(spec.operation, timeoutMillis = QUERY_TIMEOUT_MILLIS)
        } catch (timeout: TimeoutCancellationException) {
            return transportFailure(
                spec = spec,
                detail = "${transportMethod.displayName}/TIMEOUT: consulta excedeu $QUERY_TIMEOUT_MILLIS ms.",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RockchipUsbTransportException) {
            return transportFailure(spec, error.safeMessage())
        } catch (error: RuntimeException) {
            return transportFailure(spec, error.safeMessage())
        }

        return try {
            QueryOutcome(
                entry = RockchipMetadataProbeEntry(spec.name, true, spec.render(result.data)),
                stopRemainingQueries = false,
                requiresReconnect = false,
            )
        } catch (error: RuntimeException) {
            QueryOutcome(
                entry = RockchipMetadataProbeEntry(
                    name = spec.name,
                    succeeded = false,
                    value = "Resposta recebida, mas nao foi possivel interpretar: ${error.safeMessage()}",
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
            value = "Nao executado porque o transporte perdeu sincronizacao durante $blocker.",
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
        QuerySpec("READ_CAPABILITY", RockchipReadOnlyOperation.READ_CAPABILITY) { data ->
            RockchipMetadataParsers.parseCapability(data).rawHex
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
        const val MAXIMUM_ERROR_LENGTH = 240
        const val TAG = "RockchipMetadataClient"

        fun createDefaultOpener(context: Context): RockchipReadOnlyTransportOpener {
            val factory = AndroidRockchipReadOnlyTransportFactory(context)
            return RockchipReadOnlyTransportOpener { device ->
                factory.open(device, RockchipUsbIoMethod.USB_REQUEST)
            }
        }
    }
}
