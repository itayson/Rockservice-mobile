package org.rockservice.core.usb.media

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/** Descriptor for one USB Mass Storage bulk-only interface discovered without opening it. */
data class UsbMassStorageCandidate(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val interfaceId: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val hasPermission: Boolean,
)

/** Read-only discovery of USB Mass Storage BOT/SCSI interfaces suitable for future media creation. */
object AndroidUsbMassStorageDiscovery {
    private const val MASS_STORAGE_SCSI_SUBCLASS = 0x06
    private const val MASS_STORAGE_BULK_ONLY_PROTOCOL = 0x50

    fun discover(context: Context): List<UsbMassStorageCandidate> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.deviceList.values
            .flatMap { device -> device.massStorageInterfaces(usbManager) }
            .sortedWith(compareBy(UsbMassStorageCandidate::vendorId, UsbMassStorageCandidate::productId, UsbMassStorageCandidate::interfaceId))
    }

    private fun UsbDevice.massStorageInterfaces(usbManager: UsbManager): List<UsbMassStorageCandidate> {
        return buildList {
            for (index in 0 until interfaceCount) {
                val usbInterface = getInterface(index)
                if (!usbInterface.isSupportedMassStorageInterface()) continue
                add(
                    UsbMassStorageCandidate(
                        deviceId = deviceId,
                        vendorId = vendorId,
                        productId = productId,
                        productName = runCatching { productName }.getOrNull()?.takeIf(String::isNotBlank),
                        interfaceId = usbInterface.id,
                        interfaceSubclass = usbInterface.interfaceSubclass,
                        interfaceProtocol = usbInterface.interfaceProtocol,
                        hasPermission = usbManager.hasPermission(this@massStorageInterfaces),
                    ),
                )
            }
        }
    }

    private fun UsbInterface.isSupportedMassStorageInterface(): Boolean {
        if (interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE) return false
        if (interfaceSubclass != MASS_STORAGE_SCSI_SUBCLASS) return false
        if (interfaceProtocol != MASS_STORAGE_BULK_ONLY_PROTOCOL) return false
        if (endpointCount != 2) return false

        var hasBulkIn = false
        var hasBulkOut = false
        for (index in 0 until endpointCount) {
            val endpoint = getEndpoint(index)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            when (endpoint.direction) {
                UsbConstants.USB_DIR_IN -> hasBulkIn = true
                UsbConstants.USB_DIR_OUT -> hasBulkOut = true
            }
        }
        return hasBulkIn && hasBulkOut
    }
}
