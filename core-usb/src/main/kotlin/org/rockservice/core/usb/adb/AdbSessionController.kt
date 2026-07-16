package org.rockservice.core.usb.adb

import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Fixed, read-only diagnostic services that may be opened by the initial ADB session API. */
enum class AdbDiagnosticService(
    internal val wireName: String,
) {
    PROPERTIES("exec:getprop"),
    KERNEL("exec:uname -a"),
    CPU_INFO("exec:cat /proc/cpuinfo"),
    MEMORY_INFO("exec:cat /proc/meminfo"),
    STORAGE("exec:df -k"),
    BATTERY("exec:dumpsys battery"),
}

/** Failure raised when the peer rejects one explicitly requested ADB service. */
class AdbServiceRejectedException(
    message: String,
) : IllegalStateException(message)

/** Terminal failure of a running ADB session. */
class AdbSessionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * One bounded ADB stream owned by [AdbSessionController].
 *
 * The initial public API exposes only fixed read-only diagnostic services. Arbitrary shell command
 * strings are deliberately not accepted here.
 */
class AdbSessionStream internal constructor(
    private val controller: AdbSessionController,
    val localId: Long,
    val remoteId: Long,
    private val incoming: ReceiveChannel<ByteArray>,
) {
    /** Receives the next payload chunk, or null after an orderly stream close. */
    suspend fun read(): ByteArray? {
        val result = incoming.receiveCatching()
        result.exceptionOrNull()?.let { error ->
            throw AdbSessionException("ADB stream $localId failed.", error)
        }
        return result.getOrNull()
    }

    /** Sends bytes using negotiated maxdata chunking and one-WRTE-at-a-time ADB flow control. */
    suspend fun write(
        bytes: ByteArray,
        timeoutMillis: Long = AdbSessionController.DEFAULT_STREAM_OPERATION_TIMEOUT_MILLIS,
    ) {
        controller.write(localId, bytes, timeoutMillis)
    }

    /** Requests an orderly CLSE handshake for this stream. */
    suspend fun close(
        timeoutMillis: Long = AdbSessionController.DEFAULT_STREAM_OPERATION_TIMEOUT_MILLIS,
    ) {
        controller.closeStream(localId, timeoutMillis)
    }
}

/**
 * Multiplexes bounded ADB OPEN/OKAY/WRTE/CLSE streams over one already-authenticated transport.
 *
 * This controller intentionally starts after CNXN/AUTH has completed. It owns exactly one reader
 * loop, validates stream IDs fail-closed, applies negotiated maxdata limits, and never exposes an
 * arbitrary shell service string through its public API.
 */
