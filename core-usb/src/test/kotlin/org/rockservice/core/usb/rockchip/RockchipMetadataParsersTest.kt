package org.rockservice.core.usb.rockchip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RockchipMetadataParsersTest {
    @Test
    fun `parses chip info as stable uppercase hex`() {
        val result = RockchipMetadataParsers.parseChipInfo(
            ByteArray(16) { index -> index.toByte() }
        )

        assertEquals("000102030405060708090A0B0C0D0E0F", result.rawHex)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects truncated chip info`() {
        RockchipMetadataParsers.parseChipInfo(ByteArray(15))
    }

    @Test
    fun `parses five byte flash id`() {
        val result = RockchipMetadataParsers.parseFlashId(
            byteArrayOf(0xEF.toByte(), 0xAA.toByte(), 0x21, 0x00, 0x15)
        )

        assertEquals("EFAA210015", result.rawHex)
    }

    @Test
    fun `parses unsigned little endian total sector count from flash info`() {
        val data = ByteArray(11)
        data[0] = 0x78
        data[1] = 0x56
        data[2] = 0x34
        data[3] = 0xF2.toByte()

        val result = RockchipMetadataParsers.parseFlashInfo(data)

        assertEquals(0xF2345678L, result.totalSectors)
        assertEquals(11, result.rawResponseLength)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects flash info shorter than protocol minimum`() {
        RockchipMetadataParsers.parseFlashInfo(ByteArray(10))
    }

    @Test
    fun `parses storage bitmask and first available index`() {
        val result = RockchipMetadataParsers.parseStorage(
            byteArrayOf(0x20, 0x01, 0x00, 0x00)
        )

        assertEquals(0x120L, result.bitMask)
        assertEquals(5, result.firstAvailableStorageIndex)
    }

    @Test
    fun `empty storage bitmask has no selected index`() {
        val result = RockchipMetadataParsers.parseStorage(ByteArray(4))

        assertNull(result.firstAvailableStorageIndex)
    }

    @Test
    fun `parses capability payload without inventing unsupported semantics`() {
        val result = RockchipMetadataParsers.parseCapability(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        )

        assertEquals("0102030405060708", result.rawHex)
    }
}
