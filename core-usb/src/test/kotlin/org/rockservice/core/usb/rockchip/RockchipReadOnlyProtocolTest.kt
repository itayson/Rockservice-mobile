package org.rockservice.core.usb.rockchip

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RockchipReadOnlyProtocolTest {
    @Test
    fun `encodes chip info metadata command with expected CBW layout`() {
        val tag = 0x10203040
        val command = RockchipReadOnlyProtocolCodec.encodeCommand(
            operation = RockchipReadOnlyOperation.READ_CHIP_INFO,
            tag = tag,
        )

        assertEquals(RockchipReadOnlyProtocolCodec.COMMAND_BLOCK_WRAPPER_SIZE, command.size)
        assertArrayEquals(byteArrayOf(0x55, 0x53, 0x42, 0x43), command.copyOfRange(0, 4))
        assertEquals(tag, littleEndianInt(command, 4))
        assertEquals(16, littleEndianInt(command, 8))
        assertEquals(0x80, command[12].toInt() and 0xFF)
        assertEquals(0x06, command[14].toInt() and 0xFF)
        assertEquals(0x1B, command[15].toInt() and 0xFF)
    }

    @Test
    fun `encodes only allowlisted read-only test ready progress subcode`() {
        val command = RockchipReadOnlyProtocolCodec.encodeCommand(
            operation = RockchipReadOnlyOperation.TEST_UNIT_READY,
            tag = 7,
            testUnitSubCode = RockchipReadOnlyTestUnitSubCode.GET_USER_SECTOR_PROGRESS,
        )

        assertEquals(0x00, command[15].toInt() and 0xFF)
        assertEquals(0xF9, command[16].toInt() and 0xFF)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects test unit subcode for another metadata command`() {
        RockchipReadOnlyProtocolCodec.encodeCommand(
            operation = RockchipReadOnlyOperation.READ_FLASH_ID,
            tag = 1,
            testUnitSubCode = RockchipReadOnlyTestUnitSubCode.GET_USER_SECTOR_PROGRESS,
        )
    }

    @Test
    fun `decodes matching command status wrapper`() {
        val status = RockchipReadOnlyProtocolCodec.decodeStatus(
            bytes = csw(tag = 123, residue = 9, status = 0),
            expectedTag = 123,
        )

        assertEquals(123, status.tag)
        assertEquals(9L, status.dataResidue)
        assertEquals(RockchipCommandStatus.PASSED, status.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects mismatched status tag`() {
        RockchipReadOnlyProtocolCodec.decodeStatus(
            bytes = csw(tag = 2, residue = 0, status = 0),
            expectedTag = 1,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid status signature`() {
        val bytes = csw(tag = 1, residue = 0, status = 0)
        bytes[0] = 0

        RockchipReadOnlyProtocolCodec.decodeStatus(bytes, expectedTag = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unknown command status`() {
        RockchipReadOnlyProtocolCodec.decodeStatus(
            bytes = csw(tag = 1, residue = 0, status = 3),
            expectedTag = 1,
        )
    }

    @Test
    fun `accepts variable flash info response within bounded range`() {
        val result = RockchipReadOnlyProtocolCodec.decodeExchange(
            operation = RockchipReadOnlyOperation.READ_FLASH_INFO,
            data = ByteArray(128),
            statusBytes = csw(tag = 77, residue = 0, status = 0),
            expectedTag = 77,
        )

        assertEquals(128, result.data.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects truncated chip info response`() {
        RockchipReadOnlyProtocolCodec.decodeExchange(
            operation = RockchipReadOnlyOperation.READ_CHIP_INFO,
            data = ByteArray(15),
            statusBytes = csw(tag = 5, residue = 0, status = 0),
            expectedTag = 5,
        )
    }

    private fun csw(
        tag: Int,
        residue: Int,
        status: Int,
    ): ByteArray =
        ByteArray(RockchipReadOnlyProtocolCodec.COMMAND_STATUS_WRAPPER_SIZE).also { bytes ->
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0, 0x53425355)
            buffer.putInt(4, tag)
            buffer.putInt(8, residue)
            bytes[12] = status.toByte()
        }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset)
}
