package org.rockservice.core.usb.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsbMetadataBoundReadPolicyTest {
    private val geometry = UsbBlockDeviceGeometry(
        blockSizeBytes = 512,
        blockCount = 16_384L,
        capacityBytes = 8_388_608L,
    )

    @Test
    fun `authorizes validated metadata range within byte ceiling`() {
        val plan = UsbMetadataBoundReadPolicy(maximumAuthorizedBytes = 4_096L).authorize(
            geometry = geometry,
            range = UsbAuthorizedReadRange(
                startBlock = 8L,
                blockCount = 8L,
                evidence = UsbReadRangeEvidence.VALIDATED_METADATA,
                description = "Validated partition metadata range",
            ),
        )

        assertEquals(8L, plan.startBlock)
        assertEquals(8L, plan.blockCount)
        assertEquals(4_096L, plan.byteCount)
    }

    @Test
    fun `authorizes fixed probe only when explicitly tagged as validated`() {
        val plan = UsbMetadataBoundReadPolicy(maximumAuthorizedBytes = 1_024L).authorize(
            geometry = geometry,
            range = UsbAuthorizedReadRange(
                startBlock = 0L,
                blockCount = 2L,
                evidence = UsbReadRangeEvidence.VALIDATED_FIXED_PROBE,
                description = "Hardware-gated LBA 0-1 inspection",
            ),
        )

        assertEquals(1_024L, plan.byteCount)
    }

    @Test
    fun `rejects range above policy byte ceiling`() {
        val policy = UsbMetadataBoundReadPolicy(maximumAuthorizedBytes = 1_024L)

        assertThrows(IllegalArgumentException::class.java) {
            policy.authorize(
                geometry = geometry,
                range = UsbAuthorizedReadRange(
                    startBlock = 0L,
                    blockCount = 3L,
                    evidence = UsbReadRangeEvidence.VALIDATED_METADATA,
                    description = "Oversized metadata range",
                ),
            )
        }
    }

    @Test
    fun `rejects blank evidence description`() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbMetadataBoundReadPolicy().authorize(
                geometry = geometry,
                range = UsbAuthorizedReadRange(
                    startBlock = 0L,
                    blockCount = 1L,
                    evidence = UsbReadRangeEvidence.VALIDATED_METADATA,
                    description = " ",
                ),
            )
        }
    }

    @Test
    fun `delegates geometry bounds validation to planner`() {
        assertThrows(IllegalArgumentException::class.java) {
            UsbMetadataBoundReadPolicy().authorize(
                geometry = geometry,
                range = UsbAuthorizedReadRange(
                    startBlock = geometry.blockCount,
                    blockCount = 1L,
                    evidence = UsbReadRangeEvidence.VALIDATED_METADATA,
                    description = "Out-of-range metadata request",
                ),
            )
        }
    }
}
