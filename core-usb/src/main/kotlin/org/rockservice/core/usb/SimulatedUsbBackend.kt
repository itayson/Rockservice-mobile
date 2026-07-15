package org.rockservice.core.usb

class SimulatedUsbBackend(
    private val devices: List<UsbDeviceDescriptor> = listOf(
        UsbDeviceDescriptor(
            vendorId = 0x2207,
            productId = 0x330C,
            manufacturer = "Simulated Rockchip",
            product = "Loader-mode fixture",
        )
    ),
) : UsbBackend {
    override val kind = UsbBackendKind.SIMULATED

    override suspend fun listDevices(): List<UsbDeviceDescriptor> = devices

    override suspend fun read(
        device: UsbDeviceDescriptor,
        offset: Long,
        length: Int,
    ): ByteArray {
        require(device in devices) { "Device is not registered in the simulator." }
        require(offset >= 0) { "Offset must be non-negative." }
        require(length in 0..1_048_576) { "Read length exceeds the simulator safety limit." }
        require(length == 0 || offset <= Long.MAX_VALUE - (length - 1).toLong()) {
            "Read range overflows."
        }
        return ByteArray(length) { index -> ((offset + index) and 0xff).toByte() }
    }
}
