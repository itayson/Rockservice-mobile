package org.rockservice.core.usb.media

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsbBlockReadExecutorTest {
    @Test
    fun `executes planned chunks in order and returns exact integrity`() = runBlocking {
        val plan = UsbBlockReadPlanner(maximumBlocksPerChunk = 2).plan(
            geometry = UsbBlockDeviceGeometry(4, 5L, 20L),
            startBlock = 1L,
            blockCount = 4L,
        )
        val calls = mutableListOf<Pair<Long, Int>>()
        val emitted = mutableListOf<UsbBlockReadData>()
        val transport = UsbBlockReadTransport { startBlock, blockCount, blockSizeBytes, timeoutMillis ->
            calls += startBlock to blockCount
            assertEquals(4, blockSizeBytes)
            assertEquals(1_234L, timeoutMillis)
            UsbMassStorageTransferResult.Success(
                ByteArray(blockCount * blockSizeBytes) { index -> (startBlock + index).toByte() },
            )
        }

        val integrity = UsbBlockReadExecutor(transport).execute(
            plan = plan,
            timeoutMillis = 1_234L,
            onChunk = { emitted += it },
        )

        assertEquals(listOf(1L to 2, 3L to 2), calls)
        assertEquals(2, emitted.size)
        assertEquals(0L, emitted[0].chunk.index)
        assertEquals(1L, emitted[1].chunk.index)
        val allBytes = emitted.flatMap { it.data.asIterable() }.toByteArray()
        assertEquals(16L, integrity.byteCount)
        assertEquals(sha256(allBytes), integrity.sha256)
    }

    @Test
    fun `rejects short transfer before exposing chunk`() {
        val plan = UsbBlockReadPlanner(maximumBlocksPerChunk = 2).plan(
            geometry = UsbBlockDeviceGeometry(4, 2L, 8L),
            startBlock = 0L,
            blockCount = 2L,
        )
        var emitted = false
        val executor = UsbBlockReadExecutor(
            UsbBlockReadTransport { _, _, _, _ -> UsbMassStorageTransferResult.Success(ByteArray(7)) },
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { executor.execute(plan) { emitted = true } }
        }
        assertEquals(false, emitted)
    }

    @Test
    fun `maps disconnect without attempting later chunks`() {
        val plan = UsbBlockReadPlanner(maximumBlocksPerChunk = 1).plan(
            geometry = UsbBlockDeviceGeometry(4, 3L, 12L),
            startBlock = 0L,
            blockCount = 3L,
        )
        var calls = 0
        val executor = UsbBlockReadExecutor(
            UsbBlockReadTransport { _, _, _, _ ->
                calls += 1
                if (calls == 1) UsbMassStorageTransferResult.Success(ByteArray(4))
                else UsbMassStorageTransferResult.Disconnected
            },
        )

        assertThrows(UsbMassStorageDisconnectedException::class.java) {
            runBlocking { executor.execute(plan) { } }
        }
        assertEquals(2, calls)
    }

    @Test
    fun `maps transport timeout`() {
        val plan = UsbBlockReadPlanner().plan(
            geometry = UsbBlockDeviceGeometry(512, 1L, 512L),
            startBlock = 0L,
            blockCount = 1L,
        )
        val executor = UsbBlockReadExecutor(
            UsbBlockReadTransport { _, _, _, _ -> UsbMassStorageTransferResult.TimedOut },
        )

        assertThrows(UsbMassStorageTimeoutException::class.java) {
            runBlocking { executor.execute(plan) { } }
        }
    }

    @Test
    fun `passes received bytes unchanged to consumer`() = runBlocking {
        val expected = byteArrayOf(1, 2, 3, 4)
        val plan = UsbBlockReadPlanner().plan(
            geometry = UsbBlockDeviceGeometry(4, 1L, 4L),
            startBlock = 0L,
            blockCount = 1L,
        )
        var actual = ByteArray(0)
        val executor = UsbBlockReadExecutor(
            UsbBlockReadTransport { _, _, _, _ -> UsbMassStorageTransferResult.Success(expected) },
        )

        executor.execute(plan) { actual = it.data }

        assertArrayEquals(expected, actual)
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
