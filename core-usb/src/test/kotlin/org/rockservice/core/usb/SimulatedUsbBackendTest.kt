package org.rockservice.core.usb

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SimulatedUsbBackendTest {
    @Test
    fun `enumerates one simulated device`() = runTest {
        val backend = SimulatedUsbBackend()
        assertEquals(1, backend.listDevices().size)
        assertEquals(0x2207, backend.listDevices().single().vendorId)
    }

    @Test
    fun `read is deterministic`() = runTest {
        val backend = SimulatedUsbBackend()
        val device = backend.listDevices().single()
        assertArrayEquals(byteArrayOf(2, 3, 4), backend.read(device, 2, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unregistered device`() = runTest {
        val backend = SimulatedUsbBackend()
        val unknown = UsbDeviceDescriptor(1, 2, null, null)
        backend.read(unknown, 0, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects negative offset`() = runTest {
        val backend = SimulatedUsbBackend()
        backend.read(backend.listDevices().single(), -1, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects excessive length`() = runTest {
        val backend = SimulatedUsbBackend()
        backend.read(backend.listDevices().single(), 0, 1_048_577)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects overflowing range`() = runTest {
        val backend = SimulatedUsbBackend()
        backend.read(backend.listDevices().single(), Long.MAX_VALUE, 2)
    }
}
