package org.rockservice.core.usb.rockchip

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rockservice.core.usb.UsbDeviceDescriptor

class AndroidRockchipReadOnlyMetadataCloseTimeoutTest {
    @Test
    fun `blocking close keeps probe slot reserved until the worker exits`() = runTest {
        val transport = IgnoringInterruptCloseTransport()
        val firstClient = AndroidRockchipReadOnlyMetadataClient(
            opener = RockchipReadOnlyTransportOpener { transport },
            transportMethod = RockchipUsbIoMethod.USB_REQUEST,
            closeTimeoutMillis = 100L,
        )

        try {
            val firstReport = firstClient.probe(testDevice())
            assertEquals(
                listOf("TEST_UNIT_READY", "READ_CHIP_INFO", "READ_FLASH_ID", "READ_FLASH_INFO"),
                firstReport.entries.map(RockchipMetadataProbeEntry::name),
            )
            assertTrue(firstReport.entries.all(RockchipMetadataProbeEntry::succeeded))
            assertTrue(firstReport.requiresReconnect)
            assertTrue(transport.closeStarted.await(1, TimeUnit.SECONDS))

            val secondOpenCount = AtomicInteger(0)
            val secondClient = AndroidRockchipReadOnlyMetadataClient(
                opener = RockchipReadOnlyTransportOpener {
                    secondOpenCount.incrementAndGet()
                    throw AssertionError("Second transport opened before previous close finished.")
                },
                transportMethod = RockchipUsbIoMethod.USB_REQUEST,
                closeTimeoutMillis = 100L,
            )
            val secondReport = secondClient.probe(testDevice())

            assertEquals(0, secondOpenCount.get())
            assertTrue(secondReport.requiresReconnect)
            assertEquals(4, secondReport.entries.size)
            assertTrue(secondReport.entries.all { entry -> !entry.attempted })
        } finally {
            transport.releaseClose.countDown()
            assertTrue(transport.closeFinished.await(1, TimeUnit.SECONDS))
            assertEquals(1, transport.closeCount.get())
        }
    }

    private fun testDevice(): UsbDeviceDescriptor = UsbDeviceDescriptor(
        vendorId = 0x2207,
        productId = 0x320B,
        manufacturer = null,
        product = null,
        transportId = "/dev/bus/usb/test",
        hasPermission = true,
    )

    private class IgnoringInterruptCloseTransport : RockchipReadOnlyTransport {
        val closeCount = AtomicInteger(0)
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)

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
            closeCount.incrementAndGet()
            closeStarted.countDown()
            try {
                while (releaseClose.count > 0L) {
                    try {
                        releaseClose.await()
                    } catch (_: InterruptedException) {
                        // Keep waiting to emulate a synchronous driver close that ignores interruption.
                    }
                }
            } finally {
                closeFinished.countDown()
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
