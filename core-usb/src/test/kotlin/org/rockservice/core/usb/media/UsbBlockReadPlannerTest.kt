package org.rockservice.core.usb.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsbBlockReadPlannerTest {
    @Test
    fun `plans bounded chunks entirely inside geometry`() {
        val geometry = UsbBlockDeviceGeometry(
            blockSizeBytes = 512,
            blockCount = 16L,
            capacityBytes = 8_192L,
        )
        val plan = UsbBlockReadPlanner(maximumBlocksPerChunk = 4).plan(
            geometry = geometry,
            startBlock = 2L,
            blockCount = 10L,
        )

        assertEquals(2L, plan.startBlock)
        assertEquals(10L, plan.blockCount)
        assertEquals(512, plan.blockSizeBytes)
        assertEquals(1_024L, plan.startByteOffset)
        assertEquals(5_120L, plan.byteCount)
        assertEquals(3L, plan.chunkCount)
        assertEquals(
            listOf(
                UsbBlockReadChunk(
                    index = 0L,
                    startBlock = 2L,
                    blockCount = 4,
                    deviceByteOffset = 1_024L,
                    planByteOffset = 0L,
                    byteCount = 2_048,
                ),
                UsbBlockReadChunk(
                    index = 1L,
                    startBlock = 6L,
                    blockCount = 4,
                    deviceByteOffset = 3_072L,
                    planByteOffset = 2_048L,
                    byteCount = 2_048,
                ),
                UsbBlockReadChunk(
                    index = 2L,
                    startBlock = 10L,
                    blockCount = 2,
                    deviceByteOffset = 5_120L,
                    planByteOffset = 4_096L,
                    byteCount = 1_024,
                ),
            ),
            plan.chunks().toList(),
        )
    }

    @Test
    fun `allows a range ending exactly at device capacity`() {
        val geometry = UsbBlockDeviceGeometry(
            blockSizeBytes = 4_096,
            blockCount = 8L,
            capacityBytes = 32_768L,
        )

        val plan = UsbBlockReadPlanner(maximumBlocksPerChunk = 3).plan(
            geometry = geometry,
            startBlock = 6L,
            blockCount = 2L,
        )

        assertEquals(24_576L, plan.startByteOffset)
        assertEquals(8_192L, plan.byteCount)
        assertEquals(1L, plan.chunkCount)
    }

    @Test
    fun `rejects inconsistent device geometry`() {
        val geometry = UsbBlockDeviceGeometry(
            blockSizeBytes = 512,
            blockCount = 16L,
            capacityBytes = 8_191L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            UsbBlockReadPlanner().plan(
                geometry = geometry,
                startBlock = 0L,
                blockCount = 1L,
            )
        }
    }

    @Test
    fun `rejects range beyond available blocks`() {
        val geometry = UsbBlockDeviceGeometry(
            blockSizeBytes = 512,
            blockCount = 16L,
            capacityBytes = 8_192L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            UsbBlockReadPlanner().plan(
                geometry = geometry,
                startBlock = 15L,
                blockCount = 2L,
            )
        }
    }

    @Test
    fun `rejects range arithmetic overflow`() {
        val geometry = UsbBlockDeviceGeometry(
            blockSizeBytes = 1,
            blockCount = Long.MAX_VALUE,
            capacityBytes = Long.MAX_VALUE,
        )

        assertThrows(IllegalArgumentException::class.java) {
            UsbBlockReadPlanner().plan(
                geometry = geometry,
                startBlock = Long.MAX_VALUE - 1L,
                blockCount = 2L,
            )
        }
    }

    @Test
    fun `computes SHA 256 across multiple accepted chunks`() {
        val geometry = UsbBlockDeviceGeometry(
            blockSizeBytes = 4,
            blockCount = 2L,
            capacityBytes = 8L,
        )
        val accumulator = UsbBlockReadPlanner()
            .plan(geometry = geometry, startBlock = 0L, blockCount = 2L)
            .newSha256Accumulator()

        accumulator.update("abc".encodeToByteArray())
        accumulator.update("defgh".encodeToByteArray())
        val integrity = accumulator.finish()

        assertEquals(8L, integrity.byteCount)
        assertEquals(
            "9c56cc51b374c3ba189210d5b6d4bf57790d351c96c47c02190ecf1e430635ab",
            integrity.sha256,
        )
    }

    @Test
    fun `rejects data beyond planned byte count`() {
        val geometry = UsbBlockDeviceGeometry(
            blockSizeBytes = 4,
            blockCount = 2L,
            capacityBytes = 8L,
        )
        val accumulator = UsbBlockReadPlanner()
            .plan(geometry = geometry, startBlock = 0L, blockCount = 2L)
            .newSha256Accumulator()

        accumulator.update(ByteArray(7))

        assertThrows(IllegalArgumentException::class.java) {
            accumulator.update(ByteArray(2))
        }
    }

    @Test
    fun `rejects finishing an incomplete read`() {
        val geometry = UsbBlockDeviceGeometry(
            blockSizeBytes = 4,
            blockCount = 2L,
            capacityBytes = 8L,
        )
        val accumulator = UsbBlockReadPlanner()
            .plan(geometry = geometry, startBlock = 0L, blockCount = 2L)
            .newSha256Accumulator()

        accumulator.update(ByteArray(4))

        assertThrows(IllegalStateException::class.java) {
            accumulator.finish()
        }
    }

    @Test
    fun `rejects updates after integrity has been finalized`() {
        val geometry = UsbBlockDeviceGeometry(
            blockSizeBytes = 4,
            blockCount = 1L,
            capacityBytes = 4L,
        )
        val accumulator = UsbBlockReadPlanner()
            .plan(geometry = geometry, startBlock = 0L, blockCount = 1L)
            .newSha256Accumulator()

        accumulator.update(ByteArray(4))
        accumulator.finish()

        assertThrows(IllegalStateException::class.java) {
            accumulator.update(ByteArray(1))
        }
    }
}
