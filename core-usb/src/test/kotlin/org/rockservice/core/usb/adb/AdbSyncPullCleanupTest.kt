package org.rockservice.core.usb.adb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbSyncPullCleanupTest {
    @Test
    fun `successful content with QUIT failure surfaces cleanup error after closing stream`() = runTest {
        val stream = CleanupStream(
            reads = listOf(syncHeader("DONE", 0)),
            failingWriteNumbers = setOf(2),
        )

        val error = runCatching {
            AdbSyncPullEngine().pull(
                stream = stream,
                remotePath = "/file",
                onData = {},
            )
        }.exceptionOrNull()

        assertTrue(error is AdbSyncPullCleanupException)
        assertTrue(error?.cause?.message.orEmpty().contains("QUIT"))
        assertEquals(2, stream.writeCount)
        assertEquals(1, stream.closeCount)
    }

    @Test
    fun `successful content with close failure does not return a false success result`() = runTest {
        val stream = CleanupStream(
            reads = listOf(syncHeader("DONE", 0)),
            closeFailure = IOException("usb close failed"),
        )

        val error = runCatching {
            AdbSyncPullEngine().pull(
                stream = stream,
                remotePath = "/file",
                onData = {},
            )
        }.exceptionOrNull()

        assertTrue(error is AdbSyncPullCleanupException)
        assertTrue(error?.cause?.message.orEmpty().contains("fechar o stream"))
        assertEquals(2, stream.writeCount)
        assertEquals(1, stream.closeCount)
    }

    @Test
    fun `primary remote failure is preserved and cleanup failure is attached as suppressed`() = runTest {
        val stream = CleanupStream(
            reads = listOf(syncFrame("FAIL", "denied".toByteArray())),
            closeFailure = IOException("usb close failed"),
        )

        val error = runCatching {
            AdbSyncPullEngine().pull(
                stream = stream,
                remotePath = "/secret",
                onData = {},
            )
        }.exceptionOrNull()

        assertTrue(error is AdbSyncRemoteFailureException)
        assertEquals(1, error?.suppressed?.size)
        assertTrue(error?.suppressed?.single() is AdbSyncPullCleanupException)
        assertEquals(1, stream.writeCount)
        assertEquals(1, stream.closeCount)
    }

    @Test
    fun `QUIT and close failures are both retained in one cleanup exception`() = runTest {
        val stream = CleanupStream(
            reads = listOf(syncHeader("DONE", 0)),
            failingWriteNumbers = setOf(2),
            closeFailure = IOException("usb close failed"),
        )

        val error = runCatching {
            AdbSyncPullEngine().pull(
                stream = stream,
                remotePath = "/file",
                onData = {},
            )
        }.exceptionOrNull()

        assertTrue(error is AdbSyncPullCleanupException)
        assertEquals(1, error?.suppressed?.size)
        assertTrue(error?.cause?.message.orEmpty().contains("QUIT"))
        assertTrue(error?.suppressed?.single()?.message.orEmpty().contains("fechar o stream"))
    }

    @Test
    fun `initial RECV failure remains primary when close also fails`() = runTest {
        val stream = CleanupStream(
            reads = emptyList(),
            failingWriteNumbers = setOf(1),
            closeFailure = IOException("usb close failed"),
        )

        val error = runCatching {
            AdbSyncPullEngine().pull(
                stream = stream,
                remotePath = "/file",
                onData = {},
            )
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("write 1 failed", error?.message)
        assertEquals(1, error?.suppressed?.size)
        assertTrue(error?.suppressed?.single() is AdbSyncPullCleanupException)
        assertEquals(1, stream.closeCount)
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

    private class CleanupStream(
        private val reads: List<ByteArray>,
        private val failingWriteNumbers: Set<Int> = emptySet(),
        private val closeFailure: Throwable? = null,
    ) : AdbSyncPullStream {
        var writeCount = 0
        var closeCount = 0
        private var readIndex = 0

        override suspend fun write(bytes: ByteArray, timeoutMillis: Long) {
            writeCount += 1
            if (writeCount in failingWriteNumbers) {
                throw IOException("write $writeCount failed")
            }
        }

        override suspend fun read(): ByteArray? =
            if (readIndex < reads.size) reads[readIndex++].copyOf() else null

        override suspend fun close(timeoutMillis: Long) {
            closeCount += 1
            closeFailure?.let { throw it }
        }
    }
}
