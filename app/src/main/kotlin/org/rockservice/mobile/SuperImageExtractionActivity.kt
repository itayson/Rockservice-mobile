package org.rockservice.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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

/** Explicit one-at-a-time export screen for logical partitions from one analyzed raw super image. */
class SuperImageExtractionActivity : ComponentActivity() {
    private var viewModel: SuperImageExtractionViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI)?.let(Uri::parse)
        val expectedSourceSha256 = intent.getStringExtra(EXTRA_SOURCE_SHA256)
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
            ?.take(MAXIMUM_DISPLAY_NAME_LENGTH)
            ?.takeIf(String::isNotBlank)
            ?: "super.img"

        if (sourceUri == null || expectedSourceSha256.isNullOrBlank()) {
            setContent {
                MaterialTheme {
                    Scaffold { padding ->
                        Column(
                            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Exportacao de particoes logicas", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "A referencia da super.img ou o SHA-256 esperado esta ausente. Volte ao Laboratorio de Firmware, selecione a imagem e execute a analise novamente.",
                            )
                            Button(onClick = { finish() }) {
                                Text("Voltar ao Laboratorio")
                            }
                        }
                    }
                }
            }
            return
        }

        val resolvedViewModel = ViewModelProvider(
            this,
            SuperImageExtractionViewModelFactory(
                sourceUri = sourceUri,
                expectedSourceSha256 = expectedSourceSha256,
                contentResolver = applicationContext.contentResolver,
            ),
        )[SuperImageExtractionViewModel::class.java]
        viewModel = resolvedViewModel

        setContent {
            MaterialTheme {
                val state by resolvedViewModel.state.collectAsState()
                var pendingPartitionName by rememberSaveable { mutableStateOf<String?>(null) }
                val createDestination = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
                ) { destinationUri ->
                    val partitionName = pendingPartitionName
                    pendingPartitionName = null
                    if (destinationUri != null && partitionName != null) {
                        resolvedViewModel.exportPartition(partitionName, destinationUri)
                    }
                }
                val exportRunning = state.export is SuperPartitionExportState.Exporting

                Scaffold(
                    topBar = {
                        Surface(tonalElevation = 2.dp) {
                            Text(
                                "Particoes logicas de super.img",
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
                            Text("Origem: $displayName")
                            Text(
                                "A imagem e revalidada por SHA-256 antes e depois da exportacao. Apenas uma particao e exportada por operacao; a origem permanece somente leitura.",
                            )
                        }

                        when (val metadata = state.metadata) {
                            SuperMetadataState.Loading -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator()
                                        Text("Revalidando metadata liblp e planos de extents...")
                                    }
                                }
                            }

                            is SuperMetadataState.Error -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text("Falha na revalidacao", style = MaterialTheme.typography.titleMedium)
                                        Text(metadata.message)
                                    }
                                }
                            }

                            is SuperMetadataState.Ready -> {
                                item {
                                    Text(
                                        "Particoes exportaveis (${metadata.metadataCopy.name.lowercase()} metadata)",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                metadata.partitions.forEach { option ->
                                    item(key = option.name) {
                                        Card(modifier = Modifier.fillMaxWidth()) {
                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Text(option.name, style = MaterialTheme.typography.titleMedium)
                                                Text("Tamanho logico: ${option.sizeBytes} bytes")
                                                Button(
                                                    onClick = {
                                                        pendingPartitionName = option.name
                                                        createDestination.launch(
                                                            suggestedPartitionFileName(displayName, option.name),
                                                        )
                                                    },
                                                    enabled = !exportRunning,
                                                    modifier = Modifier.fillMaxWidth(),
                                                ) {
                                                    Text("Selecionar destino e exportar")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        when (val export = state.export) {
                            SuperPartitionExportState.Idle -> Unit

                            is SuperPartitionExportState.Exporting -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator()
                                        Text("Exportando ${export.partitionName}...")
                                        Text("${export.writtenBytes} / ${export.totalBytes} bytes")
                                        if (export.totalBytes > 0L) {
                                            val percentage = (export.writtenBytes * 100L / export.totalBytes).coerceIn(0L, 100L)
                                            Text("Progresso: $percentage%")
                                        }
                                    }
                                }
                            }

                            is SuperPartitionExportState.Ready -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Text("Exportacao concluida", style = MaterialTheme.typography.titleMedium)
                                        Text("Particao: ${export.report.partitionName}")
                                        Text("Bytes gravados: ${export.report.bytesWritten}")
                                        Text("SHA-256: ${export.report.outputSha256}")
                                        Text("Extents LINEAR: ${export.report.linearExtentCount}")
                                        Text("Extents ZERO: ${export.report.zeroExtentCount}")
                                    }
                                }
                            }

                            is SuperPartitionExportState.Error -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text("Falha na exportacao", style = MaterialTheme.typography.titleMedium)
                                        export.partitionName?.let { name -> Text("Particao: $name") }
                                        Text(export.message)
                                        if (export.destinationMayContainPartialData) {
                                            Text(
                                                "O destino pode conter dados parciais ou invalidos. Exclua o arquivo antes de uma nova tentativa.",
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

    companion object {
        private const val EXTRA_SOURCE_URI = "super_source_uri"
        private const val EXTRA_SOURCE_SHA256 = "super_source_sha256"
        private const val EXTRA_DISPLAY_NAME = "super_display_name"
        private const val MAXIMUM_DISPLAY_NAME_LENGTH = 255

        fun createIntent(
            context: Context,
            sourceUri: Uri,
            expectedSourceSha256: String,
            displayName: String,
        ): Intent = Intent(context, SuperImageExtractionActivity::class.java).apply {
            putExtra(EXTRA_SOURCE_URI, sourceUri.toString())
            putExtra(EXTRA_SOURCE_SHA256, expectedSourceSha256)
            putExtra(EXTRA_DISPLAY_NAME, displayName.take(MAXIMUM_DISPLAY_NAME_LENGTH))
        }
    }
}

private fun suggestedPartitionFileName(displayName: String, partitionName: String): String {
    val sourceBase = displayName.substringBeforeLast('.', missingDelimiterValue = displayName)
        .filter { character -> character.isLetterOrDigit() || character == '-' || character == '_' }
        .take(80)
        .ifBlank { "super" }
    val safePartition = partitionName
        .filter { character -> character.isLetterOrDigit() || character == '-' || character == '_' }
        .take(80)
        .ifBlank { "partition" }
    return "$sourceBase-$safePartition.img"
}
