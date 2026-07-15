package org.rockservice.core.usb

import org.junit.Test

class UsbDeviceDescriptorTest {
    @Test(expected = IllegalArgumentException::class)
    fun `rejects vendor id below usb range`() {
        UsbDeviceDescriptor(-1, 0, null, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects vendor id above usb range`() {
        UsbDeviceDescriptor(0x1_0000, 0, null, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects product id below usb range`() {
        UsbDeviceDescriptor(0, -1, null, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects product id above usb range`() {
        UsbDeviceDescriptor(0, 0x1_0000, null, null)
    }
}
