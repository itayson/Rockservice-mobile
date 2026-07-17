package org.rockservice.core.usb.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RawImageWritePlannerTest {
    @Test
    fun `plans aligned image without trailing block`() {
        val plan = RawImageWritePlanner.plan(
            imageSizeBytes = 4096L,
            targetCapacityBytes = 8192L,
            blockSizeBytes = 512,
        )

        assertEquals(8L, plan.requiredBlocks)
        assertEquals(4096L, plan.trailingCapacityBytes)
    }

    @Test
    fun `rounds partial final block up when capacity allows it`() {
        val plan = RawImageWritePlanner.plan(
            imageSizeBytes = 513L,
            targetCapacityBytes = 2048L,
            blockSizeBytes = 512,
        )

        assertEquals(2L, plan.requiredBlocks)
        assertEquals(1535L, plan.trailingCapacityBytes)
    }

    @Test
    fun `rejects image larger than target`() {
        assertThrows(IllegalArgumentException::class.java) {
            RawImageWritePlanner.plan(
                imageSizeBytes = 4097L,
                targetCapacityBytes = 4096L,
                blockSizeBytes = 512,
            )
        }
    }

    @Test
    fun `rejects final partial block when physical capacity cannot contain it`() {
        assertThrows(IllegalArgumentException::class.java) {
            RawImageWritePlanner.plan(
                imageSizeBytes = 513L,
                targetCapacityBytes = 700L,
                blockSizeBytes = 512,
            )
        }
    }

    @Test
    fun `rejects invalid dimensions`() {
        assertThrows(IllegalArgumentException::class.java) {
            RawImageWritePlanner.plan(0L, 4096L, 512)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawImageWritePlanner.plan(512L, 0L, 512)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawImageWritePlanner.plan(512L, 4096L, 0)
        }
    }
}
