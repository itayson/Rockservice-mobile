package org.rockservice.core.usb

data class RockchipUsbIdentification(
    val isRockchipVendor: Boolean,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int?,
    val deviceSubclass: Int?,
    val deviceProtocol: Int?,
    val transportId: String?,
    val hasPermission: Boolean,
)

object RockchipUsbClassifier {
    const val ROCKCHIP_VENDOR_ID: Int = 0x2207

    fun identify(device: UsbDeviceDescriptor): RockchipUsbIdentification =
        RockchipUsbIdentification(
            isRockchipVendor = device.vendorId == ROCKCHIP_VENDOR_ID,
            vendorId = device.vendorId,
            productId = device.productId,
            deviceClass = device.deviceClass,
            deviceSubclass = device.deviceSubclass,
            deviceProtocol = device.deviceProtocol,
            transportId = device.transportId,
            hasPermission = device.hasPermission,
        )
}
