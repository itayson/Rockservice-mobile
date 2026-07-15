package org.rockservice.mobile

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.rockservice.core.usb.AndroidUsbHostBackend
import org.rockservice.core.usb.UsbAttachmentEventKind
import org.rockservice.core.usb.UsbDiagnosticsDeviceSnapshot
import org.rockservice.core.usb.UsbHardwareValidationDescriptorCheck
import org.rockservice.core.usb.UsbHardwareValidationDevice
import org.rockservice.core.usb.UsbHardwareValidationEvent
import org.rockservice.core.usb.UsbHardwareValidationHostInfo
import org.rockservice.core.usb.UsbHardwareValidationNotes
import org.rockservice.core.usb.UsbHardwareValidationReport

internal sealed interface HardwareValidationRunState {
    data object Idle : HardwareValidationRunState
    data object Running : HardwareValidationRunState
    data class Ready(val report: UsbHardwareValidationReport) : HardwareValidationRunState
}

internal data class HardwareValidationScreenState(
    val boardOrDeviceModel: String = "",
    val knownSoc: String = "",
    val otgAdapter: String = "",
    val events: List<UsbHardwareValidationEvent> = emptyList(),
    val runState: HardwareValidationRunState = HardwareValidationRunState.Idle,
    val exportMessage: String? = null,
)

/** Owns passive hardware-validation evidence without retaining Android USB transport identifiers. */
internal class HardwareValidationViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(HardwareValidationScreenState())
    private var validationJob: Job? = null
    private var exportJob: Job? = null
    private var validationGeneration: Long = 0L

    val state = mutableState.asStateFlow()

    /** Updates the operator-provided board or device model with a bounded value. */
    fun setBoardOrDeviceModel(value: String) {
        mutableState.value = mutableState.value.copy(boardOrDeviceModel = value.take(MAXIMUM_NOTE_LENGTH))
    }

    /** Updates the optional known SoC label with a bounded value. */
    fun setKnownSoc(value: String) {
        mutableState.value = mutableState.value.copy(knownSoc = value.take(MAXIMUM_NOTE_LENGTH))
    }

    /** Updates the OTG cable or adapter description with a bounded value. */
    fun setOtgAdapter(value: String) {
        mutableState.value = mutableState.value.copy(otgAdapter = value.take(MAXIMUM_NOTE_LENGTH))
    }

    /** Records only attach/detach kind and timestamp; transport path hints are deliberately discarded. */
    fun recordAttachmentEvent(kind: UsbAttachmentEventKind) {
        val event = UsbHardwareValidationEvent(
            kind = kind,
            timestampEpochMillis = System.currentTimeMillis(),
        )
        mutableState.value = mutableState.value.copy(
            events = (mutableState.value.events + event).takeLast(MAXIMUM_RECORDED_EVENTS),
        )
    }

    /**
     * Runs the bounded Android USB descriptor read used by hardware gate #18.
     *
     * The backend revalidates identity, requests permission when required and opens the connection,
     * but it does not claim an interface or send an endpoint/Rockchip command.
     */
    fun runValidation(
        hostInfo: UsbHardwareValidationHostInfo,
        snapshot: UsbDiagnosticsDeviceSnapshot,
        backend: AndroidUsbHostBackend,
    ) {
        validationJob?.cancel()
        val generation = ++validationGeneration
        val stateAtStart = mutableState.value
        mutableState.value = stateAtStart.copy(
            runState = HardwareValidationRunState.Running,
            exportMessage = null,
        )
        validationJob = viewModelScope.launch(Dispatchers.IO) {
            val check = try {
                val bytes = backend.read(
                    device = snapshot.descriptor,
                    offset = 0L,
                    length = VALIDATION_DESCRIPTOR_BYTES,
                    timeoutMillis = VALIDATION_TIMEOUT_MILLIS,
                )
                UsbHardwareValidationDescriptorCheck(
                    succeeded = true,
                    bytesRead = bytes.size,
                    sha256 = bytes.sha256(),
                    detail = "Permissao, revalidacao do alvo, abertura da conexao e leitura limitada de descritores concluidas.",
                )
            } catch (timeout: TimeoutCancellationException) {
                UsbHardwareValidationDescriptorCheck(
                    succeeded = false,
                    bytesRead = 0,
                    sha256 = null,
                    detail = "Timeout durante a validacao segura de descritores.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                failedCheck("O Android negou acesso USB: ${error.message ?: "sem detalhe"}.")
            } catch (error: IllegalArgumentException) {
                failedCheck(error.message ?: "O alvo USB mudou ou deixou de estar conectado.")
            } catch (error: IllegalStateException) {
                failedCheck(error.message ?: "A permissao USB foi negada ou a conexao nao pode ser aberta.")
            } catch (error: IOException) {
                failedCheck(error.message ?: "Falha de entrada/saida durante a leitura dos descritores.")
            } catch (error: RuntimeException) {
                failedCheck("Falha inesperada ${error.javaClass.simpleName}: ${error.message ?: "sem detalhe"}.")
            }

            if (generation != validationGeneration) return@launch
            val latest = mutableState.value
            val report = UsbHardwareValidationReport(
                generatedAtEpochMillis = System.currentTimeMillis(),
                host = hostInfo,
                notes = UsbHardwareValidationNotes(
                    boardOrDeviceModel = latest.boardOrDeviceModel,
                    knownSoc = latest.knownSoc,
                    otgAdapter = latest.otgAdapter,
                ),
                device = UsbHardwareValidationDevice.from(snapshot),
                descriptorCheck = check,
                events = latest.events,
            )
            mutableState.value = latest.copy(
                runState = HardwareValidationRunState.Ready(report),
            )
        }
    }

    /** Writes the latest sanitized validation report to a destination selected by the user. */
    fun exportReport(contentResolver: ContentResolver, uri: Uri) {
        val report = (mutableState.value.runState as? HardwareValidationRunState.Ready)?.report ?: return
        exportJob?.cancel()
        exportJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val output = contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("O destino selecionado nao pode ser aberto.")
                output.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(report.toPlainText())
                }
                mutableState.value = mutableState.value.copy(
                    exportMessage = "Relatorio de validacao exportado.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                mutableState.value = mutableState.value.copy(
                    exportMessage = "O Android negou acesso ao destino do relatorio.",
                )
            } catch (error: IOException) {
                mutableState.value = mutableState.value.copy(
                    exportMessage = error.message ?: "Falha ao exportar o relatorio.",
                )
            }
        }
    }

    override fun onCleared() {
        validationGeneration += 1L
        validationJob?.cancel()
        exportJob?.cancel()
        super.onCleared()
    }

    private fun failedCheck(detail: String): UsbHardwareValidationDescriptorCheck =
        UsbHardwareValidationDescriptorCheck(
            succeeded = false,
            bytesRead = 0,
            sha256 = null,
            detail = detail,
        )

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val VALIDATION_DESCRIPTOR_BYTES = 256
        const val VALIDATION_TIMEOUT_MILLIS = 8_000L
        const val MAXIMUM_NOTE_LENGTH = 200
        const val MAXIMUM_RECORDED_EVENTS = 100
    }
}
