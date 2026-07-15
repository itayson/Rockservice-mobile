package org.rockservice.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.rockservice.core.usb.UsbDiagnosticsCoordinator
import org.rockservice.core.usb.UsbDiagnosticsScanner

/**
 * Retains passive USB diagnostics presentation state across configuration changes.
 *
 * Android USB resources are deliberately supplied per refresh and are never retained by this
 * ViewModel, allowing each Activity instance to own and close its platform resources explicitly.
 */
internal class UsbDiagnosticsViewModel : ViewModel() {
    private val coordinator = UsbDiagnosticsCoordinator()
    private var refreshJob: Job? = null

    /** Current passive diagnostics and selected-target state from the core coordinator. */
    val state = coordinator.state

    /** Cancels the previous refresh, if any, and requests a new scan using the current host scanner. */
    fun refresh(scanner: UsbDiagnosticsScanner) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            coordinator.refresh(scanner)
        }
    }

    /** Delegates target selection to the coordinator's fresh-enumeration policy. */
    fun selectTarget(transportId: String) {
        coordinator.selectTarget(transportId)
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
