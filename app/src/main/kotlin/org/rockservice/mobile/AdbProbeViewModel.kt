package org.rockservice.mobile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.rockservice.core.common.diagnostics.DiagnosticEventRecorder
import org.rockservice.core.common.diagnostics.DiagnosticSeverity
import org.rockservice.core.usb.AndroidUsbHostBackend
import org.rockservice.core.usb.UsbDeviceDescriptor
import org.rockservice.core.usb.UsbEndpointDirection
import org.rockservice.core.usb.UsbTransferType
import org.rockservice.core.usb.adb.AdbHandshakeState
import org.rockservice.core.usb.adb.AdbHandshakeStateMachine
import org.rockservice.core.usb.adb.AdbMessageTransport
import org.rockservice.core.usb.adb.AdbUsbInterfaceProfile
import org.rockservice.core.usb.adb.AndroidAdbUsbTransportFactory

internal data class AdbProbeCandidate(
    val descriptor: UsbDeviceDescriptor,
    val displayName: String,
)

internal sealed interface AdbProbeScanState {
    data object Loading : AdbProbeScanState

    data class Ready(
        val candidates: List<AdbProbeCandidate>,
    ) : AdbProbeScanState

    data class Error(
        val message: String,
    ) : AdbProbeScanState
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

internal data class AdbProbeScreenState(
    val scan: AdbProbeScanState = AdbProbeScanState.Loading,
    val operation: AdbProbeOperationState = AdbProbeOperationState.Idle,
)

/** Enumerates canonical ADB USB targets and performs only the CNXN/AUTH handshake. */
internal class AdbProbeViewModel(
    private val appContext: Context,
    private val usbBackend: AndroidUsbHostBackend,
    private val diagnosticsRecorder: DiagnosticEventRecorder = AppDiagnostics.recorder,
) : ViewModel() {
    private val transportFactory = AndroidAdbUsbTransportFactory(appContext)
    private val identityStore = AdbAppIdentityStore(appContext)
    private val mutableState = MutableStateFlow(AdbProbeScreenState())
    private val operationGeneration = AtomicLong(0L)
    private var scanJob: Job? = null
    private var probeJob: Job? = null
    private var activeTransport: AdbMessageTransport? = null

    val state = mutableState.asStateFlow()

    /** Re-enumerates USB and keeps only targets with one unambiguous canonical ADB interface. */
    fun refresh() {
        val generation = operationGeneration.incrementAndGet()
        probeJob?.cancel()
        scanJob?.cancel()
        mutableState.value = AdbProbeScreenState(scan = AdbProbeScanState.Loading)

        scanJob = viewModelScope.launch(Dispatchers.IO) {
            closeActiveTransport()
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
                if (operationGeneration.get() == generation) {
                    mutableState.value = AdbProbeScreenState(
                        scan = AdbProbeScanState.Ready(candidates),
                        operation = AdbProbeOperationState.Idle,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (operationGeneration.get() == generation) {
                    mutableState.value = AdbProbeScreenState(
                        scan = AdbProbeScanState.Error(
                            error.message ?: "Falha ao procurar interfaces ADB USB.",
                        ),
                    )
                }
            }
        }
    }

    /** Requests permission, revalidates the target and performs only the bounded ADB handshake. */
    fun probe(candidate: AdbProbeCandidate) {
        val generation = operationGeneration.incrementAndGet()
        probeJob?.cancel()
        val transportId = requireNotNull(candidate.descriptor.transportId)
        mutableState.value = mutableState.value.copy(
            operation = AdbProbeOperationState.Running(
                transportId = transportId,
                stage = "Solicitando permissao USB e revalidando o alvo...",
            ),
        )

        probeJob = viewModelScope.launch(Dispatchers.IO) {
            closeActiveTransport()
            var authorizationMayBePending = false
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
                activeTransport = transport

                withTimeout(HANDSHAKE_TOTAL_TIMEOUT_MILLIS) {
                    val start = machine.start()
                    transport.send(requireNotNull(start.outbound), MESSAGE_TIMEOUT_MILLIS)
                    publishRunning(generation, transportId, "CNXN enviado; aguardando resposta do dispositivo...")

                    repeat(MAXIMUM_HANDSHAKE_MESSAGES) {
                        val incoming = transport.receive(
                            timeoutMillis = MESSAGE_TIMEOUT_MILLIS,
                            // Modern ADB peers may omit the legacy additive checksum during handshake.
                            requireChecksum = false,
                        )
                        val transition = machine.receive(incoming)
                        when (val handshakeState = transition.state) {
                            is AdbHandshakeState.Connected -> {
                                if (operationGeneration.get() == generation) {
                                    mutableState.value = mutableState.value.copy(
                                        operation = AdbProbeOperationState.Connected(
                                            transportId = transportId,
                                            protocolVersion = handshakeState.peer.protocolVersion,
                                            maxDataBytes = handshakeState.peer.maxDataBytes,
                                            banner = handshakeState.peer.banner,
                                        ),
                                    )
                                    diagnosticsRecorder.record(
                                        severity = DiagnosticSeverity.INFO,
                                        component = "adb",
                                        action = "handshake.connected",
                                        message = "Handshake ADB concluido sem abertura de servico.",
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (timeout: TimeoutCancellationException) {
                publishFailure(
                    generation = generation,
                    message = if (authorizationMayBePending) {
                        "O dispositivo nao concluiu a autorizacao ADB. Confirme o dialogo RSA no dispositivo e execute o probe novamente."
                    } else {
                        "O dispositivo ADB nao concluiu o handshake dentro do prazo configurado."
                    },
                    authorizationMayBePending = authorizationMayBePending,
                )
            } catch (error: SecurityException) {
                publishFailure(generation, "O Android negou acesso ao dispositivo USB selecionado.")
            } catch (error: IOException) {
                publishFailure(generation, error.message ?: "Falha de entrada/saida durante o handshake ADB.")
            } catch (error: Exception) {
                publishFailure(
                    generation,
                    error.message ?: "Falha inesperada durante o probe ADB: ${error.javaClass.simpleName}.",
                    authorizationMayBePending,
                )
            } finally {
                closeActiveTransport()
            }
        }
    }

    fun cancelActiveOperation() {
        operationGeneration.incrementAndGet()
        probeJob?.cancel()
        scanJob?.cancel()
        mutableState.value = mutableState.value.copy(operation = AdbProbeOperationState.Idle)
        viewModelScope.launch(Dispatchers.IO) { closeActiveTransport() }
    }

    private fun publishRunning(
        generation: Long,
        transportId: String,
        stage: String,
        awaitingAuthorization: Boolean = false,
    ) {
        if (operationGeneration.get() != generation) return
        mutableState.value = mutableState.value.copy(
            operation = AdbProbeOperationState.Running(
                transportId = transportId,
                stage = stage,
                awaitingDeviceAuthorization = awaitingAuthorization,
            ),
        )
    }

    private fun publishFailure(
        generation: Long,
        message: String,
        authorizationMayBePending: Boolean = false,
    ) {
        if (operationGeneration.get() != generation) return
        mutableState.value = mutableState.value.copy(
            operation = AdbProbeOperationState.Error(message, authorizationMayBePending),
        )
        diagnosticsRecorder.record(
            severity = DiagnosticSeverity.ERROR,
            component = "adb",
            action = "handshake.failed",
            message = "Probe de conexao/autorizacao ADB falhou.",
            metadata = mapOf("authorizationMayBePending" to authorizationMayBePending.toString()),
        )
    }

    private suspend fun closeActiveTransport() {
        val transport = activeTransport
        activeTransport = null
        runCatching { transport?.close() }
    }

    override fun onCleared() {
        operationGeneration.incrementAndGet()
        scanJob?.cancel()
        probeJob?.cancel()
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
        const val USB_PERMISSION_TIMEOUT_MILLIS = 30_000L
        const val HANDSHAKE_TOTAL_TIMEOUT_MILLIS = 45_000L
        const val MESSAGE_TIMEOUT_MILLIS = 10_000L
        const val MAXIMUM_HANDSHAKE_MESSAGES = 8
    }
}

internal class AdbProbeViewModelFactory(
    private val context: Context,
    private val usbBackend: AndroidUsbHostBackend,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AdbProbeViewModel::class.java))
        return AdbProbeViewModel(
            appContext = context.applicationContext,
            usbBackend = usbBackend,
        ) as T
    }
}
