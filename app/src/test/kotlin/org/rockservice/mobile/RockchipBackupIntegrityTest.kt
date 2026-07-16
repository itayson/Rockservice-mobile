package org.rockservice.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RockchipBackupIntegrityTest {
    private val manifest = RockchipBackupManifest(
        createdAtEpochMillis = 1L,
        vendorId = 0x2207,
        productId = 0x330c,
        startSector = 128L,
        sectorCount = 4L,
        byteCount = 2_048L,
        sha256 = "a".repeat(64),
    )

    @Test
    fun `manifest json is local deterministic metadata without transport identifier`() {
        val json = manifest.toJson()

        assertTrue(json.contains("rockservice.backup-manifest.v1"))
        assertTrue(json.contains("\"vendorId\": 8711"))
        assertFalse(json.contains("transportId"))
    }

    @Test
    fun `restore dry run stays blocked without physical write and recovery gates`() {
        val verification = RockchipBackupVerification(
            verified = true,
            actualByteCount = manifest.byteCount,
            actualSha256 = manifest.sha256,
            detail = "ok",
        )

        val plan = RockchipRestoreDryRunPlanner.plan(
            manifest = manifest,
            verification = verification,
            currentVendorId = manifest.vendorId,
            currentProductId = manifest.productId,
            physicallyValidatedWritePath = false,
            recoveryPlanAvailable = false,
        )

        assertFalse(plan.allowed)
        assertTrue(plan.blockers.size == 2)
    }

    @Test
    fun `restore dry run rejects integrity or target mismatch`() {
        val verification = RockchipBackupVerification(
            verified = false,
            actualByteCount = manifest.byteCount,
            actualSha256 = "b".repeat(64),
            detail = "mismatch",
        )

        val plan = RockchipRestoreDryRunPlanner.plan(
            manifest = manifest,
            verification = verification,
            currentVendorId = 1,
            currentProductId = 2,
            physicallyValidatedWritePath = true,
            recoveryPlanAvailable = true,
        )

        assertFalse(plan.allowed)
        assertTrue(plan.blockers.size == 2)
    }

    @Test
    fun `restore dry run can become allowed only when every software gate is satisfied`() {
        val verification = RockchipBackupVerification(
            verified = true,
            actualByteCount = manifest.byteCount,
            actualSha256 = manifest.sha256,
            detail = "ok",
        )

        val plan = RockchipRestoreDryRunPlanner.plan(
            manifest = manifest,
            verification = verification,
            currentVendorId = manifest.vendorId,
            currentProductId = manifest.productId,
            physicallyValidatedWritePath = true,
            recoveryPlanAvailable = true,
        )

        assertTrue(plan.allowed)
        assertTrue(plan.blockers.isEmpty())
    }
}
