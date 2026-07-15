package org.rockservice.core.usb.rockchip

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rockservice.core.usb.UsbDeviceDescriptor

class AndroidRockchipReadOnlyMetadataCloseTimeoutTest {
    @Test
    fun `blocking close is isolated and cannot block a completed probe`() = runTest {
        val transport = BlockingCloseTransport()
        val client = AndroidRockchipReadOnlyMetadataClient(
            opener = RockchipReadOnlyTransportOpener { transport },
            transportMethod = RockchipUsbIoMethod.USB_REQUEST,
            closeTimeoutMillis = 100L,
        )

        val report = client.probe(testDevice())

        assertEquals(
            listOf("TEST_UNIT_READY", "READ_CHIP_INFO", "READ_FLASH_ID", "READ_FLASH_INFO"),
            report.entries.map(RockchipMetadataProbeEntry::name),
        )
        assertTrue(report.entries.all(RockchipMetadataProbeEntry::succeeded))
        assertTrue(report.requiresReconnect)
        assertEquals(1, transport.closeCount)
        assertTrue(transport.closeInterrupted.await(1, TimeUnit.SECONDS))
    }

    private fun testDevice(): UsbDeviceDescriptor = UsbDeviceDescriptor(
        vendorId = 0x2207,
        productId = 0x320B,
        manufacturer = null,
        product = null,
        transportId = "/dev/bus/usb/test",
        hasPermission = true,
    )

    private class BlockingCloseTransport : RockchipReadOnlyTransport {
        var closeCount = 0
        val closeInterrupted = CountDownLatch(1)
        private val neverReleased = CountDownLatch(1)

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
            try {
                neverReleased.await()
            } catch (interrupted: InterruptedException) {
                closeInterrupted.countDown()
                throw interrupted
            }
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
