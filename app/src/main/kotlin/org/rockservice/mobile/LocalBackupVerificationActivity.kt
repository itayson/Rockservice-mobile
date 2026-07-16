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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider

/** Verifies one local backup file against operator-supplied immutable metadata. */
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
    val resolver = LocalContext.current.contentResolver
    var selectedUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var startSectorText by rememberSaveable { mutableStateOf("0") }
    var sectorCountText by rememberSaveable { mutableStateOf("") }
    var sha256Text by rememberSaveable { mutableStateOf("") }
    val selectedUri = selectedUriString?.let(Uri::parse)
    val selectFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUriString = uri.toString()
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
            resultMessage = state.resultMessage,
            onSelectFile = { selectFile.launch(arrayOf("*/*")) },
            onStartSectorChange = {
                startSectorText = it.filter(Char::isDigit)
                viewModel.clearResult()
            },
            onSectorCountChange = {
                sectorCountText = it.filter(Char::isDigit)
                viewModel.clearResult()
            },
            onSha256Change = {
                sha256Text = it.trim().lowercase().take(64)
                viewModel.clearResult()
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
    resultMessage: String?,
    onSelectFile: () -> Unit,
    onStartSectorChange: (String) -> Unit,
    onSectorCountChange: (String) -> Unit,
    onSha256Change: (String) -> Unit,
    onVerify: () -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("A verificação é totalmente local e compara tamanho e SHA-256. Nenhum acesso USB ou de rede é executado.") }
        item {
            Button(onClick = onSelectFile, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                Text(if (selectedUri == null) "Selecionar arquivo" else "Trocar arquivo selecionado")
            }
        }
        item {
            OutlinedTextField(
                value = startSectorText,
                onValueChange = onStartSectorChange,
                label = { Text("LBA inicial") },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = sectorCountText,
                onValueChange = onSectorCountChange,
                label = { Text("Quantidade de setores de 512 bytes") },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = sha256Text,
                onValueChange = onSha256Change,
                label = { Text("SHA-256 esperado") },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            Button(
                onClick = onVerify,
                enabled = !running && selectedUri != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Verificar integridade")
            }
        }
        if (running) item { CircularProgressIndicator() }
        resultMessage?.let { message -> item { Text(message) } }
    }
}
