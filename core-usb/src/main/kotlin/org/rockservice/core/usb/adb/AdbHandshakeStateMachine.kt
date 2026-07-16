package org.rockservice.core.usb.adb

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Host identity operations required by the pure ADB handshake state machine. */
interface AdbHandshakeIdentity {
    /** Signs one 20-byte AUTH token received from the device. */
    fun signToken(token: ByteArray): ByteArray

    /** Returns the Base64 public-key record without the protocol NUL terminator. */
    fun publicKeyRecord(): String
}

/** Immutable negotiated peer information after a successful CNXN response. */
data class AdbConnectedPeer(
    val protocolVersion: Long,
    val maxDataBytes: Long,
    val banner: String,
)

/** Observable pure states of the ADB connection handshake. */
sealed interface AdbHandshakeState {
    data object Idle : AdbHandshakeState

    data object AwaitingConnectionOrAuth : AdbHandshakeState

    data object SignatureSent : AdbHandshakeState

    data object PublicKeySent : AdbHandshakeState

    data class Connected(
        val peer: AdbConnectedPeer,
    ) : AdbHandshakeState

    data class Failed(
        val reason: String,
    ) : AdbHandshakeState
}

/** One deterministic transition result: new state plus zero or one outbound protocol message. */
data class AdbHandshakeTransition(
    val state: AdbHandshakeState,
    val outbound: AdbMessage? = null,
)

/**
 * Pure, fail-closed ADB host handshake controller.
 *
 * It performs no transport I/O and never opens services. Callers send the returned outbound frame
 * and feed validated incoming [AdbMessage] instances back through [receive]. Malformed protocol
 * messages transition the attempt permanently to [AdbHandshakeState.Failed].
 */
