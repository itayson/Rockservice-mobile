package org.rockservice.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import org.rockservice.core.common.diagnostics.DiagnosticSeverity
import org.rockservice.feature.firmware.FirmwareFormat

/** Entry screen for selecting, analyzing and explicitly transforming firmware image files. */
class FirmwareLabActivity : ComponentActivity() {
    private lateinit var firmwareLabViewModel: FirmwareLabViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firmwareLabViewModel = ViewModelProvider(this)[FirmwareLabViewModel::class.java]
        AppDiagnostics.recorder.record(
            severity = DiagnosticSeverity.INFO,
            component = "app",
            action = "launcher.open",
            message = "Tela inicial do laboratório de firmware aberta em modo totalmente offline.",
        )

        setContent {
            MaterialTheme {
                val state by firmwareLabViewModel.state.collectAsState()
                var selectedFirmwareUriString by rememberSaveable { mutableStateOf<String?>(null) }
                val resolver = applicationContext.contentResolver
                val selectDocument = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    uri?.let { selectedUri ->
                        selectedFirmwareUriString = selectedUri.toString()
                        firmwareLabViewModel.analyze(resolver, selectedUri)
                    }
                }
                val exportReport = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/plain"),
                ) { uri ->
                    uri?.let { destinationUri -> firmwareLabViewModel.exportReport(resolver, destinationUri) }
                }
                val expandSparse = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
                ) { uri ->
                    uri?.let { destinationUri -> firmwareLabViewModel.expandSparse(resolver, destinationUri) }
                }

                Scaffold(
                    topBar = {
                        Surface(tonalElevation = 2.dp) {
                            Text(
                                "RockService Mobile — Offline",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                    },
                ) { padding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item {
                            FirmwareLabPanel(
                                state = state,
                                onSelectFile = { selectDocument.launch(arrayOf("*/*")) },
                                onExportReport = { suggestedName -> exportReport.launch(suggestedName) },
                                onExpandSparse = { suggestedName -> expandSparse.launch(suggestedName) },
                                onOpenBootExtraction = {
                                    val report = (state.analysis as? FirmwareLabAnalysisState.Ready)?.report
                                    val sourceUri = selectedFirmwareUriString?.let(Uri::parse)
                                    if (report != null && sourceUri != null &&
                                        report.detectedFormat == FirmwareFormat.ANDROID_BOOT_IMAGE
                                    ) {
                                        startActivity(
                                            BootImageExtractionActivity.createIntent(
                                                context = this@FirmwareLabActivity,
                                                sourceUri = sourceUri,
                                                expectedSourceSha256 = report.sha256,
                                                displayName = report.displayName,
                                            ),
                                        )
                                    }
                                },
                                onOpenSuperExtraction = {
                                    val report = (state.analysis as? FirmwareLabAnalysisState.Ready)?.report
                                    val sourceUri = selectedFirmwareUriString?.let(Uri::parse)
                                    if (report != null && sourceUri != null &&
                                        report.detectedFormat == FirmwareFormat.ANDROID_SUPER_RAW
                                    ) {
                                        startActivity(
                                            SuperImageExtractionActivity.createIntent(
                                                context = this@FirmwareLabActivity,
                                                sourceUri = sourceUri,
                                                expectedSourceSha256 = report.sha256,
                                                displayName = report.displayName,
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                        item {
                            Text(
                                "Este aplicativo opera sem ADB e sem acesso de rede. As operações usam apenas arquivos locais, armazenamento escolhido pelo usuário e dispositivos conectados fisicamente por USB/OTG.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        item {
                            Button(onClick = {
                                startActivity(Intent(this@FirmwareLabActivity, HardwareValidationActivity::class.java))
                            }) { Text("Validar hardware Rockchip por OTG") }
                        }
                        item {
                            Button(onClick = {
                                startActivity(Intent(this@FirmwareLabActivity, RockchipBackupActivity::class.java))
                            }) { Text("Criar backup Rockchip local") }
                        }
                        item {
                            Button(onClick = {
                                startActivity(Intent(this@FirmwareLabActivity, LocalBackupVerificationActivity::class.java))
                            }) { Text("Verificar integridade de backup local") }
                        }
                        item {
                            Button(onClick = {
                                startActivity(Intent(this@FirmwareLabActivity, MainActivity::class.java))
                            }) { Text("Abrir diagnóstico de dispositivos") }
                        }
                        item {
                            Button(onClick = {
                                startActivity(Intent(this@FirmwareLabActivity, DiagnosticsLogActivity::class.java))
                            }) { Text("Abrir log técnico sanitizado") }
                        }
                    }
                }
            }
        }
    }
}
