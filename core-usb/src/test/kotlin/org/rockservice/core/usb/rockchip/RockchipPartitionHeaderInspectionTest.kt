package org.rockservice.core.usb.rockchip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RockchipPartitionHeaderInspectionTest {
    @Test
    fun `fixed inspection detects MBR and GPT signatures`() {
        val data = ByteArray(RockchipPartitionHeaderInspector.EXPECTED_BYTES)
        data[510] = 0x55
        data[511] = 0xAA.toByte()
        "EFI PART".encodeToByteArray().copyInto(data, destinationOffset = 512)

        val result = RockchipPartitionHeaderInspector.inspect(data)

        assertEquals(0L, result.startSector)
        assertEquals(2, result.sectorCount)
        assertEquals(1024, result.bytesInspected)
        assertTrue(result.hasMbrSignature)
        assertTrue(result.hasGptSignature)
    }

    @Test
    fun `fixed inspection detects MBR independently from GPT`() {
        val data = ByteArray(RockchipPartitionHeaderInspector.EXPECTED_BYTES)
        data[510] = 0x55
        data[511] = 0xAA.toByte()

        val result = RockchipPartitionHeaderInspector.inspect(data)

        assertTrue(result.hasMbrSignature)
        assertFalse(result.hasGptSignature)
    }

    @Test
    fun `fixed inspection detects GPT independently from MBR`() {
        val data = ByteArray(RockchipPartitionHeaderInspector.EXPECTED_BYTES)
        "EFI PART".encodeToByteArray().copyInto(data, destinationOffset = 512)

        val result = RockchipPartitionHeaderInspector.inspect(data)

        assertFalse(result.hasMbrSignature)
        assertTrue(result.hasGptSignature)
    }

    @Test
    fun `fixed inspection returns deterministic sha256 without retaining raw bytes`() {
        val result = RockchipPartitionHeaderInspector.inspect(
            ByteArray(RockchipPartitionHeaderInspector.EXPECTED_BYTES),
        )

        assertEquals(
            "5f70bf18a086007016e948b04aed3b82103a36bea41755b6cddfaf10ace3c6ef",
            result.sha256,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fixed inspection rejects truncated input`() {
        RockchipPartitionHeaderInspector.inspect(
            ByteArray(RockchipPartitionHeaderInspector.EXPECTED_BYTES - 1),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fixed inspection rejects oversized input`() {
        RockchipPartitionHeaderInspector.inspect(
            ByteArray(RockchipPartitionHeaderInspector.EXPECTED_BYTES + 1),
        )
    }
}