class AdbHandshakeStateMachine(
    private val identity: AdbHandshakeIdentity,
    private val hostBanner: String = DEFAULT_HOST_BANNER,
    private val protocolVersion: Long = AdbProtocolCodec.DEFAULT_PROTOCOL_VERSION,
    private val maxDataBytes: Long = AdbProtocolCodec.DEFAULT_MAX_DATA_BYTES,
) {
    var state: AdbHandshakeState = AdbHandshakeState.Idle
        private set

    /** Starts exactly one handshake attempt by emitting CNXN. */
    fun start(): AdbHandshakeTransition {
        require(state == AdbHandshakeState.Idle) {
            "Handshake ADB só pode ser iniciado a partir do estado Idle. Estado atual: $state."
        }
        require(protocolVersion in MINIMUM_SUPPORTED_PROTOCOL_VERSION..AdbProtocolCodec.DEFAULT_PROTOCOL_VERSION) {
            "Versão local do protocolo ADB não suportada: 0x${protocolVersion.toString(16)}."
        }

        val outbound = AdbProtocolCodec.connect(
            banner = hostBanner,
            protocolVersion = protocolVersion,
            maxDataBytes = maxDataBytes,
        )
        state = AdbHandshakeState.AwaitingConnectionOrAuth
        return AdbHandshakeTransition(
            state = state,
            outbound = outbound,
        )
    }

    /** Applies one already framed and checksum-validated incoming ADB message. */
    fun receive(message: AdbMessage): AdbHandshakeTransition {
        require(state !is AdbHandshakeState.Idle) { "Handshake ADB ainda não foi iniciado." }
        require(state !is AdbHandshakeState.Connected) { "Handshake ADB já está conectado." }
        require(state !is AdbHandshakeState.Failed) { "Handshake ADB já falhou." }

        return try {
            when (message.command) {
                AdbCommand.CNXN -> handleConnection(message)
                AdbCommand.AUTH -> handleAuth(message)
                else -> fail(
                    "Comando ${message.command} inesperado durante handshake ADB no estado $state.",
                )
            }
        } catch (error: IllegalArgumentException) {
            fail(error.message ?: "Mensagem ADB inválida durante o handshake.")
        }
    }

    private fun handleConnection(message: AdbMessage): AdbHandshakeTransition {
        require(message.arg0 in MINIMUM_SUPPORTED_PROTOCOL_VERSION..UINT32_MAX) {
            "Versão ADB remota não suportada: 0x${message.arg0.toString(16)}."
        }
        require(message.arg1 > 0L) { "maxdata ADB remoto deve ser maior que zero." }
        require(message.payload.size <= AdbProtocolCodec.MAXIMUM_HANDSHAKE_PAYLOAD_BYTES) {
            "Banner CNXN remoto possui ${message.payload.size} bytes; limite de handshake: " +
                "${AdbProtocolCodec.MAXIMUM_HANDSHAKE_PAYLOAD_BYTES}."
        }

        val banner = decodeConnectionBanner(message.payload)
        val peer = AdbConnectedPeer(
            protocolVersion = minOf(message.arg0, protocolVersion),
            maxDataBytes = minOf(message.arg1, maxDataBytes),
            banner = banner,
        )
        state = AdbHandshakeState.Connected(peer)
        return AdbHandshakeTransition(state = state)
    }

    private fun handleAuth(message: AdbMessage): AdbHandshakeTransition {
        require(message.arg0 == AdbAuthType.TOKEN.wireValue) {
            "Mensagem AUTH durante handshake deve carregar TOKEN; recebido tipo ${message.arg0}."
        }
        require(message.arg1 == 0L) { "AUTH TOKEN ADB deve usar arg1 igual a zero." }
        require(message.payload.size == AdbRsaAuth.AUTH_TOKEN_BYTES) {
            "AUTH TOKEN ADB deve conter exatamente ${AdbRsaAuth.AUTH_TOKEN_BYTES} bytes."
        }

        return when (state) {
            AdbHandshakeState.AwaitingConnectionOrAuth -> {
                val signature = signToken(message.payload.copyOf())
                require(signature.size == AdbRsaAuth.RSA_BYTES) {
                    "Identidade ADB retornou assinatura com ${signature.size} bytes; esperado: ${AdbRsaAuth.RSA_BYTES}."
                }
                state = AdbHandshakeState.SignatureSent
                AdbHandshakeTransition(
                    state = state,
                    outbound = AdbProtocolCodec.authSignature(signature),
                )
            }

            AdbHandshakeState.SignatureSent -> {
                val publicKeyRecord = readPublicKeyRecord()
                require(publicKeyRecord.isNotBlank()) { "Identidade ADB retornou registro público vazio." }
                state = AdbHandshakeState.PublicKeySent
                AdbHandshakeTransition(
                    state = state,
                    outbound = AdbProtocolCodec.authPublicKey(publicKeyRecord),
                )
            }

            AdbHandshakeState.PublicKeySent -> fail(
                "Dispositivo solicitou um novo AUTH TOKEN após o envio da chave pública; autorização do usuário não foi concluída.",
            )

            else -> fail("AUTH TOKEN inesperado no estado $state.")
        }
    }

    private fun decodeConnectionBanner(payload: ByteArray): String {
        require(payload.isNotEmpty()) { "Banner CNXN remoto não pode ser vazio." }
        val contentSize = if (payload.last() == 0.toByte()) payload.size - 1 else payload.size
        require(contentSize > 0) { "Banner CNXN remoto não pode conter apenas NUL." }
        require((0 until contentSize).none { index -> payload[index] == 0.toByte() }) {
            "Banner CNXN remoto contém NUL interno."
        }

        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val banner = try {
            decoder.decode(ByteBuffer.wrap(payload, 0, contentSize)).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Banner CNXN remoto não contém UTF-8 válido.", error)
        }
        require(banner.isNotBlank()) { "Banner CNXN remoto não pode ser vazio." }
        return banner
    }

    private fun signToken(token: ByteArray): ByteArray = try {
        identity.signToken(token)
    } catch (error: RuntimeException) {
        throw IllegalArgumentException(
            "Identidade ADB falhou ao assinar o AUTH TOKEN: ${error.message ?: error.javaClass.simpleName}.",
            error,
        )
    }

    private fun readPublicKeyRecord(): String = try {
        identity.publicKeyRecord()
    } catch (error: RuntimeException) {
        throw IllegalArgumentException(
            "Identidade ADB falhou ao fornecer a chave pública: ${error.message ?: error.javaClass.simpleName}.",
            error,
        )
    }

    private fun fail(reason: String): AdbHandshakeTransition {
        state = AdbHandshakeState.Failed(reason)
        return AdbHandshakeTransition(state = state)
    }

    private companion object {
        // Do not advertise optional ADB features until the corresponding services are implemented.
        const val DEFAULT_HOST_BANNER = "host::"
        const val MINIMUM_SUPPORTED_PROTOCOL_VERSION = 0x01000000L
        const val UINT32_MAX = 0xFFFF_FFFFL
    }
}
