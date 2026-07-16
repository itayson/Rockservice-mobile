package org.rockservice.core.usb

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUsbHostPermissionTest {
    @Test
    fun `explicit permission request returns freshly permitted descriptor`() = runTest {
        val platform = PermissionPlatform(initialPermission = false)
        val backend = AndroidUsbHostBackend(platform)
        val device = backend.listDevices().single()

        val permitted = backend.requestPermission(device)

        assertTrue(permitted.hasPermission)
        assertEquals(device.transportId, permitted.transportId)
        assertEquals(1, platform.permissionRequests)
        assertEquals(0, platform.rawDescriptorReads)
    }

    @Test
    fun `already permitted target does not request permission again`() = runTest {
        val platform = PermissionPlatform(initialPermission = true)
        val backend = AndroidUsbHostBackend(platform)

        val permitted = backend.requestPermission(backend.listDevices().single())

        assertTrue(permitted.hasPermission)
        assertEquals(0, platform.permissionRequests)
    }

    @Test(expected = IllegalStateException::class)
    fun `explicit permission request fails when user denies`() = runTest {
        val platform = PermissionPlatform(initialPermission = false, grantPermissionOnRequest = false)
        val backend = AndroidUsbHostBackend(platform)

        backend.requestPermission(backend.listDevices().single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `explicit permission revalidates identity after grant`() = runTest {
        val platform = PermissionPlatform(initialPermission = false, swapIdentityAfterPermission = true)
        val backend = AndroidUsbHostBackend(platform)

        backend.requestPermission(backend.listDevices().single())
    }

    private class PermissionPlatform(
        initialPermission: Boolean,
        private val grantPermissionOnRequest: Boolean = true,
        private val swapIdentityAfterPermission: Boolean = false,
    ) : UsbHostPlatform {
        var permissionGranted = initialPermission
        var permissionRequests = 0
        var rawDescriptorReads = 0
        private var identitySwapped = false

        private val device = UsbHostDeviceSnapshot(
            transportId = "usb-host://adb",
            vendorId = 0x18D1,
            productId = 0x4EE7,
            manufacturer = "Android",
            product = "ADB fixture",
            serialNumber = null,
            deviceClass = 0,
            deviceSubclass = 0,
            deviceProtocol = 0,
            hasPermission = initialPermission,
        )

        override suspend fun listDevices(): List<UsbHostDeviceSnapshot> = listOf(
            device.copy(
                productId = if (identitySwapped) 0x0001 else device.productId,
                hasPermission = permissionGranted,
            ),
        )

        override suspend fun inspectTopology(transportId: String): UsbDeviceTopology =
            UsbDeviceTopology(transportId = transportId, interfaces = emptyList())

        override suspend fun requestPermission(transportId: String): Boolean {
            permissionRequests += 1
            permissionGranted = grantPermissionOnRequest
            if (permissionGranted && swapIdentityAfterPermission) identitySwapped = true
            return permissionGranted
        }

        override suspend fun readRawDescriptors(transportId: String): ByteArray {
            rawDescriptorReads += 1
            return ByteArray(0)
        }

        override suspend fun close() = Unit
    }
}
