package org.rockservice.mobile

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

internal data class FirmwareLabScreenState(
    val analysis: FirmwareLabAnalysisState = FirmwareLabAnalysisState.Idle,
    val exportMessage: String? = null,
)

/** Retains firmware-laboratory state while platform document access remains owned by the Activity. */
internal class FirmwareLabViewModel : ViewModel() {
    private val analyzer = FirmwareLabAnalyzer()
    private val mutableState = MutableStateFlow(FirmwareLabScreenState())
    private var analysisJob: Job? = null
    private var exportJob: Job? = null

    val state = mutableState.asStateFlow()

    /** Cancels a previous analysis and analyzes the selected read-only document on an IO dispatcher. */
    fun analyze(contentResolver: ContentResolver, uri: Uri) {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val metadata = readDocumentMetadata(contentResolver, uri)
                mutableState.value = FirmwareLabScreenState(
                    analysis = FirmwareLabAnalysisState.Analyzing(metadata.displayName),
                )
                val report = analyzer.analyze(
                    displayName = metadata.displayName,
                    declaredSizeBytes = metadata.sizeBytes,
                ) {
                    contentResolver.openInputStream(uri)?.buffered()
                        ?: throw IOException("O provedor de documentos nao abriu o arquivo selecionado.")
                }
                mutableState.value = FirmwareLabScreenState(
                    analysis = FirmwareLabAnalysisState.Ready(report),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                publishError("Acesso ao arquivo foi negado pelo Android. Selecione o arquivo novamente.")
            } catch (error: IOException) {
                publishError(error.message ?: "Falha de entrada/saida ao analisar o arquivo.")
            } catch (error: IllegalArgumentException) {
                publishError(error.message ?: "O arquivo possui estrutura invalida ou nao suportada.")
            } catch (error: Exception) {
                publishError("Falha inesperada ao analisar o arquivo: ${error.message ?: error.javaClass.simpleName}.")
            }
        }
    }

    /** Writes the latest generated text report to a user-selected destination. */
    fun exportReport(contentResolver: ContentResolver, uri: Uri) {
        val report = (mutableState.value.analysis as? FirmwareLabAnalysisState.Ready)?.report ?: return
        exportJob?.cancel()
        exportJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val output = contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("O destino selecionado nao pode ser aberto para escrita.")
                output.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(report.toPlainText())
                }
                mutableState.value = mutableState.value.copy(
                    exportMessage = "Relatorio exportado com sucesso.",
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

    private fun publishError(message: String) {
        mutableState.value = FirmwareLabScreenState(
            analysis = FirmwareLabAnalysisState.Error(message),
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
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex).takeIf { size -> size >= 0L }?.let { size ->
                        sizeBytes = size
                    }
                }
            }
        }
        return DocumentMetadata(
            displayName = displayName?.takeIf(String::isNotBlank) ?: "firmware.bin",
            sizeBytes = sizeBytes,
        )
    }

    override fun onCleared() {
        analysisJob?.cancel()
        exportJob?.cancel()
        super.onCleared()
    }

    private data class DocumentMetadata(
        val displayName: String,
        val sizeBytes: Long?,
    )
}
