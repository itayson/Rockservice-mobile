package org.rockservice.core.usb.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ADB transport commands supported by the defensive protocol codec. */
enum class AdbCommand(
    val wireValue: Long,
) {
    SYNC(fourCc("SYNC")),
    CNXN(fourCc("CNXN")),
    AUTH(fourCc("AUTH")),
    OPEN(fourCc("OPEN")),
    OKAY(fourCc("OKAY")),
    CLSE(fourCc("CLSE")),
    WRTE(fourCc("WRTE")),
    ;

    companion object {
        fun fromWireValue(value: Long): AdbCommand =
            entries.singleOrNull { command -> command.wireValue == value }
                ?: throw IllegalArgumentException(
                    "Comando ADB nao suportado: 0x${value.toString(16).uppercase()}.",
                )
    }
}

/** Authentication message types carried in the first ADB argument. */
enum class AdbAuthType(
    val wireValue: Long,
) {
    TOKEN(1),
    SIGNATURE(2),
    RSA_PUBLIC_KEY(3),
}

/** Parsed ADB header before its payload is read from a transport. */
data class AdbMessageHeader(
    val command: AdbCommand,
    val arg0: Long,
    val arg1: Long,
    val dataLength: Int,
    val dataChecksum: Long,
)

/** Complete validated ADB transport message. */
data class AdbMessage(
    val command: AdbCommand,
    val arg0: Long,
    val arg1: Long,
    val payload: ByteArray = ByteArray(0),
) {
    init {
        require(arg0 in UINT32_RANGE) { "arg0 ADB deve caber em uint32." }
        require(arg1 in UINT32_RANGE) { "arg1 ADB deve caber em uint32." }
    }
}

/**
 * Encodes and validates the framing shared by USB and socket ADB transports.
 *
 * The codec performs no device I/O and accepts only a bounded payload size.
 */
object AdbProtocolCodec {
    const val HEADER_SIZE_BYTES = 24
    const val DEFAULT_PROTOCOL_VERSION: Long = 0x01000001L
    const val DEFAULT_MAX_DATA_BYTES: Long = 1024L * 1024L
    const val MAXIMUM_PAYLOAD_BYTES = 1024 * 1024

