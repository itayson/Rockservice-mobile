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
            "c9f39787c8a6e0c9e373999a185e9cf2df16ea642189f65a71b4262bf304913c",
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
            "32beecb58a128af824850b16b85d7f2d4d31f2b4f565b1e5a7f140f09a31fdf",
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
        val observedTimeouts = mutableListOf<Long>()
        val reader = object : RockchipBoundedLbaReader {
            override suspend fun readLba(startSector: Long, sectorCount: Int, timeoutMillis: Long): ByteArray {
                observedTimeouts += timeoutMillis
                if (observedTimeouts.size == 1) delay(50)
                return ByteArray(sectorCount * 512)
            }
        }

        RockchipBoundedBackupEngine(maximumSectorCount = 4, chunkSectorCount = 1).backup(
            reader = reader,
            startSector = 0,
            sectorCount = 2,
            timeoutMillis = 1_000,
            onData = {},
        )

        assertEquals(2, observedTimeouts.size)
        assertTrue(observedTimeouts[1] < observedTimeouts[0])
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
