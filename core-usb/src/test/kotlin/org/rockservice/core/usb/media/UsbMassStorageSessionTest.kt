package org.rockservice.core.usb.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UsbMassStorageSessionTest {
    @Test
    fun `reads block geometry from READ CAPACITY 10`() {
        var capturedCommand = byteArrayOf()
        var capturedExpectedBytes = 0
        val session = UsbMassStorageSession { command, expectedBytes ->
            capturedCommand = command
            capturedExpectedBytes = expectedBytes
            byteArrayOf(
                0x00, 0x00, 0x07, 0xff.toByte(),
                0x00, 0x00, 0x02, 0x00,
            )
        }

        val geometry = session.readGeometry()

        assertContentEquals(
            byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            capturedCommand,
        )
        assertEquals(8, capturedExpectedBytes)
        assertEquals(512, geometry.blockSizeBytes)
        assertEquals(2048L, geometry.blockCount)
        assertEquals(1_048_576L, geometry.capacityBytes)
    }

    @Test
    fun `rejects truncated READ CAPACITY response`() {
        val session = UsbMassStorageSession { _, _ -> ByteArray(7) }

        assertFailsWith<IllegalArgumentException> {
            session.readGeometry()
        }
    }

    @Test
    fun `rejects zero block size`() {
        val session = UsbMassStorageSession { _, _ ->
            byteArrayOf(
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            session.readGeometry()
        }
    }

    @Test
    fun `requires READ CAPACITY 16 for large devices`() {
        val session = UsbMassStorageSession { _, _ ->
            byteArrayOf(
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
                0x00, 0x00, 0x02, 0x00,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            session.readGeometry()
        }
    }
}
