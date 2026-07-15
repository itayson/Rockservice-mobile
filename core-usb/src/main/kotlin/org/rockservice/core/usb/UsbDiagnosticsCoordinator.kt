package org.rockservice.core.usb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Immutable passive diagnostic snapshot for one freshly enumerated USB device. */
data class UsbDiagnosticsDeviceSnapshot(
    val descriptor: UsbDeviceDescriptor,
    val topology: UsbDeviceTopology,
    val rockchipProbe: RockchipPassiveProbeResult,
)

/** Result of the latest passive USB diagnostics scan. */
sealed interface UsbDiagnosticsState {
    data object Loading : UsbDiagnosticsState

    data class Ready(
        val devices: List<UsbDiagnosticsDeviceSnapshot>,
    ) : UsbDiagnosticsState

    data class Error(
        val message: String,
    ) : UsbDiagnosticsState
}

/** Boundary used by state holders to request one fresh passive USB diagnostic snapshot. */
interface UsbDiagnosticsScanner {
    /** Returns one complete passive snapshot or rethrows cooperative coroutine cancellation. */
    suspend fun scan(): UsbDiagnosticsState
}

/**
 * Enumerates Android USB Host devices and inspects descriptor topology without sending commands.
 */
class AndroidUsbDiagnosticsScanner(
    private val backend: AndroidUsbHostBackend,
) : UsbDiagnosticsScanner {
    /** Performs one fresh passive enumeration against the Android USB Host backend. */
    override suspend fun scan(): UsbDiagnosticsState =
        try {
            val devices = backend.listDevices().map { device ->
                val topology = backend.inspectTopology(device)
                UsbDiagnosticsDeviceSnapshot(
                    descriptor = device,
                    topology = topology,
                    rockchipProbe = RockchipPassiveProbe.probe(device, topology),
                )
            }
            UsbDiagnosticsState.Ready(devices)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            UsbDiagnosticsState.Error(
                message = buildString {
                    append("Falha ao enumerar dispositivos USB. ")
                    append("Reconecte o dispositivo e tente novamente.")
                    error.message?.takeIf(String::isNotBlank)?.let { detail ->
                        append(" Detalhe: ")
                        append(detail)
                    }
                },
            )
        }
}

/** Immutable coordinator state containing the latest diagnostics and selected transport target. */
data class UsbDiagnosticsCoordinatorState(
    val diagnostics: UsbDiagnosticsState = UsbDiagnosticsState.Loading,
    val selectedTransportId: String? = null,
)

/**
 * Serializes passive diagnostics refreshes and target selection against one shared state snapshot.
 */
class UsbDiagnosticsCoordinator {
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow(UsbDiagnosticsCoordinatorState())

    /** Read-only state for presentation or other consumers. */
    val state: StateFlow<UsbDiagnosticsCoordinatorState> = _state.asStateFlow()

    /**
     * Runs one serialized refresh and reconciles the selected target with the new enumeration.
     * Any non-successful refresh clears the target because its continued presence was not revalidated.
     */
    suspend fun refresh(scanner: UsbDiagnosticsScanner) {
        operationMutex.withLock {
            val previous = _state.value
            _state.value = previous.copy(diagnostics = UsbDiagnosticsState.Loading)

            val refreshed = scanner.scan()
            val reconciledSelection = if (refreshed is UsbDiagnosticsState.Ready) {
                UsbTargetSelectionPolicy.reconcile(
                    selectedTransportId = previous.selectedTransportId,
                    devices = refreshed.devices.map { device -> device.descriptor },
                )
            } else {
                null
            }

            _state.value = UsbDiagnosticsCoordinatorState(
                diagnostics = refreshed,
                selectedTransportId = reconciledSelection,
            )
        }
    }

    /** Selects a uniquely enumerated target while excluding concurrent refresh state transitions. */
    suspend fun selectTarget(transportId: String) {
        operationMutex.withLock {
            val current = _state.value
            val ready = current.diagnostics as? UsbDiagnosticsState.Ready ?: return@withLock
            val candidate = ready.devices.singleOrNull { device ->
                device.descriptor.transportId == transportId
            } ?: return@withLock

            _state.value = current.copy(
                selectedTransportId = UsbTargetSelectionPolicy.select(
                    candidate = candidate.descriptor,
                    devices = ready.devices.map { device -> device.descriptor },
                ),
            )
        }
    }
}
