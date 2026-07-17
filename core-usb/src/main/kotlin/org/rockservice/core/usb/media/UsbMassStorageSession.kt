package org.rockservice.core.usb.media

/** Read-only transport boundary for USB Mass Storage SCSI commands. */
fun interface UsbMassStorageCommandTransport {
    /**
     * Executes a SCSI command and returns exactly the device response bytes.
     * Implementations must reject data-out commands while real USB writes are disabled.
     */
    fun execute(commandBlock: ByteArray, expectedDataInBytes: Int): ByteArray
}

data class UsbBlockDeviceGeometry(
    val blockSizeBytes: Int,
    val blockCount: Long,
    val capacityBytes: Long,
)

/**
 * Minimal read-only USB Mass Storage session foundation.
 *
 * This class currently exposes only SCSI READ CAPACITY (10). It does not implement any
 * destructive command and does not enable physical USB writes.
 */
class UsbMassStorageSession(
    private val transport: UsbMassStorageCommandTransport,
) {
    fun readGeometry(): UsbBlockDeviceGeometry {
        val response = transport.execute(
            commandBlock = byteArrayOf(
                SCSI_READ_CAPACITY_10,
                0, 0, 0, 0, 0, 0, 0, 0, 0,
            ),
            expectedDataInBytes = READ_CAPACITY_10_RESPONSE_BYTES,
        )

        require(response.size == READ_CAPACITY_10_RESPONSE_BYTES) {
            "READ CAPACITY (10) deve retornar exatamente 8 bytes; recebido: ${response.size}."
        }

        val lastLogicalBlockAddress = response.readUnsignedIntBigEndian(offset = 0)
        require(lastLogicalBlockAddress != MAX_UNSIGNED_INT) {
            "O dispositivo excede o limite do READ CAPACITY (10); READ CAPACITY (16) é necessário."
        }

        val blockSize = response.readUnsignedIntBigEndian(offset = 4)
        require(blockSize in 1..Int.MAX_VALUE.toLong()) {
            "O tamanho de bloco reportado pelo dispositivo é inválido: $blockSize bytes."
        }

        val blockCount = Math.addExact(lastLogicalBlockAddress, 1L)
        val capacityBytes = Math.multiplyExact(blockCount, blockSize)

        return UsbBlockDeviceGeometry(
            blockSizeBytes = blockSize.toInt(),
            blockCount = blockCount,
            capacityBytes = capacityBytes,
        )
    }

    private fun ByteArray.readUnsignedIntBigEndian(offset: Int): Long =
        ((this[offset].toLong() and 0xffL) shl 24) or
            ((this[offset + 1].toLong() and 0xffL) shl 16) or
            ((this[offset + 2].toLong() and 0xffL) shl 8) or
            (this[offset + 3].toLong() and 0xffL)

    private companion object {
        const val SCSI_READ_CAPACITY_10: Byte = 0x25
        const val READ_CAPACITY_10_RESPONSE_BYTES = 8
        const val MAX_UNSIGNED_INT = 0xffff_ffffL
    }
}
