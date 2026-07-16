package org.rockservice.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import org.rockservice.core.usb.AndroidUsbAttachmentMonitor
import org.rockservice.core.usb.adb.AdbDiagnosticSectionStatus
import org.rockservice.core.usb.adb.AdbDiagnosticService

class AdbProbeActivity : ComponentActivity() {
    private lateinit var usbAttachmentMonitor: AndroidUsbAttachmentMonitor
    private lateinit var probeViewModel: AdbProbeViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probeViewModel = ViewModelProvider(
            this,
            AdbProbeViewModelFactory(applicationContext),
        )[AdbProbeViewModel::class.java]
        usbAttachmentMonitor = AndroidUsbAttachmentMonitor(applicationContext) {
            probeViewModel.cancelActiveOperation()
            probeViewModel.refresh()
        }
        usbAttachmentMonitor.start()
        probeViewModel.start()

        setContent {
            MaterialTheme {
                val state by probeViewModel.state.collectAsState()
                val operationRunning = state.operation is AdbProbeOperationState.Running
                val diagnosticsRunning = state.diagnostics is AdbProbeDiagnosticsState.Running
                val busy = operationRunning || diagnosticsRunning
                Scaffold(
                    topBar = {
                        Surface(tonalElevation = 2.dp) {
                            Text(
                                "ADB por USB",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                    },
                ) { padding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("Conexao, autorizacao e diagnostico", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "A validacao executa somente CNXN/AUTH. Depois de conectado, a coleta read-only continua opcional e so abre os servicos exibidos quando voce tocar no botao de diagnostico.",
                                    )
                                    Button(onClick = { probeViewModel.refresh() }, enabled = !busy) {
                                        Text("Atualizar dispositivos")
                                    }
                                }
                            }
                        }

                        when (val scan = state.scan) {
                            AdbProbeScanState.Loading -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CircularProgressIndicator()
                                        Text("Inspecionando topologias USB em modo passivo...")
                                    }
                                }
                            }
                            is AdbProbeScanState.Error -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Falha na busca", style = MaterialTheme.typography.titleMedium)
                                        Text(scan.message)
                                    }
                                }
                            }
                            is AdbProbeScanState.Ready -> {
                                if (scan.candidates.isEmpty()) {
                                    item {
                                        Text("Nenhum dispositivo com uma interface ADB USB inequivoca foi detectado. Ative a depuracao USB no dispositivo alvo e reconecte o cabo.")
                                    }
                                } else {
                                    item { Text("Dispositivos detectados", style = MaterialTheme.typography.titleLarge) }
                                    items(
                                        items = scan.candidates,
                                        key = { candidate -> requireNotNull(candidate.descriptor.transportId) },
                                    ) { candidate ->
                                        Card(modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(candidate.displayName, style = MaterialTheme.typography.titleMedium)
                                                Text("VID:PID %04X:%04X".format(candidate.descriptor.vendorId, candidate.descriptor.productId))
                                                Text(
                                                    if (candidate.descriptor.hasPermission) "Permissao USB: concedida"
                                                    else "Permissao USB: sera solicitada ao iniciar",
                                                )
                                                Button(
                                                    onClick = { probeViewModel.probe(candidate) },
                                                    enabled = !busy,
                                                    modifier = Modifier.fillMaxWidth(),
                                                ) {
                                                    Text("Validar conexao ADB")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        when (val operation = state.operation) {
                            AdbProbeOperationState.Idle -> Unit
                            is AdbProbeOperationState.Running -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CircularProgressIndicator()
                                        Text("Validacao em andamento", style = MaterialTheme.typography.titleMedium)
                                        Text(operation.stage)
                                        if (operation.awaitingDeviceAuthorization) {
                                            Text("Confirme o dialogo de depuracao USB no dispositivo conectado. A identidade publica pertence somente a esta instalacao do RockService Mobile.")
                                        }
                                        Button(onClick = { probeViewModel.cancelActiveOperation() }) { Text("Cancelar") }
                                    }
                                }
                            }
                            is AdbProbeOperationState.Connected -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("ADB conectado e autorizado", style = MaterialTheme.typography.titleMedium)
                                        Text("Versao negociada: 0x${operation.protocolVersion.toString(16).uppercase()}")
                                        Text("Max data negociado: ${operation.maxDataBytes} bytes")
                                        Text("Banner remoto: ${safePreview(operation.banner, MAXIMUM_BANNER_PREVIEW_CHARS)}")
                                        Text("Nenhum servico remoto foi aberto automaticamente. A coleta abaixo e uma acao explicita e somente leitura.")
                                        Button(
                                            onClick = { probeViewModel.collectDiagnostics() },
                                            enabled = !diagnosticsRunning,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(
                                                if (state.diagnostics is AdbProbeDiagnosticsState.Ready) {
                                                    "Coletar novamente"
                                                } else {
                                                    "Coletar diagnosticos somente leitura"
                                                },
                                            )
                                        }
                                        Button(
                                            onClick = { probeViewModel.cancelActiveOperation() },
                                            enabled = !diagnosticsRunning,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text("Desconectar ADB")
                                        }
                                    }
                                }
                            }
                            is AdbProbeOperationState.Error -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Validacao ADB falhou", style = MaterialTheme.typography.titleMedium)
                                        Text(operation.message)
                                        if (operation.authorizationMayBePending) {
                                            Text("A autorizacao pode continuar pendente no dispositivo. Confirme o dialogo e tente novamente.")
                                        }
                                    }
                                }
                            }
                        }

                        when (val diagnostics = state.diagnostics) {
                            AdbProbeDiagnosticsState.Idle -> Unit
                            AdbProbeDiagnosticsState.Running -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CircularProgressIndicator()
                                        Text("Coletando diagnosticos ADB", style = MaterialTheme.typography.titleMedium)
                                        Text("Os servicos read-only sao executados sequencialmente com timeout e limites de memoria.")
                                        Button(onClick = { probeViewModel.cancelDiagnostics() }) {
                                            Text("Cancelar coleta")
                                        }
                                    }
                                }
                            }
                            is AdbProbeDiagnosticsState.Error -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Falha na coleta ADB", style = MaterialTheme.typography.titleMedium)
                                        Text(diagnostics.message)
                                        if (state.operation is AdbProbeOperationState.Connected) {
                                            Button(onClick = { probeViewModel.collectDiagnostics() }) {
                                                Text("Tentar novamente")
                                            }
                                        }
                                    }
                                }
                            }
                            is AdbProbeDiagnosticsState.Ready -> {
                                item {
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("Diagnosticos somente leitura", style = MaterialTheme.typography.titleMedium)
                                            Text("${diagnostics.snapshot.sections.size} secoes; ${diagnostics.snapshot.retainedByteCount} bytes retidos em memoria.")
                                            if (diagnostics.snapshot.budgetExhausted) {
                                                Text("O limite global de memoria foi atingido; secoes posteriores podem ter sido ignoradas.")
                                            }
                                            Text("Nenhuma saida abaixo e persistida ou enviada automaticamente pelo aplicativo.")
                                        }
                                    }
                                }
                                items(
                                    items = diagnostics.snapshot.sections,
                                    key = { section -> section.service.name },
                                ) { section ->
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(diagnosticServiceLabel(section.service), style = MaterialTheme.typography.titleMedium)
                                            Text("Status: ${diagnosticStatusLabel(section.status)}")
                                            Text("Bytes retidos: ${section.retainedByteCount}; observados: ${section.observedByteCount}")
                                            section.message?.let { message -> Text(message) }
                                            if (section.text.isNotEmpty()) {
                                                Text(
                                                    safePreview(section.text, MAXIMUM_SECTION_PREVIEW_CHARS),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                                if (section.text.length > MAXIMUM_SECTION_PREVIEW_CHARS) {
                                                    Text("Previa visual limitada a $MAXIMUM_SECTION_PREVIEW_CHARS caracteres.")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (::usbAttachmentMonitor.isInitialized) usbAttachmentMonitor.close()
        super.onDestroy()
    }
}

private fun diagnosticServiceLabel(service: AdbDiagnosticService): String = when (service) {
    AdbDiagnosticService.PROPERTIES -> "Propriedades Android (getprop)"
    AdbDiagnosticService.KERNEL -> "Kernel (uname -a)"
    AdbDiagnosticService.CPU_INFO -> "CPU (/proc/cpuinfo)"
    AdbDiagnosticService.MEMORY_INFO -> "Memoria (/proc/meminfo)"
    AdbDiagnosticService.STORAGE -> "Armazenamento (df -k)"
    AdbDiagnosticService.BATTERY -> "Bateria (dumpsys battery)"
}

private fun diagnosticStatusLabel(status: AdbDiagnosticSectionStatus): String = when (status) {
    AdbDiagnosticSectionStatus.SUCCESS -> "concluido"
    AdbDiagnosticSectionStatus.TRUNCATED -> "truncado pelo limite"
    AdbDiagnosticSectionStatus.UNSUPPORTED -> "nao suportado pelo dispositivo"
    AdbDiagnosticSectionStatus.TIMEOUT -> "timeout"
    AdbDiagnosticSectionStatus.FAILED -> "falhou"
    AdbDiagnosticSectionStatus.SKIPPED_BUDGET -> "ignorado por limite global"
}

private fun safePreview(value: String, maximumChars: Int): String {
    require(maximumChars > 0) { "Limite de previa deve ser positivo." }
    val sanitized = buildString(minOf(value.length, maximumChars + 1)) {
        value.forEach { character ->
            when {
                character == '\n' || character == '\r' || character == '\t' -> append(character)
                character.isISOControl() -> append('\uFFFD')
                else -> append(character)
            }
            if (length > maximumChars) return@buildString
        }
    }
    return if (sanitized.length > maximumChars) {
        sanitized.take(maximumChars - 1) + "…"
    } else {
        sanitized
    }
}

private const val MAXIMUM_BANNER_PREVIEW_CHARS = 500
private const val MAXIMUM_SECTION_PREVIEW_CHARS = 2_000
