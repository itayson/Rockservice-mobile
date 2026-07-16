package org.rockservice.core.usb.rockchip

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rockservice.core.usb.UsbDeviceDescriptor

class AndroidRockchipReadOnlyMetadataClientTest {
    @Test
    fun `probe uses physically validated baseline operations in one session`() = runTest {
        val transport = RecordingTransport()
        var openCount = 0
        val client = AndroidRockchipReadOnlyMetadataClient(
            opener = RockchipReadOnlyTransportOpener {
                openCount += 1
                transport
            },
            transportMethod = RockchipUsbIoMethod.USB_REQUEST,
        )

        val report = client.probe(testDevice())

        assertEquals(1, openCount)
        assertEquals(listOf(0x00, 0x1B, 0x01, 0x1A), transport.opcodes)
        assertTrue(report.entries.all(RockchipMetadataProbeEntry::succeeded))
        assertFalse(report.requiresReconnect)
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun `transport failure stops remaining commands and requires reconnect`() = runTest {
        val transport = RecordingTransport(failOpcode = RockchipReadOnlyOperation.READ_FLASH_ID.opcode)
        val client = AndroidRockchipReadOnlyMetadataClient(
            opener = RockchipReadOnlyTransportOpener { transport },
            transportMethod = RockchipUsbIoMethod.USB_REQUEST,
        )

        val report = client.probe(testDevice())

        assertEquals(listOf(0x00, 0x1B, 0x01), transport.opcodes)
        assertTrue(report.requiresReconnect)
        assertTrue(report.entries[0].succeeded)
        assertTrue(report.entries[1].succeeded)
        assertFalse(report.entries[2].succeeded)
        assertTrue(report.entries[2].attempted)
        assertFalse(report.entries[3].attempted)
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun `partition header inspection reads exactly two sectors and returns sanitized evidence`() = runTest {
        val payload = ByteArray(RockchipPartitionHeaderInspector.EXPECTED_BYTES).also { data ->
            data[510] = 0x55
            data[511] = 0xAA.toByte()
            "EFI PART".encodeToByteArray().copyInto(data, destinationOffset = 512)
        }
        val transport = RecordingTransport(readLbaPayload = payload)
        var openCount = 0
        val client = AndroidRockchipReadOnlyMetadataClient(
            opener = RockchipReadOnlyTransportOpener {
                openCount += 1
                transport
            },
            transportMethod = RockchipUsbIoMethod.USB_REQUEST,
        )

        val report = client.inspectPartitionHeaders(testDevice())

        assertEquals(1, openCount)
        assertEquals(listOf(0x14), transport.opcodes)
        assertEquals(listOf(1024..1024), transport.responseLengthRanges)
        assertTrue(report.succeeded)
        assertEquals(0L, report.startSector)
        assertEquals(2, report.sectorCount)
        assertEquals(1024, report.bytesRead)
        assertEquals(true, report.hasMbrSignature)
        assertEquals(true, report.hasGptSignature)
        assertTrue(report.sha256?.matches(Regex("[0-9a-f]{64}")) == true)
        assertFalse(report.requiresReconnect)
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun `partition header inspection rejects truncated transport data and requires reconnect`() = runTest {
        val transport = RecordingTransport(
            readLbaPayload = ByteArray(RockchipPartitionHeaderInspector.EXPECTED_BYTES - 1),
        )
        val client = AndroidRockchipReadOnlyMetadataClient(
            opener = RockchipReadOnlyTransportOpener { transport },
            transportMethod = RockchipUsbIoMethod.USB_REQUEST,
        )

        val report = client.inspectPartitionHeaders(testDevice())

        assertFalse(report.succeeded)
        assertEquals(0, report.bytesRead)
        assertNull(report.sha256)
        assertNull(report.hasMbrSignature)
        assertNull(report.hasGptSignature)
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

    private class RecordingTransport(
        private val failOpcode: Int? = null,
        private val readLbaPayload: ByteArray? = null,
    ) : RockchipReadOnlyTransport {
        val opcodes = mutableListOf<Int>()
        val responseLengthRanges = mutableListOf<IntRange>()
        var closeCount = 0

        override suspend fun exchange(
            command: ByteArray,
            responseLengthRange: IntRange,
            timeoutMillis: Long,
        ): RockchipRawExchange {
            val opcode = command[15].toInt() and 0xFF
            opcodes += opcode
            responseLengthRanges += responseLengthRange
            if (opcode == failOpcode) {
                throw RockchipUsbTransportException(
                    stage = RockchipTransportStage.DATA_READ,
                    method = RockchipUsbIoMethod.USB_REQUEST,
                    detail = "synthetic transport failure",
                )
            }

            val tag = ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
            val data = if (opcode == 0x14 && readLbaPayload != null) {
                readLbaPayload.copyOf()
            } else {
                ByteArray(responseLengthRange.first)
            }
            return RockchipRawExchange(
                data = data,
                statusBytes = successfulCsw(tag),
            )
        }

        override suspend fun close() {
            closeCount += 1
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
