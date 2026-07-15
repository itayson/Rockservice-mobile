package org.rockservice.core.usb

data class UsbDeviceDescriptor(
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String?,
    val product: String?,
    val serialNumber: String? = null,
)

enum class UsbBackendKind { SIMULATED, ANDROID_HOST }

sealed interface UsbOperation {
    data object Enumerate : UsbOperation
    data class Read(val offset: Long, val length: Int) : UsbOperation
}

interface UsbBackend {
    val kind: UsbBackendKind
    suspend fun listDevices(): List<UsbDeviceDescriptor>
    suspend fun read(device: UsbDeviceDescriptor, offset: Long, length: Int): ByteArray
}
