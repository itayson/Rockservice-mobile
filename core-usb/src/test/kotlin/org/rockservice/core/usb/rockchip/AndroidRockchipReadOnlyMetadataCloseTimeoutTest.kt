package org.rockservice.core.usb.rockchip

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rockservice.core.usb.UsbDeviceDescriptor

class AndroidRockchipReadOnlyMetadataCloseTimeoutTest {
    @Test
    fun `close timeout does not block a completed probe`() = runTest {
        val transport = SlowCloseTransport()
        val client = AndroidRockchipReadOnlyMetadataClient(
            opener = RockchipReadOnlyTransportOpener { transport },
            transportMethod = RockchipUsbIoMethod.USB_REQUEST,
        )

        val report = client.probe(testDevice())

        assertTrue(report.entries.all(RockchipMetadataProbeEntry::succeeded))
        assertTrue(report.requiresReconnect)
        assertEquals(1, transport.closeCount)
    }

    private fun testDevice(): UsbDeviceDescriptor = UsbDeviceDescriptor(
        vendorId = 0x2207,
        productId = 0x320B,
        manufacturer = null,
        product = null,
        transportId = "/dev/bus/usb/test",
        hasPermission = true,
    )

    private class SlowCloseTransport : RockchipReadOnlyTransport {
        var closeCount = 0

        override suspend fun exchange(
            command: ByteArray,
            responseLengthRange: IntRange,
            timeoutMillis: Long,
        ): RockchipRawExchange {
            val tag = ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
            return RockchipRawExchange(
                data = ByteArray(responseLengthRange.first),
                statusBytes = successfulCsw(tag),
            )
        }

        override suspend fun close() {
            closeCount += 1
            delay(3_000L)
        }

        private fun successfulCsw(tag: Int): ByteArray =
            ByteArray(RockchipReadOnlyProtocolCodec.COMMAND_STATUS_WRAPPER_SIZE).also { bytes ->
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                buffer.putInt(0, 0x53425355)
                buffer.putInt(4, tag)
                buffer.putInt(8, 0)
                bytes[12] = 0
            }
    }
}
