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
internal class FirmwareLabViewModel : ViewModel() {
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                publishError(
                    generation,
                    "Acesso ao arquivo foi negado pelo Android. Selecione o arquivo novamente.",
                )
            } catch (error: IOException) {
                publishError(generation, error.message ?: "Falha de entrada/saida ao analisar o arquivo.")
            } catch (error: IllegalArgumentException) {
                publishError(generation, error.message ?: "O arquivo possui estrutura invalida ou nao suportada.")
            } catch (error: Exception) {
                publishError(
                    generation,
                    "Falha inesperada ao analisar o arquivo: ${error.message ?: error.javaClass.simpleName}.",
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
                output.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(report.toPlainText())
                }
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

    /** Expands the analyzed sparse source into an explicitly selected destination document. */
    fun expandSparse(contentResolver: ContentResolver, destinationUri: Uri) {
        val request = synchronized(stateLock) {
            val report = (mutableState.value.analysis as? FirmwareLabAnalysisState.Ready)?.report
                ?: return
            val sourceUri = selectedSourceUri ?: return
            if (report.detectedFormat != FirmwareFormat.ANDROID_SPARSE) return
            ExpansionRequest(sourceUri = sourceUri, destinationUri = destinationUri, report = report)
        }

        if (request.sourceUri == request.destinationUri) {
            publishExpansionError(
                report = request.report,
                generation = expansionGeneration.get(),
                message = "O destino nao pode ser o mesmo documento da imagem sparse de origem.",
                destinationMayContainPartialData = false,
            )
            return
        }

        val generation = expansionGeneration.incrementAndGet()
        expansionJob?.cancel()
        synchronized(stateLock) {
            val currentReport = (mutableState.value.analysis as? FirmwareLabAnalysisState.Ready)?.report
            if (currentReport != request.report || selectedSourceUri != request.sourceUri) return
            mutableState.value = mutableState.value.copy(
                expansion = FirmwareLabExpansionState.Expanding(request.report.displayName),
            )
        }

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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                publishExpansionError(
                    report = request.report,
                    sourceUri = request.sourceUri,
                    generation = generation,
                    message = "O Android negou acesso à origem ou ao destino selecionado.",
                    destinationMayContainPartialData = destinationOpened,
                )
            } catch (error: IOException) {
                publishExpansionError(
                    report = request.report,
                    sourceUri = request.sourceUri,
                    generation = generation,
                    message = error.message ?: "Falha de entrada/saida durante a expansao Android Sparse.",
                    destinationMayContainPartialData = destinationOpened,
                )
            } catch (error: IllegalArgumentException) {
                publishExpansionError(
                    report = request.report,
                    sourceUri = request.sourceUri,
                    generation = generation,
                    message = error.message ?: "A imagem Android Sparse e invalida ou excede os limites configurados.",
                    destinationMayContainPartialData = destinationOpened,
                )
            } catch (error: Exception) {
                publishExpansionError(
                    report = request.report,
                    sourceUri = request.sourceUri,
                    generation = generation,
                    message = "Falha inesperada na expansao: ${error.message ?: error.javaClass.simpleName}.",
                    destinationMayContainPartialData = destinationOpened,
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

    private fun publishError(generation: Long, message: String) {
        publishAnalysis(
            generation = generation,
            analysis = FirmwareLabAnalysisState.Error(message),
        )
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

    private fun publishExpansionError(
        report: FirmwareLabReport,
        generation: Long,
        message: String,
        destinationMayContainPartialData: Boolean,
        sourceUri: Uri? = selectedSourceUri,
    ) {
        val currentSource = sourceUri ?: return
        publishExpansion(
            report = report,
            sourceUri = currentSource,
            generation = generation,
            expansion = FirmwareLabExpansionState.Error(
                message = message,
                destinationMayContainPartialData = destinationMayContainPartialData,
            ),
        )
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
