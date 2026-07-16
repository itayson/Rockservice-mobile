package org.rockservice.core.usb.rockchip

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RockchipBackupManifestCodecTest {
    @Test
    fun `encode is deterministic and decode round trips`() {
        val manifest = RockchipBackupManifest(
            startSector = 32,
            sectorCount = 4,
            byteCount = 2048,
            sha256 = "a".repeat(64),
        )

        val encoded = RockchipBackupManifestCodec.encode(manifest)

        assertEquals(
            """
            ROCKSERVICE-ROCKCHIP-BACKUP-MANIFEST
            version=1
            startSector=32
            sectorCount=4
            byteCount=2048
            sha256=${"a".repeat(64)}
            """.trimIndent() + "\n",
            encoded,
        )
        assertEquals(manifest, RockchipBackupManifestCodec.decode(encoded))
    }

    @Test
    fun `decode accepts CRLF sidecar files`() {
        val encoded = RockchipBackupManifestCodec.encode(
            RockchipBackupManifest(0, 1, 512, "b".repeat(64)),
        ).replace("\n", "\r\n")

        assertEquals(1L, RockchipBackupManifestCodec.decode(encoded).sectorCount)
    }

    @Test
    fun `bounded stream decode returns the same manifest`() {
        val manifest = RockchipBackupManifest(8, 2, 1024, "c".repeat(64))
        val bytes = RockchipBackupManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8)

        assertEquals(
            manifest,
            RockchipBackupManifestCodec.decode(ByteArrayInputStream(bytes), maximumBytes = 1024),
        )
    }

    @Test
    fun `bounded stream decode rejects oversized input`() {
        val bytes = ByteArray(513) { 'x'.code.toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            RockchipBackupManifestCodec.decode(ByteArrayInputStream(bytes), maximumBytes = 512)
        }
    }

    @Test
    fun `bounded stream decode rejects malformed UTF-8`() {
        val malformed = byteArrayOf(0xC3.toByte(), 0x28)

        assertThrows(IllegalArgumentException::class.java) {
            RockchipBackupManifestCodec.decode(ByteArrayInputStream(malformed), maximumBytes = 512)
        }
    }

    @Test
    fun `decode rejects duplicate fields`() {
        val encoded = RockchipBackupManifestCodec.encode(
            RockchipBackupManifest(0, 1, 512, "d".repeat(64)),
        ) + "sha256=${"e".repeat(64)}\n"

        assertThrows(IllegalArgumentException::class.java) {
            RockchipBackupManifestCodec.decode(encoded)
        }
    }

    @Test
    fun `decode rejects unknown fields`() {
        val encoded = RockchipBackupManifestCodec.encode(
            RockchipBackupManifest(0, 1, 512, "f".repeat(64)),
        ) + "deviceSerial=secret\n"

        assertThrows(IllegalArgumentException::class.java) {
            RockchipBackupManifestCodec.decode(encoded)
        }
    }

    @Test
    fun `decode rejects empty digest through manifest invariant`() {
        val encoded = RockchipBackupManifestCodec.encode(
            RockchipBackupManifest(0, 1, 512, "a".repeat(64)),
        ).replace("sha256=${"a".repeat(64)}", "sha256=")

        assertThrows(IllegalArgumentException::class.java) {
            RockchipBackupManifestCodec.decode(encoded)
        }
    }

    @Test
    fun `decode rejects non hexadecimal digest through manifest invariant`() {
        val encoded = RockchipBackupManifestCodec.encode(
            RockchipBackupManifest(0, 1, 512, "a".repeat(64)),
        ).replace("sha256=${"a".repeat(64)}", "sha256=${"g".repeat(64)}")

        assertThrows(IllegalArgumentException::class.java) {
            RockchipBackupManifestCodec.decode(encoded)
        }
    }

    @Test
    fun `completed result converts to identical manifest metadata`() {
        val result = RockchipBoundedBackupResult(
            startSector = 10,
            sectorCount = 2,
            byteCount = 1024,
            sha256 = "f".repeat(64),
        )

        assertEquals(
            RockchipBackupManifest(10, 2, 1024, "f".repeat(64)),
            result.toBackupManifest(),
        )
    }
}