    /** Encodes one complete frame with checksum and command magic. */
    fun encode(message: AdbMessage): ByteArray {
        require(message.payload.size <= MAXIMUM_PAYLOAD_BYTES) {
            "Payload ADB possui ${message.payload.size} bytes; limite: $MAXIMUM_PAYLOAD_BYTES."
        }
        val checksum = checksum(message.payload)
        return ByteArray(HEADER_SIZE_BYTES + message.payload.size).also { frame ->
            val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0, message.command.wireValue.toInt())
            buffer.putInt(4, message.arg0.toInt())
            buffer.putInt(8, message.arg1.toInt())
            buffer.putInt(12, message.payload.size)
            buffer.putInt(16, checksum.toInt())
            buffer.putInt(20, message.command.wireValue.inv().toInt())
            message.payload.copyInto(frame, destinationOffset = HEADER_SIZE_BYTES)
        }
    }

    /** Decodes and validates exactly one 24-byte ADB header. */
    fun decodeHeader(bytes: ByteArray): AdbMessageHeader {
        require(bytes.size == HEADER_SIZE_BYTES) {
            "Header ADB deve conter exatamente $HEADER_SIZE_BYTES bytes."
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val commandValue = buffer.getInt(0).toLong() and UINT32_MAX
        val magic = buffer.getInt(20).toLong() and UINT32_MAX
        require(magic == (commandValue xor UINT32_MAX)) {
            "Magic ADB nao corresponde ao comando recebido."
        }
        val command = AdbCommand.fromWireValue(commandValue)
        val dataLengthLong = buffer.getInt(12).toLong() and UINT32_MAX
        require(dataLengthLong <= MAXIMUM_PAYLOAD_BYTES.toLong()) {
            "Payload ADB declarado possui $dataLengthLong bytes; limite: $MAXIMUM_PAYLOAD_BYTES."
        }
        return AdbMessageHeader(
            command = command,
            arg0 = buffer.getInt(4).toLong() and UINT32_MAX,
            arg1 = buffer.getInt(8).toLong() and UINT32_MAX,
            dataLength = dataLengthLong.toInt(),
            dataChecksum = buffer.getInt(16).toLong() and UINT32_MAX,
        )
    }

    /** Validates a payload against a previously decoded header and builds the complete message. */
    fun decodePayload(
        header: AdbMessageHeader,
        payload: ByteArray,
        requireChecksum: Boolean = true,
    ): AdbMessage {
        require(payload.size == header.dataLength) {
            "Payload ADB possui ${payload.size} bytes; header declara ${header.dataLength}."
        }
        if (requireChecksum) {
            val actualChecksum = checksum(payload)
            require(actualChecksum == header.dataChecksum) {
                "Checksum ADB divergente: esperado 0x${header.dataChecksum.toString(16).uppercase()}, " +
                    "calculado 0x${actualChecksum.toString(16).uppercase()}."
            }
        }
        return AdbMessage(
            command = header.command,
            arg0 = header.arg0,
            arg1 = header.arg1,
            payload = payload.copyOf(),
        )
    }

    /** Decodes exactly one complete frame. */
    fun decode(
        frame: ByteArray,
        requireChecksum: Boolean = true,
    ): AdbMessage {
        require(frame.size >= HEADER_SIZE_BYTES) { "Frame ADB truncado antes do header completo." }
        val header = decodeHeader(frame.copyOfRange(0, HEADER_SIZE_BYTES))
        require(frame.size == HEADER_SIZE_BYTES + header.dataLength) {
            "Frame ADB possui ${frame.size} bytes; esperado: ${HEADER_SIZE_BYTES + header.dataLength}."
        }
        return decodePayload(
            header = header,
            payload = frame.copyOfRange(HEADER_SIZE_BYTES, frame.size),
            requireChecksum = requireChecksum,
        )
    }

    /** Creates the initial host connection message with a null-terminated banner. */
    fun connect(
        banner: String,
        protocolVersion: Long = DEFAULT_PROTOCOL_VERSION,
        maxDataBytes: Long = DEFAULT_MAX_DATA_BYTES,
    ): AdbMessage {
        require(protocolVersion in UINT32_RANGE) { "Versao do protocolo ADB deve caber em uint32." }
        require(maxDataBytes in 1L..MAXIMUM_PAYLOAD_BYTES.toLong()) {
            "maxDataBytes ADB deve estar entre 1 e $MAXIMUM_PAYLOAD_BYTES."
        }
        return AdbMessage(
            command = AdbCommand.CNXN,
            arg0 = protocolVersion,
            arg1 = maxDataBytes,
            payload = nullTerminatedUtf8(banner, "banner ADB"),
        )
    }

    /** Creates an OPEN request for one explicitly chosen ADB service. */
    fun open(
        localId: Long,
        service: String,
    ): AdbMessage {
        require(localId in 1L..UINT32_MAX) { "localId ADB deve estar entre 1 e uint32 max." }
        return AdbMessage(
            command = AdbCommand.OPEN,
            arg0 = localId,
            arg1 = 0,
            payload = nullTerminatedUtf8(service, "servico ADB"),
        )
    }

    /** Creates an AUTH response carrying a signature of the device token. */
    fun authSignature(signature: ByteArray): AdbMessage {
        require(signature.isNotEmpty()) { "Assinatura AUTH ADB nao pode ser vazia." }
        return AdbMessage(
            command = AdbCommand.AUTH,
            arg0 = AdbAuthType.SIGNATURE.wireValue,
            arg1 = 0,
            payload = signature.copyOf(),
        )
    }

    /** Creates an AUTH response carrying a null-terminated ADB RSA public-key record. */
    fun authPublicKey(publicKeyRecord: String): AdbMessage = AdbMessage(
        command = AdbCommand.AUTH,
        arg0 = AdbAuthType.RSA_PUBLIC_KEY.wireValue,
        arg1 = 0,
        payload = nullTerminatedUtf8(publicKeyRecord, "chave publica AUTH ADB"),
    )

    /** Computes the legacy ADB additive checksum over unsigned payload bytes. */
    fun checksum(payload: ByteArray): Long =
        payload.fold(0L) { sum, byte -> (sum + (byte.toInt() and 0xFF)) and UINT32_MAX }

    private fun nullTerminatedUtf8(value: String, label: String): ByteArray {
        require(value.isNotBlank()) { "$label nao pode ser vazio." }
        require('\u0000' !in value) { "$label nao pode conter NUL interno." }
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size < MAXIMUM_PAYLOAD_BYTES) {
            "$label excede o limite de payload ADB."
        }
        return encoded + byteArrayOf(0)
    }
}

private const val UINT32_MAX = 0xFFFF_FFFFL
private val UINT32_RANGE = 0L..UINT32_MAX

private fun fourCc(value: String): Long {
    require(value.length == 4) { "ADB FourCC deve conter quatro caracteres." }
    return (value[0].code.toLong() and 0xFFL) or
        ((value[1].code.toLong() and 0xFFL) shl 8) or
        ((value[2].code.toLong() and 0xFFL) shl 16) or
        ((value[3].code.toLong() and 0xFFL) shl 24)
}
