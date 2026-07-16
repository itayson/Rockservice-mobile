package org.rockservice.mobile

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.rockservice.core.common.diagnostics.DiagnosticEventRecorder
import org.rockservice.core.common.diagnostics.DiagnosticSeverity
import org.rockservice.feature.firmware.AndroidBootImageParser
import org.rockservice.feature.firmware.AndroidBootSectionExtractionReport
import org.rockservice.feature.firmware.AndroidBootSectionExtractor
import org.rockservice.feature.firmware.AndroidBootSectionType

internal sealed interface BootImageMetadataState {
    data object Loading : BootImageMetadataState

    data class Ready(
        val extractableSections: List<AndroidBootSectionType>,
    ) : BootImageMetadataState

    data class Error(
        val message: String,
    ) : BootImageMetadataState
}

internal sealed interface BootSectionExtractionState {
    data object Idle : BootSectionExtractionState

    data class Extracting(
        val sectionType: AndroidBootSectionType,
    ) : BootSectionExtractionState

    data class Ready(
        val report: AndroidBootSectionExtractionReport,
    ) : BootSectionExtractionState

    data class Error(
        val sectionType: AndroidBootSectionType?,
        val message: String,
        val destinationMayContainPartialData: Boolean,
    ) : BootSectionExtractionState
}

internal data class BootImageExtractionScreenState(
    val metadata: BootImageMetadataState = BootImageMetadataState.Loading,
    val extraction: BootSectionExtractionState = BootSectionExtractionState.Idle,
)

