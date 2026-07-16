package org.rockservice.core.usb.adb

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAdbUsbTransportTest {
    @Test
    fun `send writes exactly one encoded frame`() = runTest {
        val io = FakeAdbUsbIo()
        val transport = AndroidAdbUsbTransport(io)
        val message = AdbMessage(AdbCommand.CNXN, 0x01000001, 4096, "host::\u0000".toByteArray())

        transport.send(message, 1_000)

        assertEquals(1, io.writes.size)
        assertArrayEquals(AdbProtocolCodec.encode(message), io.writes.single())
    }

    @Test
    fun `receive reads header then declared payload and validates checksum`() = runTest {
        val message = AdbMessage(AdbCommand.AUTH, AdbAuthType.TOKEN.wireValue, 0, ByteArray(20) { 7 })
        val frame = AdbProtocolCodec.encode(message)
        val io = FakeAdbUsbIo(
            reads = ArrayDeque(
                listOf(
                    frame.copyOfRange(0, AdbProtocolCodec.HEADER_SIZE_BYTES),
                    frame.copyOfRange(AdbProtocolCodec.HEADER_SIZE_BYTES, frame.size),
                ),
            ),
        )
        val transport = AndroidAdbUsbTransport(io)

        val decoded = transport.receive(1_000)

        assertEquals(message, decoded)
        assertEquals(listOf(AdbProtocolCodec.HEADER_SIZE_BYTES, 20), io.requestedReadLengths)
    }

    @Test
    fun `receive with empty payload performs only header read`() = runTest {
        val message = AdbMessage(AdbCommand.OKAY, 1, 2)
        val frame = AdbProtocolCodec.encode(message)
        val io = FakeAdbUsbIo(reads = ArrayDeque(listOf(frame)))
        val transport = AndroidAdbUsbTransport(io)

        assertEquals(message, transport.receive(1_000))
        assertEquals(listOf(AdbProtocolCodec.HEADER_SIZE_BYTES), io.requestedReadLengths)
    }

    @Test
    fun `close is idempotent and blocks later operations`() = runTest {
        val io = FakeAdbUsbIo()
        val transport = AndroidAdbUsbTransport(io)

        transport.close()
        transport.close()
        val error = runCatching {
            transport.send(AdbMessage(AdbCommand.OKAY, 1, 2), 1_000)
        }.exceptionOrNull()

        assertEquals(1, io.closeCount)
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `canonical adb interface profile matches only exact triplet`() {
        assertTrue(AdbUsbInterfaceProfile.matches(0xFF, 0x42, 0x01))
        assertTrue(!AdbUsbInterfaceProfile.matches(0xFF, 0x42, 0x02))
        assertTrue(!AdbUsbInterfaceProfile.matches(0xFF, 0x41, 0x01))
        assertTrue(!AdbUsbInterfaceProfile.matches(0x00, 0x42, 0x01))
    }

    private class FakeAdbUsbIo(
        private val reads: ArrayDeque<ByteArray> = ArrayDeque(),
    ) : AdbUsbIo {
        val writes = mutableListOf<ByteArray>()
        val requestedReadLengths = mutableListOf<Int>()
        var closeCount = 0

        override fun writeExactly(bytes: ByteArray, timeoutMillis: Int) {
            writes += bytes.copyOf()
        }

        override fun readExactly(byteCount: Int, timeoutMillis: Int): ByteArray {
            requestedReadLengths += byteCount
            return reads.removeFirst().also { bytes ->
                require(bytes.size == byteCount)
            }
        }

        override fun close() {
            closeCount += 1
        }
    }
}
