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
import org.rockservice.feature.firmware.AndroidSuperLogicalPartitionExportReport
import org.rockservice.feature.firmware.AndroidSuperLogicalPartitionExporter
import org.rockservice.feature.firmware.AndroidSuperLogicalPartitionMapper
import org.rockservice.feature.firmware.AndroidSuperLogicalPartitionPlan
import org.rockservice.feature.firmware.AndroidSuperMetadata
import org.rockservice.feature.firmware.AndroidSuperMetadataCopy
import org.rockservice.feature.firmware.AndroidSuperMetadataParser
import org.rockservice.feature.firmware.FirmwareAnalyzer
import org.rockservice.feature.firmware.FirmwareFormat

internal data class SuperLogicalPartitionOption(
    val name: String,
    val sizeBytes: Long,
)

internal sealed interface SuperMetadataState {
    data object Loading : SuperMetadataState

    data class Ready(
        val partitions: List<SuperLogicalPartitionOption>,
        val metadataCopy: AndroidSuperMetadataCopy,
    ) : SuperMetadataState

    data class Error(
        val message: String,
    ) : SuperMetadataState
}

internal sealed interface SuperPartitionExportState {
    data object Idle : SuperPartitionExportState

    data class Exporting(
        val partitionName: String,
        val writtenBytes: Long,
        val totalBytes: Long,
    ) : SuperPartitionExportState

    data class Ready(
        val report: AndroidSuperLogicalPartitionExportReport,
    ) : SuperPartitionExportState

    data class Error(
        val partitionName: String?,
        val message: String,
        val destinationMayContainPartialData: Boolean,
    ) : SuperPartitionExportState
}

internal data class SuperImageExtractionScreenState(
    val metadata: SuperMetadataState = SuperMetadataState.Loading,
    val export: SuperPartitionExportState = SuperPartitionExportState.Idle,
)

