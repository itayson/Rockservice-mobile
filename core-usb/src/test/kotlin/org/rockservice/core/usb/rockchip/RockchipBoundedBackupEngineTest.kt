package org.rockservice.core.usb.rockchip

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RockchipBoundedBackupEngineTest {
    @Test
    fun `backup chunks sequentially hashes bytes and reports progress`() = runTest {
        val reader = RecordingReader()
        val sink = ByteArrayOutputStream()
        val progress = mutableListOf<RockchipBoundedBackupProgress>()

        val result = RockchipBoundedBackupEngine(
            maximumSectorCount = 100,
            chunkSectorCount = 3,
        ).backup(
            reader = reader,
            startSector = 10,
            sectorCount = 8,
            timeoutMillis = 10_000,
            onData = sink::write,
            onProgress = progress::add,
        )

        assertEquals(listOf(10L to 3, 13L to 3, 16L to 2), reader.requests.map { it.startSector to it.sectorCount })
        assertEquals(8L * 512L, result.byteCount)
        assertEquals(8L, result.sectorCount)
        assertEquals(10L, result.startSector)
        assertEquals(
            "11b210afc13ffed63d99879eb84733ce19b3737f2d3522769d8042ddf93d0df6",
            result.sha256,
        )
        assertEquals(listOf(3L, 6L, 8L), progress.map { it.completedSectors })
        assertEquals(8L * 512L, progress.last().completedBytes)
        assertArrayEquals(reader.allBytes(), sink.toByteArray())
    }

    @Test
    fun `sink mutation cannot alter integrity hash`() = runTest {
        val reader = RecordingReader(fillByte = 0x41)

        val result = RockchipBoundedBackupEngine(maximumSectorCount = 4).backup(
            reader = reader,
            startSector = 0,
            sectorCount = 1,
            onData = { bytes -> bytes.fill(0) },
        )

        assertEquals(
            "32beecb58a128af8248504600bd203dcc676adf41045300485655e6b8780a01d",
            result.sha256,
        )
    }

    @Test
    fun `invalid ranges fail before any read`() = runTest {
        val reader = RecordingReader()
        val engine = RockchipBoundedBackupEngine(maximumSectorCount = 10)

        val errors = listOf(
            runCatching { engine.backup(reader, -1, 1, onData = {}) }.exceptionOrNull(),
            runCatching { engine.backup(reader, 0, 0, onData = {}) }.exceptionOrNull(),
            runCatching { engine.backup(reader, 0, 11, onData = {}) }.exceptionOrNull(),
            runCatching { engine.backup(reader, 0xFFFF_FFFFL, 2, onData = {}) }.exceptionOrNull(),
        )

        assertTrue(errors.all { it is IllegalArgumentException || it is ArithmeticException })
        assertTrue(reader.requests.isEmpty())
    }

    @Test
    fun `short read fails closed before progress is published`() = runTest {
        val progress = mutableListOf<RockchipBoundedBackupProgress>()
        val reader = object : RockchipBoundedLbaReader {
            override suspend fun readLba(startSector: Long, sectorCount: Int, timeoutMillis: Long): ByteArray =
                ByteArray(sectorCount * 512 - 1)
        }

        val error = runCatching {
            RockchipBoundedBackupEngine(maximumSectorCount = 4).backup(
                reader = reader,
                startSector = 0,
                sectorCount = 1,
                onData = {},
                onProgress = progress::add,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(progress.isEmpty())
    }

    @Test
    fun `total timeout covers reader and caller sink`() = runTest {
        val reader = object : RockchipBoundedLbaReader {
            override suspend fun readLba(startSector: Long, sectorCount: Int, timeoutMillis: Long): ByteArray {
                delay(60_000)
                return ByteArray(sectorCount * 512)
            }
        }

        val error = runCatching {
            RockchipBoundedBackupEngine(maximumSectorCount = 4).backup(
                reader = reader,
                startSector = 0,
                sectorCount = 1,
                timeoutMillis = 100,
                onData = {},
            )
        }.exceptionOrNull()

        assertTrue(error is TimeoutCancellationException)
    }

    @Test
    fun `reader receives shrinking remaining timeout budget`() = runTest {
        var nowNanos = 1_000_000_000L
        val observedTimeouts = mutableListOf<Long>()
        val reader = object : RockchipBoundedLbaReader {
            override suspend fun readLba(startSector: Long, sectorCount: Int, timeoutMillis: Long): ByteArray {
                observedTimeouts += timeoutMillis
                nowNanos += 250_000_000L
                return ByteArray(sectorCount * 512)
            }
        }

        RockchipBoundedBackupEngine(
            maximumSectorCount = 4,
            chunkSectorCount = 1,
            monotonicNanos = { nowNanos },
        ).backup(
            reader = reader,
            startSector = 0,
            sectorCount = 2,
            timeoutMillis = 1_000,
            onData = {},
        )

        assertEquals(listOf(1_000L, 750L), observedTimeouts)
    }

    private data class Request(val startSector: Long, val sectorCount: Int, val bytes: ByteArray)

    private class RecordingReader(
        private val fillByte: Int? = null,
    ) : RockchipBoundedLbaReader {
        val requests = mutableListOf<Request>()

        override suspend fun readLba(startSector: Long, sectorCount: Int, timeoutMillis: Long): ByteArray {
            val bytes = ByteArray(sectorCount * 512) { index ->
                fillByte?.toByte() ?: ((startSector + index).toInt() and 0xFF).toByte()
            }
            requests += Request(startSector, sectorCount, bytes.copyOf())
            return bytes
        }

        fun allBytes(): ByteArray = requests.fold(ByteArray(0)) { output, request -> output + request.bytes }
    }
}
