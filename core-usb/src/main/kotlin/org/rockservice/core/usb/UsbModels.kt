package org.rockservice.core.usb

data class UsbDeviceDescriptor(
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String?,
    val product: String?,
    val serialNumber: String? = null,
    val transportId: String? = null,
    val deviceClass: Int? = null,
    val deviceSubclass: Int? = null,
    val deviceProtocol: Int? = null,
    val hasPermission: Boolean = false,
) {
    init {
        require(vendorId in 0x0000..0xFFFF) { "vendorId must be in 0x0000..0xFFFF." }
        require(productId in 0x0000..0xFFFF) { "productId must be in 0x0000..0xFFFF." }
        require(transportId == null || transportId.isNotBlank()) { "transportId must not be blank." }
        require(deviceClass == null || deviceClass in 0x00..0xFF) {
            "deviceClass must be in 0x00..0xFF."
        }
        require(deviceSubclass == null || deviceSubclass in 0x00..0xFF) {
            "deviceSubclass must be in 0x00..0xFF."
        }
        require(deviceProtocol == null || deviceProtocol in 0x00..0xFF) {
            "deviceProtocol must be in 0x00..0xFF."
        }
    }
}

enum class UsbBackendKind { SIMULATED, ANDROID_HOST }

sealed interface UsbOperation {
    data object Enumerate : UsbOperation
    data class Read(val offset: Long, val length: Int) : UsbOperation
}

interface UsbBackend {
    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 5_000L
    }

    val kind: UsbBackendKind

    suspend fun listDevices(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): List<UsbDeviceDescriptor>

    suspend fun read(
        device: UsbDeviceDescriptor,
        offset: Long,
        length: Int,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): ByteArray

    /**
     * Releases backend resources. Implementations must make this operation idempotent.
     * Calls made after close must fail rather than silently reopening a transport.
     */
    suspend fun close()
}
