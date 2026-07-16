package org.rockservice.core.usb.rockchip

import java.security.MessageDigest

/**
 * Sanitized result of inspecting the fixed LBA 0-1 partition-header window.
 *
 * Raw storage bytes are intentionally not retained by this model.
 */
internal data class RockchipPartitionHeaderInspection(
    val startSector: Long,
    val sectorCount: Int,
    val bytesInspected: Int,
    val sha256: String,
    val hasMbrSignature: Boolean,
    val hasGptSignature: Boolean,
)

/**
 * Defines and parses the only partition-header inspection window allowed before partition backup.
 *
 * The request is intentionally fixed at two 512-byte sectors beginning at LBA 0. Callers cannot
 * provide arbitrary offsets or lengths through this boundary.
 */
internal object RockchipPartitionHeaderInspector {
    const val START_SECTOR: Long = 0L
    const val SECTOR_COUNT: Int = 2
    const val SECTOR_SIZE_BYTES: Int = 512
    const val EXPECTED_BYTES: Int = SECTOR_COUNT * SECTOR_SIZE_BYTES

    private const val MBR_SIGNATURE_OFFSET = 510
    private const val GPT_SIGNATURE_OFFSET = SECTOR_SIZE_BYTES
    private val GPT_SIGNATURE = byteArrayOf(
        'E'.code.toByte(),
        'F'.code.toByte(),
        'I'.code.toByte(),
        ' '.code.toByte(),
        'P'.code.toByte(),
        'A'.code.toByte(),
        'R'.code.toByte(),
        'T'.code.toByte(),
    )

    /** Inspects exactly the fixed 1024-byte window and returns only sanitized structural evidence. */
    fun inspect(data: ByteArray): RockchipPartitionHeaderInspection {
        require(data.size == EXPECTED_BYTES) {
            "Partition-header inspection requires exactly $EXPECTED_BYTES bytes; received ${data.size}."
        }

        val hasMbrSignature =
            (data[MBR_SIGNATURE_OFFSET].toInt() and 0xFF) == 0x55 &&
                (data[MBR_SIGNATURE_OFFSET + 1].toInt() and 0xFF) == 0xAA
        val hasGptSignature = GPT_SIGNATURE.indices.all { index ->
            data[GPT_SIGNATURE_OFFSET + index] == GPT_SIGNATURE[index]
        }

        return RockchipPartitionHeaderInspection(
            startSector = START_SECTOR,
            sectorCount = SECTOR_COUNT,
            bytesInspected = data.size,
            sha256 = data.sha256(),
            hasMbrSignature = hasMbrSignature,
            hasGptSignature = hasGptSignature,
        )
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }
}
