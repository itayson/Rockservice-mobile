package org.rockservice.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rockservice.core.common.diagnostics.DiagnosticSeverity

/** Displays the in-memory sanitized diagnostics buffer and exports it only on explicit user action. */
class DiagnosticsLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val events by AppDiagnostics.recorder.events.collectAsState()
                var message by remember { mutableStateOf<String?>(null) }
                val exportLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/x-ndjson"),
                ) { uri ->
                    uri?.let { destination ->
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    val content = AppDiagnostics.recorder.exportJsonLines()
                                    val output = applicationContext.contentResolver.openOutputStream(destination, "wt")
                                        ?: throw IOException("O destino selecionado não pode ser aberto.")
                                    output.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(content) }
                                    Result.success(Unit)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: SecurityException) {
                                    Result.failure(error)
                                } catch (error: IOException) {
                                    Result.failure(error)
                                }
                            }

                            result.fold(
                                onSuccess = {
                                    message = "Log técnico exportado com sucesso."
                                    AppDiagnostics.recorder.record(
                                        severity = DiagnosticSeverity.INFO,
                                        component = "diagnostics",
                                        action = "export",
                                        message = "Exportação manual do log técnico concluída.",
                                        metadata = mapOf("eventCount" to events.size.toString()),
                                    )
                                },
                                onFailure = { error ->
                                    message = error.message?.take(240)?.ifBlank { null }
                                        ?: "Falha ao exportar o log técnico."
                                },
                            )
                        }
                    }
                }

                Scaffold(
                    topBar = {
                        Surface(tonalElevation = 2.dp) {
                            Text(
                                "Log técnico sanitizado",
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
                            Text(
                                "Os eventos ficam somente em memória. Campos sensíveis conhecidos são redigidos antes de entrar no buffer.",
                            )
                        }
                        item {
                            Text("Eventos retidos: ${events.size}", style = MaterialTheme.typography.titleMedium)
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { exportLauncher.launch("rockservice-diagnostics.jsonl") },
                                    enabled = events.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Exportar JSONL sanitizado")
                                }
                                Button(
                                    onClick = {
                                        AppDiagnostics.recorder.clear()
                                        message = "Eventos em memória removidos."
                                    },
                                    enabled = events.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Limpar eventos em memória")
                                }
                            }
                        }
                        message?.let { status -> item { Text(status) } }

                        if (events.isEmpty()) {
                            item { Text("Nenhum evento técnico registrado nesta execução.") }
                        } else {
                            items(
                                items = events.takeLast(MAX_VISIBLE_EVENTS).asReversed(),
                                key = { event -> event.sequence },
                            ) { event ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text("#${event.sequence} ${event.severity} — ${event.component}/${event.action}")
                                        Text(Instant.ofEpochMilli(event.timestampEpochMillis).toString())
                                        Text(event.message)
                                        if (event.metadata.isNotEmpty()) {
                                            Text(
                                                event.metadata.entries.joinToString { (key, value) -> "$key=$value" },
                                            )
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

    private companion object {
        const val MAX_VISIBLE_EVENTS = 100
    }
}
