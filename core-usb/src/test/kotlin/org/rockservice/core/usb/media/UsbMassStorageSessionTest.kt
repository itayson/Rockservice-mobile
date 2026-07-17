package org.rockservice.core.usb.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbMassStorageSessionTest {
    @Test
    fun `reads block geometry through typed READ CAPACITY 10 command`() = runTest {
        var capturedCommand: UsbMassStorageReadCommand? = null
        var capturedTimeoutMillis = 0L
        val session = UsbMassStorageSession { command, timeoutMillis ->
            capturedCommand = command
            capturedTimeoutMillis = timeoutMillis
            UsbMassStorageTransferResult.Success(
                byteArrayOf(
                    0x00, 0x00, 0x07, 0xff.toByte(),
                    0x00, 0x00, 0x02, 0x00,
                ),
            )
        }

        val geometry = session.readGeometry(timeoutMillis = 2_500L)

        assertEquals(UsbMassStorageReadCommand.READ_CAPACITY_10, capturedCommand)
        assertEquals(8, capturedCommand?.expectedDataInBytes)
        assertEquals(2_500L, capturedTimeoutMillis)
        assertEquals(512, geometry.blockSizeBytes)
        assertEquals(2048L, geometry.blockCount)
        assertEquals(1_048_576L, geometry.capacityBytes)
    }

    @Test
    fun `rejects truncated READ CAPACITY response`() = runTest {
        val session = UsbMassStorageSession { _, _ ->
            UsbMassStorageTransferResult.Success(ByteArray(7))
        }

        expectFailure(IllegalArgumentException::class.java) {
            session.readGeometry()
        }
    }

    @Test
    fun `rejects zero block size`() = runTest {
        val session = UsbMassStorageSession { _, _ ->
            UsbMassStorageTransferResult.Success(
                byteArrayOf(
                    0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00,
                ),
            )
        }

        expectFailure(IllegalArgumentException::class.java) {
            session.readGeometry()
        }
    }

    @Test
    fun `requires READ CAPACITY 16 for large devices`() = runTest {
        val session = UsbMassStorageSession { _, _ ->
            UsbMassStorageTransferResult.Success(
                byteArrayOf(
                    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
                    0x00, 0x00, 0x02, 0x00,
                ),
            )
        }

        expectFailure(IllegalArgumentException::class.java) {
            session.readGeometry()
        }
    }

    @Test
    fun `surfaces disconnect without waiting for timeout`() = runTest {
        val session = UsbMassStorageSession { _, _ ->
            UsbMassStorageTransferResult.Disconnected
        }

        expectFailure(UsbMassStorageDisconnectedException::class.java) {
            session.readGeometry(timeoutMillis = 10_000L)
        }
    }

    @Test
    fun `surfaces transport reported timeout`() = runTest {
        val session = UsbMassStorageSession { _, _ ->
            UsbMassStorageTransferResult.TimedOut
        }

        expectFailure(UsbMassStorageTimeoutException::class.java) {
            session.readGeometry(timeoutMillis = 10_000L)
        }
    }

    @Test
    fun `session timeout cancels cooperative transport`() = runTest {
        val session = UsbMassStorageSession { _, _ ->
            awaitCancellation()
        }

        val error = expectFailure(UsbMassStorageTimeoutException::class.java) {
            session.readGeometry(timeoutMillis = 100L)
        }

        assertTrue(error.cause is TimeoutCancellationException)
    }

    @Test
    fun `caller cancellation propagates to in-flight transport`() = runTest {
        val transferStarted = CompletableDeferred<Unit>()
        val session = UsbMassStorageSession { _, _ ->
            transferStarted.complete(Unit)
            awaitCancellation()
        }

        val job = launch {
            session.readGeometry(timeoutMillis = 10_000L)
        }

        transferStarted.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
    }

    private suspend fun <T : Throwable> expectFailure(
        type: Class<T>,
        block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (error: Throwable) {
            if (type.isInstance(error)) {
                return type.cast(error)
            }
            throw AssertionError(
                "Expected ${type.simpleName}, but received ${error::class.java.simpleName}.",
                error,
            )
        }
        throw AssertionError("Expected ${type.simpleName}, but no exception was thrown.")
    }
}
