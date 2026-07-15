package org.rockservice.core.usb.rockchip

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rockservice.core.usb.UsbDeviceDescriptor

class AndroidRockchipReadOnlyMetadataOpenFailureTest {
    @Test
    fun `open failure returns reconnect report and skips later queries`() = runTest {
        var openCount = 0
        val client = AndroidRockchipReadOnlyMetadataClient(
            opener = RockchipReadOnlyTransportOpener {
                openCount += 1
                throw IllegalStateException("synthetic open failure")
            },
            transportMethod = RockchipUsbIoMethod.USB_REQUEST,
        )

        val report = client.probe(testDevice())

        assertEquals(1, openCount)
        assertTrue(report.requiresReconnect)
        assertEquals(4, report.entries.size)
        assertFalse(report.entries.first().succeeded)
        assertTrue(report.entries.first().attempted)
        assertTrue(report.entries.drop(1).all { entry -> !entry.attempted })
    }

    private fun testDevice(): UsbDeviceDescriptor = UsbDeviceDescriptor(
        vendorId = 0x2207,
        productId = 0x320B,
        manufacturer = null,
        product = null,
        transportId = "/dev/bus/usb/test",
        hasPermission = true,
    )
}
