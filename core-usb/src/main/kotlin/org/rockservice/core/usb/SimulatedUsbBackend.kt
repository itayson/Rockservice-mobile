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
        require(offset >= 0) { "Offset must be non-negative." }
        require(length in 0..1_048_576) { "Read length exceeds the simulator safety limit." }
        return ByteArray(length) { index -> ((offset + index) and 0xff).toByte() }
    }
}
