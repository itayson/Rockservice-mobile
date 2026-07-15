package org.rockservice.feature.firmware

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBootImageParserTest {
    private val parser = AndroidBootImageParser(
        maximumInputBytes = 32L * 1024 * 1024,
        maximumImageBytes = 32L * 1024 * 1024,
        maximumLegacyPageSizeBytes = 64L * 1024,
    )

    @Test
    fun `parses v0 legacy layout`() {
        val fixture = bootImage(
            version = 0,
            pageSize = 2048,
            kernelSize = 100,
            ramdiskSize = 50,
            secondSize = 20,
        )

        val result = parser.parse(ByteArrayInputStream(fixture.bytes))

        assertEquals(AndroidBootHeaderVersion.V0, result.headerVersion)
        assertEquals(2048L, result.pageSizeBytes)
        assertEquals(1632L, result.headerSizeBytes)
        assertEquals(8192L, result.minimumImageSizeBytes)
        assertEquals(fixture.bytes.size.toLong(), result.bytesConsumed)
        assertSection(result, AndroidBootSectionType.KERNEL, offset = 2048, size = 100, padded = 2048)
        assertSection(result, AndroidBootSectionType.RAMDISK, offset = 4096, size = 50, padded = 2048)
        assertSection(result, AndroidBootSectionType.SECOND_STAGE, offset = 6144, size = 20, padded = 2048)
        assertNull(result.recoveryDtboOffsetBytes)
    }

    @Test
    fun `parses v1 recovery dtbo layout and validates offset`() {
        val fixture = bootImage(
            version = 1,
            pageSize = 2048,
            kernelSize = 100,
            ramdiskSize = 50,
            recoveryDtboSize = 30,
        )

        val result = parser.parse(ByteArrayInputStream(fixture.bytes))

        assertEquals(AndroidBootHeaderVersion.V1, result.headerVersion)
        assertEquals(1648L, result.headerSizeBytes)
        assertEquals(6144L, result.recoveryDtboOffsetBytes)
        assertSection(result, AndroidBootSectionType.RECOVERY_DTBO, offset = 6144, size = 30, padded = 2048)
        assertEquals(8192L, result.minimumImageSizeBytes)
    }

    @Test
    fun `parses v2 dtb after recovery dtbo`() {
        val fixture = bootImage(
            version = 2,
            pageSize = 4096,
            kernelSize = 100,
            ramdiskSize = 50,
            secondSize = 20,
            recoveryDtboSize = 30,
            dtbSize = 40,
        )

        val result = parser.parse(ByteArrayInputStream(fixture.bytes))

        assertEquals(AndroidBootHeaderVersion.V2, result.headerVersion)
        assertEquals(1660L, result.headerSizeBytes)
        assertSection(result, AndroidBootSectionType.KERNEL, offset = 4096, size = 100, padded = 4096)
        assertSection(result, AndroidBootSectionType.RAMDISK, offset = 8192, size = 50, padded = 4096)
        assertSection(result, AndroidBootSectionType.SECOND_STAGE, offset = 12288, size = 20, padded = 4096)
        assertSection(result, AndroidBootSectionType.RECOVERY_DTBO, offset = 16384, size = 30, padded = 4096)
        assertSection(result, AndroidBootSectionType.DTB, offset = 20480, size = 40, padded = 4096)
        assertEquals(24576L, result.minimumImageSizeBytes)
    }

    @Test
    fun `parses v3 fixed 4096 byte page layout`() {
        val fixture = bootImage(
            version = 3,
            kernelSize = 100,
            ramdiskSize = 200,
        )

        val result = parser.parse(ByteArrayInputStream(fixture.bytes))

        assertEquals(AndroidBootHeaderVersion.V3, result.headerVersion)
        assertEquals(4096L, result.pageSizeBytes)
        assertEquals(1580L, result.headerSizeBytes)
        assertSection(result, AndroidBootSectionType.KERNEL, offset = 4096, size = 100, padded = 4096)
        assertSection(result, AndroidBootSectionType.RAMDISK, offset = 8192, size = 200, padded = 4096)
        assertEquals(12288L, result.minimumImageSizeBytes)
    }

    @Test
    fun `parses v4 boot signature after ramdisk`() {
        val fixture = bootImage(
            version = 4,
            kernelSize = 100,
            ramdiskSize = 200,
            signatureSize = 300,
        )

        val result = parser.parse(ByteArrayInputStream(fixture.bytes))

        assertEquals(AndroidBootHeaderVersion.V4, result.headerVersion)
        assertEquals(1584L, result.headerSizeBytes)
        assertSection(result, AndroidBootSectionType.BOOT_SIGNATURE, offset = 12288, size = 300, padded = 4096)
        assertEquals(16384L, result.minimumImageSizeBytes)
    }

    @Test
    fun `preserves encoded os version metadata`() {
        val fixture = bootImage(
            version = 4,
            kernelSize = 1,
            osVersionEncoded = 0x12345678L,
        )

        val result = parser.parse(ByteArrayInputStream(fixture.bytes))

        assertEquals(0x12345678L, result.osVersionEncoded)
    }

    @Test
    fun `supports partial input stream reads and zero skip`() {
        val fixture = bootImage(
            version = 2,
            pageSize = 2048,
            kernelSize = 100,
            ramdiskSize = 50,
        )

        val result = parser.parse(PartialInputStream(fixture.bytes, maximumChunk = 5))

        assertEquals(fixture.bytes.size.toLong(), result.bytesConsumed)
        assertEquals(AndroidBootHeaderVersion.V2, result.headerVersion)
    }

    @Test
    fun `accepts legacy declared header extension inside header page`() {
        val fixture = bootImage(
            version = 1,
            pageSize = 4096,
            kernelSize = 1,
            headerSizeOverride = 1800,
        )

        val result = parser.parse(ByteArrayInputStream(fixture.bytes))

        assertEquals(1800L, result.headerSizeBytes)
        assertEquals(4096L, result.sections.first().paddedSizeBytes)
    }

    @Test
    fun `accepts modern declared header extension inside fixed page`() {
        val fixture = bootImage(
            version = 4,
            kernelSize = 1,
            headerSizeOverride = 1800,
        )

        val result = parser.parse(ByteArrayInputStream(fixture.bytes))

        assertEquals(1800L, result.headerSizeBytes)
        assertEquals(4096L, result.sections.first().paddedSizeBytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid magic`() {
        val fixture = bootImage(version = 0, pageSize = 2048, kernelSize = 1)
        fixture.bytes[0] = 'X'.code.toByte()
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unsupported header version`() {
        val fixture = bootImage(version = 0, pageSize = 2048, kernelSize = 1)
        putU32(fixture.bytes, offset = 40, value = 5)
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects zero legacy page size`() {
        val fixture = bootImage(version = 0, pageSize = 2048, kernelSize = 1)
        putU32(fixture.bytes, offset = 36, value = 0)
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects legacy page smaller than header structure`() {
        val fixture = bootImage(version = 0, pageSize = 2048, kernelSize = 1)
        putU32(fixture.bytes, offset = 36, value = 1024)
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects legacy page above configured resource limit`() {
        val fixture = bootImage(version = 0, pageSize = 2048, kernelSize = 1)
        putU32(fixture.bytes, offset = 36, value = 128 * 1024L)
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects v1 declared header smaller than structure`() {
        val fixture = bootImage(version = 1, pageSize = 2048, kernelSize = 1)
        putU32(fixture.bytes, offset = 1644, value = 1647)
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects v2 declared header smaller than structure`() {
        val fixture = bootImage(version = 2, pageSize = 2048, kernelSize = 1)
        putU32(fixture.bytes, offset = 1644, value = 1659)
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects modern header larger than fixed page`() {
        val fixture = bootImage(version = 4, kernelSize = 1)
        putU32(fixture.bytes, offset = 20, value = 5000)
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects recovery dtbo offset inconsistent with computed layout`() {
        val fixture = bootImage(
            version = 1,
            pageSize = 2048,
            kernelSize = 100,
            ramdiskSize = 50,
            recoveryDtboSize = 30,
        )
        putU64(fixture.bytes, offset = 1636, value = 1234)
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects recovery dtbo offset above signed long range`() {
        val fixture = bootImage(
            version = 1,
            pageSize = 2048,
            recoveryDtboSize = 1,
        )
        repeat(8) { index -> fixture.bytes[1636 + index] = 0xFF.toByte() }
        parser.parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects truncated payload layout`() {
        val fixture = bootImage(
            version = 4,
            kernelSize = 100,
            ramdiskSize = 200,
            signatureSize = 300,
        )
        parser.parse(ByteArrayInputStream(fixture.bytes.copyOf(fixture.bytes.size - 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects layout above configured image limit`() {
        val fixture = bootImage(
            version = 3,
            kernelSize = 5000,
            ramdiskSize = 5000,
        )
        AndroidBootImageParser(
            maximumInputBytes = 32L * 1024 * 1024,
            maximumImageBytes = 8192,
            maximumLegacyPageSizeBytes = 64L * 1024,
        ).parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects layout above configured input limit before payload scan`() {
        val fixture = bootImage(
            version = 3,
            kernelSize = 5000,
        )
        AndroidBootImageParser(
            maximumInputBytes = 8192,
            maximumImageBytes = 32L * 1024 * 1024,
            maximumLegacyPageSizeBytes = 64L * 1024,
        ).parse(ByteArrayInputStream(fixture.bytes))
    }

    @Test
    fun `zero length payload sections are omitted from section list`() {
        val fixture = bootImage(version = 3)

        val result = parser.parse(ByteArrayInputStream(fixture.bytes))

        assertEquals(listOf(AndroidBootSectionType.HEADER), result.sections.map { it.type })
        assertEquals(4096L, result.minimumImageSizeBytes)
    }

    private fun assertSection(
        result: AndroidBootImageMetadata,
        type: AndroidBootSectionType,
        offset: Long,
        size: Long,
        padded: Long,
    ) {
        val section = result.sections.single { item -> item.type == type }
        assertEquals(offset, section.offsetBytes)
        assertEquals(size, section.sizeBytes)
        assertEquals(padded, section.paddedSizeBytes)
    }

    private data class BootFixture(val bytes: ByteArray)

    private fun bootImage(
        version: Int,
        pageSize: Int = 4096,
        kernelSize: Int = 0,
        ramdiskSize: Int = 0,
        secondSize: Int = 0,
        recoveryDtboSize: Int = 0,
        dtbSize: Int = 0,
        signatureSize: Int = 0,
        headerSizeOverride: Int? = null,
        osVersionEncoded: Long = 0,
    ): BootFixture {
        require(version in 0..4)
        val structSize = when (version) {
            0 -> 1632
            1 -> 1648
            2 -> 1660
            3 -> 1580
            else -> 1584
        }
        val effectivePageSize = if (version >= 3) 4096 else pageSize
        val header = ByteArray(structSize)
        "ANDROID!".encodeToByteArray().copyInto(header, destinationOffset = 0)

        if (version <= 2) {
            putU32(header, 8, kernelSize.toLong())
            putU32(header, 16, ramdiskSize.toLong())
            putU32(header, 24, secondSize.toLong())
            putU32(header, 36, pageSize.toLong())
            putU32(header, 40, version.toLong())
            putU32(header, 44, osVersionEncoded)

            if (version >= 1) {
                putU32(header, 1632, recoveryDtboSize.toLong())
                val recoveryOffset = computeLegacyRecoveryOffset(
                    pageSize = pageSize.toLong(),
                    kernelSize = kernelSize.toLong(),
                    ramdiskSize = ramdiskSize.toLong(),
                    secondSize = secondSize.toLong(),
                )
                putU64(header, 1636, recoveryOffset)
                putU32(
                    header,
                    1644,
                    (headerSizeOverride ?: structSize).toLong(),
                )
            }
            if (version >= 2) {
                putU32(header, 1648, dtbSize.toLong())
            }
        } else {
            putU32(header, 8, kernelSize.toLong())
            putU32(header, 12, ramdiskSize.toLong())
            putU32(header, 16, osVersionEncoded)
            putU32(header, 20, (headerSizeOverride ?: structSize).toLong())
            putU32(header, 40, version.toLong())
            if (version >= 4) {
                putU32(header, 1580, signatureSize.toLong())
            }
        }

        val output = ByteArrayOutputStream()
        output.write(header)
        output.write(ByteArray(effectivePageSize - header.size))
        appendPadded(output, kernelSize, effectivePageSize)
        appendPadded(output, ramdiskSize, effectivePageSize)
        if (version <= 2) {
            appendPadded(output, secondSize, effectivePageSize)
        }
        if (version >= 1 && version <= 2) {
            appendPadded(output, recoveryDtboSize, effectivePageSize)
        }
        if (version == 2) {
            appendPadded(output, dtbSize, effectivePageSize)
        }
        if (version == 4) {
            appendPadded(output, signatureSize, effectivePageSize)
        }

        return BootFixture(output.toByteArray())
    }

    private fun appendPadded(output: ByteArrayOutputStream, size: Int, pageSize: Int) {
        if (size == 0) return
        output.write(ByteArray(size) { index -> (index and 0xFF).toByte() })
        val remainder = size % pageSize
        if (remainder != 0) {
            output.write(ByteArray(pageSize - remainder))
        }
    }

    private fun computeLegacyRecoveryOffset(
        pageSize: Long,
        kernelSize: Long,
        ramdiskSize: Long,
        secondSize: Long,
    ): Long =
        pageSize + alignForFixture(kernelSize, pageSize) +
            alignForFixture(ramdiskSize, pageSize) +
            alignForFixture(secondSize, pageSize)

    private fun alignForFixture(value: Long, pageSize: Long): Long {
        if (value == 0L) return 0L
        val remainder = value % pageSize
        return if (remainder == 0L) value else value + pageSize - remainder
    }

    private fun putU32(target: ByteArray, offset: Int, value: Long) {
        require(value in 0..0xFFFF_FFFFL)
        repeat(4) { index ->
            target[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte()
        }
    }

    private fun putU64(target: ByteArray, offset: Int, value: Long) {
        require(value >= 0)
        repeat(8) { index ->
            target[offset + index] = ((value ushr (index * 8)) and 0xFF).toByte()
        }
    }

    private class PartialInputStream(
        private val bytes: ByteArray,
        private val maximumChunk: Int,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int =
            if (position >= bytes.size) -1 else bytes[position++].toInt() and 0xFF

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, maximumChunk, bytes.size - position)
            bytes.copyInto(
                destination = target,
                destinationOffset = offset,
                startIndex = position,
                endIndex = position + count,
            )
            position += count
            return count
        }

        override fun skip(byteCount: Long): Long = 0
    }
}
