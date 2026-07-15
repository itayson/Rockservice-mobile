package org.rockservice.core.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbHardwareValidationReportTest {
    @Test
    fun `report excludes serial number and transport identity`() {
        val snapshot = UsbDiagnosticsDeviceSnapshot(
            descriptor = UsbDeviceDescriptor(
                vendorId = 0x2207,
                productId = 0x330C,
                manufacturer = "Rockchip",
                product = "USB Device",
                serialNumber = "SENSITIVE-SERIAL-123",
                transportId = "/dev/bus/usb/001/002",
                deviceClass = 0,
                deviceSubclass = 0,
                deviceProtocol = 0,
                hasPermission = false,
            ),
            topology = UsbDeviceTopology(
                transportId = "/dev/bus/usb/001/002",
                interfaces = listOf(
                    UsbInterfaceDescriptor(
                        id = 0,
                        alternateSetting = 0,
                        interfaceClass = 0xFF,
                        interfaceSubclass = 0x06,
                        interfaceProtocol = 0x05,
                        endpoints = listOf(
                            UsbEndpointDescriptor(0x81, UsbEndpointDirection.IN, UsbTransferType.BULK, 512, 0),
                            UsbEndpointDescriptor(0x02, UsbEndpointDirection.OUT, UsbTransferType.BULK, 512, 0),
                        ),
                    ),
                ),
            ),
            rockchipProbe = RockchipPassiveProbeResult(
                level = RockchipPassiveProbeLevel.ROCKCHIP_BULK_TRANSPORT_CANDIDATE,
                vendorId = 0x2207,
                productId = 0x330C,
                transportId = "/dev/bus/usb/001/002",
                hasBulkIn = true,
                hasBulkOut = true,
                interfaceCount = 1,
            ),
        )

        val report = UsbHardwareValidationReport(
            generatedAtEpochMillis = 0L,
            host = UsbHardwareValidationHostInfo("Xiaomi", "Redmi", "12", 31),
            notes = UsbHardwareValidationNotes("TV Box teste", "RK3566", "USB-C OTG"),
            device = UsbHardwareValidationDevice.from(snapshot),
            descriptorCheck = UsbHardwareValidationDescriptorCheck(
                succeeded = true,
                bytesRead = 18,
                sha256 = "a".repeat(64),
                detail = "Leitura limitada concluida.",
            ),
            events = listOf(UsbHardwareValidationEvent(UsbAttachmentEventKind.ATTACHED, 0L)),
        )

        val text = report.toPlainText()

        assertTrue(text.contains("2207:330C"))
        assertTrue(text.contains("ROCKCHIP_BULK_TRANSPORT_CANDIDATE"))
        assertTrue(text.contains("endpoint 0x81 IN BULK"))
        assertFalse(text.contains("SENSITIVE-SERIAL-123"))
        assertFalse(text.contains("/dev/bus/usb/001/002"))
    }

    @Test
    fun `descriptor check rejects malformed digest`() {
        try {
            UsbHardwareValidationDescriptorCheck(
                succeeded = true,
                bytesRead = 1,
                sha256 = "not-a-sha256",
                detail = "invalid",
            )
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
