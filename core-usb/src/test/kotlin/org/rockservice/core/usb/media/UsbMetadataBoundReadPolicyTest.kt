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
    fun `authorizes metadata range only through trusted issuer`() {
        val authorization = UsbReadRangeAuthorizationIssuer.validatedMetadata(
            startBlock = 8L,
            blockCount = 8L,
            description = "Validated partition metadata range",
        )

        val plan = UsbMetadataBoundReadPolicy(maximumAuthorizedBytes = 4_096L).authorize(
            geometry = geometry,
            authorization = authorization,
        )

        assertEquals(8L, plan.startBlock)
        assertEquals(8L, plan.blockCount)
        assertEquals(4_096L, plan.byteCount)
    }

    @Test
    fun `authorizes only the exact range declared by approved fixed probe`() {
        val authorization = UsbReadRangeAuthorizationIssuer.approvedFixedProbe(
            probe = UsbApprovedFixedProbe.LBA_0_1_INSPECTION,
            description = "Hardware-gated LBA 0-1 inspection",
        )

        val plan = UsbMetadataBoundReadPolicy(maximumAuthorizedBytes = 1_024L).authorize(
            geometry = geometry,
            authorization = authorization,
        )

        assertEquals(0L, plan.startBlock)
        assertEquals(2L, plan.blockCount)
        assertEquals(1_024L, plan.byteCount)
    }

    @Test
    fun `fixed probe authorization cannot carry an arbitrary caller supplied range`() {
        val authorization = UsbReadRangeAuthorizationIssuer.approvedFixedProbe(
            probe = UsbApprovedFixedProbe.LBA_0_1_INSPECTION,
            description = "Approved fixed probe",
        )

        val plan = UsbMetadataBoundReadPolicy(maximumAuthorizedBytes = 1_024L).authorize(
            geometry = geometry,
            authorization = authorization,
        )

        assertEquals(0L, plan.startBlock)
        assertEquals(2L, plan.blockCount)
    }

    @Test
    fun `rejects range above policy byte ceiling`() {
        val authorization = UsbReadRangeAuthorizationIssuer.validatedMetadata(
            startBlock = 0L,
            blockCount = 3L,
            description = "Oversized metadata range",
        )
        val policy = UsbMetadataBoundReadPolicy(maximumAuthorizedBytes = 1_024L)

        assertThrows(IllegalArgumentException::class.java) {
            policy.authorize(
                geometry = geometry,
                authorization = authorization,
            )
        }
    }

    @Test
    fun `rejects blank authorization description`() {
        val authorization = UsbReadRangeAuthorizationIssuer.validatedMetadata(
            startBlock = 0L,
            blockCount = 1L,
            description = " ",
        )

        assertThrows(IllegalArgumentException::class.java) {
            UsbMetadataBoundReadPolicy().authorize(
                geometry = geometry,
                authorization = authorization,
            )
        }
    }

    @Test
    fun `delegates geometry bounds validation to planner`() {
        val authorization = UsbReadRangeAuthorizationIssuer.validatedMetadata(
            startBlock = geometry.blockCount,
            blockCount = 1L,
            description = "Out-of-range metadata request",
        )

        assertThrows(IllegalArgumentException::class.java) {
            UsbMetadataBoundReadPolicy().authorize(
                geometry = geometry,
                authorization = authorization,
            )
        }
    }
}
