package org.rockservice.mobile

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.rockservice.core.common.diagnostics.DiagnosticEventRecorder
import org.rockservice.core.common.diagnostics.DiagnosticSeverity
import org.rockservice.feature.firmware.AndroidSparseExpansionReport
import org.rockservice.feature.firmware.AndroidSparseImageExpander
import org.rockservice.feature.firmware.FirmwareFormat
import org.rockservice.feature.firmware.FirmwareLabAnalyzer
import org.rockservice.feature.firmware.FirmwareLabReport

internal sealed interface FirmwareLabAnalysisState {
    data object Idle : FirmwareLabAnalysisState

    data class Analyzing(
        val displayName: String,
    ) : FirmwareLabAnalysisState

    data class Ready(
        val report: FirmwareLabReport,
    ) : FirmwareLabAnalysisState

    data class Error(
        val message: String,
    ) : FirmwareLabAnalysisState
}

internal sealed interface FirmwareLabExpansionState {
    data object Idle : FirmwareLabExpansionState

    data class Expanding(
        val displayName: String,
    ) : FirmwareLabExpansionState

    data class Ready(
        val report: AndroidSparseExpansionReport,
    ) : FirmwareLabExpansionState

    data class Error(
        val message: String,
        val destinationMayContainPartialData: Boolean,
    ) : FirmwareLabExpansionState
}

internal data class FirmwareLabScreenState(
    val analysis: FirmwareLabAnalysisState = FirmwareLabAnalysisState.Idle,
    val exportMessage: String? = null,
    val expansion: FirmwareLabExpansionState = FirmwareLabExpansionState.Idle,
)

