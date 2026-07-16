package org.rockservice.mobile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.rockservice.core.common.diagnostics.DiagnosticEventRecorder
import org.rockservice.core.common.diagnostics.DiagnosticSeverity
import org.rockservice.core.usb.AndroidUsbHostBackend
import org.rockservice.core.usb.UsbDeviceDescriptor
import org.rockservice.core.usb.UsbEndpointDirection
import org.rockservice.core.usb.UsbTransferType
import org.rockservice.core.usb.adb.AdbDiagnosticSnapshot
import org.rockservice.core.usb.adb.AdbHandshakeState
import org.rockservice.core.usb.adb.AdbHandshakeStateMachine
import org.rockservice.core.usb.adb.AdbMessageTransport
import org.rockservice.core.usb.adb.AdbReadonlyDiagnosticRunner
import org.rockservice.core.usb.adb.AdbSessionController
import org.rockservice.core.usb.adb.AdbUsbInterfaceProfile
import org.rockservice.core.usb.adb.AndroidAdbUsbTransportFactory

internal data class AdbProbeCandidate(
    val descriptor: UsbDeviceDescriptor,
    val displayName: String,
)

internal sealed interface AdbProbeScanState {
    data object Loading : AdbProbeScanState
    data class Ready(val candidates: List<AdbProbeCandidate>) : AdbProbeScanState
    data class Error(val message: String) : AdbProbeScanState
}

internal sealed interface AdbProbeOperationState {
    data object Idle : AdbProbeOperationState

    data class Running(
        val transportId: String,
        val stage: String,
        val awaitingDeviceAuthorization: Boolean = false,
    ) : AdbProbeOperationState

    data class Connected(
        val transportId: String,
        val protocolVersion: Long,
        val maxDataBytes: Long,
        val banner: String,
    ) : AdbProbeOperationState

    data class Error(
        val message: String,
        val authorizationMayBePending: Boolean = false,
    ) : AdbProbeOperationState
}

internal sealed interface AdbProbeDiagnosticsState {
    data object Idle : AdbProbeDiagnosticsState
    data object Running : AdbProbeDiagnosticsState
    data class Ready(val snapshot: AdbDiagnosticSnapshot) : AdbProbeDiagnosticsState
    data class Error(val message: String) : AdbProbeDiagnosticsState
}

internal data class AdbProbeScreenState(
    val scan: AdbProbeScanState = AdbProbeScanState.Loading,
    val operation: AdbProbeOperationState = AdbProbeOperationState.Idle,
    val diagnostics: AdbProbeDiagnosticsState = AdbProbeDiagnosticsState.Idle,
)

private data class AdbConnectionResources(
    val session: AdbSessionController?,
    val transport: AdbMessageTransport?,
) {
    val isEmpty: Boolean
        get() = session == null && transport == null
}

private data class AdbDiagnosticsRequest(
    val operationGeneration: Long,
    val collectionGeneration: Long,
    val session: AdbSessionController,
)

/**
 * Enumerates canonical ADB USB targets, performs CNXN/AUTH, and retains an authenticated session
 * only after explicit user initiation. Diagnostic services are opened only by [collectDiagnostics].
 */
