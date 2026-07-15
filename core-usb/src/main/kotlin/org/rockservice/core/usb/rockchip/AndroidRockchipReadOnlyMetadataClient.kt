package org.rockservice.core.usb.rockchip

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.rockservice.core.usb.UsbDeviceDescriptor

data class RockchipMetadataProbeEntry(
    val name: String,
    val succeeded: Boolean,
    val value: String,
)

data class RockchipMetadataProbeReport(
    val entries: List<RockchipMetadataProbeEntry>,
) {
    val hasAnySuccess: Boolean
        get() = entries.any(RockchipMetadataProbeEntry::succeeded)
}

/**
 * Public metadata-only facade for the physically validated Android Rockchip USB transport.
 *
 * Each command opens an isolated transport session so a failed or timed-out exchange cannot leave
 * later metadata queries on an unknown protocol phase. No arbitrary command or write API is exposed.
 */
class AndroidRockchipReadOnlyMetadataClient(context: Context) {
    private val factory = AndroidRockchipReadOnlyTransportFactory(context.applicationContext)

    suspend fun probe(device: UsbDeviceDescriptor): RockchipMetadataProbeReport =
        RockchipMetadataProbeReport(
            entries = listOf(
                query(device, "TEST_UNIT_READY", RockchipReadOnlyOperation.TEST_UNIT_READY) { "ready" },
                query(device, "READ_CHIP_INFO", RockchipReadOnlyOperation.READ_CHIP_INFO) { data ->
                    RockchipMetadataParsers.parseChipInfo(data).rawHex
                },
                query(device, "READ_FLASH_ID", RockchipReadOnlyOperation.READ_FLASH_ID) { data ->
                    RockchipMetadataParsers.parseFlashId(data).rawHex
                },
                query(device, "READ_FLASH_INFO", RockchipReadOnlyOperation.READ_FLASH_INFO) { data ->
                    val parsed = RockchipMetadataParsers.parseFlashInfo(data)
                    "totalSectors=${parsed.totalSectors}, responseBytes=${parsed.rawResponseLength}"
                },
                query(device, "READ_STORAGE", RockchipReadOnlyOperation.READ_STORAGE) { data ->
                    val parsed = RockchipMetadataParsers.parseStorage(data)
                    "bitMask=0x${parsed.bitMask.toString(16).uppercase()}, firstIndex=${parsed.firstAvailableStorageIndex ?: "none"}"
                },
                query(device, "READ_CAPABILITY", RockchipReadOnlyOperation.READ_CAPABILITY) { data ->
                    RockchipMetadataParsers.parseCapability(data).rawHex
                },
            ),
        )

    private suspend fun query(
        device: UsbDeviceDescriptor,
        name: String,
        operation: RockchipReadOnlyOperation,
        render: (ByteArray) -> String,
    ): RockchipMetadataProbeEntry {
        val transport = try {
            factory.open(device)
        } catch (error: RuntimeException) {
            return RockchipMetadataProbeEntry(name, false, error.safeMessage())
        }
        val session = RockchipReadOnlySession(transport)
        return try {
            val result = session.query(operation, timeoutMillis = QUERY_TIMEOUT_MILLIS)
            RockchipMetadataProbeEntry(name, true, render(result.data))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RuntimeException) {
            RockchipMetadataProbeEntry(name, false, error.safeMessage())
        } finally {
            try {
                session.close()
            } catch (_: RuntimeException) {
                // The query result is more useful than a secondary cleanup failure. The Android IO
                // adapter still attempts releaseInterface and connection.close deterministically.
            }
        }
    }

    private fun Throwable.safeMessage(): String =
        message?.take(240)?.ifBlank { null } ?: javaClass.simpleName

    private companion object {
        const val QUERY_TIMEOUT_MILLIS = 5_000L
    }
}
