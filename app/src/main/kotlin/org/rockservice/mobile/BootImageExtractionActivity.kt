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
import org.rockservice.feature.firmware.AndroidBootSectionType

/** Explicit export screen for one analyzed Android Boot Image source. */
class BootImageExtractionActivity : ComponentActivity() {
    private lateinit var viewModel: BootImageExtractionViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI)?.let(Uri::parse)
        val expectedSourceSha256 = intent.getStringExtra(EXTRA_SOURCE_SHA256)
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)?.take(MAXIMUM_DISPLAY_NAME_LENGTH)
            ?: "boot.img"
        if (sourceUri == null || expectedSourceSha256.isNullOrBlank()) {
            finish()
            return
        }

        viewModel = ViewModelProvider(
            this,
            BootImageExtractionViewModelFactory(
                sourceUri = sourceUri,
                expectedSourceSha256 = expectedSourceSha256,
                contentResolver = applicationContext.contentResolver,
            ),
        )[BootImageExtractionViewModel::class.java]

        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsState()
                var pendingSectionName by rememberSaveable { mutableStateOf<String?>(null) }
                val createDestination = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
                ) { destinationUri ->
                    val sectionType = pendingSectionName?.let { name ->
                        AndroidBootSectionType.entries.singleOrNull { section -> section.name == name }
                    }
                    pendingSectionName = null
                    if (destinationUri != null && sectionType != null) {
                        viewModel.extract(sectionType, destinationUri)
                    }
                }
                val extractionRunning = state.extraction is BootSectionExtractionState.Extracting

                Scaffold(
                    topBar = {
                        Surface(tonalElevation = 2.dp) {
                            Text(
                                "Extracao Android Boot Image",
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
                                "Cada payload e revalidado contra o layout atual e o SHA-256 completo da imagem analisada. A origem nunca e modificada.",
                            )
                        }

                        when (val metadata = state.metadata) {
                            BootImageMetadataState.Loading -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator()
                                        Text("Revalidando a estrutura da Boot Image...")
                                    }
                                }
                            }

                            is BootImageMetadataState.Error -> item {
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

                            is BootImageMetadataState.Ready -> {
                                item {
                                    Text("Payloads disponiveis", style = MaterialTheme.typography.titleMedium)
                                }
                                metadata.extractableSections.forEach { sectionType ->
                                    item(key = sectionType.name) {
                                        Button(
                                            onClick = {
                                                pendingSectionName = sectionType.name
                                                createDestination.launch(
                                                    suggestedSectionFileName(displayName, sectionType),
                                                )
                                            },
                                            enabled = !extractionRunning,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text("Extrair ${sectionType.displayLabel()}")
                                        }
                                    }
                                }
                            }
                        }

                        when (val extraction = state.extraction) {
                            BootSectionExtractionState.Idle -> Unit
                            is BootSectionExtractionState.Extracting -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator()
                                        Text("Extraindo ${extraction.sectionType.displayLabel()}...")
                                        Text("A origem esta sendo revalidada durante a operacao.")
                                    }
                                }
                            }

                            is BootSectionExtractionState.Ready -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Text("Extracao concluida", style = MaterialTheme.typography.titleMedium)
                                        Text("Secao: ${extraction.report.sectionType.displayLabel()}")
                                        Text("Bytes extraidos: ${extraction.report.extractedBytes}")
                                        Text("SHA-256 do payload: ${extraction.report.sectionSha256}")
                                        Text("SHA-256 da origem revalidada: ${extraction.report.sourceSha256}")
                                    }
                                }
                            }

                            is BootSectionExtractionState.Error -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text("Falha na extracao", style = MaterialTheme.typography.titleMedium)
                                        extraction.sectionType?.let { section ->
                                            Text("Secao: ${section.displayLabel()}")
                                        }
                                        Text(extraction.message)
                                        if (extraction.destinationMayContainPartialData) {
                                            Text(
                                                "O destino pode conter dados parciais ou invalidos. Exclua esse arquivo antes de tentar novamente.",
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
        private const val EXTRA_SOURCE_URI = "boot_source_uri"
        private const val EXTRA_SOURCE_SHA256 = "boot_source_sha256"
        private const val EXTRA_DISPLAY_NAME = "boot_display_name"
        private const val MAXIMUM_DISPLAY_NAME_LENGTH = 255

        fun createIntent(
            context: Context,
            sourceUri: Uri,
            expectedSourceSha256: String,
            displayName: String,
        ): Intent = Intent(context, BootImageExtractionActivity::class.java).apply {
            putExtra(EXTRA_SOURCE_URI, sourceUri.toString())
            putExtra(EXTRA_SOURCE_SHA256, expectedSourceSha256)
            putExtra(EXTRA_DISPLAY_NAME, displayName.take(MAXIMUM_DISPLAY_NAME_LENGTH))
        }
    }
}

private fun suggestedSectionFileName(
    displayName: String,
    sectionType: AndroidBootSectionType,
): String {
    val base = displayName.substringBeforeLast('.', missingDelimiterValue = displayName)
    return "$base-${sectionType.name.lowercase()}.bin"
}

private fun AndroidBootSectionType.displayLabel(): String = when (this) {
    AndroidBootSectionType.HEADER -> "header"
    AndroidBootSectionType.KERNEL -> "kernel"
    AndroidBootSectionType.RAMDISK -> "ramdisk"
    AndroidBootSectionType.SECOND_STAGE -> "second stage"
    AndroidBootSectionType.RECOVERY_DTBO -> "recovery DTBO"
    AndroidBootSectionType.DTB -> "DTB"
    AndroidBootSectionType.BOOT_SIGNATURE -> "boot signature"
}
