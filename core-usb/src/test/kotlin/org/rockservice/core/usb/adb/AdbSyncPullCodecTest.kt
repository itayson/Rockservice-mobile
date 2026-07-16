package org.rockservice.core.usb.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdbSyncPullCodecTest {
    @Test
    fun `RECV request uses little endian header and raw non terminated UTF8 path`() {
        val path = "/sdcard/Download/teste.bin"
        val frame = AdbSyncPullCodec.encodeReceiveRequest(path)
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(AdbSyncPullCodec.commandId("RECV"), buffer.getInt(0))
        assertEquals(path.toByteArray().size, buffer.getInt(4))
        assertArrayEquals(path.toByteArray(), frame.copyOfRange(8, frame.size))
        assertFalse(frame.last() == 0.toByte())
    }

    @Test
    fun `RECV validates UTF8 byte length instead of Kotlin character count`() {
        val withinLimit = "é".repeat(AdbSyncPullCodec.MAXIMUM_PATH_BYTES / 2)
        val tooLarge = withinLimit + "a"

        assertEquals(
            AdbSyncPullCodec.HEADER_SIZE_BYTES + AdbSyncPullCodec.MAXIMUM_PATH_BYTES,
            AdbSyncPullCodec.encodeReceiveRequest(withinLimit).size,
        )
        expectIllegalArgument { AdbSyncPullCodec.encodeReceiveRequest(tooLarge) }
        expectIllegalArgument { AdbSyncPullCodec.encodeReceiveRequest(" ") }
        expectIllegalArgument { AdbSyncPullCodec.encodeReceiveRequest("/data/local\u0000tmp") }
    }

    @Test
    fun `QUIT is exactly one zero length Sync request`() {
        val frame = AdbSyncPullCodec.encodeQuitRequest()
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(8, frame.size)
        assertEquals(AdbSyncPullCodec.commandId("QUIT"), buffer.getInt(0))
        assertEquals(0, buffer.getInt(4))
    }

    @Test
    fun `decoder handles fragmented DATA header payload and DONE`() {
        val decoder = AdbSyncPullDecoder()
        val data = syncFrame("DATA", byteArrayOf(1, 2, 3, 4, 5))
        val done = syncHeader("DONE", 0)

        assertTrue(decoder.feed(data.copyOfRange(0, 3)).isEmpty())
        assertTrue(decoder.feed(data.copyOfRange(3, 9)).isEmpty())
        val responses = decoder.feed(data.copyOfRange(9, data.size) + done)

        assertEquals(2, responses.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), (responses[0] as AdbSyncPullResponse.Data).bytes)
        assertEquals(AdbSyncPullResponse.Done, responses[1])
        assertTrue(decoder.isTerminal)
        decoder.finish()
    }

    @Test
    fun `decoder emits multiple DATA frames from one ADB chunk`() {
        val decoder = AdbSyncPullDecoder()
        val combined = syncFrame("DATA", "first".toByteArray()) +
            syncFrame("DATA", "second".toByteArray()) +
            syncHeader("DONE", 0)

        val responses = decoder.feed(combined)

        assertEquals(3, responses.size)
        assertArrayEquals("first".toByteArray(), (responses[0] as AdbSyncPullResponse.Data).bytes)
        assertArrayEquals("second".toByteArray(), (responses[1] as AdbSyncPullResponse.Data).bytes)
        assertEquals(AdbSyncPullResponse.Done, responses[2])
    }

    @Test
    fun `FAIL is terminal and sanitizes embedded NUL in message`() {
        val decoder = AdbSyncPullDecoder()
        val responses = decoder.feed(
            syncFrame("FAIL", byteArrayOf('n'.code.toByte(), 'o'.code.toByte(), 0, 'p'.code.toByte())),
        )

        assertEquals(
            AdbSyncPullResponse.Fail("no\uFFFDp"),
            responses.single(),
        )
        assertTrue(decoder.isTerminal)
        decoder.finish()
    }

    @Test
    fun `decoder rejects DATA larger than Sync maximum before buffering its payload`() {
        val decoder = AdbSyncPullDecoder()
        val header = syncHeader("DATA", AdbSyncPullCodec.MAXIMUM_DATA_BYTES + 1)

        expectIllegalArgument { decoder.feed(header) }
    }

    @Test
    fun `decoder rejects unknown response command`() {
        val decoder = AdbSyncPullDecoder()

        expectIllegalArgument { decoder.feed(syncHeader("SEND", 0)) }
    }

    @Test
    fun `DONE must have zero length and no trailing protocol bytes`() {
        expectIllegalArgument {
            AdbSyncPullDecoder().feed(syncHeader("DONE", 1) + byteArrayOf(0))
        }
        expectIllegalArgument {
            AdbSyncPullDecoder().feed(syncHeader("DONE", 0) + syncHeader("DONE", 0))
        }
    }

    @Test
    fun `decoder rejects bytes supplied after terminal response`() {
        val decoder = AdbSyncPullDecoder()
        decoder.feed(syncHeader("DONE", 0))

        expectIllegalState { decoder.feed(byteArrayOf(1)) }
    }

    @Test
    fun `finish rejects truncated or prematurely closed Sync stream`() {
        val truncated = AdbSyncPullDecoder()
        truncated.feed(syncFrame("DATA", byteArrayOf(1, 2, 3)).copyOfRange(0, 9))
        expectIllegalArgument { truncated.finish() }

        val noTerminal = AdbSyncPullDecoder()
        noTerminal.feed(syncFrame("DATA", byteArrayOf(1, 2, 3)))
        expectIllegalArgument { noTerminal.finish() }
    }

    @Test
    fun `DATA response uses content equality instead of ByteArray reference equality`() {
        val first = AdbSyncPullResponse.Data(byteArrayOf(1, 2, 3))
        val second = AdbSyncPullResponse.Data(byteArrayOf(1, 2, 3))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    private fun syncFrame(command: String, payload: ByteArray): ByteArray =
        syncHeader(command, payload.size) + payload

    private fun syncHeader(command: String, length: Int): ByteArray =
        ByteArray(AdbSyncPullCodec.HEADER_SIZE_BYTES).also { header ->
            ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0, AdbSyncPullCodec.commandId(command))
                putInt(4, length)
            }
        }

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException = try {
        block()
        fail("Expected IllegalArgumentException")
        error("unreachable")
    } catch (error: IllegalArgumentException) {
        error
    }

    private fun expectIllegalState(block: () -> Unit): IllegalStateException = try {
        block()
        fail("Expected IllegalStateException")
        error("unreachable")
    } catch (error: IllegalStateException) {
        error
    }
}
