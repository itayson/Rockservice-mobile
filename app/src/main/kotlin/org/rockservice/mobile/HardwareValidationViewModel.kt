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
import org.rockservice.core.usb.rockchip.AndroidRockchipReadOnlyMetadataClient
import org.rockservice.core.usb.rockchip.RockchipMetadataProbeReport

internal sealed interface HardwareValidationRunState {
    data object Idle : HardwareValidationRunState
    data object Running : HardwareValidationRunState
    data class Ready(val report: UsbHardwareValidationReport) : HardwareValidationRunState
}

internal sealed interface RockchipMetadataProbeState {
    data object Idle : RockchipMetadataProbeState
    data object Running : RockchipMetadataProbeState
    data class Ready(val report: RockchipMetadataProbeReport) : RockchipMetadataProbeState
    data class Error(val message: String) : RockchipMetadataProbeState
}

internal data class HardwareValidationScreenState(
    val boardOrDeviceModel: String = "",
    val knownSoc: String = "",
    val otgAdapter: String = "",
    val events: List<UsbHardwareValidationEvent> = emptyList(),
    val runState: HardwareValidationRunState = HardwareValidationRunState.Idle,
    val metadataProbeState: RockchipMetadataProbeState = RockchipMetadataProbeState.Idle,
    val exportMessage: String? = null,
)

/** Owns passive hardware-validation evidence without retaining Android USB transport identifiers. */
internal class HardwareValidationViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(HardwareValidationScreenState())
    private val stateLock = Any()
    private var validationJob: Job? = null
    private var metadataProbeJob: Job? = null
    private var exportJob: Job? = null
    @Volatile
    private var validationGeneration: Long = 0L
    @Volatile
    private var metadataProbeGeneration: Long = 0L
    @Volatile
    private var exportGeneration: Long = 0L

    val state = mutableState.asStateFlow()

    fun setBoardOrDeviceModel(value: String) {
        mutableState.value = mutableState.value.copy(boardOrDeviceModel = value.take(MAXIMUM_NOTE_LENGTH))
    }

    fun setKnownSoc(value: String) {
        mutableState.value = mutableState.value.copy(knownSoc = value.take(MAXIMUM_NOTE_LENGTH))
    }

    fun setOtgAdapter(value: String) {
        mutableState.value = mutableState.value.copy(otgAdapter = value.take(MAXIMUM_NOTE_LENGTH))
    }

    fun invalidateActiveTarget() {
        validationJob?.cancel()
        metadataProbeJob?.cancel()
        exportJob?.cancel()
        synchronized(stateLock) {
            validationGeneration += 1L
            metadataProbeGeneration += 1L
            exportGeneration += 1L
            mutableState.value = mutableState.value.copy(
                runState = HardwareValidationRunState.Idle,
                metadataProbeState = RockchipMetadataProbeState.Idle,
                exportMessage = null,
            )
        }
    }

    fun recordAttachmentEvent(kind: UsbAttachmentEventKind) {
        val event = UsbHardwareValidationEvent(
            kind = kind,
            timestampEpochMillis = System.currentTimeMillis(),
        )
        validationJob?.cancel()
        metadataProbeJob?.cancel()
        exportJob?.cancel()
        synchronized(stateLock) {
            validationGeneration += 1L
            metadataProbeGeneration += 1L
            exportGeneration += 1L
            val current = mutableState.value
            mutableState.value = current.copy(
                events = (current.events + event).takeLast(MAXIMUM_RECORDED_EVENTS),
                runState = HardwareValidationRunState.Idle,
                metadataProbeState = RockchipMetadataProbeState.Idle,
                exportMessage = null,
            )
        }
    }

    fun runValidation(
        hostInfo: UsbHardwareValidationHostInfo,
        snapshot: UsbDiagnosticsDeviceSnapshot,
        backend: AndroidUsbHostBackend,
    ) {
        validationJob?.cancel()
        metadataProbeJob?.cancel()
        exportJob?.cancel()
        val generation = synchronized(stateLock) {
            metadataProbeGeneration += 1L
            exportGeneration += 1L
            val nextGeneration = ++validationGeneration
            val stateAtStart = mutableState.value
            mutableState.value = stateAtStart.copy(
                runState = HardwareValidationRunState.Running,
                metadataProbeState = RockchipMetadataProbeState.Idle,
                exportMessage = null,
            )
            nextGeneration
        }
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

            synchronized(stateLock) {
                if (generation != validationGeneration) return@synchronized
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
                mutableState.value = latest.copy(runState = HardwareValidationRunState.Ready(report))
            }
        }
    }

    /** Runs allowlisted Rockchip metadata commands only after the passive validation succeeded. */
    fun runMetadataProbe(
        snapshot: UsbDiagnosticsDeviceSnapshot,
        client: AndroidRockchipReadOnlyMetadataClient,
    ) {
        metadataProbeJob?.cancel()
        val generation = synchronized(stateLock) {
            val validation = mutableState.value.runState as? HardwareValidationRunState.Ready
                ?: return
            if (!validation.report.descriptorCheck.succeeded) return
            val nextGeneration = ++metadataProbeGeneration
            mutableState.value = mutableState.value.copy(metadataProbeState = RockchipMetadataProbeState.Running)
            nextGeneration
        }
        metadataProbeJob = viewModelScope.launch(Dispatchers.IO) {
            val nextState = try {
                RockchipMetadataProbeState.Ready(client.probe(snapshot.descriptor))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                RockchipMetadataProbeState.Error(
                    error.message?.take(MAXIMUM_PROBE_ERROR_LENGTH)?.ifBlank { null }
                        ?: "Falha inesperada ${error.javaClass.simpleName} no probe de metadados.",
                )
            }
            synchronized(stateLock) {
                if (generation != metadataProbeGeneration) return@synchronized
                mutableState.value = mutableState.value.copy(metadataProbeState = nextState)
            }
        }
    }

    fun exportReport(contentResolver: ContentResolver, uri: Uri) {
        exportJob?.cancel()
        val export = synchronized(stateLock) {
            val report = (mutableState.value.runState as? HardwareValidationRunState.Ready)?.report
                ?: return
            val generation = ++exportGeneration
            mutableState.value = mutableState.value.copy(exportMessage = null)
            report to generation
        }
        val report = export.first
        val generation = export.second
        exportJob = viewModelScope.launch(Dispatchers.IO) {
            val message = try {
                val output = contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("O destino selecionado nao pode ser aberto.")
                output.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(report.toPlainText()) }
                "Relatorio de validacao exportado."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                error.message?.ifBlank { null }
                    ?: "O Android negou acesso ao destino do relatorio."
            } catch (error: IOException) {
                error.message ?: "Falha ao exportar o relatorio."
            }
            synchronized(stateLock) {
                if (generation != exportGeneration) return@synchronized
                mutableState.value = mutableState.value.copy(exportMessage = message)
            }
        }
    }

    override fun onCleared() {
        validationJob?.cancel()
        metadataProbeJob?.cancel()
        exportJob?.cancel()
        synchronized(stateLock) {
            validationGeneration += 1L
            metadataProbeGeneration += 1L
            exportGeneration += 1L
        }
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
        const val MAXIMUM_PROBE_ERROR_LENGTH = 240
    }
}
