package org.rockservice.core.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RockchipUsbClassifierTest {
    @Test
    fun `identifies Rockchip vendor id conservatively`() {
        val result = RockchipUsbClassifier.identify(
            UsbDeviceDescriptor(
                vendorId = 0x2207,
                productId = 0x330C,
                manufacturer = "Rockchip",
                product = "USB device",
                transportId = "usb-host://rockchip",
            )
        )

        assertTrue(result.isRockchipVendor)
    }

    @Test
    fun `does not classify another vendor as Rockchip`() {
        val result = RockchipUsbClassifier.identify(
            UsbDeviceDescriptor(
                vendorId = 0x18D1,
                productId = 0x4EE7,
                manufacturer = "Other",
                product = "USB device",
                transportId = "usb-host://other",
            )
        )

        assertFalse(result.isRockchipVendor)
    }
}
