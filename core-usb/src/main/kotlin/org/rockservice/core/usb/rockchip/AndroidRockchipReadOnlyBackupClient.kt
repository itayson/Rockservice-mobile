package org.rockservice.core.usb.rockchip

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.rockservice.core.usb.UsbDeviceDescriptor

/**
 * Android bridge for one explicitly requested, bounded, read-only Rockchip backup.
 *
 * The caller owns destination selection and persistence. This client opens exactly one validated
 * Rockchip transport, keeps one session for the whole bounded range, and closes it before returning.
 */
class AndroidRockchipReadOnlyBackupClient(
    context: Context,
    private val maximumSectorCount: Long = RockchipBoundedBackupEngine.DEFAULT_MAXIMUM_SECTOR_COUNT,
) {
    private val factory = AndroidRockchipReadOnlyTransportFactory(context.applicationContext)

    init {
        require(maximumSectorCount in 1L..RockchipBoundedBackupEngine.HARD_MAXIMUM_SECTOR_COUNT) {
            "maximumSectorCount must be between 1 and ${RockchipBoundedBackupEngine.HARD_MAXIMUM_SECTOR_COUNT}."
        }
    }

    suspend fun backup(
        device: UsbDeviceDescriptor,
        startSector: Long,
        sectorCount: Long,
        timeoutMillis: Long = RockchipBoundedBackupEngine.DEFAULT_TIMEOUT_MILLIS,
        onData: suspend (ByteArray) -> Unit,
        onProgress: suspend (RockchipBoundedBackupProgress) -> Unit = {},
    ): RockchipBoundedBackupResult {
        val transport = factory.open(device, RockchipUsbIoMethod.USB_REQUEST)
        val session = RockchipReadOnlySession(transport)
        var primaryFailure: Throwable? = null
        try {
            return RockchipBoundedBackupEngine(maximumSectorCount = maximumSectorCount).backup(
                session = session,
                startSector = startSector,
                sectorCount = sectorCount,
                timeoutMillis = timeoutMillis,
                onData = onData,
                onProgress = onProgress,
            )
        } catch (cancelled: CancellationException) {
            primaryFailure = cancelled
            throw cancelled
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                withContext(NonCancellable) { session.close() }
            } catch (closeFailure: Throwable) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(closeFailure)
                } else {
                    throw closeFailure
                }
            }
        }
    }
}
