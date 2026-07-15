package org.rockservice.core.usb.rockchip

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class RockchipReadOnlyOperation(
    val opcode: Int,
    val transferLength: Int,
    val minResponseLength: Int = transferLength,
    val maxResponseLength: Int = transferLength,
) {
    TEST_UNIT_READY(opcode = 0x00, transferLength = 0),
    READ_FLASH_ID(opcode = 0x01, transferLength = 5),
    READ_FLASH_INFO(
        opcode = 0x1A,
        transferLength = 11,
        minResponseLength = 11,
        maxResponseLength = 512,
    ),
    READ_CHIP_INFO(opcode = 0x1B, transferLength = 16),
    READ_STORAGE(opcode = 0x2B, transferLength = 4),
    READ_CAPABILITY(opcode = 0xAA, transferLength = 8),
}

enum class RockchipReadOnlyTestUnitSubCode(val wireValue: Int) {
    NONE(0x00),
    GET_USER_SECTOR_PROGRESS(0xF9),
}

enum class RockchipCommandStatus(val wireValue: Int) {
    PASSED(0),
    FAILED(1),
    PHASE_ERROR(2),
    ;

    companion object {
        fun fromWireValue(value: Int): RockchipCommandStatus =
            entries.singleOrNull { status -> status.wireValue == value }
                ?: throw IllegalArgumentException("Unsupported Rockchip CSW status: $value")
    }
}

data class RockchipCommandStatusWrapper(
    val tag: Int,
    val dataResidue: Long,
    val status: RockchipCommandStatus,
)

data class RockchipReadOnlyExchangeResult(
    val operation: RockchipReadOnlyOperation,
    val data: ByteArray,
    val status: RockchipCommandStatusWrapper,
)

object RockchipReadOnlyProtocolCodec {
    const val COMMAND_BLOCK_WRAPPER_SIZE: Int = 31
    const val COMMAND_STATUS_WRAPPER_SIZE: Int = 13

    private const val CBW_SIGNATURE: Int = 0x43425355
    private const val CSW_SIGNATURE: Int = 0x53425355
    private const val DIRECTION_IN: Int = 0x80
    private const val COMMAND_BLOCK_LENGTH: Int = 0x06

    fun encodeCommand(
        operation: RockchipReadOnlyOperation,
        tag: Int,
        testUnitSubCode: RockchipReadOnlyTestUnitSubCode = RockchipReadOnlyTestUnitSubCode.NONE,
    ): ByteArray {
        require(
            operation == RockchipReadOnlyOperation.TEST_UNIT_READY ||
                testUnitSubCode == RockchipReadOnlyTestUnitSubCode.NONE
        ) {
            "Test-unit subcodes are valid only for TEST_UNIT_READY."
        }

        return ByteArray(COMMAND_BLOCK_WRAPPER_SIZE).also { bytes ->
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0, CBW_SIGNATURE)
            buffer.putInt(4, tag)
            buffer.putInt(8, operation.transferLength)
            bytes[12] = DIRECTION_IN.toByte()
            bytes[13] = 0
            bytes[14] = COMMAND_BLOCK_LENGTH.toByte()
            bytes[15] = operation.opcode.toByte()
            bytes[16] = testUnitSubCode.wireValue.toByte()
        }
    }

    fun decodeStatus(
        bytes: ByteArray,
        expectedTag: Int,
    ): RockchipCommandStatusWrapper {
        require(bytes.size == COMMAND_STATUS_WRAPPER_SIZE) {
            "Rockchip CSW must contain exactly $COMMAND_STATUS_WRAPPER_SIZE bytes."
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val signature = buffer.getInt(0)
        require(signature == CSW_SIGNATURE) { "Invalid Rockchip CSW signature." }

        val tag = buffer.getInt(4)
        require(tag == expectedTag) { "Rockchip CSW tag does not match the command tag." }

        return RockchipCommandStatusWrapper(
            tag = tag,
            dataResidue = buffer.getInt(8).toLong() and 0xFFFF_FFFFL,
            status = RockchipCommandStatus.fromWireValue(bytes[12].toInt() and 0xFF),
        )
    }

    fun decodeExchange(
        operation: RockchipReadOnlyOperation,
        data: ByteArray,
        statusBytes: ByteArray,
        expectedTag: Int,
    ): RockchipReadOnlyExchangeResult {
        require(data.size in operation.minResponseLength..operation.maxResponseLength) {
            "Unexpected ${operation.name} response length: ${data.size}."
        }

        return RockchipReadOnlyExchangeResult(
            operation = operation,
            data = data.copyOf(),
            status = decodeStatus(statusBytes, expectedTag),
        )
    }
}
