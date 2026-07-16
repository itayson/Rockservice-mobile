package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class RawFilesystemInspectorTest {
    @Test
    fun `detects ext4 superblock and block size`() {
        val bytes = ByteArray(2048)
        littleEndianBuffer(bytes).putInt(1024 + 0x18, 2)
        littleEndianBuffer(bytes).putShort(1024 + 0x38, 0xEF53.toShort())

        val result = RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))

        assertEquals(RawFilesystemType.EXT4, result.type)
        assertEquals(4096L, result.blockSizeBytes)
    }

    @Test
    fun `detects f2fs superblock and block size`() {
        val bytes = ByteArray(2048)
        littleEndianBuffer(bytes).putInt(1024, 0xF2F52010.toInt())
        littleEndianBuffer(bytes).putInt(1024 + 16, 12)

        val result = RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))

        assertEquals(RawFilesystemType.F2FS, result.type)
        assertEquals(4096L, result.blockSizeBytes)
    }

    @Test
    fun `detects erofs superblock and block size`() {
        val bytes = ByteArray(2048)
        littleEndianBuffer(bytes).putInt(1024, 0xE0F5E1E2.toInt())
        bytes[1024 + 12] = 12

        val result = RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))

        assertEquals(RawFilesystemType.EROFS, result.type)
        assertEquals(4096L, result.blockSizeBytes)
    }

    @Test
    fun `detects squashfs superblock and block size`() {
        val bytes = ByteArray(256)
        littleEndianBuffer(bytes).putInt(0, 0x73717368)
        littleEndianBuffer(bytes).putInt(12, 131072)

        val result = RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))

        assertEquals(RawFilesystemType.SQUASHFS, result.type)
        assertEquals(131072L, result.blockSizeBytes)
    }

    @Test
    fun `unknown prefix remains unknown`() {
        val result = RawFilesystemInspector().inspect(ByteArrayInputStream(ByteArray(2048)))

        assertEquals(RawFilesystemType.UNKNOWN, result.type)
        assertNull(result.blockSizeBytes)
    }

    @Test
    fun `truncated ext4 prefix does not create a false positive`() {
        val bytes = ByteArray(1081)
        bytes[1080] = 0x53

        val result = RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))

        assertEquals(RawFilesystemType.UNKNOWN, result.type)
    }

    @Test
    fun `invalid f2fs block size bits fail closed`() {
        val bytes = ByteArray(2048)
        littleEndianBuffer(bytes).putInt(1024, 0xF2F52010.toInt())
        littleEndianBuffer(bytes).putInt(1024 + 16, 31)

        expectIllegalArgument {
            RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `invalid erofs block size bits fail closed`() {
        val bytes = ByteArray(2048)
        littleEndianBuffer(bytes).putInt(1024, 0xE0F5E1E2.toInt())
        bytes[1024 + 12] = 2

        expectIllegalArgument {
            RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `invalid squashfs block size fail closed`() {
        val bytes = ByteArray(256)
        littleEndianBuffer(bytes).putInt(0, 0x73717368)
        littleEndianBuffer(bytes).putInt(12, 12345)

        expectIllegalArgument {
            RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `configured prefix limit is respected`() {
        val bytes = ByteArray(8192)
        val result = RawFilesystemInspector(maximumPrefixBytes = 2048)
            .inspect(ByteArrayInputStream(bytes))

        assertEquals(2048, result.bytesInspected)
    }

    private fun littleEndianBuffer(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun expectIllegalArgument(block: () -> Unit): IllegalArgumentException = try {
        block()
        fail("Expected IllegalArgumentException")
        error("unreachable")
    } catch (error: IllegalArgumentException) {
        error
    }
}
