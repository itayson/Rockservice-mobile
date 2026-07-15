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

/** Renders the read-only firmware laboratory controls and bounded report preview. */
@Composable
internal fun FirmwareLabPanel(
    state: FirmwareLabScreenState,
    onSelectFile: () -> Unit,
    onExportReport: (String) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Laboratorio de Firmware",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "Selecione uma imagem para identificar o formato, calcular SHA-256 e executar os parsers estruturais disponiveis. Nenhum payload e gravado ou modificado.",
        )
        Button(onClick = onSelectFile) {
            Text("Selecionar firmware")
        }

        when (val analysis = state.analysis) {
            FirmwareLabAnalysisState.Idle -> {
                Text("Nenhum arquivo analisado nesta sessao.")
            }

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
                        Text(
                            "Falha na analise",
                            style = MaterialTheme.typography.titleMedium,
                        )
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
                Button(onClick = { onExportReport(report.suggestedReportFileName) }) {
                    Text("Exportar relatorio tecnico")
                }
                state.exportMessage?.let { message ->
                    Text(message)
                }
            }
        }
    }
}

private const val MAXIMUM_PREVIEW_LINES_PER_SECTION = 20
