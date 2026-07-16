package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RawFilesystemInspectorTest {
    @Test
    fun `detects ext4 superblock with ext4 specific feature and block size`() {
        val bytes = ByteArray(2048)
        littleEndianBuffer(bytes).putInt(1024 + 0x18, 2)
        littleEndianBuffer(bytes).putShort(1024 + 0x38, 0xEF53.toShort())
        littleEndianBuffer(bytes).putInt(1024 + 0x60, 0x40)

        val result = RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))

        assertEquals(RawFilesystemType.EXT4, result.type)
        assertEquals(4096L, result.blockSizeBytes)
        assertEquals(64, result.prefixSha256.length)
    }

    @Test
    fun `ext family magic without ext4 specific incompat feature is not classified as ext4`() {
        val bytes = ByteArray(2048)
        littleEndianBuffer(bytes).putInt(1024 + 0x18, 2)
        littleEndianBuffer(bytes).putShort(1024 + 0x38, 0xEF53.toShort())

        val result = RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))

        assertEquals(RawFilesystemType.UNKNOWN, result.type)
    }

    @Test
    fun `detects f2fs superblock and coherent geometry`() {
        val bytes = ByteArray(2048)
        val buffer = littleEndianBuffer(bytes)
        buffer.putInt(1024, 0xF2F52010.toInt())
        buffer.putInt(1024 + 8, 9)
        buffer.putInt(1024 + 12, 3)
        buffer.putInt(1024 + 16, 12)
        buffer.putInt(1024 + 20, 9)

        val result = RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))

        assertEquals(RawFilesystemType.F2FS, result.type)
        assertEquals(4096L, result.blockSizeBytes)
    }

    @Test
    fun `detects coherent 16k f2fs geometry`() {
        val bytes = ByteArray(2048)
        val buffer = littleEndianBuffer(bytes)
        buffer.putInt(1024, 0xF2F52010.toInt())
        buffer.putInt(1024 + 8, 9)
        buffer.putInt(1024 + 12, 5)
        buffer.putInt(1024 + 16, 14)
        buffer.putInt(1024 + 20, 9)

        val result = RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))

        assertEquals(RawFilesystemType.F2FS, result.type)
        assertEquals(16L * 1024L, result.blockSizeBytes)
    }

    @Test
    fun `f2fs magic with incompatible geometry remains unknown`() {
        val bytes = ByteArray(2048)
        val buffer = littleEndianBuffer(bytes)
        buffer.putInt(1024, 0xF2F52010.toInt())
        buffer.putInt(1024 + 8, 9)
        buffer.putInt(1024 + 12, 3)
        buffer.putInt(1024 + 16, 11)
        buffer.putInt(1024 + 20, 9)

        val result = RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))

        assertEquals(RawFilesystemType.UNKNOWN, result.type)
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
        val bytes = ByteArray(2048)
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
        assertEquals(64, result.prefixSha256.length)
    }

    @Test
    fun `prefix digest is deterministic and changes with content`() {
        val first = RawFilesystemInspector().inspect(ByteArrayInputStream(ByteArray(2048)))
        val second = RawFilesystemInspector().inspect(ByteArrayInputStream(ByteArray(2048)))
        val changedBytes = ByteArray(2048).also { it[0] = 1 }
        val changed = RawFilesystemInspector().inspect(ByteArrayInputStream(changedBytes))

        assertEquals(first.prefixSha256, second.prefixSha256)
        assertNotEquals(first.prefixSha256, changed.prefixSha256)
    }

    @Test
    fun `truncated ext4 prefix does not create a false positive`() {
        val bytes = ByteArray(1081)
        bytes[1080] = 0x53

        val result = RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))

        assertEquals(RawFilesystemType.UNKNOWN, result.type)
    }

    @Test
    fun `truncated f2fs prefix around magic boundary remains unknown`() {
        val bytes = ByteArray(1028)
        littleEndianBuffer(bytes).putInt(1024, 0xF2F52010.toInt())

        val result = RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))

        assertEquals(RawFilesystemType.UNKNOWN, result.type)
    }

    @Test
    fun `truncated erofs prefix around block size field remains unknown`() {
        val bytes = ByteArray(1024 + 12)
        littleEndianBuffer(bytes).putInt(1024, 0xE0F5E1E2.toInt())

        val result = RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))

        assertEquals(RawFilesystemType.UNKNOWN, result.type)
    }

    @Test
    fun `truncated squashfs prefix before block size remains unknown`() {
        val bytes = ByteArray(15)
        littleEndianBuffer(bytes).putInt(0, 0x73717368)

        val result = RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))

        assertEquals(RawFilesystemType.UNKNOWN, result.type)
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
        val bytes = ByteArray(2048)
        littleEndianBuffer(bytes).putInt(0, 0x73717368)
        littleEndianBuffer(bytes).putInt(12, 12345)

        expectIllegalArgument {
            RawFilesystemInspector().inspect(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun `path traversal text zip headers and arbitrary hash text remain uninterpreted`() {
        val hostileInputs = listOf(
            "../../../../system/etc/passwd".toByteArray(),
            byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(2044) { 0x7F },
            "sha256:".plus("f".repeat(64)).toByteArray(),
        )

        hostileInputs.forEach { input ->
            val padded = input.copyOf(maxOf(2048, input.size))
            val result = RawFilesystemInspector().inspect(ByteArrayInputStream(padded))
            assertEquals(RawFilesystemType.UNKNOWN, result.type)
        }
    }

    @Test
    fun `configured prefix limit is respected`() {
        val bytes = ByteArray(8192)
        val result = RawFilesystemInspector(maximumPrefixBytes = 2048)
            .inspect(ByteArrayInputStream(bytes))

        assertEquals(2048, result.bytesInspected)
    }

    @Test
    fun `io errors include bounded inspection context`() {
        val error = try {
            RawFilesystemInspector().inspect(
                object : InputStream() {
                    override fun read(): Int = throw IOException("boom")
                    override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("boom")
                },
            )
            fail("Expected IOException")
            error("unreachable")
        } catch (failure: IOException) {
            failure
        }

        assertTrue(error.message.orEmpty().contains("limite=4096"))
        assertTrue(error.message.orEmpty().contains("offset=0"))
        assertEquals("boom", error.cause?.message)
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
