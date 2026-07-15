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
}
