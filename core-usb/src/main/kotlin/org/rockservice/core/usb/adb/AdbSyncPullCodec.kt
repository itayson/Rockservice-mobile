package org.rockservice.core.usb.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** One validated response frame from the read-only ADB Sync pull protocol. */
sealed interface AdbSyncPullResponse {
    /** One DATA payload. Equality is based on byte content. */
    class Data(
        bytes: ByteArray,
    ) : AdbSyncPullResponse {
        val bytes: ByteArray = bytes.copyOf()

        override fun equals(other: Any?): Boolean =
            other is Data && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Data(${bytes.size} bytes)"
    }

    /** Successful end of one RECV transfer. */
    data object Done : AdbSyncPullResponse

    /** Terminal error returned by the Sync service. */
    data class Fail(
        val message: String,
    ) : AdbSyncPullResponse
}

/**
 * Encodes the minimal ADB Sync v1 requests required for read-only file retrieval.
 *
 * This codec intentionally does not represent SEND, DATA upload, file mutation, or compression.
 */
object AdbSyncPullCodec {
    const val HEADER_SIZE_BYTES = 8
    const val MAXIMUM_PATH_BYTES = 1024
    const val MAXIMUM_DATA_BYTES = 64 * 1024
    const val MAXIMUM_ERROR_BYTES = 64 * 1024

    /** Encodes `RECV <path>` using the legacy Sync request shared by old and current peers. */
    fun encodeReceiveRequest(remotePath: String): ByteArray {
        require(remotePath.isNotBlank()) { "Caminho remoto ADB Sync nao pode ser vazio." }
        require('\u0000' !in remotePath) { "Caminho remoto ADB Sync nao pode conter NUL." }
        val pathBytes = strictUtf8(remotePath)
        require(pathBytes.size <= MAXIMUM_PATH_BYTES) {
            "Caminho remoto ADB Sync possui ${pathBytes.size} bytes; limite: $MAXIMUM_PATH_BYTES."
        }

        return ByteArray(HEADER_SIZE_BYTES + pathBytes.size).also { frame ->
            val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0, ID_RECV_V1)
            buffer.putInt(4, pathBytes.size)
            pathBytes.copyInto(frame, destinationOffset = HEADER_SIZE_BYTES)
        }
    }

    /** Encodes an orderly QUIT request for a dedicated `sync:` service stream. */
    fun encodeQuitRequest(): ByteArray = ByteArray(HEADER_SIZE_BYTES).also { frame ->
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, ID_QUIT)
        buffer.putInt(4, 0)
    }

    internal fun commandId(value: String): Int = fourCc(value)

    internal const val ID_RECV_V1 = 0x56434552
    internal const val ID_DATA = 0x41544144
    internal const val ID_DONE = 0x454E4F44
    internal const val ID_FAIL = 0x4C494146
    internal const val ID_QUIT = 0x54495551

    private fun strictUtf8(value: String): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(CharBuffer.wrap(value))
        return ByteArray(encoded.remaining()).also(encoded::get)
    }
}

/**
 * Incrementally decodes `DATA* -> DONE|FAIL` from arbitrary ADB stream chunk boundaries.
 *
 * One decoder instance represents exactly one RECV operation and becomes terminal after DONE/FAIL.
 */
class AdbSyncPullDecoder {
    private var pending = ByteArray(0)
    private var terminal = false

    val isTerminal: Boolean
        get() = terminal

