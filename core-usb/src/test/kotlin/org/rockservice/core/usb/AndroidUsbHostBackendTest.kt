package org.rockservice.core.usb

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUsbHostBackendTest {
    @Test
    fun `enumerates Android host metadata without requesting permission`() = runTest {
        val platform = FakeUsbHostPlatform(initialPermission = false)
        val backend = AndroidUsbHostBackend(platform)

        val device = backend.listDevices().single()

        assertEquals("usb-host://1", device.transportId)
        assertEquals(0x2207, device.vendorId)
        assertEquals(0x330C, device.productId)
        assertFalse(device.hasPermission)
        assertEquals(0, platform.permissionRequests)
    }

    @Test
    fun `topology inspection is passive and returns endpoint metadata`() = runTest {
        val platform = FakeUsbHostPlatform(initialPermission = false)
        val backend = AndroidUsbHostBackend(platform)
        val device = backend.listDevices().single()

        val topology = backend.inspectTopology(device)

        assertEquals("usb-host://1", topology.transportId)
        assertTrue(topology.hasBidirectionalBulkPair())
        assertEquals(0, platform.permissionRequests)
    }

    @Test
    fun `read requests permission and returns bounded raw descriptor slice`() = runTest {
        val platform = FakeUsbHostPlatform(initialPermission = false)
        val backend = AndroidUsbHostBackend(platform)
        val device = backend.listDevices().single()

        val result = backend.read(device, offset = 1, length = 3)

        assertArrayEquals(byteArrayOf(11, 12, 13), result)
        assertEquals(1, platform.permissionRequests)
        assertTrue(platform.permissionGranted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `read rejects target identity changes after enumeration`() = runTest {
        val platform = FakeUsbHostPlatform(initialPermission = true)
        val backend = AndroidUsbHostBackend(platform)
        val forged = backend.listDevices().single().copy(productId = 0x0001)

        backend.read(forged, offset = 0, length = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `read revalidates identity after permission before opening connection`() = runTest {
        val platform = FakeUsbHostPlatform(
            initialPermission = false,
            swapIdentityAfterPermission = true,
        )
        val backend = AndroidUsbHostBackend(platform)
        val device = backend.listDevices().single()

        backend.read(device, offset = 0, length = 1)
    }

    @Test(expected = IllegalStateException::class)
    fun `read fails when permission is denied`() = runTest {
        val platform = FakeUsbHostPlatform(
            initialPermission = false,
            grantPermissionOnRequest = false,
        )
        val backend = AndroidUsbHostBackend(platform)
        val device = backend.listDevices().single()

        backend.read(device, offset = 0, length = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `read requires transport id from fresh enumeration`() = runTest {
        val platform = FakeUsbHostPlatform(initialPermission = true)
        val backend = AndroidUsbHostBackend(platform)
        val device = backend.listDevices().single().copy(transportId = null)

        backend.read(device, offset = 0, length = 1)
    }

    @Test
    fun `read past raw descriptors returns empty array`() = runTest {
        val platform = FakeUsbHostPlatform(initialPermission = true)
        val backend = AndroidUsbHostBackend(platform)
        val device = backend.listDevices().single()

        assertArrayEquals(ByteArray(0), backend.read(device, offset = 100, length = 4))
    }

    @Test(expected = IllegalStateException::class)
    fun `operations fail after close`() = runTest {
        val platform = FakeUsbHostPlatform(initialPermission = true)
        val backend = AndroidUsbHostBackend(platform)

        backend.close()
        assertTrue(platform.closed)
        backend.listDevices()
    }

    private class FakeUsbHostPlatform(
        initialPermission: Boolean,
        private val grantPermissionOnRequest: Boolean = true,
        private val swapIdentityAfterPermission: Boolean = false,
    ) : UsbHostPlatform {
        var permissionGranted: Boolean = initialPermission
        var permissionRequests: Int = 0
        var closed: Boolean = false
        private var identitySwapped: Boolean = false

        private val device = UsbHostDeviceSnapshot(
            transportId = "usb-host://1",
            vendorId = 0x2207,
            productId = 0x330C,
            manufacturer = "Rockchip",
            product = "USB fixture",
            serialNumber = null,
            deviceClass = 0,
            deviceSubclass = 0,
            deviceProtocol = 0,
            hasPermission = initialPermission,
        )

        override suspend fun listDevices(): List<UsbHostDeviceSnapshot> =
            listOf(
                device.copy(
                    productId = if (identitySwapped) 0x0001 else device.productId,
                    hasPermission = permissionGranted,
                )
            )

        override suspend fun inspectTopology(transportId: String): UsbDeviceTopology {
            require(transportId == device.transportId)
            return UsbDeviceTopology(
                transportId = transportId,
                interfaces = listOf(
                    UsbInterfaceDescriptor(
                        id = 0,
                        alternateSetting = 0,
                        interfaceClass = 0xFF,
                        interfaceSubclass = 0,
                        interfaceProtocol = 0,
                        endpoints = listOf(
                            UsbEndpointDescriptor(
                                address = 0x81,
                                direction = UsbEndpointDirection.IN,
                                transferType = UsbTransferType.BULK,
                                maxPacketSize = 512,
                                interval = 0,
                            ),
                            UsbEndpointDescriptor(
                                address = 0x01,
                                direction = UsbEndpointDirection.OUT,
                                transferType = UsbTransferType.BULK,
                                maxPacketSize = 512,
                                interval = 0,
                            ),
                        ),
                    )
                ),
            )
        }

        override suspend fun requestPermission(transportId: String): Boolean {
            require(transportId == device.transportId)
            permissionRequests += 1
            permissionGranted = grantPermissionOnRequest
            if (permissionGranted && swapIdentityAfterPermission) {
                identitySwapped = true
            }
            return permissionGranted
        }

        override suspend fun readRawDescriptors(transportId: String): ByteArray {
            check(permissionGranted)
            require(transportId == device.transportId)
            return byteArrayOf(10, 11, 12, 13, 14)
        }

        override suspend fun close() {
            closed = true
        }
    }
}
