package org.rockservice.core.usb.rockchip

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RockchipReadOnlySessionTest {
    @Test
    fun `query delegates an allowlisted command and validates the response`() = runTest {
        val transport = FakeTransport()
        val session = RockchipReadOnlySession(transport, initialTag = 42)

        val result = session.query(RockchipReadOnlyOperation.READ_CHIP_INFO)

        assertEquals(RockchipReadOnlyOperation.READ_CHIP_INFO, result.operation)
        assertEquals(16, result.data.size)
        assertEquals(42, result.status.tag)
        assertEquals(0x1B, transport.commands.single()[15].toInt() and 0xFF)
    }

    @Test(expected = IllegalStateException::class)
    fun `query rejects a failed command status`() = runTest {
        val session = RockchipReadOnlySession(FakeTransport(status = RockchipCommandStatus.FAILED))

        session.query(RockchipReadOnlyOperation.READ_FLASH_ID)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `query rejects nonpositive timeout before transport use`() = runTest {
        val transport = FakeTransport()
        val session = RockchipReadOnlySession(transport)

        session.query(RockchipReadOnlyOperation.READ_CHIP_INFO, timeoutMillis = 0)
    }

    @Test
    fun `queries are serialized across the transport boundary`() = runTest {
        val transport = FakeTransport(delayMillis = 10)
        val session = RockchipReadOnlySession(transport)

        listOf(
            async { session.query(RockchipReadOnlyOperation.READ_CHIP_INFO) },
            async { session.query(RockchipReadOnlyOperation.READ_FLASH_ID) },
            async { session.query(RockchipReadOnlyOperation.READ_CAPABILITY) },
        ).awaitAll()

        assertEquals(1, transport.maxConcurrentExchanges.get())
        assertEquals(3, transport.commands.size)
    }

    @Test
    fun `close is idempotent`() = runTest {
        val transport = FakeTransport()
        val session = RockchipReadOnlySession(transport)

        session.close()
        session.close()

        assertEquals(1, transport.closeCount)
    }

    @Test(expected = IllegalStateException::class)
    fun `query fails after close`() = runTest {
        val session = RockchipReadOnlySession(FakeTransport())
        session.close()

        session.query(RockchipReadOnlyOperation.READ_CHIP_INFO)
    }

    private class FakeTransport(
        private val status: RockchipCommandStatus = RockchipCommandStatus.PASSED,
        private val delayMillis: Long = 0,
    ) : RockchipReadOnlyTransport {
        val commands = mutableListOf<ByteArray>()
        val maxConcurrentExchanges = AtomicInteger(0)
        private val activeExchanges = AtomicInteger(0)
        var closeCount: Int = 0

        override suspend fun exchange(
            command: ByteArray,
            responseLengthRange: IntRange,
            timeoutMillis: Long,
        ): RockchipRawExchange {
            require(timeoutMillis > 0)
            val active = activeExchanges.incrementAndGet()
            maxConcurrentExchanges.updateAndGet { previous -> maxOf(previous, active) }
            try {
                commands += command.copyOf()
                if (delayMillis > 0) delay(delayMillis)
                val tag = ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
                return RockchipRawExchange(
                    data = ByteArray(responseLengthRange.first),
                    statusBytes = csw(tag, status),
                )
            } finally {
                activeExchanges.decrementAndGet()
            }
        }

        override suspend fun close() {
            closeCount += 1
        }

        private fun csw(tag: Int, status: RockchipCommandStatus): ByteArray =
            ByteArray(RockchipReadOnlyProtocolCodec.COMMAND_STATUS_WRAPPER_SIZE).also { bytes ->
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                buffer.putInt(0, 0x53425355)
                buffer.putInt(4, tag)
                buffer.putInt(8, 0)
                bytes[12] = status.wireValue.toByte()
            }
    }
}
