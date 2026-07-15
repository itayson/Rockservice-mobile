package org.rockservice.core.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbTargetSelectionPolicyTest {
    @Test
    fun `selects a uniquely enumerated transport target`() {
        val device = device("usb-host://1")

        assertEquals(
            "usb-host://1",
            UsbTargetSelectionPolicy.select(device, listOf(device)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects candidate that is no longer enumerated`() {
        UsbTargetSelectionPolicy.select(
            candidate = device("usb-host://1"),
            devices = listOf(device("usb-host://2")),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects ambiguous duplicate transport targets`() {
        val candidate = device("usb-host://1")
        UsbTargetSelectionPolicy.select(candidate, listOf(candidate, candidate.copy()))
    }

    @Test
    fun `reconcile clears a detached target`() {
        assertNull(
            UsbTargetSelectionPolicy.reconcile(
                selectedTransportId = "usb-host://1",
                devices = listOf(device("usb-host://2")),
            )
        )
    }

    @Test
    fun `reconcile keeps a unique attached target`() {
        assertEquals(
            "usb-host://1",
            UsbTargetSelectionPolicy.reconcile(
                selectedTransportId = "usb-host://1",
                devices = listOf(device("usb-host://1")),
            ),
        )
    }

    private fun device(transportId: String): UsbDeviceDescriptor =
        UsbDeviceDescriptor(
            vendorId = 0x2207,
            productId = 0x330C,
            manufacturer = "Rockchip",
            product = "Fixture",
            transportId = transportId,
        )
}