class AdbSessionController(
    private val transport: AdbMessageTransport,
    val peer: AdbConnectedPeer,
    private val scope: CoroutineScope,
    private val receivePollTimeoutMillis: Long = DEFAULT_RECEIVE_POLL_TIMEOUT_MILLIS,
    private val incomingBufferChunks: Int = DEFAULT_INCOMING_BUFFER_CHUNKS,
    private val maximumOpenStreams: Int = DEFAULT_MAXIMUM_OPEN_STREAMS,
    private val closeTransportOnClose: Boolean = true,
) {
    private val started = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val terminalFailure = AtomicReference<Throwable?>(null)
    private val nextLocalId = AtomicLong(1L)
    private val streamsMutex = Mutex()
    private val streams = mutableMapOf<Long, SessionStreamState>()
    private val tombstones = LinkedHashSet<Long>()
    private val termination = CompletableDeferred<Unit>()

    @Volatile
    private var readerJob: Job? = null

    init {
        require(peer.protocolVersion in MINIMUM_PROTOCOL_VERSION..AdbProtocolCodec.DEFAULT_PROTOCOL_VERSION) {
            "Versao ADB negociada fora da faixa suportada pela sessao: 0x${peer.protocolVersion.toString(16)}."
        }
        require(peer.maxDataBytes in 1L..AdbProtocolCodec.MAXIMUM_PAYLOAD_BYTES.toLong()) {
            "maxdata ADB negociado fora do limite local: ${peer.maxDataBytes}."
        }
        require(receivePollTimeoutMillis > 0L) { "Polling timeout da sessao ADB deve ser positivo." }
        require(incomingBufferChunks in 1..MAXIMUM_INCOMING_BUFFER_CHUNKS) {
            "Buffer de entrada ADB deve estar entre 1 e $MAXIMUM_INCOMING_BUFFER_CHUNKS chunks."
        }
        require(maximumOpenStreams in 1..MAXIMUM_OPEN_STREAMS) {
            "Limite de streams ADB deve estar entre 1 e $MAXIMUM_OPEN_STREAMS."
        }
    }

    /** Starts the single transport reader loop exactly once. */
    fun start() {
        terminalFailure.get()?.let { error ->
            throw AdbSessionException("Sessao ADB ja esta em estado terminal de falha.", error)
        }
        check(!closing.get()) { "Sessao ADB ja esta fechando ou encerrada." }
        check(started.compareAndSet(false, true)) { "Sessao ADB ja foi iniciada." }
        readerJob = scope.launch { readerLoop() }
    }

    /** Opens one allowlisted read-only diagnostic service. */
    suspend fun open(
        service: AdbDiagnosticService,
        timeoutMillis: Long = DEFAULT_STREAM_OPERATION_TIMEOUT_MILLIS,
    ): AdbSessionStream {
        require(timeoutMillis > 0L) { "Timeout de abertura ADB deve ser positivo." }
        ensureRunning()

        val localId = allocateLocalId()
        val state = SessionStreamState(
            localId = localId,
            incoming = Channel(incomingBufferChunks),
        )
        streamsMutex.withLock {
            check(streams.size < maximumOpenStreams) {
                "Sessao ADB atingiu o limite de $maximumOpenStreams streams simultaneos."
            }
            check(streams.put(localId, state) == null) { "localId ADB duplicado: $localId." }
        }

        try {
            transport.send(
                AdbProtocolCodec.open(localId = localId, service = service.wireName),
                timeoutMillis = timeoutMillis,
            )
            val remoteId = withTimeout(timeoutMillis) { state.opened.await() }
            return AdbSessionStream(
                controller = this,
                localId = localId,
                remoteId = remoteId,
                incoming = state.incoming,
            )
        } catch (error: Throwable) {
            finalizeStream(localId, error)
            throw error
        }
    }

    internal suspend fun write(
        localId: Long,
        bytes: ByteArray,
        timeoutMillis: Long,
    ) {
        require(timeoutMillis > 0L) { "Timeout de escrita ADB deve ser positivo." }
        if (bytes.isEmpty()) return
        ensureRunning()
        val state = requireStream(localId)
        val maximumChunkBytes = peer.maxDataBytes.toInt()

        state.writeMutex.withLock {
            var offset = 0
            while (offset < bytes.size) {
                ensureRunning()
                val chunkSize = minOf(maximumChunkBytes, bytes.size - offset)
                val payload = bytes.copyOfRange(offset, offset + chunkSize)
                val acknowledgement = CompletableDeferred<Unit>()
                val remoteId = state.stateMutex.withLock {
                    check(!state.closed) { "Stream ADB $localId ja esta fechado." }
                    check(!state.closeSent) { "Stream ADB $localId ja iniciou fechamento." }
                    val currentRemoteId = checkNotNull(state.remoteId) {
                        "Stream ADB $localId ainda nao concluiu OPEN/OKAY."
                    }
                    check(state.pendingWriteAck == null) {
                        "Stream ADB $localId ja possui WRTE aguardando OKAY."
                    }
                    state.pendingWriteAck = acknowledgement
                    currentRemoteId
                }

                try {
                    transport.send(
                        AdbMessage(
                            command = AdbCommand.WRTE,
                            arg0 = localId,
                            arg1 = remoteId,
                            payload = payload,
                        ),
                        timeoutMillis = timeoutMillis,
                    )
                    withTimeout(timeoutMillis) { acknowledgement.await() }
                } finally {
                    state.stateMutex.withLock {
                        if (state.pendingWriteAck === acknowledgement) {
                            state.pendingWriteAck = null
                        }
                    }
                }
                offset += chunkSize
            }
        }
    }

    internal suspend fun closeStream(
        localId: Long,
        timeoutMillis: Long,
    ) {
        require(timeoutMillis > 0L) { "Timeout de fechamento ADB deve ser positivo." }
        val state = findStream(localId) ?: return

        state.writeMutex.withLock {
            val remoteId = state.stateMutex.withLock {
                if (state.closed || state.closeSent) return@withLock null
                val currentRemoteId = checkNotNull(state.remoteId) {
                    "Stream ADB $localId nao possui remoteId para CLSE."
                }
                state.closeSent = true
                currentRemoteId
            } ?: return@withLock

            try {
                transport.send(
                    AdbMessage(AdbCommand.CLSE, arg0 = localId, arg1 = remoteId),
                    timeoutMillis = timeoutMillis,
                )
                withTimeout(timeoutMillis) { state.closeAck.await() }
            } finally {
                if (!state.closeAck.isCompleted) {
                    finalizeStream(
                        localId,
                        CancellationException("Stream ADB $localId encerrado localmente sem CLSE remoto."),
                    )
                }
            }
        }
    }

    /** Closes all active streams, stops the reader, and optionally releases the transport. */
    suspend fun close() {
        if (!closing.compareAndSet(false, true)) {
            termination.await()
            return
        }

        try {
            withContext(NonCancellable) {
                val active = streamsMutex.withLock { streams.values.toList() }
                active.forEach { state ->
                    state.stateMutex.withLock {
                        state.pendingWriteAck?.completeExceptionally(
                            CancellationException("Sessao ADB esta encerrando."),
                        )
                        state.pendingWriteAck = null
                    }
                }
                active.forEach { state ->
                    state.writeMutex.withLock {
                        val closeFrame = state.stateMutex.withLock {
                            val remoteId = state.remoteId
                            if (state.closed || state.closeSent || remoteId == null) {
                                null
                            } else {
                                state.closeSent = true
                                AdbMessage(AdbCommand.CLSE, arg0 = state.localId, arg1 = remoteId)
                            }
                        }
                        if (closeFrame != null) {
                            runCatching {
                                transport.send(closeFrame, DEFAULT_STREAM_OPERATION_TIMEOUT_MILLIS)
                            }
                        }
                    }
                }

                readerJob?.cancelAndJoin()
                terminateAllStreams(CancellationException("Sessao ADB encerrada localmente."))
                if (closeTransportOnClose) transport.close()
            }
        } finally {
            termination.complete(Unit)
        }
    }

    private suspend fun readerLoop() {
        val requireChecksum = peer.protocolVersion < CHECKSUM_OPTIONAL_PROTOCOL_VERSION
        try {
            while (!closing.get()) {
                val incoming = try {
                    transport.receive(
                        timeoutMillis = receivePollTimeoutMillis,
                        requireChecksum = requireChecksum,
                    )
                } catch (_: TimeoutCancellationException) {
                    continue
                }
                handleIncoming(incoming)
            }
        } catch (cancelled: CancellationException) {
            if (!closing.get()) failSession(cancelled)
        } catch (error: Throwable) {
            failSession(error)
        }
    }

    private suspend fun handleIncoming(message: AdbMessage) {
        when (message.command) {
            AdbCommand.OKAY -> handleOkay(message)
            AdbCommand.WRTE -> handleWrite(message)
            AdbCommand.CLSE -> handleClose(message)
            else -> throw AdbSessionException(
                "Comando ${message.command} inesperado depois do handshake ADB.",
            )
        }
    }

    private suspend fun handleOkay(message: AdbMessage) {
        val state = findStream(message.arg1)
        if (state == null) {
            handleLateFrameForClosedStream(message)
            return
        }
        state.stateMutex.withLock {
            check(!state.closed) { "OKAY recebido para stream ADB fechado ${state.localId}." }
            val remoteId = state.remoteId
            if (remoteId == null) {
                require(message.arg0 in 1L..UINT32_MAX) { "OKAY ADB retornou remoteId invalido: ${message.arg0}." }
                state.remoteId = message.arg0
                state.opened.complete(message.arg0)
            } else {
                require(message.arg0 == remoteId) {
                    "OKAY ADB usa remoteId ${message.arg0}; esperado: $remoteId."
                }
                val acknowledgement = checkNotNull(state.pendingWriteAck) {
                    "OKAY ADB inesperado sem WRTE pendente no stream ${state.localId}."
                }
                state.pendingWriteAck = null
                acknowledgement.complete(Unit)
            }
        }
    }

    private suspend fun handleWrite(message: AdbMessage) {
        require(message.payload.size <= peer.maxDataBytes) {
            "WRTE ADB possui ${message.payload.size} bytes; maxdata negociado: ${peer.maxDataBytes}."
        }
        val state = findStream(message.arg1)
        if (state == null) {
            handleLateFrameForClosedStream(message)
            return
        }
        val remoteId = state.stateMutex.withLock {
            check(!state.closed) { "WRTE recebido para stream ADB fechado ${state.localId}." }
            val currentRemoteId = checkNotNull(state.remoteId) {
                "WRTE recebido antes do OKAY de abertura no stream ${state.localId}."
            }
            require(message.arg0 == currentRemoteId) {
                "WRTE ADB usa remoteId ${message.arg0}; esperado: $currentRemoteId."
            }
            currentRemoteId
        }

        check(state.incoming.trySend(message.payload.copyOf()).isSuccess) {
            "Buffer do stream ADB ${state.localId} excedeu $incomingBufferChunks chunks sem consumo."
        }
        transport.send(
            AdbMessage(AdbCommand.OKAY, arg0 = state.localId, arg1 = remoteId),
            timeoutMillis = DEFAULT_STREAM_OPERATION_TIMEOUT_MILLIS,
        )
    }

    private suspend fun handleClose(message: AdbMessage) {
        val state = findStream(message.arg1)
        if (state == null) {
            val knownClosedStream = isTombstoned(message.arg1)
            if (knownClosedStream) return
            throw AdbSessionException("CLSE recebido para localId ADB desconhecido ${message.arg1}.")
        }

        var reciprocalClose: AdbMessage? = null
        var rejection: Throwable? = null
        state.stateMutex.withLock {
            val remoteId = state.remoteId
            if (remoteId == null) {
                require(message.arg0 == 0L || message.arg0 in 1L..UINT32_MAX) {
                    "CLSE ADB de rejeicao usa remoteId invalido: ${message.arg0}."
                }
                rejection = AdbServiceRejectedException(
                    "O dispositivo ADB rejeitou o servico solicitado para localId ${state.localId}.",
                )
                state.opened.completeExceptionally(requireNotNull(rejection))
            } else {
                require(message.arg0 == remoteId) {
                    "CLSE ADB usa remoteId ${message.arg0}; esperado: $remoteId."
                }
                if (!state.closeSent) {
                    state.closeSent = true
                    reciprocalClose = AdbMessage(
                        AdbCommand.CLSE,
                        arg0 = state.localId,
                        arg1 = remoteId,
                    )
                }
            }
            state.closed = true
            state.pendingWriteAck?.completeExceptionally(
                AdbSessionException("Stream ADB ${state.localId} foi fechado pelo peer."),
            )
            state.pendingWriteAck = null
            state.closeAck.complete(Unit)
        }

        reciprocalClose?.let { closeFrame ->
            transport.send(closeFrame, DEFAULT_STREAM_OPERATION_TIMEOUT_MILLIS)
        }
        finalizeStream(state.localId, rejection)
    }

    private suspend fun handleLateFrameForClosedStream(message: AdbMessage) {
        check(isTombstoned(message.arg1)) {
            "${message.command} recebido para localId ADB desconhecido ${message.arg1}."
        }
        require(message.arg0 in 1L..UINT32_MAX) {
            "${message.command} tardio usa remoteId ADB invalido: ${message.arg0}."
        }
        transport.send(
            AdbMessage(
                command = AdbCommand.CLSE,
                arg0 = message.arg1,
                arg1 = message.arg0,
            ),
            timeoutMillis = DEFAULT_STREAM_OPERATION_TIMEOUT_MILLIS,
        )
    }

    private suspend fun failSession(error: Throwable) {
        if (!terminalFailure.compareAndSet(null, error)) {
            termination.await()
            return
        }
        closing.set(true)
        try {
            withContext(NonCancellable) {
                terminateAllStreams(AdbSessionException("Sessao ADB falhou.", error))
                if (closeTransportOnClose) runCatching { transport.close() }
            }
        } finally {
            termination.complete(Unit)
        }
    }

    private fun ensureRunning() {
        terminalFailure.get()?.let { error ->
            throw AdbSessionException("Sessao ADB esta em estado terminal de falha.", error)
        }
        check(started.get()) { "Sessao ADB ainda nao foi iniciada." }
        check(!closing.get()) { "Sessao ADB esta fechando ou encerrada." }
    }

    private fun allocateLocalId(): Long {
        val localId = nextLocalId.getAndIncrement()
        check(localId in 1L..UINT32_MAX) { "Espaco de localId ADB foi esgotado." }
        return localId
    }

    private suspend fun requireStream(localId: Long): SessionStreamState =
        findStream(localId) ?: throw AdbSessionException("localId ADB desconhecido: $localId.")

    private suspend fun findStream(localId: Long): SessionStreamState? =
        streamsMutex.withLock { streams[localId] }

    private suspend fun isTombstoned(localId: Long): Boolean =
        streamsMutex.withLock { localId in tombstones }

    private suspend fun finalizeStream(localId: Long, cause: Throwable?) {
        val removed = streamsMutex.withLock {
            val state = streams.remove(localId) ?: return@withLock null
            rememberTombstone(localId)
            state
        } ?: return

        removed.stateMutex.withLock {
            removed.closed = true
            if (!removed.opened.isCompleted && cause != null) removed.opened.completeExceptionally(cause)
            removed.pendingWriteAck?.completeExceptionally(
                cause ?: CancellationException("Stream ADB $localId foi encerrado."),
            )
            removed.pendingWriteAck = null
            removed.closeAck.complete(Unit)
        }
        removed.incoming.close(cause)
    }

    private suspend fun terminateAllStreams(cause: Throwable) {
        val active = streamsMutex.withLock {
            val snapshot = streams.values.toList()
            streams.clear()
            snapshot.forEach { state -> rememberTombstone(state.localId) }
            snapshot
        }

        active.forEach { state ->
            state.stateMutex.withLock {
                state.closed = true
                if (!state.opened.isCompleted) state.opened.completeExceptionally(cause)
                state.pendingWriteAck?.completeExceptionally(cause)
                state.pendingWriteAck = null
                state.closeAck.complete(Unit)
            }
            state.incoming.close(cause)
        }
    }

    private fun rememberTombstone(localId: Long) {
        tombstones += localId
        while (tombstones.size > MAXIMUM_TOMBSTONES) {
            tombstones.remove(tombstones.first())
        }
    }

    companion object {
        const val DEFAULT_STREAM_OPERATION_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_RECEIVE_POLL_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_INCOMING_BUFFER_CHUNKS = 8
        const val DEFAULT_MAXIMUM_OPEN_STREAMS = 8

        private const val MAXIMUM_INCOMING_BUFFER_CHUNKS = 256
        private const val MAXIMUM_OPEN_STREAMS = 64
        private const val MAXIMUM_TOMBSTONES = 64
        private const val MINIMUM_PROTOCOL_VERSION = 0x01000000L
        private const val CHECKSUM_OPTIONAL_PROTOCOL_VERSION = 0x01000001L
        private const val UINT32_MAX = 0xFFFF_FFFFL
    }
}

private class SessionStreamState(
    val localId: Long,
    val incoming: Channel<ByteArray>,
) {
    val stateMutex = Mutex()
    val writeMutex = Mutex()
    val opened = CompletableDeferred<Long>()
    val closeAck = CompletableDeferred<Unit>()
    var remoteId: Long? = null
    var pendingWriteAck: CompletableDeferred<Unit>? = null
    var closeSent = false
    var closed = false
}
