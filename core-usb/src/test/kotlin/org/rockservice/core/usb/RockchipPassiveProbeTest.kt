package org.rockservice.core.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RockchipPassiveProbeTest {
    @Test
    fun `marks Rockchip device with bulk pair as transport candidate`() {
        val result = RockchipPassiveProbe.probe(
            device = rockchipDevice(),
            topology = topology(
                UsbEndpointDirection.IN,
                UsbEndpointDirection.OUT,
            ),
        )

        assertEquals(
            RockchipPassiveProbeLevel.ROCKCHIP_BULK_TRANSPORT_CANDIDATE,
            result.level,
        )
        assertTrue(result.isRockchipVendor)
        assertTrue(result.isBidirectionalBulkCandidate)
    }

    @Test
    fun `does not infer transport candidate without both bulk directions`() {
        val result = RockchipPassiveProbe.probe(
            device = rockchipDevice(),
            topology = topology(UsbEndpointDirection.IN),
        )

        assertEquals(RockchipPassiveProbeLevel.ROCKCHIP_VENDOR_ONLY, result.level)
        assertFalse(result.isBidirectionalBulkCandidate)
    }

    @Test
    fun `does not classify another vendor as Rockchip`() {
        val device = rockchipDevice().copy(vendorId = 0x18D1)
        val result = RockchipPassiveProbe.probe(
            device = device,
            topology = topology(
                UsbEndpointDirection.IN,
                UsbEndpointDirection.OUT,
            ),
        )

        assertEquals(RockchipPassiveProbeLevel.NOT_ROCKCHIP, result.level)
        assertFalse(result.isRockchipVendor)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects topology from another transport target`() {
        RockchipPassiveProbe.probe(
            device = rockchipDevice(),
            topology = topology(UsbEndpointDirection.IN).copy(transportId = "usb-host://other"),
        )
    }

    private fun rockchipDevice(): UsbDeviceDescriptor =
        UsbDeviceDescriptor(
            vendorId = 0x2207,
            productId = 0x330C,
            manufacturer = "Rockchip",
            product = "USB fixture",
            transportId = "usb-host://1",
        )

    private fun topology(vararg directions: UsbEndpointDirection): UsbDeviceTopology =
        UsbDeviceTopology(
            transportId = "usb-host://1",
            interfaces = listOf(
                UsbInterfaceDescriptor(
                    id = 0,
                    alternateSetting = 0,
                    interfaceClass = 0xFF,
                    interfaceSubclass = 0,
                    interfaceProtocol = 0,
                    endpoints = directions.mapIndexed { index, direction ->
                        UsbEndpointDescriptor(
                            address = if (direction == UsbEndpointDirection.IN) {
                                0x81 + index
                            } else {
                                0x01 + index
                            },
                            direction = direction,
                            transferType = UsbTransferType.BULK,
                            maxPacketSize = 512,
                            interval = 0,
                        )
                    },
                )
            ),
        )
}
