package org.rockservice.core.usb

enum class UsbEndpointDirection { IN, OUT }

enum class UsbTransferType { CONTROL, ISOCHRONOUS, BULK, INTERRUPT, UNKNOWN }

data class UsbEndpointDescriptor(
    val address: Int,
    val direction: UsbEndpointDirection,
    val transferType: UsbTransferType,
    val maxPacketSize: Int,
    val interval: Int,
) {
    init {
        require(address in 0x00..0xFF) { "Endpoint address must be in 0x00..0xFF." }
        require(maxPacketSize in 0..0xFFFF) { "Endpoint maxPacketSize must be in 0..65535." }
        require(interval in 0..0xFF) { "Endpoint interval must be in 0..255." }
    }
}

data class UsbInterfaceDescriptor(
    val id: Int,
    val alternateSetting: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<UsbEndpointDescriptor>,
) {
    init {
        require(id in 0..0xFF) { "Interface id must be in 0..255." }
        require(alternateSetting in 0..0xFF) { "Alternate setting must be in 0..255." }
        require(interfaceClass in 0..0xFF) { "Interface class must be in 0..255." }
        require(interfaceSubclass in 0..0xFF) { "Interface subclass must be in 0..255." }
        require(interfaceProtocol in 0..0xFF) { "Interface protocol must be in 0..255." }
    }
}

data class UsbDeviceTopology(
    val transportId: String,
    val interfaces: List<UsbInterfaceDescriptor>,
) {
    init {
        require(transportId.isNotBlank()) { "transportId must not be blank." }
    }

    val endpoints: List<UsbEndpointDescriptor>
        get() = interfaces.flatMap(UsbInterfaceDescriptor::endpoints)

    fun hasBulkEndpoint(direction: UsbEndpointDirection): Boolean =
        endpoints.any { endpoint ->
            endpoint.transferType == UsbTransferType.BULK && endpoint.direction == direction
        }

    fun hasBidirectionalBulkPair(): Boolean =
        hasBulkEndpoint(UsbEndpointDirection.IN) && hasBulkEndpoint(UsbEndpointDirection.OUT)
}