    /**
     * Feeds one ADB stream payload and returns every complete Sync response decoded from it.
     * A single ADB WRTE may contain a partial frame or several complete Sync frames.
     */
    fun feed(bytes: ByteArray): List<AdbSyncPullResponse> {
        require(bytes.size <= AdbProtocolCodec.MAXIMUM_PAYLOAD_BYTES) {
            "Chunk ADB Sync possui ${bytes.size} bytes; limite do transporte: ${AdbProtocolCodec.MAXIMUM_PAYLOAD_BYTES}."
        }
        check(!terminal || bytes.isEmpty()) {
            "ADB Sync recebeu bytes adicionais depois de uma resposta terminal."
        }
        if (bytes.isEmpty()) return emptyList()

        val combined = ByteArray(pending.size + bytes.size)
        pending.copyInto(combined)
        bytes.copyInto(combined, destinationOffset = pending.size)

        val responses = mutableListOf<AdbSyncPullResponse>()
        var offset = 0
        while (combined.size - offset >= AdbSyncPullCodec.HEADER_SIZE_BYTES) {
            val id = readIntLittleEndian(combined, offset)
            val declaredLength = readUInt32LittleEndian(combined, offset + 4)
            val maximumBodyBytes = when (id) {
                AdbSyncPullCodec.ID_DATA -> AdbSyncPullCodec.MAXIMUM_DATA_BYTES
                AdbSyncPullCodec.ID_FAIL -> AdbSyncPullCodec.MAXIMUM_ERROR_BYTES
                AdbSyncPullCodec.ID_DONE -> 0
                else -> throw IllegalArgumentException(
                    "Resposta ADB Sync nao suportada: ${fourCcLabel(id)} (0x${id.toUInt().toString(16).uppercase()}).",
                )
            }
            require(declaredLength <= maximumBodyBytes.toLong()) {
                "Resposta ${fourCcLabel(id)} declara $declaredLength bytes; limite: $maximumBodyBytes."
            }

            val frameSize = AdbSyncPullCodec.HEADER_SIZE_BYTES + declaredLength.toInt()
            if (combined.size - offset < frameSize) break

            val payloadStart = offset + AdbSyncPullCodec.HEADER_SIZE_BYTES
            val payloadEnd = payloadStart + declaredLength.toInt()
            when (id) {
                AdbSyncPullCodec.ID_DATA -> {
                    responses += AdbSyncPullResponse.Data(
                        combined.copyOfRange(payloadStart, payloadEnd),
                    )
                }

                AdbSyncPullCodec.ID_DONE -> {
                    terminal = true
                    responses += AdbSyncPullResponse.Done
                }

                AdbSyncPullCodec.ID_FAIL -> {
                    terminal = true
                    responses += AdbSyncPullResponse.Fail(
                        message = combined.copyOfRange(payloadStart, payloadEnd)
                            .toString(Charsets.UTF_8)
                            .replace('\u0000', '\uFFFD'),
                    )
                }
            }
            offset += frameSize

            if (terminal) {
                require(offset == combined.size) {
                    "ADB Sync recebeu ${combined.size - offset} bytes depois da resposta terminal."
                }
                break
            }
        }

        pending = combined.copyOfRange(offset, combined.size)
        require(pending.size < AdbSyncPullCodec.HEADER_SIZE_BYTES + maximumPossibleBodyBytes()) {
            "Buffer incremental ADB Sync excedeu o maior frame permitido sem completar uma mensagem."
        }
        return responses
    }

    /** Verifies that the underlying ADB stream ended only after a complete DONE/FAIL response. */
    fun finish() {
        require(pending.isEmpty()) {
            "Stream ADB Sync terminou com ${pending.size} bytes de uma mensagem truncada."
        }
        require(terminal) { "Stream ADB Sync terminou antes de DONE/FAIL." }
    }

    private fun maximumPossibleBodyBytes(): Int = maxOf(
        AdbSyncPullCodec.MAXIMUM_DATA_BYTES,
        AdbSyncPullCodec.MAXIMUM_ERROR_BYTES,
    )
}

private fun readIntLittleEndian(bytes: ByteArray, offset: Int): Int =
    ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int

private fun readUInt32LittleEndian(bytes: ByteArray, offset: Int): Long =
    readIntLittleEndian(bytes, offset).toLong() and 0xFFFF_FFFFL

private fun fourCc(value: String): Int {
    require(value.length == 4) { "ADB Sync FourCC deve conter quatro caracteres." }
    return (value[0].code and 0xFF) or
        ((value[1].code and 0xFF) shl 8) or
        ((value[2].code and 0xFF) shl 16) or
        ((value[3].code and 0xFF) shl 24)
}

private fun fourCcLabel(value: Int): String = buildString(4) {
    repeat(4) { index ->
        val byte = (value ushr (index * 8)) and 0xFF
        append(if (byte in 0x20..0x7E) byte.toChar() else '?')
    }
}
