package org.rockservice.core.usb.rockchip

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RockchipBoundedLbaProtocolTest {
    @Test
    fun `bounded read command uses expected wire layout`() {
        val command = RockchipReadOnlyProtocolCodec.encodeReadLbaCommand(
            tag = 9,
            startSector = 0x01020304,
            sectorCount = 1,
        )

        assertEquals(512, littleEndianInt(command, 8))
        assertEquals(0x80, command[12].toInt() and 0xFF)
        assertEquals(0x0A, command[14].toInt() and 0xFF)
        assertEquals(0x14, command[15].toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), command.copyOfRange(17, 21))
        assertArrayEquals(byteArrayOf(0x00, 0x01), command.copyOfRange(22, 24))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bounded read rejects requests above local limit`() {
        RockchipReadOnlyProtocolCodec.encodeReadLbaCommand(
            tag = 1,
            startSector = 0,
            sectorCount = RockchipReadOnlyProtocolCodec.MAX_BOUNDED_LBA_SECTORS + 1,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bounded read rejects truncated response`() {
        RockchipReadOnlyProtocolCodec.decodeReadLbaExchange(
            startSector = 0,
            sectorCount = 1,
            data = ByteArray(511),
            statusBytes = successfulCsw(tag = 4),
            expectedTag = 4,
        )
    }

    private fun successfulCsw(tag: Int): ByteArray =
        ByteArray(RockchipReadOnlyProtocolCodec.COMMAND_STATUS_WRAPPER_SIZE).also { bytes ->
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0, 0x53425355)
            buffer.putInt(4, tag)
            bytes[12] = 0
        }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)
}
