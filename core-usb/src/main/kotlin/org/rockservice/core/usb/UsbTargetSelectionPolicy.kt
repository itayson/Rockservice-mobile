package org.rockservice.core.usb

object UsbTargetSelectionPolicy {
    fun select(
        candidate: UsbDeviceDescriptor,
        devices: List<UsbDeviceDescriptor>,
    ): String {
        val transportId = requireNotNull(candidate.transportId) {
            "USB target selection requires a transportId from a fresh enumeration."
        }
        require(devices.count { device -> device.transportId == transportId } == 1) {
            "USB target must exist exactly once in the current enumeration."
        }
        return transportId
    }

    fun reconcile(
        selectedTransportId: String?,
        devices: List<UsbDeviceDescriptor>,
    ): String? {
        if (selectedTransportId == null) return null
        return selectedTransportId.takeIf { transportId ->
            devices.count { device -> device.transportId == transportId } == 1
        }
    }
}
