package org.rockservice.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.rockservice.core.common.diagnostics.DiagnosticEventRecorder
import org.rockservice.core.common.diagnostics.DiagnosticSeverity
import org.rockservice.core.usb.UsbDiagnosticsCoordinator
import org.rockservice.core.usb.UsbDiagnosticsScanner
import org.rockservice.core.usb.UsbDiagnosticsState

/**
 * Retains passive USB diagnostics presentation state across configuration changes.
 *
 * Android USB resources are deliberately supplied per refresh and are never retained by this
 * ViewModel, allowing each Activity instance to own and close its platform resources explicitly.
 */
internal class UsbDiagnosticsViewModel(
    private val diagnosticsRecorder: DiagnosticEventRecorder = AppDiagnostics.recorder,
) : ViewModel() {
    private val coordinator = UsbDiagnosticsCoordinator()
    private var refreshJob: Job? = null

    /** Current passive diagnostics and selected-target state from the core coordinator. */
    val state = coordinator.state

    /** Cancels the previous refresh, if any, and requests a new scan using the current host scanner. */
    fun refresh(scanner: UsbDiagnosticsScanner) {
        refreshJob?.cancel()
        diagnosticsRecorder.record(
            severity = DiagnosticSeverity.INFO,
            component = "usb",
            action = "diagnostics.refresh.requested",
            message = "Nova enumeração passiva USB solicitada.",
        )
        refreshJob = viewModelScope.launch {
            try {
                coordinator.refresh(scanner)
                when (val result = state.value.diagnostics) {
                    UsbDiagnosticsState.Loading -> Unit
                    is UsbDiagnosticsState.Error -> diagnosticsRecorder.record(
                        severity = DiagnosticSeverity.ERROR,
                        component = "usb",
                        action = "diagnostics.refresh.failed",
                        message = "Enumeração passiva USB concluída com falha.",
                    )
                    is UsbDiagnosticsState.Ready -> diagnosticsRecorder.record(
                        severity = DiagnosticSeverity.INFO,
                        component = "usb",
                        action = "diagnostics.refresh.completed",
                        message = "Enumeração passiva USB concluída.",
                        metadata = mapOf("deviceCount" to result.devices.size.toString()),
                    )
                }
            } catch (cancelled: CancellationException) {
                diagnosticsRecorder.record(
                    severity = DiagnosticSeverity.DEBUG,
                    component = "usb",
                    action = "diagnostics.refresh.cancelled",
                    message = "Enumeração USB anterior cancelada por uma solicitação mais nova.",
                )
                throw cancelled
            }
        }
    }

    /** Serializes target selection with any in-flight refresh through the core coordinator. */
    fun selectTarget(transportId: String) {
        diagnosticsRecorder.record(
            severity = DiagnosticSeverity.INFO,
            component = "usb",
            action = "target.selection.requested",
            message = "Seleção explícita de alvo USB solicitada.",
            metadata = mapOf("transportId" to transportId),
        )
        viewModelScope.launch {
            coordinator.selectTarget(transportId)
            diagnosticsRecorder.record(
                severity = DiagnosticSeverity.INFO,
                component = "usb",
                action = "target.selection.completed",
                message = "Seleção explícita de alvo USB processada.",
                metadata = mapOf("transportId" to transportId),
            )
        }
    }

    /** Cancels an in-flight refresh before the owning Android host closes its USB resources. */
    fun cancelRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    override fun onCleared() {
        cancelRefresh()
        super.onCleared()
    }
}
