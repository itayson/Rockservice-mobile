package org.rockservice.mobile

import android.content.Intent
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import org.rockservice.core.common.diagnostics.DiagnosticSeverity

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
            message = "Tela inicial do laboratório de firmware aberta.",
        )

        setContent {
            MaterialTheme {
                val state by firmwareLabViewModel.state.collectAsState()
                val resolver = applicationContext.contentResolver
                val selectDocument = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    uri?.let { selectedUri ->
                        firmwareLabViewModel.analyze(resolver, selectedUri)
                    }
                }
                val exportReport = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/plain"),
                ) { uri ->
                    uri?.let { destinationUri ->
                        firmwareLabViewModel.exportReport(resolver, destinationUri)
                    }
                }
                val expandSparse = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
                ) { uri ->
                    uri?.let { destinationUri ->
                        firmwareLabViewModel.expandSparse(resolver, destinationUri)
                    }
                }

                Scaffold(
                    topBar = {
                        Surface(tonalElevation = 2.dp) {
                            Text(
                                "RockService Mobile",
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
                                onSelectFile = {
                                    selectDocument.launch(arrayOf("*/*"))
                                },
                                onExportReport = { suggestedName ->
                                    exportReport.launch(suggestedName)
                                },
                                onExpandSparse = { suggestedName ->
                                    expandSparse.launch(suggestedName)
                                },
                            )
                        }
                        item {
                            Button(
                                onClick = {
                                    startActivity(
                                        Intent(
                                            this@FirmwareLabActivity,
                                            HardwareValidationActivity::class.java,
                                        ),
                                    )
                                },
                            ) {
                                Text("Validar hardware Rockchip por OTG")
                            }
                        }
                        item {
                            Button(
                                onClick = {
                                    startActivity(Intent(this@FirmwareLabActivity, MainActivity::class.java))
                                },
                            ) {
                                Text("Abrir diagnostico de dispositivos")
                            }
                        }
                        item {
                            Button(
                                onClick = {
                                    startActivity(
                                        Intent(
                                            this@FirmwareLabActivity,
                                            DiagnosticsLogActivity::class.java,
                                        ),
                                    )
                                },
                            ) {
                                Text("Abrir log técnico sanitizado")
                            }
                        }
                    }
                }
            }
        }
    }
}
