package org.rockservice.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.rockservice.feature.firmware.FirmwareFormat

/** Renders firmware laboratory analysis plus explicitly selected safe transformation controls. */
@Composable
internal fun FirmwareLabPanel(
    state: FirmwareLabScreenState,
    onSelectFile: () -> Unit,
    onExportReport: (String) -> Unit,
    onExpandSparse: (String) -> Unit,
    onOpenBootExtraction: () -> Unit,
    onOpenSuperExtraction: () -> Unit,
) {
    val expansionRunning = state.expansion is FirmwareLabExpansionState.Expanding
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Laboratorio de Firmware",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "Selecione uma imagem para identificar o formato, calcular SHA-256 e executar os parsers estruturais disponiveis. Nenhuma transformacao e executada automaticamente.",
        )
        Button(
            onClick = onSelectFile,
            enabled = !expansionRunning && state.analysis !is FirmwareLabAnalysisState.Analyzing,
        ) {
            Text("Selecionar firmware")
        }

        when (val analysis = state.analysis) {
            FirmwareLabAnalysisState.Idle -> Text("Nenhum arquivo analisado nesta sessao.")

            is FirmwareLabAnalysisState.Analyzing -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Analisando ${analysis.displayName}...")
                        Text("O hash e os parsers podem exigir mais de uma leitura do arquivo.")
                    }
                }
            }

            is FirmwareLabAnalysisState.Error -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Falha na analise", style = MaterialTheme.typography.titleMedium)
                        Text(analysis.message)
                    }
                }
            }

            is FirmwareLabAnalysisState.Ready -> {
                val report = analysis.report
                report.sections.forEach { section ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(section.title, style = MaterialTheme.typography.titleMedium)
                            section.lines.take(MAXIMUM_PREVIEW_LINES_PER_SECTION).forEach { line ->
                                Text(line, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (section.lines.size > MAXIMUM_PREVIEW_LINES_PER_SECTION) {
                                Text(
                                    "${section.lines.size - MAXIMUM_PREVIEW_LINES_PER_SECTION} linhas adicionais estao no relatorio exportavel.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                Button(
                    onClick = { onExportReport(report.suggestedReportFileName) },
                    enabled = !expansionRunning,
                ) {
                    Text("Exportar relatorio tecnico")
                }
                state.exportMessage?.let { message -> Text(message) }

                if (report.detectedFormat == FirmwareFormat.ANDROID_SPARSE) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Expansao Android Sparse", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "A expansao grava somente no destino escolhido por voce. Em caso de falha durante a escrita, o destino pode ficar parcial e deve ser removido antes de uma nova tentativa.",
                            )
                            Button(
                                onClick = { onExpandSparse(suggestExpandedFileName(report.displayName)) },
                                enabled = !expansionRunning,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Selecionar destino e expandir para RAW")
                            }
                        }
                    }
                }

                if (report.detectedFormat == FirmwareFormat.ANDROID_BOOT_IMAGE) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Payloads Android Boot Image", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Abra a tela de extracao para revalidar a imagem e exportar somente uma secao de payload por vez. O header nao e oferecido.",
                            )
                            Button(
                                onClick = onOpenBootExtraction,
                                enabled = !expansionRunning,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Abrir extracao controlada de payloads")
                            }
                        }
                    }
                }

                if (report.detectedFormat == FirmwareFormat.ANDROID_SUPER_RAW) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Particoes logicas Android super", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "A metadata liblp sera revalidada antes da exportacao. Escolha uma particao e um destino por vez; a super.img de origem permanece somente leitura.",
                            )
                            Button(
                                onClick = onOpenSuperExtraction,
                                enabled = !expansionRunning,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Abrir exportacao de particoes logicas")
                            }
                        }
                    }
                }
            }
        }

        when (val expansion = state.expansion) {
            FirmwareLabExpansionState.Idle -> Unit
            is FirmwareLabExpansionState.Expanding -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("Expandindo ${expansion.displayName} em streaming...")
                        Text("Nao feche o aplicativo nem altere o documento de origem durante esta operacao.")
                    }
                }
            }

            is FirmwareLabExpansionState.Ready -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text("Expansao concluida", style = MaterialTheme.typography.titleMedium)
                        Text("Bytes RAW gravados: ${expansion.report.expandedSizeBytes}")
                        Text("SHA-256: ${expansion.report.outputSha256}")
                        Text("CRC32: 0x${expansion.report.outputCrc32.toString(16).uppercase()}")
                        Text("Chunks CRC32 validados: ${expansion.report.validatedCrc32Chunks}")
                        Text(
                            if (expansion.report.headerChecksumValidated) {
                                "Checksum do header sparse validado."
                            } else {
                                "A imagem sparse nao declarou checksum global no header."
                            },
                        )
                    }
                }
            }

            is FirmwareLabExpansionState.Error -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Falha na expansao", style = MaterialTheme.typography.titleMedium)
                        Text(expansion.message)
                        if (expansion.destinationMayContainPartialData) {
                            Text(
                                "O destino pode conter dados parciais. Exclua esse arquivo antes de tentar novamente.",
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun suggestExpandedFileName(displayName: String): String = when {
    displayName.endsWith(".sparse", ignoreCase = true) ->
        displayName.dropLast(".sparse".length) + ".img"
    displayName.endsWith(".img", ignoreCase = true) ->
        displayName.dropLast(".img".length) + "-expanded.img"
    else -> "$displayName-expanded.img"
}

private const val MAXIMUM_PREVIEW_LINES_PER_SECTION = 20