internal class AdbProbeViewModel(
    private val appContext: Context,
    private val diagnosticsRecorder: DiagnosticEventRecorder = AppDiagnostics.recorder,
) : ViewModel() {
    private val usbBackend = AndroidUsbHostBackend(appContext)
    private val transportFactory = AndroidAdbUsbTransportFactory(appContext)
    private val identityStore = AdbAppIdentityStore(appContext)
    private val mutableState = MutableStateFlow(AdbProbeScreenState())
    private val operationGeneration = AtomicLong(0L)
    private val diagnosticsGeneration = AtomicLong(0L)
    private val started = AtomicBoolean(false)
    private val connectionLock = Any()
    private val connectionLifecycleMutex = Mutex()
    private var scanJob: Job? = null
    private var probeJob: Job? = null
    private var diagnosticsJob: Job? = null
    private var pendingConnectionClose: Job? = null

    @Volatile
    private var activeTransport: AdbMessageTransport? = null

    @Volatile
    private var activeSession: AdbSessionController? = null

    val state = mutableState.asStateFlow()

    /** Starts the initial scan exactly once for this ViewModel lifetime. */
    fun start() {
        if (started.compareAndSet(false, true)) refresh()
    }

    fun refresh() {
        val generation = synchronized(connectionLock) {
            val nextGeneration = operationGeneration.incrementAndGet()
            diagnosticsGeneration.incrementAndGet()
            mutableState.value = AdbProbeScreenState(scan = AdbProbeScanState.Loading)
            nextGeneration
        }
        probeJob?.cancel()
        diagnosticsJob?.cancel()
        scanJob?.cancel()

        scanJob = viewModelScope.launch(Dispatchers.IO) {
            closeActiveConnection()
            try {
                val candidates = usbBackend.listDevices()
                    .mapNotNull { descriptor ->
                        if (operationGeneration.get() != generation) return@launch
                        val topology = usbBackend.inspectTopology(descriptor)
                        if (!topology.isUnambiguousAdbProfile()) return@mapNotNull null
                        AdbProbeCandidate(
                            descriptor = descriptor,
                            displayName = descriptor.product
                                ?: descriptor.manufacturer
                                ?: "USB ${descriptor.vendorId.toString(16).uppercase()}:${descriptor.productId.toString(16).uppercase()}",
                        )
                    }
                    .sortedBy { candidate -> candidate.displayName.lowercase() }
                publishScanIfCurrent(
                    generation = generation,
                    scan = AdbProbeScanState.Ready(candidates),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                publishScanIfCurrent(
                    generation = generation,
                    scan = AdbProbeScanState.Error(
                        error.message ?: "Falha ao procurar interfaces ADB USB.",
                    ),
                )
            }
        }
    }

    fun probe(candidate: AdbProbeCandidate) {
        val transportId = requireNotNull(candidate.descriptor.transportId)
        val generation = synchronized(connectionLock) {
            val nextGeneration = operationGeneration.incrementAndGet()
            diagnosticsGeneration.incrementAndGet()
            mutableState.value = mutableState.value.copy(
                operation = AdbProbeOperationState.Running(
                    transportId = transportId,
                    stage = "Solicitando permissao USB e revalidando o alvo...",
                ),
                diagnostics = AdbProbeDiagnosticsState.Idle,
            )
            nextGeneration
        }
        probeJob?.cancel()
        diagnosticsJob?.cancel()

        probeJob = viewModelScope.launch(Dispatchers.IO) {
            closeActiveConnection()
            var authorizationMayBePending = false
            var ownedTransport: AdbMessageTransport? = null
            try {
                val permitted = usbBackend.requestPermission(candidate.descriptor, USB_PERMISSION_TIMEOUT_MILLIS)
                val topology = usbBackend.inspectTopology(permitted)
                require(topology.isUnambiguousAdbProfile()) {
                    "A topologia USB ADB mudou depois da permissao."
                }
                publishRunning(generation, transportId, "Abrindo transporte ADB validado...")

                val identity = identityStore.loadOrCreate()
                val machine = AdbHandshakeStateMachine(identity)
                val transport = transportFactory.open(permitted)
                ownedTransport = transport
                registerActiveTransport(transport)

                withTimeout(HANDSHAKE_TOTAL_TIMEOUT_MILLIS) {
                    val start = machine.start()
                    transport.send(requireNotNull(start.outbound), MESSAGE_TIMEOUT_MILLIS)
                    publishRunning(generation, transportId, "CNXN enviado; aguardando resposta do dispositivo...")

                    repeat(MAXIMUM_HANDSHAKE_MESSAGES) {
                        val incoming = transport.receive(
                            timeoutMillis = MESSAGE_TIMEOUT_MILLIS,
                            requireChecksum = false,
                        )
                        val transition = machine.receive(incoming)
                        when (val handshakeState = transition.state) {
                            is AdbHandshakeState.Connected -> {
                                val session = AdbSessionController(
                                    transport = transport,
                                    peer = handshakeState.peer,
                                    scope = viewModelScope,
                                    closeTransportOnClose = true,
                                )
                                session.start()
                                val connectedState = AdbProbeOperationState.Connected(
                                    transportId = transportId,
                                    protocolVersion = handshakeState.peer.protocolVersion,
                                    maxDataBytes = handshakeState.peer.maxDataBytes,
                                    banner = handshakeState.peer.banner,
                                )
                                if (!promoteAndPublishConnected(
                                        generation = generation,
                                        transport = transport,
                                        session = session,
                                        connectedState = connectedState,
                                    )
                                ) {
                                    session.close()
                                    throw CancellationException("A operacao ADB foi substituida antes da promocao da sessao.")
                                }
                                ownedTransport = null

                                if (isCurrentConnectedSession(generation, session)) {
                                    diagnosticsRecorder.record(
                                        severity = DiagnosticSeverity.INFO,
                                        component = "adb",
                                        action = "handshake.connected",
                                        message = "Handshake ADB concluido; sessao autenticada disponivel para acoes explicitas.",
                                        metadata = mapOf(
                                            "protocolVersion" to handshakeState.peer.protocolVersion.toString(),
                                            "maxDataBytes" to handshakeState.peer.maxDataBytes.toString(),
                                        ),
                                    )
                                }
                                return@withTimeout
                            }

                            is AdbHandshakeState.Failed -> throw IllegalArgumentException(handshakeState.reason)

                            AdbHandshakeState.PublicKeySent -> {
                                authorizationMayBePending = true
                                publishRunning(
                                    generation,
                                    transportId,
                                    "Chave publica enviada. Confirme a autorizacao RSA na tela do dispositivo ADB.",
                                    awaitingAuthorization = true,
                                )
                            }

                            AdbHandshakeState.SignatureSent -> publishRunning(
                                generation,
                                transportId,
                                "Assinatura AUTH enviada; aguardando validacao do dispositivo...",
                            )

                            else -> Unit
                        }
                        transition.outbound?.let { outbound ->
                            transport.send(outbound, MESSAGE_TIMEOUT_MILLIS)
                        }
                    }
                    throw IllegalStateException(
                        "O handshake ADB excedeu o numero maximo de mensagens permitido sem concluir.",
                    )
                }
            } catch (timeout: TimeoutCancellationException) {
                publishFailure(
                    generation = generation,
                    message = if (authorizationMayBePending) {
                        "O dispositivo nao concluiu a autorizacao ADB. Confirme o dialogo RSA no dispositivo e execute a validacao novamente."
                    } else {
                        "O dispositivo ADB nao concluiu o handshake dentro do prazo configurado."
                    },
                    authorizationMayBePending = authorizationMayBePending,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                publishFailure(generation, "O Android negou acesso ao dispositivo USB selecionado.")
            } catch (error: IOException) {
                publishFailure(generation, error.message ?: "Falha de entrada/saida durante o handshake ADB.")
            } catch (error: Exception) {
                publishFailure(
                    generation,
                    error.message ?: "Falha inesperada durante a validacao ADB: ${error.javaClass.simpleName}.",
                    authorizationMayBePending,
                )
            } finally {
                ownedTransport?.let { transport -> closeOwnedTransport(transport) }
            }
        }
    }

    /** Opens the allowlisted read-only services only after an explicit user action. */
    fun collectDiagnostics() {
        val request = synchronized(connectionLock) {
            val collectionGeneration = diagnosticsGeneration.incrementAndGet()
            val session = activeSession
            if (session == null || mutableState.value.operation !is AdbProbeOperationState.Connected) {
                mutableState.value = mutableState.value.copy(
                    diagnostics = AdbProbeDiagnosticsState.Error(
                        "Nao existe uma sessao ADB autenticada ativa para coletar diagnosticos.",
                    ),
                )
                null
            } else {
                mutableState.value = mutableState.value.copy(
                    diagnostics = AdbProbeDiagnosticsState.Running,
                )
                AdbDiagnosticsRequest(
                    operationGeneration = operationGeneration.get(),
                    collectionGeneration = collectionGeneration,
                    session = session,
                )
            }
        }
        diagnosticsJob?.cancel()
        if (request == null) {
            diagnosticsJob = null
            return
        }

        diagnosticsJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = AdbReadonlyDiagnosticRunner(request.session).collect()
                val published = publishDiagnosticsIfCurrent(
                    request = request,
                    diagnostics = AdbProbeDiagnosticsState.Ready(snapshot),
                )
                if (!published) return@launch

                diagnosticsRecorder.record(
                    severity = DiagnosticSeverity.INFO,
                    component = "adb",
                    action = "diagnostics.completed",
                    message = "Coleta ADB somente leitura concluida.",
                    metadata = mapOf(
                        "sectionCount" to snapshot.sections.size.toString(),
                        "retainedByteCount" to snapshot.retainedByteCount.toString(),
                        "budgetExhausted" to snapshot.budgetExhausted.toString(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val published = publishDiagnosticsIfCurrent(
                    request = request,
                    diagnostics = AdbProbeDiagnosticsState.Error(
                        error.message ?: "Falha inesperada na coleta ADB: ${error.javaClass.simpleName}.",
                    ),
                )
                if (!published) return@launch

                diagnosticsRecorder.record(
                    severity = DiagnosticSeverity.ERROR,
                    component = "adb",
                    action = "diagnostics.failed",
                    message = "Coleta ADB somente leitura falhou.",
                    metadata = mapOf("errorType" to error.javaClass.simpleName),
                )
            }
        }
    }

    fun cancelDiagnostics() {
        synchronized(connectionLock) {
            diagnosticsGeneration.incrementAndGet()
            if (mutableState.value.operation is AdbProbeOperationState.Connected) {
                mutableState.value = mutableState.value.copy(
                    diagnostics = AdbProbeDiagnosticsState.Idle,
                )
            }
        }
        diagnosticsJob?.cancel()
        diagnosticsJob = null
    }

    fun cancelActiveOperation() {
        synchronized(connectionLock) {
            operationGeneration.incrementAndGet()
            diagnosticsGeneration.incrementAndGet()
            mutableState.value = mutableState.value.copy(
                operation = AdbProbeOperationState.Idle,
                diagnostics = AdbProbeDiagnosticsState.Idle,
            )
        }
        probeJob?.cancel()
        diagnosticsJob?.cancel()
        scanJob?.cancel()
        scheduleActiveConnectionClose()
    }

    private fun publishScanIfCurrent(
        generation: Long,
        scan: AdbProbeScanState,
    ): Boolean = synchronized(connectionLock) {
        if (operationGeneration.get() != generation) {
            false
        } else {
            mutableState.value = AdbProbeScreenState(
                scan = scan,
                operation = AdbProbeOperationState.Idle,
            )
            true
        }
    }

    private fun publishRunning(
        generation: Long,
        transportId: String,
        stage: String,
        awaitingAuthorization: Boolean = false,
    ): Boolean = synchronized(connectionLock) {
        if (operationGeneration.get() != generation) {
            false
        } else {
            mutableState.value = mutableState.value.copy(
                operation = AdbProbeOperationState.Running(
                    transportId = transportId,
                    stage = stage,
                    awaitingDeviceAuthorization = awaitingAuthorization,
                ),
                diagnostics = AdbProbeDiagnosticsState.Idle,
            )
            true
        }
    }

    private fun publishFailure(
        generation: Long,
        message: String,
        authorizationMayBePending: Boolean = false,
    ) {
        val published = synchronized(connectionLock) {
            if (operationGeneration.get() != generation) {
                false
            } else {
                mutableState.value = mutableState.value.copy(
                    operation = AdbProbeOperationState.Error(message, authorizationMayBePending),
                    diagnostics = AdbProbeDiagnosticsState.Idle,
                )
                true
            }
        }
        if (!published) return

        diagnosticsRecorder.record(
            severity = DiagnosticSeverity.ERROR,
            component = "adb",
            action = "handshake.failed",
            message = "Validacao de conexao/autorizacao ADB falhou.",
            metadata = mapOf("authorizationMayBePending" to authorizationMayBePending.toString()),
        )
    }

    private fun registerActiveTransport(transport: AdbMessageTransport) {
        synchronized(connectionLock) {
            check(activeTransport == null && activeSession == null) {
                "Ja existe um recurso ADB ativo durante o registro de um novo transporte."
            }
            activeTransport = transport
        }
    }

    private fun promoteAndPublishConnected(
        generation: Long,
        transport: AdbMessageTransport,
        session: AdbSessionController,
        connectedState: AdbProbeOperationState.Connected,
    ): Boolean = synchronized(connectionLock) {
        if (
            operationGeneration.get() != generation ||
            activeTransport !== transport ||
            activeSession != null
        ) {
            false
        } else {
            // The session is the sole logical I/O owner. Keep the direct transport reference only
            // as an idempotent fallback close handle if session.close() itself fails unexpectedly.
            activeSession = session
            mutableState.value = mutableState.value.copy(
                operation = connectedState,
                diagnostics = AdbProbeDiagnosticsState.Idle,
            )
            true
        }
    }

    private fun isCurrentConnectedSession(
        generation: Long,
        session: AdbSessionController,
    ): Boolean = synchronized(connectionLock) {
        operationGeneration.get() == generation && activeSession === session
    }

    private fun publishDiagnosticsIfCurrent(
        request: AdbDiagnosticsRequest,
        diagnostics: AdbProbeDiagnosticsState,
    ): Boolean = synchronized(connectionLock) {
        if (
            operationGeneration.get() != request.operationGeneration ||
            diagnosticsGeneration.get() != request.collectionGeneration ||
            activeSession !== request.session
        ) {
            false
        } else {
            mutableState.value = mutableState.value.copy(diagnostics = diagnostics)
            true
        }
    }

    private suspend fun closeActiveConnection() {
        scheduleActiveConnectionClose()?.join()
    }

    private fun scheduleActiveConnectionClose(): Job? {
        var newlyScheduled: Job? = null
        val jobToAwait = synchronized(connectionLock) {
            val resources = AdbConnectionResources(
                session = activeSession,
                transport = activeTransport,
            )
            activeSession = null
            activeTransport = null

            val previousClose = pendingConnectionClose
            if (resources.isEmpty) {
                previousClose
            } else {
                resourceCleanupScope.launch(start = CoroutineStart.LAZY) {
                    previousClose?.join()
                    closeCapturedConnection(resources)
                }.also { job ->
                    pendingConnectionClose = job
                    newlyScheduled = job
                }
            }
        }

        newlyScheduled?.let { job ->
            job.invokeOnCompletion {
                synchronized(connectionLock) {
                    if (pendingConnectionClose === job) pendingConnectionClose = null
                }
            }
            job.start()
        }
        return jobToAwait
    }

    private suspend fun closeCapturedConnection(resources: AdbConnectionResources) {
        connectionLifecycleMutex.withLock {
            resources.session?.let { session -> closeSessionSafely(session, "active") }
            resources.transport?.let { transport -> closeTransportSafely(transport, "fallback") }
        }
    }

    private suspend fun closeOwnedTransport(transport: AdbMessageTransport) {
        synchronized(connectionLock) {
            if (activeTransport === transport && activeSession == null) activeTransport = null
        }
        withContext(NonCancellable) {
            connectionLifecycleMutex.withLock {
                closeTransportSafely(transport, "owned")
            }
        }
    }

    private suspend fun closeSessionSafely(
        session: AdbSessionController,
        ownership: String,
    ) {
        try {
            withContext(NonCancellable) {
                session.close()
            }
        } catch (error: Exception) {
            diagnosticsRecorder.record(
                severity = DiagnosticSeverity.ERROR,
                component = "adb",
                action = "session.close.failed",
                message = "Falha ao fechar sessao ADB de forma deterministica.",
                metadata = mapOf(
                    "ownership" to ownership,
                    "errorType" to error.javaClass.simpleName,
                ),
            )
        }
    }

    private suspend fun closeTransportSafely(
        transport: AdbMessageTransport,
        ownership: String,
    ) {
        try {
            withContext(NonCancellable) {
                transport.close()
            }
        } catch (error: Exception) {
            diagnosticsRecorder.record(
                severity = DiagnosticSeverity.ERROR,
                component = "adb",
                action = "transport.close.failed",
                message = "Falha ao fechar transporte ADB de forma deterministica.",
                metadata = mapOf(
                    "ownership" to ownership,
                    "errorType" to error.javaClass.simpleName,
                ),
            )
        }
    }

    private suspend fun closeBackendSafely() {
        try {
            withContext(NonCancellable) {
                usbBackend.close()
            }
        } catch (error: Exception) {
            diagnosticsRecorder.record(
                severity = DiagnosticSeverity.ERROR,
                component = "adb",
                action = "backend.close.failed",
                message = "Falha ao fechar backend USB do probe ADB.",
                metadata = mapOf("errorType" to error.javaClass.simpleName),
            )
        }
    }

    override fun onCleared() {
        synchronized(connectionLock) {
            operationGeneration.incrementAndGet()
            diagnosticsGeneration.incrementAndGet()
        }
        scanJob?.cancel()
        probeJob?.cancel()
        diagnosticsJob?.cancel()
        val closeJob = scheduleActiveConnectionClose()
        resourceCleanupScope.launch {
            closeJob?.join()
            closeBackendSafely()
        }
        super.onCleared()
    }

    private fun org.rockservice.core.usb.UsbDeviceTopology.isUnambiguousAdbProfile(): Boolean {
        val matchingInterfaces = interfaces.filter { usbInterface ->
            AdbUsbInterfaceProfile.matches(
                usbInterface.interfaceClass,
                usbInterface.interfaceSubclass,
                usbInterface.interfaceProtocol,
            )
        }
        if (matchingInterfaces.size != 1) return false
        val endpoints = matchingInterfaces.single().endpoints
        val bulkIn = endpoints.count { endpoint ->
            endpoint.transferType == UsbTransferType.BULK && endpoint.direction == UsbEndpointDirection.IN
        }
        val bulkOut = endpoints.count { endpoint ->
            endpoint.transferType == UsbTransferType.BULK && endpoint.direction == UsbEndpointDirection.OUT
        }
        return bulkIn == 1 && bulkOut == 1
    }

    private companion object {
        val resourceCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        const val USB_PERMISSION_TIMEOUT_MILLIS = 30_000L
        const val HANDSHAKE_TOTAL_TIMEOUT_MILLIS = 45_000L
        const val MESSAGE_TIMEOUT_MILLIS = 10_000L
        const val MAXIMUM_HANDSHAKE_MESSAGES = 8
    }
}

internal class AdbProbeViewModelFactory(
    private val context: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AdbProbeViewModel::class.java))
        return AdbProbeViewModel(appContext = context.applicationContext) as T
    }
}
