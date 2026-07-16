package org.rockservice.core.usb.adb

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
 * and feed validated incoming [AdbMessage] instances back through [receive].
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
        state = AdbHandshakeState.AwaitingConnectionOrAuth
        return AdbHandshakeTransition(
            state = state,
            outbound = AdbProtocolCodec.connect(
                banner = hostBanner,
                protocolVersion = protocolVersion,
                maxDataBytes = maxDataBytes,
            ),
        )
    }

    /** Applies one already validated incoming ADB message. */
    fun receive(message: AdbMessage): AdbHandshakeTransition {
        require(state !is AdbHandshakeState.Idle) { "Handshake ADB ainda não foi iniciado." }
        require(state !is AdbHandshakeState.Connected) { "Handshake ADB já está conectado." }
        require(state !is AdbHandshakeState.Failed) { "Handshake ADB já falhou." }

        return when (message.command) {
            AdbCommand.CNXN -> handleConnection(message)
            AdbCommand.AUTH -> handleAuth(message)
            else -> fail(
                "Comando ${message.command} inesperado durante handshake ADB no estado $state.",
            )
        }
    }

    private fun handleConnection(message: AdbMessage): AdbHandshakeTransition {
        require(message.arg0 in MINIMUM_SUPPORTED_PROTOCOL_VERSION..UINT32_MAX) {
            "Versão ADB remota não suportada: 0x${message.arg0.toString(16)}."
        }
        require(message.arg1 in 1L..AdbProtocolCodec.MAXIMUM_PAYLOAD_BYTES.toLong()) {
            "maxdata ADB remoto fora do limite local: ${message.arg1}."
        }
        require(message.payload.isNotEmpty() && message.payload.last() == 0.toByte()) {
            "Banner CNXN remoto deve terminar em NUL."
        }
        require(message.payload.dropLast(1).none { byte -> byte == 0.toByte() }) {
            "Banner CNXN remoto contém NUL interno."
        }
        val banner = message.payload.copyOfRange(0, message.payload.size - 1).toString(Charsets.UTF_8)
        require(banner.isNotBlank()) { "Banner CNXN remoto não pode ser vazio." }

        val peer = AdbConnectedPeer(
            protocolVersion = message.arg0,
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
                val signature = identity.signToken(message.payload.copyOf())
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
                val publicKeyRecord = identity.publicKeyRecord()
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

    private fun fail(reason: String): AdbHandshakeTransition {
        state = AdbHandshakeState.Failed(reason)
        return AdbHandshakeTransition(state = state)
    }

    private companion object {
        const val DEFAULT_HOST_BANNER = "host::features=shell_v2,cmd;"
        const val MINIMUM_SUPPORTED_PROTOCOL_VERSION = 0x01000000L
        const val UINT32_MAX = 0xFFFF_FFFFL
    }
}
