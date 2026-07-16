package org.rockservice.core.usb.adb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbSyncPullEngineTest {
    @Test
    fun `successful fragmented pull streams bytes hashes content sends QUIT and closes`() = runTest {
        val data1 = syncFrame("DATA", "hello ".toByteArray())
        val data2 = syncFrame("DATA", "world".toByteArray())
        val done = syncHeader("DONE", 0)
        val stream = FakeSyncPullStream(
            reads = listOf(
                data1.copyOfRange(0, 5),
                data1.copyOfRange(5, data1.size) + data2 + done,
            ),
        )
        val sink = ByteArrayOutputStream()

        val result = AdbSyncPullEngine().pull(
            stream = stream,
            remotePath = "/sdcard/test.txt",
            onData = sink::write,
        )

        assertEquals(11L, result.byteCount)
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            result.sha256,
        )
        assertArrayEquals("hello world".toByteArray(), sink.toByteArray())
        assertEquals(2, stream.writes.size)
        assertArrayEquals(
            AdbSyncPullCodec.encodeReceiveRequest("/sdcard/test.txt"),
            stream.writes[0].bytes,
        )
        assertArrayEquals(AdbSyncPullCodec.encodeQuitRequest(), stream.writes[1].bytes)
        assertEquals(1, stream.closeCount)
    }

    @Test
    fun `empty successful pull returns SHA256 of empty content`() = runTest {
        val stream = FakeSyncPullStream(reads = listOf(syncHeader("DONE", 0)))

        val result = AdbSyncPullEngine().pull(
            stream = stream,
            remotePath = "/data/local/tmp/empty",
            onData = { error("Empty pull must not emit DATA") },
        )

        assertEquals(0L, result.byteCount)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            result.sha256,
        )
        assertEquals(2, stream.writes.size)
        assertEquals(1, stream.closeCount)
    }

    @Test
    fun `limit overflow preserves already delivered bytes and does not send QUIT`() = runTest {
        val stream = FakeSyncPullStream(
            reads = listOf(
                syncFrame("DATA", "abcd".toByteArray()) +
                    syncFrame("DATA", "efgh".toByteArray()) +
                    syncHeader("DONE", 0),
            ),
        )
        val sink = ByteArrayOutputStream()

        val error = runCatching {
            AdbSyncPullEngine().pull(
                stream = stream,
                remotePath = "/large.bin",
                maximumBytes = 5,
                onData = sink::write,
            )
        }.exceptionOrNull()

        assertTrue(error is AdbSyncPullLimitExceededException)
        error as AdbSyncPullLimitExceededException
        assertEquals(5L, error.maximumBytes)
        assertEquals(4L, error.bytesDelivered)
        assertEquals(4, error.rejectedChunkBytes)
        assertArrayEquals("abcd".toByteArray(), sink.toByteArray())
        assertEquals(1, stream.writes.size)
        assertEquals(1, stream.closeCount)
    }

    @Test
    fun `remote FAIL is surfaced and closes without sending QUIT`() = runTest {
        val stream = FakeSyncPullStream(
            reads = listOf(syncFrame("FAIL", "Permission denied".toByteArray())),
        )

        val error = runCatching {
            AdbSyncPullEngine().pull(
                stream = stream,
                remotePath = "/data/secret",
                onData = {},
            )
        }.exceptionOrNull()

        assertTrue(error is AdbSyncRemoteFailureException)
        assertEquals("Permission denied", (error as AdbSyncRemoteFailureException).remoteMessage)
        assertEquals(1, stream.writes.size)
        assertEquals(1, stream.closeCount)
    }

    @Test
    fun `premature ADB stream close becomes protocol failure`() = runTest {
        val full = syncFrame("DATA", byteArrayOf(1, 2, 3, 4))
        val stream = FakeSyncPullStream(
            reads = listOf(full.copyOfRange(0, full.size - 1)),
        )

        val error = runCatching {
            AdbSyncPullEngine().pull(
                stream = stream,
                remotePath = "/truncated.bin",
                onData = {},
            )
        }.exceptionOrNull()

        assertTrue(error is AdbSyncPullProtocolException)
        assertEquals(1, stream.writes.size)
        assertEquals(1, stream.closeCount)
    }

    @Test
    fun `total timeout preserves coroutine cancellation semantics and closes stream`() = runTest {
        val stream = FakeSyncPullStream(
            reads = emptyList(),
            readDelayMillis = 60_000,
        )

        val error = runCatching {
            AdbSyncPullEngine().pull(
                stream = stream,
                remotePath = "/slow.bin",
                timeoutMillis = 100,
                onData = {},
            )
        }.exceptionOrNull()

        assertTrue(error is TimeoutCancellationException)
        assertEquals(1, stream.writes.size)
        assertEquals(1, stream.closeCount)
    }

    @Test
    fun `sink failure closes stream and does not send QUIT`() = runTest {
        val stream = FakeSyncPullStream(
            reads = listOf(
                syncFrame("DATA", "content".toByteArray()) + syncHeader("DONE", 0),
            ),
        )

        val error = runCatching {
            AdbSyncPullEngine().pull(
                stream = stream,
                remotePath = "/file.bin",
                onData = { throw IOException("destination failed") },
            )
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(1, stream.writes.size)
        assertEquals(1, stream.closeCount)
    }

    @Test
    fun `sink mutation cannot change SHA256 integrity result`() = runTest {
        val stream = FakeSyncPullStream(
            reads = listOf(
                syncFrame("DATA", "abc".toByteArray()) + syncHeader("DONE", 0),
            ),
        )

        val result = AdbSyncPullEngine().pull(
            stream = stream,
            remotePath = "/abc",
            onData = { bytes -> bytes[0] = 'z'.code.toByte() },
        )

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            result.sha256,
        )
    }

    @Test
    fun `invalid caller limits fail before engine assumes stream ownership`() = runTest {
        val stream = FakeSyncPullStream(reads = emptyList())

        val byteLimitError = runCatching {
            AdbSyncPullEngine().pull(
                stream = stream,
                remotePath = "/file",
                maximumBytes = 0,
                onData = {},
            )
        }.exceptionOrNull()
        val timeoutError = runCatching {
            AdbSyncPullEngine().pull(
                stream = stream,
                remotePath = "/file",
                timeoutMillis = 0,
                onData = {},
            )
        }.exceptionOrNull()

        assertTrue(byteLimitError is IllegalArgumentException)
        assertTrue(timeoutError is IllegalArgumentException)
        assertTrue(stream.writes.isEmpty())
        assertEquals(0, stream.closeCount)
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

    private class FakeSyncPullStream(
        private val reads: List<ByteArray>,
        private val readDelayMillis: Long = 0,
    ) : AdbSyncPullStream {
        data class Write(
            val bytes: ByteArray,
            val timeoutMillis: Long,
        )

        val writes = mutableListOf<Write>()
        var closeCount = 0
        private var readIndex = 0

        override suspend fun write(bytes: ByteArray, timeoutMillis: Long) {
            writes += Write(bytes.copyOf(), timeoutMillis)
        }

        override suspend fun read(): ByteArray? {
            if (readDelayMillis > 0) delay(readDelayMillis)
            if (readIndex >= reads.size) return null
            return reads[readIndex++].copyOf()
        }

        override suspend fun close(timeoutMillis: Long) {
            closeCount += 1
        }
    }
}