/** Retains firmware-laboratory state while opening document streams only for active operations. */
internal class FirmwareLabViewModel(
    private val diagnosticsRecorder: DiagnosticEventRecorder = AppDiagnostics.recorder,
) : ViewModel() {
    private val analyzer = FirmwareLabAnalyzer()
    private val sparseExpander = AndroidSparseImageExpander()
    private val mutableState = MutableStateFlow(FirmwareLabScreenState())
    private val stateLock = Any()
    private val analysisGeneration = AtomicLong(0L)
    private val expansionGeneration = AtomicLong(0L)
    private var selectedSourceUri: Uri? = null
    private var analysisJob: Job? = null
    private var exportJob: Job? = null
    private var expansionJob: Job? = null

    val state = mutableState.asStateFlow()

    /** Cancels previous work and analyzes the selected read-only document on an IO dispatcher. */
    fun analyze(contentResolver: ContentResolver, uri: Uri) {
        val generation = analysisGeneration.incrementAndGet()
        expansionGeneration.incrementAndGet()
        analysisJob?.cancel()
        exportJob?.cancel()
        expansionJob?.cancel()
        synchronized(stateLock) {
            selectedSourceUri = uri
            mutableState.value = FirmwareLabScreenState()
        }
        diagnosticsRecorder.record(
            severity = DiagnosticSeverity.INFO,
            component = "firmware",
            action = "analysis.requested",
            message = "Análise de firmware solicitada pelo operador.",
        )

        analysisJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val metadata = readDocumentMetadata(contentResolver, uri)
                publishAnalysis(
                    generation = generation,
                    analysis = FirmwareLabAnalysisState.Analyzing(metadata.displayName),
                )
                val report = analyzer.analyze(
                    displayName = metadata.displayName,
                    declaredSizeBytes = metadata.sizeBytes,
                ) {
                    contentResolver.openInputStream(uri)?.buffered()
                        ?: throw IOException("O provedor de documentos nao abriu o arquivo selecionado.")
                }
                publishAnalysis(
                    generation = generation,
                    analysis = FirmwareLabAnalysisState.Ready(report),
                )
                if (analysisGeneration.get() == generation) {
                    diagnosticsRecorder.record(
                        severity = DiagnosticSeverity.INFO,
                        component = "firmware",
                        action = "analysis.completed",
                        message = "Análise estrutural de firmware concluída.",
                        metadata = buildMap {
                            put("format", report.detectedFormat.name)
                            report.declaredSizeBytes?.let { size -> put("declaredSizeBytes", size.toString()) }
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                diagnosticsRecorder.record(
                    severity = DiagnosticSeverity.DEBUG,
                    component = "firmware",
                    action = "analysis.cancelled",
                    message = "Análise anterior cancelada por uma operação mais nova.",
                )
                throw cancelled
            } catch (error: SecurityException) {
                publishAnalysisFailure(
                    generation = generation,
                    message = "Acesso ao arquivo foi negado pelo Android. Selecione o arquivo novamente.",
                    errorType = error.javaClass.simpleName,
                )
            } catch (error: IOException) {
                publishAnalysisFailure(
                    generation = generation,
                    message = error.message ?: "Falha de entrada/saida ao analisar o arquivo.",
                    errorType = error.javaClass.simpleName,
                )
            } catch (error: IllegalArgumentException) {
                publishAnalysisFailure(
                    generation = generation,
                    message = error.message ?: "O arquivo possui estrutura invalida ou nao suportada.",
                    errorType = error.javaClass.simpleName,
                )
            } catch (error: Exception) {
                publishAnalysisFailure(
                    generation = generation,
                    message = "Falha inesperada ao analisar o arquivo: ${error.message ?: error.javaClass.simpleName}.",
                    errorType = error.javaClass.simpleName,
                )
            }
        }
    }

    /** Writes the latest generated text report to a user-selected destination. */
    fun exportReport(contentResolver: ContentResolver, uri: Uri) {
        val report = synchronized(stateLock) {
            (mutableState.value.analysis as? FirmwareLabAnalysisState.Ready)?.report
        } ?: return
        exportJob?.cancel()
        exportJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val output = contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("O destino selecionado nao pode ser aberto para escrita.")
                output.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(report.toPlainText()) }
                publishExportMessage(report, "Relatorio exportado com sucesso.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                publishExportMessage(report, "O Android negou acesso ao destino do relatorio.")
            } catch (error: IOException) {
                publishExportMessage(report, error.message ?: "Falha ao exportar o relatorio.")
            }
        }
    }

    /** Expands the analyzed Android Sparse source into an explicitly selected destination document. */
    fun expandSparse(contentResolver: ContentResolver, destinationUri: Uri) {
        val request = synchronized(stateLock) {
            val report = (mutableState.value.analysis as? FirmwareLabAnalysisState.Ready)?.report
                ?: return
            val sourceUri = selectedSourceUri ?: return
            if (report.detectedFormat != FirmwareFormat.ANDROID_SPARSE) return
            ExpansionRequest(sourceUri = sourceUri, destinationUri = destinationUri, report = report)
        }

        val generation = expansionGeneration.incrementAndGet()
        expansionJob?.cancel()

        if (request.sourceUri == request.destinationUri) {
            publishExpansion(
                report = request.report,
                sourceUri = request.sourceUri,
                generation = generation,
                expansion = FirmwareLabExpansionState.Error(
                    message = "O destino nao pode ser o mesmo documento da imagem sparse de origem.",
                    destinationMayContainPartialData = false,
                ),
            )
            diagnosticsRecorder.record(
                severity = DiagnosticSeverity.WARNING,
                component = "firmware",
                action = "sparse.expand.blocked",
                message = "Expansão Android Sparse bloqueada porque origem e destino são o mesmo documento.",
            )
            return
        }

        synchronized(stateLock) {
            val currentReport = (mutableState.value.analysis as? FirmwareLabAnalysisState.Ready)?.report
            if (currentReport != request.report || selectedSourceUri != request.sourceUri) return
            mutableState.value = mutableState.value.copy(
                expansion = FirmwareLabExpansionState.Expanding(request.report.displayName),
            )
        }
        diagnosticsRecorder.record(
            severity = DiagnosticSeverity.INFO,
            component = "firmware",
            action = "sparse.expand.started",
            message = "Expansão Android Sparse iniciada para destino selecionado explicitamente.",
        )

        expansionJob = viewModelScope.launch(Dispatchers.IO) {
            var destinationOpened = false
            try {
                val input = contentResolver.openInputStream(request.sourceUri)?.buffered()
                    ?: throw IOException("O provedor de documentos nao abriu a imagem sparse de origem.")
                val expansionReport = input.use { source ->
                    val rawOutput = contentResolver.openOutputStream(request.destinationUri, "w")
                        ?: throw IOException("O destino selecionado nao pode ser aberto para escrita.")
                    destinationOpened = true
                    rawOutput.buffered().use { destination ->
                        sparseExpander.expand(source, destination)
                    }
                }
                publishExpansion(
                    report = request.report,
                    sourceUri = request.sourceUri,
                    generation = generation,
                    expansion = FirmwareLabExpansionState.Ready(expansionReport),
                )
                if (expansionGeneration.get() == generation) {
                    diagnosticsRecorder.record(
                        severity = DiagnosticSeverity.INFO,
                        component = "firmware",
                        action = "sparse.expand.completed",
                        message = "Expansão Android Sparse concluída.",
                        metadata = mapOf(
                            "expandedSizeBytes" to expansionReport.expandedSizeBytes.toString(),
                            "validatedCrc32Chunks" to expansionReport.validatedCrc32Chunks.toString(),
                            "headerChecksumValidated" to expansionReport.headerChecksumValidated.toString(),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                diagnosticsRecorder.record(
                    severity = DiagnosticSeverity.DEBUG,
                    component = "firmware",
                    action = "sparse.expand.cancelled",
                    message = "Expansão Android Sparse cancelada por uma operação mais nova.",
                    metadata = mapOf("destinationOpened" to destinationOpened.toString()),
                )
                throw cancelled
            } catch (error: SecurityException) {
                publishExpansionFailure(
                    request = request,
                    generation = generation,
                    message = "O Android negou acesso à origem ou ao destino selecionado.",
                    destinationMayContainPartialData = destinationOpened,
                    errorType = error.javaClass.simpleName,
                )
            } catch (error: IOException) {
                publishExpansionFailure(
                    request = request,
                    generation = generation,
                    message = error.message ?: "Falha de entrada/saida durante a expansao Android Sparse.",
                    destinationMayContainPartialData = destinationOpened,
                    errorType = error.javaClass.simpleName,
                )
            } catch (error: IllegalArgumentException) {
                publishExpansionFailure(
                    request = request,
                    generation = generation,
                    message = error.message ?: "A imagem Android Sparse e invalida ou excede os limites configurados.",
                    destinationMayContainPartialData = destinationOpened,
                    errorType = error.javaClass.simpleName,
                )
            } catch (error: Exception) {
                publishExpansionFailure(
                    request = request,
                    generation = generation,
                    message = "Falha inesperada na expansao: ${error.message ?: error.javaClass.simpleName}.",
                    destinationMayContainPartialData = destinationOpened,
                    errorType = error.javaClass.simpleName,
                )
            }
        }
    }

    private fun publishAnalysis(
        generation: Long,
        analysis: FirmwareLabAnalysisState,
    ) {
        if (analysisGeneration.get() != generation) return
        synchronized(stateLock) {
            if (analysisGeneration.get() != generation) return
            mutableState.value = mutableState.value.copy(
                analysis = analysis,
                exportMessage = null,
                expansion = FirmwareLabExpansionState.Idle,
            )
        }
    }

    private fun publishAnalysisFailure(
        generation: Long,
        message: String,
        errorType: String,
    ) {
        publishAnalysis(generation, FirmwareLabAnalysisState.Error(message))
        if (analysisGeneration.get() == generation) {
            diagnosticsRecorder.record(
                severity = DiagnosticSeverity.ERROR,
                component = "firmware",
                action = "analysis.failed",
                message = "Análise estrutural de firmware falhou.",
                metadata = mapOf("errorType" to errorType),
            )
        }
    }

    private fun publishExportMessage(report: FirmwareLabReport, message: String) {
        synchronized(stateLock) {
            val current = mutableState.value
            val currentReport = (current.analysis as? FirmwareLabAnalysisState.Ready)?.report
            if (currentReport != report) return
            mutableState.value = current.copy(exportMessage = message)
        }
    }

    private fun publishExpansion(
        report: FirmwareLabReport,
        sourceUri: Uri,
        generation: Long,
        expansion: FirmwareLabExpansionState,
    ) {
        if (expansionGeneration.get() != generation) return
        synchronized(stateLock) {
            if (expansionGeneration.get() != generation) return
            val currentReport = (mutableState.value.analysis as? FirmwareLabAnalysisState.Ready)?.report
            if (currentReport != report || selectedSourceUri != sourceUri) return
            mutableState.value = mutableState.value.copy(expansion = expansion)
        }
    }

    private fun publishExpansionFailure(
        request: ExpansionRequest,
        generation: Long,
        message: String,
        destinationMayContainPartialData: Boolean,
        errorType: String,
    ) {
        publishExpansion(
            report = request.report,
            sourceUri = request.sourceUri,
            generation = generation,
            expansion = FirmwareLabExpansionState.Error(
                message = message,
                destinationMayContainPartialData = destinationMayContainPartialData,
            ),
        )
        if (expansionGeneration.get() == generation) {
            diagnosticsRecorder.record(
                severity = DiagnosticSeverity.ERROR,
                component = "firmware",
                action = "sparse.expand.failed",
                message = "Expansão Android Sparse falhou.",
                metadata = mapOf(
                    "destinationMayContainPartialData" to destinationMayContainPartialData.toString(),
                    "errorType" to errorType,
                ),
            )
        }
    }

    private fun readDocumentMetadata(
        contentResolver: ContentResolver,
        uri: Uri,
    ): DocumentMetadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex).takeIf { size -> size >= 0L }?.let { size -> sizeBytes = size }
                }
            }
        }
        val safeDisplayName = displayName
            ?.filter { character -> character >= ' ' && character != '\u007F' }
            ?.take(MAXIMUM_DISPLAY_NAME_LENGTH)
            ?.takeIf(String::isNotBlank)
            ?: "firmware.bin"
        return DocumentMetadata(
            displayName = safeDisplayName,
            sizeBytes = sizeBytes,
        )
    }

    override fun onCleared() {
        analysisGeneration.incrementAndGet()
        expansionGeneration.incrementAndGet()
        analysisJob?.cancel()
        exportJob?.cancel()
        expansionJob?.cancel()
        super.onCleared()
    }

    private data class DocumentMetadata(
        val displayName: String,
        val sizeBytes: Long?,
    )

    private data class ExpansionRequest(
        val sourceUri: Uri,
        val destinationUri: Uri,
        val report: FirmwareLabReport,
    )

    private companion object {
        const val MAXIMUM_DISPLAY_NAME_LENGTH = 255
    }
}
