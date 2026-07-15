package org.rockservice.core.usb

enum class RockchipPassiveProbeLevel {
    NOT_ROCKCHIP,
    ROCKCHIP_VENDOR_ONLY,
    ROCKCHIP_BULK_TRANSPORT_CANDIDATE,
}

data class RockchipPassiveProbeResult(
    val level: RockchipPassiveProbeLevel,
    val vendorId: Int,
    val productId: Int,
    val transportId: String?,
    val hasBulkIn: Boolean,
    val hasBulkOut: Boolean,
    val interfaceCount: Int,
) {
    val isRockchipVendor: Boolean
        get() = level != RockchipPassiveProbeLevel.NOT_ROCKCHIP

    val isBidirectionalBulkCandidate: Boolean
        get() = level == RockchipPassiveProbeLevel.ROCKCHIP_BULK_TRANSPORT_CANDIDATE
}

object RockchipPassiveProbe {
    /**
     * Produces a passive transport hint from USB descriptors only.
     *
     * This does not identify Loader or Maskrom mode and sends no USB transfer to the device.
     */
    fun probe(
        device: UsbDeviceDescriptor,
        topology: UsbDeviceTopology,
    ): RockchipPassiveProbeResult {
        val transportId = requireNotNull(device.transportId) {
            "Passive Rockchip probing requires a transportId from a fresh USB enumeration."
        }
        require(topology.transportId == transportId) {
            "USB topology belongs to a different transport target."
        }

        val isRockchip = device.vendorId == RockchipUsbClassifier.ROCKCHIP_VENDOR_ID
        val hasBulkIn = topology.hasBulkEndpoint(UsbEndpointDirection.IN)
        val hasBulkOut = topology.hasBulkEndpoint(UsbEndpointDirection.OUT)
        val level = when {
            !isRockchip -> RockchipPassiveProbeLevel.NOT_ROCKCHIP
            hasBulkIn && hasBulkOut -> RockchipPassiveProbeLevel.ROCKCHIP_BULK_TRANSPORT_CANDIDATE
            else -> RockchipPassiveProbeLevel.ROCKCHIP_VENDOR_ONLY
        }

        return RockchipPassiveProbeResult(
            level = level,
            vendorId = device.vendorId,
            productId = device.productId,
            transportId = device.transportId,
            hasBulkIn = hasBulkIn,
            hasBulkOut = hasBulkOut,
            interfaceCount = topology.interfaces.size,
        )
    }
}
