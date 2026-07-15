package org.rockservice.core.usb.rockchip

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRockchipReadOnlyTransportTest {
    @Test
    fun `exchange writes CBW then reads data and CSW`() = runTest {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val status = ByteArray(RockchipReadOnlyProtocolCodec.COMMAND_STATUS_WRAPPER_SIZE) { 7 }
        val io = FakeRockchipUsbIo(reads = ArrayDeque(listOf(data, status)))
        val transport = AndroidRockchipReadOnlyTransport(io)
        val command = ByteArray(RockchipReadOnlyProtocolCodec.COMMAND_BLOCK_WRAPPER_SIZE) { 3 }

        val result = transport.exchange(command, 5..5, 1_000)

        assertArrayEquals(command, io.writes.single())
        assertArrayEquals(data, result.data)
        assertArrayEquals(status, result.statusBytes)
        assertEquals(listOf(5, 13), io.requestedReadLengths)
    }

    @Test
    fun `zero length command skips data phase and reads CSW`() = runTest {
        val status = ByteArray(RockchipReadOnlyProtocolCodec.COMMAND_STATUS_WRAPPER_SIZE)
        val io = FakeRockchipUsbIo(reads = ArrayDeque(listOf(status)))
        val transport = AndroidRockchipReadOnlyTransport(io)

        val result = transport.exchange(
            ByteArray(RockchipReadOnlyProtocolCodec.COMMAND_BLOCK_WRAPPER_SIZE),
            0..0,
            1_000,
        )

        assertEquals(0, result.data.size)
        assertEquals(listOf(13), io.requestedReadLengths)
    }

    @Test
    fun `short command write fails closed`() = runTest {
        val io = FakeRockchipUsbIo(writeResult = 30)
        val transport = AndroidRockchipReadOnlyTransport(io)

        val error = runCatching {
            transport.exchange(
                ByteArray(RockchipReadOnlyProtocolCodec.COMMAND_BLOCK_WRAPPER_SIZE),
                0..0,
                1_000,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("short write"))
    }

    @Test
    fun `short data read fails before CSW phase`() = runTest {
        val io = FakeRockchipUsbIo(reads = ArrayDeque(listOf(byteArrayOf(1, 2))))
        val transport = AndroidRockchipReadOnlyTransport(io)

        val error = runCatching {
            transport.exchange(
                ByteArray(RockchipReadOnlyProtocolCodec.COMMAND_BLOCK_WRAPPER_SIZE),
                5..5,
                1_000,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(listOf(5), io.requestedReadLengths)
    }

    @Test
    fun `short CSW read fails closed`() = runTest {
        val io = FakeRockchipUsbIo(reads = ArrayDeque(listOf(ByteArray(12))))
        val transport = AndroidRockchipReadOnlyTransport(io)

        val error = runCatching {
            transport.exchange(
                ByteArray(RockchipReadOnlyProtocolCodec.COMMAND_BLOCK_WRAPPER_SIZE),
                0..0,
                1_000,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("CSW short read"))
    }

    @Test
    fun `close is idempotent`() = runTest {
        val io = FakeRockchipUsbIo()
        val transport = AndroidRockchipReadOnlyTransport(io)

        transport.close()
        transport.close()

        assertEquals(1, io.closeCount)
    }

    private class FakeRockchipUsbIo(
        private val writeResult: Int = RockchipReadOnlyProtocolCodec.COMMAND_BLOCK_WRAPPER_SIZE,
        private val reads: ArrayDeque<ByteArray> = ArrayDeque(),
    ) : RockchipUsbIo {
        val writes = mutableListOf<ByteArray>()
        val requestedReadLengths = mutableListOf<Int>()
        var closeCount = 0

        override fun write(bytes: ByteArray, timeoutMillis: Int): Int {
            writes += bytes.copyOf()
            return writeResult
        }

        override fun read(maximumLength: Int, timeoutMillis: Int): ByteArray {
            requestedReadLengths += maximumLength
            return reads.removeFirst()
        }

        override fun close() {
            closeCount += 1
        }
    }
}