/** Owns one explicitly selected Boot Image source while exporting individual validated payloads. */
internal class BootImageExtractionViewModel(
    private val sourceUri: Uri,
    private val expectedSourceSha256: String,
    private val contentResolver: ContentResolver,
    private val diagnosticsRecorder: DiagnosticEventRecorder = AppDiagnostics.recorder,
) : ViewModel() {
    private val parser = AndroidBootImageParser()
    private val extractor = AndroidBootSectionExtractor()
    private val mutableState = MutableStateFlow(BootImageExtractionScreenState())
    private val operationGeneration = AtomicLong(0L)
    private val extractionMutex = Mutex()
    private var metadataJob: Job? = null
    private var extractionJob: Job? = null

    val state = mutableState.asStateFlow()

    init {
        loadMetadata()
    }

    /** Extracts one listed non-header payload into an explicitly selected destination document. */
    fun extract(sectionType: AndroidBootSectionType, destinationUri: Uri) {
        val ready = mutableState.value.metadata as? BootImageMetadataState.Ready ?: return
        if (sectionType == AndroidBootSectionType.HEADER || sectionType !in ready.extractableSections) return

        val generation = operationGeneration.incrementAndGet()
        extractionJob?.cancel()

        if (sourceUri == destinationUri) {
            publishExtraction(
                generation = generation,
                state = BootSectionExtractionState.Error(
                    sectionType = sectionType,
                    message = "O destino nao pode ser o mesmo documento da Boot Image de origem.",
                    destinationMayContainPartialData = false,
                ),
            )
            diagnosticsRecorder.record(
                severity = DiagnosticSeverity.WARNING,
                component = "firmware",
                action = "boot.extract.blocked",
                message = "Extração Boot Image bloqueada porque origem e destino são o mesmo documento.",
                metadata = mapOf("section" to sectionType.name),
            )
            return
        }

        publishExtraction(generation, BootSectionExtractionState.Extracting(sectionType))
        diagnosticsRecorder.record(
            severity = DiagnosticSeverity.INFO,
            component = "firmware",
            action = "boot.extract.started",
            message = "Extração controlada de seção Boot Image iniciada.",
            metadata = mapOf("section" to sectionType.name),
        )

        extractionJob = viewModelScope.launch(Dispatchers.IO) {
            extractionMutex.withLock {
                coroutineContext.ensureActive()
                if (operationGeneration.get() != generation) return@withLock

                // CreateDocument may already have created or truncated the destination before this method runs.
                // Until a successful completion proves otherwise, treat it as potentially partial/invalid.
                var destinationMayContainPartialData = true
                try {
                    val metadata = contentResolver.openInputStream(sourceUri)?.buffered()?.use(parser::parse)
                        ?: throw IOException("O provedor de documentos nao abriu a Boot Image de origem.")
                    coroutineContext.ensureActive()

                    val matchingSections = metadata.sections.filter { section ->
                        section.type == sectionType && section.sizeBytes > 0L
                    }
                    require(matchingSections.size == 1) {
                        "A seção $sectionType nao esta mais disponivel de forma inequívoca na Boot Image atual."
                    }

                    val source = contentResolver.openInputStream(sourceUri)?.buffered()
                        ?: throw IOException("O provedor de documentos nao reabriu a Boot Image de origem.")
                    val extractionReport = source.use { input ->
                        val output = contentResolver.openOutputStream(destinationUri, "w")
                            ?: throw IOException("O destino selecionado nao pode ser aberto para escrita.")
                        output.buffered().use { destination ->
                            extractor.extract(
                                source = input,
                                metadata = metadata,
                                expectedSourceSha256 = expectedSourceSha256,
                                sectionType = sectionType,
                                destination = destination,
                                checkpoint = { coroutineContext.ensureActive() },
                            )
                        }
                    }
                    coroutineContext.ensureActive()

                    destinationMayContainPartialData = false
                    publishExtraction(
                        generation = generation,
                        state = BootSectionExtractionState.Ready(extractionReport),
                    )
                    if (operationGeneration.get() == generation) {
                        diagnosticsRecorder.record(
                            severity = DiagnosticSeverity.INFO,
                            component = "firmware",
                            action = "boot.extract.completed",
                            message = "Extração controlada de seção Boot Image concluída.",
                            metadata = mapOf(
                                "section" to sectionType.name,
                                "extractedBytes" to extractionReport.extractedBytes.toString(),
                            ),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    diagnosticsRecorder.record(
                        severity = DiagnosticSeverity.DEBUG,
                        component = "firmware",
                        action = "boot.extract.cancelled",
                        message = "Extração Boot Image anterior cancelada por uma operação mais nova.",
                        metadata = mapOf(
                            "section" to sectionType.name,
                            "destinationMayContainPartialData" to destinationMayContainPartialData.toString(),
                        ),
                    )
                    throw cancelled
                } catch (error: SecurityException) {
                    publishFailure(
                        generation,
                        sectionType,
                        destinationMayContainPartialData,
                        error.javaClass.simpleName,
                        "O Android negou acesso à origem ou ao destino selecionado.",
                    )
                } catch (error: IOException) {
                    publishFailure(
                        generation,
                        sectionType,
                        destinationMayContainPartialData,
                        error.javaClass.simpleName,
                        error.message ?: "Falha de entrada/saida durante a extração Boot Image.",
                    )
                } catch (error: IllegalArgumentException) {
                    publishFailure(
                        generation,
                        sectionType,
                        destinationMayContainPartialData,
                        error.javaClass.simpleName,
                        error.message ?: "A Boot Image mudou, esta truncada ou possui layout invalido.",
                    )
                } catch (error: Exception) {
                    publishFailure(
                        generation,
                        sectionType,
                        destinationMayContainPartialData,
                        error.javaClass.simpleName,
                        "Falha inesperada na extração: ${error.message ?: error.javaClass.simpleName}.",
                    )
                }
            }
        }
    }

    private fun loadMetadata() {
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val metadata = contentResolver.openInputStream(sourceUri)?.buffered()?.use(parser::parse)
                    ?: throw IOException("O provedor de documentos nao abriu a Boot Image de origem.")
                val sections = metadata.sections
                    .asSequence()
                    .filter { section -> section.type != AndroidBootSectionType.HEADER && section.sizeBytes > 0L }
                    .map { section -> section.type }
                    .distinct()
                    .toList()
                require(sections.isNotEmpty()) { "A Boot Image nao contém payloads extraíveis neste gate." }
                mutableState.value = BootImageExtractionScreenState(
                    metadata = BootImageMetadataState.Ready(sections),
                    extraction = BootSectionExtractionState.Idle,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                publishMetadataError("O Android negou acesso à Boot Image selecionada.")
            } catch (error: IOException) {
                publishMetadataError(error.message ?: "Falha de entrada/saida ao revalidar a Boot Image.")
            } catch (error: IllegalArgumentException) {
                publishMetadataError(error.message ?: "A Boot Image possui layout invalido ou não suportado.")
            } catch (error: Exception) {
                publishMetadataError(
                    "Falha inesperada ao revalidar a Boot Image: ${error.message ?: error.javaClass.simpleName}.",
                )
            }
        }
    }

    private fun publishMetadataError(message: String) {
        mutableState.value = BootImageExtractionScreenState(
            metadata = BootImageMetadataState.Error(message),
            extraction = BootSectionExtractionState.Idle,
        )
        diagnosticsRecorder.record(
            severity = DiagnosticSeverity.ERROR,
            component = "firmware",
            action = "boot.metadata.failed",
            message = "Revalidação estrutural de Boot Image falhou.",
        )
    }

    private fun publishFailure(
        generation: Long,
        sectionType: AndroidBootSectionType,
        destinationMayContainPartialData: Boolean,
        errorType: String,
        message: String,
    ) {
        publishExtraction(
            generation = generation,
            state = BootSectionExtractionState.Error(
                sectionType = sectionType,
                message = message,
                destinationMayContainPartialData = destinationMayContainPartialData,
            ),
        )
        if (operationGeneration.get() == generation) {
            diagnosticsRecorder.record(
                severity = DiagnosticSeverity.ERROR,
                component = "firmware",
                action = "boot.extract.failed",
                message = "Extração controlada de seção Boot Image falhou.",
                metadata = mapOf(
                    "section" to sectionType.name,
                    "destinationMayContainPartialData" to destinationMayContainPartialData.toString(),
                    "errorType" to errorType,
                ),
            )
        }
    }

    private fun publishExtraction(generation: Long, state: BootSectionExtractionState) {
        if (operationGeneration.get() != generation) return
        mutableState.value = mutableState.value.copy(extraction = state)
    }

    override fun onCleared() {
        operationGeneration.incrementAndGet()
        metadataJob?.cancel()
        extractionJob?.cancel()
        super.onCleared()
    }
}

internal class BootImageExtractionViewModelFactory(
    private val sourceUri: Uri,
    private val expectedSourceSha256: String,
    private val contentResolver: ContentResolver,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(BootImageExtractionViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return BootImageExtractionViewModel(
            sourceUri = sourceUri,
            expectedSourceSha256 = expectedSourceSha256,
            contentResolver = contentResolver,
        ) as T
    }
}
