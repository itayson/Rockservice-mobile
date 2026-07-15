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
) {
    val hasAnySuccess: Boolean
        get() = entries.any(RockchipMetadataProbeEntry::succeeded)
}

/**
 * Public metadata-only facade for the physically validated Android Rockchip USB transport.
 *
 * Each attempted command opens an isolated transport session so a failed exchange cannot leave
 * later metadata queries on an unknown protocol phase. The Android path currently uses UsbRequest
 * with a finite wait so the physical bulk-OUT behavior can be diagnosed without an infinite block.
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
        val entries = mutableListOf<RockchipMetadataProbeEntry>()
        val specs = querySpecs()

        for ((index, spec) in specs.withIndex()) {
            val outcome = query(device, spec)
            entries += outcome.entry
            if (outcome.stopRemainingQueries) {
                val blocker = outcome.entry.name
                specs.drop(index + 1).forEach { skipped ->
                    entries += RockchipMetadataProbeEntry(
                        name = skipped.name,
                        succeeded = false,
                        value = "Nao executado porque o transporte falhou antes de concluir $blocker.",
                        attempted = false,
                    )
                }
                break
            }
        }

        return RockchipMetadataProbeReport(
            transportMethod = transportMethod.displayName,
            entries = entries,
        )
    }

    private suspend fun query(
        device: UsbDeviceDescriptor,
        spec: QuerySpec,
    ): QueryOutcome {
        val transport = try {
            opener.open(device)
        } catch (error: RuntimeException) {
            return QueryOutcome(
                entry = RockchipMetadataProbeEntry(spec.name, false, error.safeMessage()),
                stopRemainingQueries = true,
            )
        }
        val session = RockchipReadOnlySession(transport)
        return try {
            val result = session.query(spec.operation, timeoutMillis = QUERY_TIMEOUT_MILLIS)
            QueryOutcome(
                entry = RockchipMetadataProbeEntry(spec.name, true, spec.render(result.data)),
                stopRemainingQueries = false,
            )
        } catch (timeout: TimeoutCancellationException) {
            QueryOutcome(
                entry = RockchipMetadataProbeEntry(
                    spec.name,
                    false,
                    "${transportMethod.displayName}/TIMEOUT: consulta excedeu $QUERY_TIMEOUT_MILLIS ms.",
                ),
                stopRemainingQueries = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RockchipUsbTransportException) {
            QueryOutcome(
                entry = RockchipMetadataProbeEntry(spec.name, false, error.safeMessage()),
                stopRemainingQueries = error.stage == RockchipTransportStage.COMMAND_WRITE,
            )
        } catch (error: RuntimeException) {
            QueryOutcome(
                entry = RockchipMetadataProbeEntry(spec.name, false, error.safeMessage()),
                stopRemainingQueries = false,
            )
        } finally {
            try {
                session.close()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Failed to close isolated Rockchip metadata session for ${spec.name}.", error)
            }
        }
    }

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
        QuerySpec("READ_STORAGE", RockchipReadOnlyOperation.READ_STORAGE) { data ->
            val parsed = RockchipMetadataParsers.parseStorage(data)
            "bitMask=0x${parsed.bitMask.toString(16).uppercase()}, firstIndex=${parsed.firstAvailableStorageIndex ?: "none"}"
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