/** Revalidates one selected raw super image and exports only explicitly selected logical partitions. */
internal class SuperImageExtractionViewModel(
    private val sourceUri: Uri,
    private val expectedSourceSha256: String,
    private val contentResolver: ContentResolver,
    private val diagnosticsRecorder: DiagnosticEventRecorder = AppDiagnostics.recorder,
) : ViewModel() {
    private val firmwareAnalyzer = FirmwareAnalyzer()
    private val metadataParser = AndroidSuperMetadataParser()
    private val mapper = AndroidSuperLogicalPartitionMapper()
    private val exporter = AndroidSuperLogicalPartitionExporter()
    private val mutableState = MutableStateFlow(SuperImageExtractionScreenState())
    private val operationGeneration = AtomicLong(0L)
    private val exportMutex = Mutex()
    private var metadataJob: Job? = null
    private var exportJob: Job? = null

    val state = mutableState.asStateFlow()

    init {
        loadMetadata()
    }

    /** Exports exactly one currently validated logical partition to a user-selected destination. */
    fun exportPartition(partitionName: String, destinationUri: Uri) {
        val ready = mutableState.value.metadata as? SuperMetadataState.Ready ?: return
        if (ready.partitions.none { option -> option.name == partitionName }) return

        val generation = operationGeneration.incrementAndGet()
        exportJob?.cancel()

        if (sourceUri == destinationUri) {
            publishExport(
                generation,
                SuperPartitionExportState.Error(
                    partitionName = partitionName,
                    message = "O destino nao pode ser o mesmo documento da super.img de origem.",
                    destinationMayContainPartialData = false,
                ),
            )
            diagnosticsRecorder.record(
                severity = DiagnosticSeverity.WARNING,
                component = "firmware",
                action = "super.partition.export.blocked",
                message = "Exportação de partição lógica bloqueada porque origem e destino são o mesmo documento.",
                metadata = mapOf("partition" to partitionName),
            )
            return
        }

        publishExport(
            generation,
            SuperPartitionExportState.Exporting(
                partitionName = partitionName,
                writtenBytes = 0L,
                totalBytes = ready.partitions.single { option -> option.name == partitionName }.sizeBytes,
            ),
        )

        exportJob = viewModelScope.launch(Dispatchers.IO) {
            exportMutex.withLock {
                coroutineContext.ensureActive()
                if (operationGeneration.get() != generation) return@withLock

                var destinationMayContainPartialData = true
                try {
                    val initialAnalysis = analyzeSource()
                    require(initialAnalysis.format == FirmwareFormat.ANDROID_SUPER_RAW) {
                        "A origem nao e mais uma super.img raw reconhecida."
                    }
                    require(initialAnalysis.sha256.equals(expectedSourceSha256, ignoreCase = true)) {
                        "A super.img de origem mudou desde a analise inicial. Selecione e analise o arquivo novamente."
                    }
                    coroutineContext.ensureActive()

                    val metadata = parseMetadataWithFallback()
                    val plan = mapper.mapNamed(metadata, partitionName)
                    require(plan.extents.all { extent ->
                        extent !is org.rockservice.feature.firmware.AndroidSuperLogicalExtentPlan.Linear ||
                            extent.blockDeviceIndex == 0
                    }) {
                        "A partição $partitionName depende de block devices externos e nao pode ser exportada a partir de um único documento neste gate."
                    }

                    val output = contentResolver.openOutputStream(destinationUri, "w")
                        ?: throw IOException("O destino selecionado nao pode ser aberto para escrita.")
                    val report = output.buffered().use { destination ->
                        exporter.export(
                            plan = plan,
                            openBlockDevice = { blockDeviceIndex ->
                                require(blockDeviceIndex == 0) {
                                    "Block device $blockDeviceIndex nao esta disponivel neste fluxo de documento único."
                                }
                                contentResolver.openInputStream(sourceUri)?.buffered()
                                    ?: throw IOException("O provedor de documentos nao reabriu a super.img de origem.")
                            },
                            destination = destination,
                            checkpoint = { coroutineContext.ensureActive() },
                            onProgress = { written, total ->
                                publishProgress(generation, partitionName, written, total)
                            },
                        )
                    }
                    coroutineContext.ensureActive()

                    val finalAnalysis = analyzeSource()
                    require(finalAnalysis.format == FirmwareFormat.ANDROID_SUPER_RAW &&
                        finalAnalysis.sha256.equals(expectedSourceSha256, ignoreCase = true)
                    ) {
                        "A super.img de origem mudou durante a exportação. O destino deve ser descartado."
                    }

                    destinationMayContainPartialData = false
                    publishExport(generation, SuperPartitionExportState.Ready(report))
                    if (operationGeneration.get() == generation) {
                        diagnosticsRecorder.record(
                            severity = DiagnosticSeverity.INFO,
                            component = "firmware",
                            action = "super.partition.export.completed",
                            message = "Exportação controlada de partição lógica concluída.",
                            metadata = mapOf(
                                "partition" to partitionName,
                                "bytesWritten" to report.bytesWritten.toString(),
                            ),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    diagnosticsRecorder.record(
                        severity = DiagnosticSeverity.DEBUG,
                        component = "firmware",
                        action = "super.partition.export.cancelled",
                        message = "Exportação de partição lógica cancelada por uma operação mais nova.",
                        metadata = mapOf("partition" to partitionName),
                    )
                    throw cancelled
                } catch (error: SecurityException) {
                    publishFailure(
                        generation,
                        partitionName,
                        destinationMayContainPartialData,
                        error.javaClass.simpleName,
                        "O Android negou acesso à origem ou ao destino selecionado.",
                    )
                } catch (error: IOException) {
                    publishFailure(
                        generation,
                        partitionName,
                        destinationMayContainPartialData,
                        error.javaClass.simpleName,
                        error.message ?: "Falha de entrada/saida durante a exportação da partição lógica.",
                    )
                } catch (error: IllegalArgumentException) {
                    publishFailure(
                        generation,
                        partitionName,
                        destinationMayContainPartialData,
                        error.javaClass.simpleName,
                        error.message ?: "A metadata liblp mudou ou possui estrutura inválida.",
                    )
                } catch (error: Exception) {
                    publishFailure(
                        generation,
                        partitionName,
                        destinationMayContainPartialData,
                        error.javaClass.simpleName,
                        "Falha inesperada na exportação: ${error.message ?: error.javaClass.simpleName}.",
                    )
                }
            }
        }
    }

    private fun loadMetadata() {
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val analysis = analyzeSource()
                require(analysis.format == FirmwareFormat.ANDROID_SUPER_RAW) {
                    "O documento selecionado nao e uma super.img raw reconhecida."
                }
                require(analysis.sha256.equals(expectedSourceSha256, ignoreCase = true)) {
                    "A super.img mudou desde a analise inicial. Volte ao Laboratorio de Firmware e analise novamente."
                }

                val metadata = parseMetadataWithFallback()
                val options = mapper.map(metadata)
                    .filter { plan ->
                        plan.extents.all { extent ->
                            extent !is org.rockservice.feature.firmware.AndroidSuperLogicalExtentPlan.Linear ||
                                extent.blockDeviceIndex == 0
                        }
                    }
                    .map { plan -> SuperLogicalPartitionOption(plan.name, plan.sizeBytes) }
                    .sortedBy { option -> option.name }
                require(options.isNotEmpty()) {
                    "A metadata liblp nao possui partições exportáveis a partir deste único documento."
                }

                mutableState.value = SuperImageExtractionScreenState(
                    metadata = SuperMetadataState.Ready(options, metadata.metadataCopy),
                    export = SuperPartitionExportState.Idle,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                publishMetadataError("O Android negou acesso à super.img selecionada.")
            } catch (error: IOException) {
                publishMetadataError(error.message ?: "Falha de entrada/saida ao revalidar a super.img.")
            } catch (error: IllegalArgumentException) {
                publishMetadataError(error.message ?: "A super.img possui metadata liblp inválida ou não suportada.")
            } catch (error: Exception) {
                publishMetadataError(
                    "Falha inesperada ao revalidar a super.img: ${error.message ?: error.javaClass.simpleName}.",
                )
            }
        }
    }

    private fun analyzeSource() = contentResolver.openInputStream(sourceUri)?.buffered()?.use(firmwareAnalyzer::analyze)
        ?: throw IOException("O provedor de documentos nao abriu a super.img de origem.")

    private fun parseMetadataWithFallback(): AndroidSuperMetadata {
        try {
            return openSource().use { source ->
                metadataParser.parse(source, metadataCopy = AndroidSuperMetadataCopy.PRIMARY)
            }
        } catch (primaryError: IllegalArgumentException) {
            try {
                return openSource().use { source ->
                    metadataParser.parse(source, metadataCopy = AndroidSuperMetadataCopy.BACKUP)
                }
            } catch (backupError: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Metadata liblp primária e backup do slot 0 são inválidas. Primária: " +
                        "${primaryError.message}; backup: ${backupError.message}",
                    backupError,
                )
            }
        }
    }

    private fun openSource() = contentResolver.openInputStream(sourceUri)?.buffered()
        ?: throw IOException("O provedor de documentos nao abriu a super.img de origem.")

    private fun publishProgress(generation: Long, partitionName: String, written: Long, total: Long) {
        if (operationGeneration.get() != generation) return
        val current = mutableState.value.export as? SuperPartitionExportState.Exporting ?: return
        if (current.partitionName != partitionName || written < current.writtenBytes) return
        mutableState.value = mutableState.value.copy(
            export = current.copy(writtenBytes = written, totalBytes = total),
        )
    }

    private fun publishMetadataError(message: String) {
        mutableState.value = SuperImageExtractionScreenState(
            metadata = SuperMetadataState.Error(message),
            export = SuperPartitionExportState.Idle,
        )
        diagnosticsRecorder.record(
            severity = DiagnosticSeverity.ERROR,
            component = "firmware",
            action = "super.metadata.failed",
            message = "Revalidação estrutural de super.img falhou.",
        )
    }

    private fun publishFailure(
        generation: Long,
        partitionName: String,
        destinationMayContainPartialData: Boolean,
        errorType: String,
        message: String,
    ) {
        publishExport(
            generation,
            SuperPartitionExportState.Error(
                partitionName = partitionName,
                message = message,
                destinationMayContainPartialData = destinationMayContainPartialData,
            ),
        )
        if (operationGeneration.get() == generation) {
            diagnosticsRecorder.record(
                severity = DiagnosticSeverity.ERROR,
                component = "firmware",
                action = "super.partition.export.failed",
                message = "Exportação controlada de partição lógica falhou.",
                metadata = mapOf(
                    "partition" to partitionName,
                    "destinationMayContainPartialData" to destinationMayContainPartialData.toString(),
                    "errorType" to errorType,
                ),
            )
        }
    }

    private fun publishExport(generation: Long, export: SuperPartitionExportState) {
        if (operationGeneration.get() != generation) return
        mutableState.value = mutableState.value.copy(export = export)
    }

    override fun onCleared() {
        operationGeneration.incrementAndGet()
        metadataJob?.cancel()
        exportJob?.cancel()
        super.onCleared()
    }
}

internal class SuperImageExtractionViewModelFactory(
    private val sourceUri: Uri,
    private val expectedSourceSha256: String,
    private val contentResolver: ContentResolver,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SuperImageExtractionViewModel::class.java)) {
            "Unsupported ViewModel class: ${modelClass.name}"
        }
        return SuperImageExtractionViewModel(
            sourceUri = sourceUri,
            expectedSourceSha256 = expectedSourceSha256,
            contentResolver = contentResolver,
        ) as T
    }
}
