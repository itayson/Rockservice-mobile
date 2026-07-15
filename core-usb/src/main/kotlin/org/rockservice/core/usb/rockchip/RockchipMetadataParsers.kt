package org.rockservice.core.usb.rockchip

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RockchipChipInfo(
    val rawHex: String,
)

data class RockchipFlashId(
    val rawHex: String,
)

data class RockchipFlashInfo(
    val totalSectors: Long,
    val rawResponseLength: Int,
)

data class RockchipStorageInfo(
    val bitMask: Long,
    val firstAvailableStorageIndex: Int?,
)

data class RockchipCapabilityInfo(
    val rawHex: String,
)

object RockchipMetadataParsers {
    fun parseChipInfo(data: ByteArray): RockchipChipInfo {
        require(data.size == RockchipReadOnlyOperation.READ_CHIP_INFO.transferLength) {
            "Chip info must contain exactly 16 bytes."
        }
        return RockchipChipInfo(rawHex = data.toHex())
    }

    fun parseFlashId(data: ByteArray): RockchipFlashId {
        require(data.size == RockchipReadOnlyOperation.READ_FLASH_ID.transferLength) {
            "Flash ID must contain exactly 5 bytes."
        }
        return RockchipFlashId(rawHex = data.toHex())
    }

    fun parseFlashInfo(data: ByteArray): RockchipFlashInfo {
        val operation = RockchipReadOnlyOperation.READ_FLASH_INFO
        require(data.size in operation.minResponseLength..operation.maxResponseLength) {
            "Flash info response must contain between ${operation.minResponseLength} and ${operation.maxResponseLength} bytes."
        }

        val totalSectors = ByteBuffer.wrap(data, 0, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0xFFFF_FFFFL
        return RockchipFlashInfo(
            totalSectors = totalSectors,
            rawResponseLength = data.size,
        )
    }

    fun parseStorage(data: ByteArray): RockchipStorageInfo {
        require(data.size == LEGACY_STORAGE_RESPONSE_BYTES) {
            "Storage response must contain exactly $LEGACY_STORAGE_RESPONSE_BYTES bytes."
        }

        val bitMask = ByteBuffer.wrap(data)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0xFFFF_FFFFL
        val firstIndex = (0 until Int.SIZE_BITS).firstOrNull { bit ->
            (bitMask and (1L shl bit)) != 0L
        }
        return RockchipStorageInfo(
            bitMask = bitMask,
            firstAvailableStorageIndex = firstIndex,
        )
    }

    fun parseCapability(data: ByteArray): RockchipCapabilityInfo {
        require(data.size == RockchipReadOnlyOperation.READ_CAPABILITY.transferLength) {
            "Capability response must contain exactly 8 bytes."
        }
        return RockchipCapabilityInfo(rawHex = data.toHex())
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xFF) }

    private const val LEGACY_STORAGE_RESPONSE_BYTES = 4
}
