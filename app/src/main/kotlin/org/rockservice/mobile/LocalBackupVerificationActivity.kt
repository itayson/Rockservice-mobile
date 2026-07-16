package org.rockservice.mobile

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider

/** Verifies one local backup file against operator-supplied or imported immutable metadata. */
class LocalBackupVerificationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = ViewModelProvider(this)[LocalBackupVerificationViewModel::class.java]
        setContent {
            MaterialTheme {
                LocalBackupVerificationScreen(viewModel)
            }
        }
    }
}

@Composable
private fun LocalBackupVerificationScreen(viewModel: LocalBackupVerificationViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val resolver = context.contentResolver
    var selectedUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var startSectorText by rememberSaveable { mutableStateOf("0") }
    var sectorCountText by rememberSaveable { mutableStateOf("") }
    var sha256Text by rememberSaveable { mutableStateOf("") }
    var appliedManifestRevision by rememberSaveable { mutableStateOf(0L) }
    val selectedUri = selectedUriString?.let(Uri::parse)

    val selectFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUriString = uri.toString()
            viewModel.clearResult()
        }
    }
    val selectManifest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.loadManifest(resolver, uri)
    }

    LaunchedEffect(state.manifestRevision) {
        val manifest = state.loadedManifest
        if (manifest != null && state.manifestRevision > appliedManifestRevision) {
            startSectorText = manifest.startSector.toString()
            sectorCountText = manifest.sectorCount.toString()
            sha256Text = manifest.sha256
            appliedManifestRevision = state.manifestRevision
            viewModel.clearResult()
        }
    }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 2.dp) {
                Text(
                    "Verificar backup local",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        },
    ) { padding ->
        LocalBackupVerificationForm(
            modifier = Modifier.fillMaxSize().padding(padding),
            selectedUri = selectedUri,
            startSectorText = startSectorText,
            sectorCountText = sectorCountText,
            sha256Text = sha256Text,
            running = state.running,
            verificationPassed = state.verificationPassed,
            manifestLoading = state.manifestLoading,
            manifestMessage = state.manifestMessage,
            resultMessage = state.resultMessage,
            onSelectFile = { selectFile.launch(arrayOf("*/*")) },
            onSelectManifest = { selectManifest.launch(arrayOf("text/*", "application/octet-stream")) },
            onStartSectorChange = {
                startSectorText = it.filter(Char::isDigit)
                viewModel.markMetadataEdited()
            },
            onSectorCountChange = {
                sectorCountText = it.filter(Char::isDigit)
                viewModel.markMetadataEdited()
            },
            onSha256Change = {
                sha256Text = it.trim().lowercase().take(64)
                viewModel.markMetadataEdited()
            },
            onVerify = {
                val uri = selectedUri
                if (uri != null) {
                    viewModel.verify(
                        resolver = resolver,
                        uri = uri,
                        startSectorText = startSectorText,
                        sectorCountText = sectorCountText,
                        sha256Text = sha256Text,
                    )
                }
            },
            onOpenFirmwareLab = {
                val uri = selectedUri
                if (uri != null && state.verificationPassed) {
                    context.startActivity(FirmwareLabActivity.createIntent(context, uri))
                }
            },
        )
    }
}

@Composable
private fun LocalBackupVerificationForm(
    modifier: Modifier,
    selectedUri: Uri?,
    startSectorText: String,
    sectorCountText: String,
    sha256Text: String,
    running: Boolean,
    verificationPassed: Boolean,
    manifestLoading: Boolean,
    manifestMessage: String?,
    resultMessage: String?,
    onSelectFile: () -> Unit,
    onSelectManifest: () -> Unit,
    onStartSectorChange: (String) -> Unit,
    onSectorCountChange: (String) -> Unit,
    onSha256Change: (String) -> Unit,
    onVerify: () -> Unit,
    onOpenFirmwareLab: () -> Unit,
) {
    val busy = running || manifestLoading
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "A verificação é totalmente local. Você pode importar o manifesto gerado pelo RockService ou preencher os metadados manualmente.",
            )
        }
        item {
            Button(onClick = onSelectManifest, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Carregar manifesto de integridade")
            }
        }
        if (manifestLoading) item { CircularProgressIndicator() }
        manifestMessage?.let { message -> item { Text(message) } }
        item {
            Button(onClick = onSelectFile, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (selectedUri == null) "Selecionar arquivo de backup" else "Trocar arquivo de backup")
            }
        }
        item {
            OutlinedTextField(
                value = startSectorText,
                onValueChange = onStartSectorChange,
                label = { Text("LBA inicial") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = sectorCountText,
                onValueChange = onSectorCountChange,
                label = { Text("Quantidade de setores de 512 bytes") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = sha256Text,
                onValueChange = onSha256Change,
                label = { Text("SHA-256 esperado") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            Button(
                onClick = onVerify,
                enabled = !busy && selectedUri != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Verificar integridade")
            }
        }
        if (running) item { CircularProgressIndicator() }
        resultMessage?.let { message -> item { Text(message) } }
        if (verificationPassed && selectedUri != null) {
            item {
                Button(
                    onClick = onOpenFirmwareLab,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Analisar arquivo verificado no Firmware Lab")
                }
            }
        }
    }
}
