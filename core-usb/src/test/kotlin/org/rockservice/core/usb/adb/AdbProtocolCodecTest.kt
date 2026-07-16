package org.rockservice.core.usb.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdbProtocolCodecTest {
    @Test
    fun `connect frame uses expected CNXN wire fields and round trips`() {
        val message = AdbProtocolCodec.connect(
            banner = "host::features=shell_v2;",
            protocolVersion = 0x01000001,
            maxDataBytes = 4096,
        )

        val frame = AdbProtocolCodec.encode(message)
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(0x4E584E43, buffer.getInt(0))
        assertEquals(0x01000001, buffer.getInt(4))
        assertEquals(4096, buffer.getInt(8))
        assertEquals(message.payload.size, buffer.getInt(12))
        assertEquals(0xB1A7B1BC.toInt(), buffer.getInt(20))
        assertEquals(message, AdbProtocolCodec.decode(frame))
    }

    @Test
    fun `checksum treats payload bytes as unsigned`() {
        assertEquals(
            0x01FEL,
            AdbProtocolCodec.checksum(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x01)),
        )
    }

    @Test
    fun `open service is null terminated`() {
        val message = AdbProtocolCodec.open(localId = 7, service = "shell:id")

        assertEquals(AdbCommand.OPEN, message.command)
        assertEquals(7L, message.arg0)
        assertEquals(0L, message.arg1)
        assertArrayEquals("shell:id\u0000".toByteArray(), message.payload)
    }

    @Test
    fun `auth helpers use expected auth message types`() {
        val signature = AdbProtocolCodec.authSignature(byteArrayOf(1, 2, 3))
        val publicKey = AdbProtocolCodec.authPublicKey("AAAA test@rockservice")

        assertEquals(AdbAuthType.SIGNATURE.wireValue, signature.arg0)
        assertArrayEquals(byteArrayOf(1, 2, 3), signature.payload)
        assertEquals(AdbAuthType.RSA_PUBLIC_KEY.wireValue, publicKey.arg0)
        assertEquals(0, publicKey.payload.last().toInt())
    }

    @Test
    fun `header can be decoded before payload`() {
        val original = AdbMessage(
            command = AdbCommand.WRTE,
            arg0 = 0xFFFF_FFFFL,
            arg1 = 42,
            payload = byteArrayOf(4, 5, 6),
        )
        val frame = AdbProtocolCodec.encode(original)

        val header = AdbProtocolCodec.decodeHeader(frame.copyOfRange(0, AdbProtocolCodec.HEADER_SIZE_BYTES))
        val decoded = AdbProtocolCodec.decodePayload(
            header,
            frame.copyOfRange(AdbProtocolCodec.HEADER_SIZE_BYTES, frame.size),
        )

        assertEquals(0xFFFF_FFFFL, header.arg0)
        assertEquals(original, decoded)
    }

    @Test
    fun `decode can explicitly skip checksum for negotiated modern transports`() {
        val frame = AdbProtocolCodec.encode(
            AdbMessage(AdbCommand.OKAY, arg0 = 1, arg1 = 2, payload = byteArrayOf(9)),
        )
        ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 0)

        val decoded = AdbProtocolCodec.decode(frame, requireChecksum = false)

        assertEquals(AdbCommand.OKAY, decoded.command)
    }

    @Test
    fun `rejects invalid command magic`() {
        val frame = AdbProtocolCodec.encode(AdbMessage(AdbCommand.CLSE, 1, 2))
        ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 0)

        expectIllegalArgument { AdbProtocolCodec.decode(frame) }
    }

    @Test
    fun `rejects unknown command`() {
        val frame = ByteArray(AdbProtocolCodec.HEADER_SIZE_BYTES)
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        val command = 0x11223344
        buffer.putInt(0, command)
        buffer.putInt(20, command.inv())

        val error = expectIllegalArgument { AdbProtocolCodec.decode(frame) }

        assertTrue(error.message.orEmpty().contains("nao suportado"))
    }

    @Test
    fun `rejects checksum mismatch`() {
        val frame = AdbProtocolCodec.encode(
            AdbMessage(AdbCommand.WRTE, 1, 2, byteArrayOf(1, 2, 3)),
        )
        frame[frame.lastIndex] = 9

        expectIllegalArgument { AdbProtocolCodec.decode(frame) }
    }

    @Test
    fun `rejects frame with trailing bytes`() {
        val frame = AdbProtocolCodec.encode(AdbMessage(AdbCommand.OKAY, 1, 2)) + byteArrayOf(0)

        expectIllegalArgument { AdbProtocolCodec.decode(frame) }
    }

    @Test
    fun `rejects payload length above local limit before allocation`() {
        val header = ByteArray(AdbProtocolCodec.HEADER_SIZE_BYTES)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = 0x4E584E43
        buffer.putInt(0, command)
        buffer.putInt(12, AdbProtocolCodec.MAXIMUM_PAYLOAD_BYTES + 1)
        buffer.putInt(20, command.inv())

        expectIllegalArgument { AdbProtocolCodec.decodeHeader(header) }
    }

    @Test
    fun `rejects truncated complete frame`() {
        val frame = AdbProtocolCodec.encode(
            AdbMessage(AdbCommand.WRTE, 1, 2, byteArrayOf(1, 2, 3)),
        ).copyOfRange(0, AdbProtocolCodec.HEADER_SIZE_BYTES + 2)

        expectIllegalArgument { AdbProtocolCodec.decode(frame) }
    }

    @Test
    fun `all supported commands round trip`() {
        AdbCommand.entries.forEachIndexed { index, command ->
            val payload = if (command == AdbCommand.SYNC) ByteArray(0) else byteArrayOf(index.toByte())
            val message = AdbMessage(command, arg0 = index.toLong(), arg1 = (index + 1).toLong(), payload = payload)
            assertEquals(message, AdbProtocolCodec.decode(AdbProtocolCodec.encode(message)))
        }
    }

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException = try {
        block()
        fail("Expected IllegalArgumentException")
        error("unreachable")
    } catch (error: IllegalArgumentException) {
        error
    }
}
