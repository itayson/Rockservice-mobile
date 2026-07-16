package org.rockservice.core.usb.rockchip

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RockchipBackupVerifierTest {
    @Test
    fun `matching size and digest verify successfully`() {
        val bytes = ByteArray(1024) { index -> (index and 0xff).toByte() }
        val manifest = RockchipBackupManifest(
            startSector = 0,
            sectorCount = 2,
            byteCount = bytes.size.toLong(),
            sha256 = bytes.sha256(),
        )

        val result = RockchipBackupVerifier.verify(manifest, ByteArrayInputStream(bytes))

        assertTrue(result.sizeMatches)
        assertTrue(result.sha256Matches)
        assertTrue(result.verified)
    }

    @Test
    fun `truncated backup fails size and digest verification`() {
        val expected = ByteArray(1024) { 0x41 }
        val actual = expected.copyOf(512)
        val manifest = RockchipBackupManifest(0, 2, 1024, expected.sha256())

        val result = RockchipBackupVerifier.verify(manifest, ByteArrayInputStream(actual))

        assertFalse(result.sizeMatches)
        assertFalse(result.sha256Matches)
        assertFalse(result.verified)
    }

    @Test
    fun `same size with altered bytes fails digest verification`() {
        val expected = ByteArray(512) { 0x22 }
        val altered = expected.copyOf().also { it[100] = 0x23 }
        val manifest = RockchipBackupManifest(10, 1, 512, expected.sha256())

        val result = RockchipBackupVerifier.verify(manifest, ByteArrayInputStream(altered))

        assertTrue(result.sizeMatches)
        assertFalse(result.sha256Matches)
        assertFalse(result.verified)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `manifest rejects inconsistent byte count`() {
        RockchipBackupManifest(0, 2, 512, "0".repeat(64))
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }
}
