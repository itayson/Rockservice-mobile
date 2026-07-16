package org.rockservice.mobile

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rockservice.core.usb.rockchip.RockchipBackupManifest
import org.rockservice.core.usb.rockchip.RockchipBackupVerifier

/** Verifies one local backup file against operator-supplied immutable metadata. */
class LocalBackupVerificationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                var selectedUri by remember { mutableStateOf<Uri?>(null) }
                var startSectorText by remember { mutableStateOf("0") }
                var sectorCountText by remember { mutableStateOf("") }
                var sha256Text by remember { mutableStateOf("") }
                var running by remember { mutableStateOf(false) }
                var resultMessage by remember { mutableStateOf<String?>(null) }

                val selectFile = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    selectedUri = uri
                    resultMessage = null
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            Text(
                                "A verificação é totalmente local e compara tamanho e SHA-256. Nenhum acesso USB ou de rede é executado.",
                            )
                        }
                        item {
                            Button(
                                onClick = { selectFile.launch(arrayOf("*/*")) },
                                enabled = !running,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (selectedUri == null) "Selecionar arquivo" else "Trocar arquivo selecionado")
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = startSectorText,
                                onValueChange = { startSectorText = it.filter(Char::isDigit) },
                                label = { Text("LBA inicial") },
                                enabled = !running,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = sectorCountText,
                                onValueChange = { sectorCountText = it.filter(Char::isDigit) },
                                label = { Text("Quantidade de setores de 512 bytes") },
                                enabled = !running,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = sha256Text,
                                onValueChange = { sha256Text = it.trim().lowercase().take(64) },
                                label = { Text("SHA-256 esperado") },
                                enabled = !running,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        item {
                            Button(
                                onClick = {
                                    val uri = selectedUri ?: return@Button
                                    val startSector = startSectorText.toLongOrNull()
                                    val sectorCount = sectorCountText.toLongOrNull()
                                    if (startSector == null || sectorCount == null) {
                                        resultMessage = "Informe LBA inicial e quantidade de setores válidos."
                                        return@Button
                                    }

                                    running = true
                                    resultMessage = null
                                    lifecycleScope.launch {
                                        val message = withContext(Dispatchers.IO) {
                                            try {
                                                val byteCount = Math.multiplyExact(sectorCount, 512L)
                                                val manifest = RockchipBackupManifest(
                                                    startSector = startSector,
                                                    sectorCount = sectorCount,
                                                    byteCount = byteCount,
                                                    sha256 = sha256Text,
                                                )
                                                val input = contentResolver.openInputStream(uri)
                                                    ?: throw IOException("O arquivo selecionado não pode ser aberto.")
                                                val verification = input.use { source ->
                                                    RockchipBackupVerifier.verify(manifest, source)
                                                }
                                                if (verification.verified) {
                                                    "Arquivo íntegro: tamanho e SHA-256 correspondem ao manifesto."
                                                } else {
                                                    buildString {
                                                        append("Verificação falhou.")
                                                        append(" Tamanho: ")
                                                        append(if (verification.sizeMatches) "OK" else "DIVERGENTE")
                                                        append(". SHA-256: ")
                                                        append(if (verification.sha256Matches) "OK" else "DIVERGENTE")
                                                        append('.')
                                                    }
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: Exception) {
                                                error.message ?: "Falha ao verificar o arquivo local."
                                            }
                                        }
                                        running = false
                                        resultMessage = message
                                    }
                                },
                                enabled = !running && selectedUri != null && sectorCountText.isNotBlank() && sha256Text.length == 64,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Verificar integridade")
                            }
                        }
                        if (running) {
                            item { CircularProgressIndicator() }
                        }
                        resultMessage?.let { message ->
                            item { Text(message) }
                        }
                    }
                }
            }
        }
    }
}
