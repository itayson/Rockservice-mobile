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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.rockservice.core.usb.AndroidUsbAttachmentMonitor
import org.rockservice.core.usb.AndroidUsbDiagnosticsScanner
import org.rockservice.core.usb.AndroidUsbHostBackend
import org.rockservice.core.usb.UsbDiagnosticsScanner
import org.rockservice.core.usb.UsbDiagnosticsState
import org.rockservice.core.usb.rockchip.AndroidRockchipReadOnlyBackupClient
import org.rockservice.core.usb.rockchip.AndroidRockchipReadOnlyMetadataClient

/** Explicit, offline, read-only Rockchip backup flow with a conservative initial physical range cap. */
class RockchipBackupActivity : ComponentActivity() {
    private lateinit var usbBackend: AndroidUsbHostBackend
    private lateinit var usbAttachmentMonitor: AndroidUsbAttachmentMonitor
    private lateinit var usbScanner: UsbDiagnosticsScanner
    private lateinit var usbViewModel: UsbDiagnosticsViewModel
    private lateinit var backupViewModel: RockchipBackupViewModel
    private lateinit var metadataClient: AndroidRockchipReadOnlyMetadataClient
    private lateinit var backupClient: AndroidRockchipReadOnlyBackupClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbViewModel = ViewModelProvider(this)[UsbDiagnosticsViewModel::class.java]
        backupViewModel = ViewModelProvider(this)[RockchipBackupViewModel::class.java]
        usbBackend = AndroidUsbHostBackend(applicationContext)
        usbScanner = AndroidUsbDiagnosticsScanner(usbBackend)
        metadataClient = AndroidRockchipReadOnlyMetadataClient(applicationContext)
        backupClient = AndroidRockchipReadOnlyBackupClient(
            applicationContext,
            maximumSectorCount = UI_MAXIMUM_SECTORS,
        )
        usbAttachmentMonitor = AndroidUsbAttachmentMonitor(applicationContext) {
            backupViewModel.cancel()
            usbViewModel.refresh(usbScanner)
        }
        usbAttachmentMonitor.start()
        usbViewModel.refresh(usbScanner)

        setContent {
            MaterialTheme {
                val usbState by usbViewModel.state.collectAsState()
                val backupState by backupViewModel.state.collectAsState()
                val scope = rememberCoroutineScope()
                var probeJob by remember { mutableStateOf<Job?>(null) }
                var probeRunning by remember { mutableStateOf(false) }
                var baselinePassed by remember { mutableStateOf(false) }
                var probeMessage by remember { mutableStateOf<String?>(null) }
                var startSectorText by remember { mutableStateOf("0") }
                var sectorCountText by remember { mutableStateOf("1") }

                val ready = usbState.diagnostics as? UsbDiagnosticsState.Ready
                val selectedSnapshot = ready?.devices?.singleOrNull {
                    it.descriptor.transportId == usbState.selectedTransportId
                }
                val parsedStartSector = startSectorText.toLongOrNull()
                val parsedSectorCount = sectorCountText.toLongOrNull()
                val rangeValid = parsedStartSector != null && parsedStartSector >= 0L &&
                    parsedSectorCount != null && parsedSectorCount in 1L..UI_MAXIMUM_SECTORS

                val createBackup = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
                ) { uri ->
                    val snapshot = selectedSnapshot
                    val start = parsedStartSector
                    val count = parsedSectorCount
                    if (uri != null && snapshot != null && start != null && count != null && baselinePassed) {
                        backupViewModel.start(
                            contentResolver = applicationContext.contentResolver,
                            destination = uri,
                            device = snapshot.descriptor,
                            client = backupClient,
                            startSector = start,
                            sectorCount = count,
                            timeoutMillis = BACKUP_TIMEOUT_MILLIS,
                        )
                    }
                }
                val exportManifest = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/plain"),
                ) { uri ->
                    uri?.let { destination ->
                        backupViewModel.exportManifest(
                            contentResolver = applicationContext.contentResolver,
                            destination = destination,
                        )
                    }
                }

                fun invalidateTargetGate() {
                    probeJob?.cancel()
                    probeJob = null
                    probeRunning = false
                    baselinePassed = false
                    probeMessage = null
                    backupViewModel.cancel()
                }

                LaunchedEffect(usbState.selectedTransportId) { invalidateTargetGate() }

                Scaffold(
                    topBar = {
                        Surface(tonalElevation = 2.dp) {
                            Text(
                                "Backup Rockchip offline",
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
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Somente leitura", style = MaterialTheme.typography.titleMedium)
                                    Text("O fluxo é totalmente offline e exporta apenas para um destino escolhido pelo usuário via SAF.")
                                    Text("Limite inicial da interface: 2 MiB por operação. Nenhuma escrita é enviada ao dispositivo Rockchip.")
                                }
                            }
                        }

                        item {
                            Button(onClick = { usbViewModel.refresh(usbScanner) }) { Text("Atualizar dispositivos USB") }
                        }

                        when (val diagnostics = usbState.diagnostics) {
                            UsbDiagnosticsState.Loading -> item { CircularProgressIndicator() }
                            is UsbDiagnosticsState.Error -> item { Text(diagnostics.message) }
                            is UsbDiagnosticsState.Ready -> items(
                                diagnostics.devices,
                                key = { requireNotNull(it.descriptor.transportId) },
                            ) { snapshot ->
                                val model = snapshot.toUiModel()
                                val selected = usbState.selectedTransportId == model.transportId
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(model.title, style = MaterialTheme.typography.titleMedium)
                                        Text(model.vendorProduct)
                                        Text(model.permissionLabel)
                                        Text(model.rockchipProbeLabel)
                                        Button(
                                            onClick = {
                                                invalidateTargetGate()
                                                usbViewModel.selectTarget(model.transportId)
                                            },
                                            enabled = !selected,
                                        ) { Text(if (selected) "Alvo selecionado" else "Selecionar alvo") }
                                    }
                                }
                            }
                        }

                        item {
                            Button(
                                onClick = {
                                    val snapshot = selectedSnapshot ?: return@Button
                                    invalidateTargetGate()
                                    probeRunning = true
                                    probeJob = scope.launch(Dispatchers.IO) {
                                        try {
                                            val report = metadataClient.probe(snapshot.descriptor)
                                            val passed = report.entries.isNotEmpty() &&
                                                report.entries.all { it.attempted && it.succeeded } &&
                                                !report.requiresReconnect
                                            baselinePassed = passed
                                            probeMessage = if (passed) {
                                                "Baseline read-only validada. O backup bounded pode ser iniciado."
                                            } else {
                                                "A baseline Rockchip não passou integralmente; o backup permanece bloqueado."
                                            }
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: RuntimeException) {
                                            baselinePassed = false
                                            probeMessage = error.message?.take(240) ?: "Falha no probe Rockchip."
                                        } finally {
                                            probeRunning = false
                                        }
                                    }
                                },
                                enabled = selectedSnapshot != null && !probeRunning && backupState !is RockchipBackupState.Running,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Validar baseline Rockchip") }
                        }

                        if (probeRunning) item { CircularProgressIndicator() }
                        probeMessage?.let { message -> item { Text(message) } }

                        item {
                            OutlinedTextField(
                                value = startSectorText,
                                onValueChange = { startSectorText = it.filter(Char::isDigit).take(10) },
                                label = { Text("LBA inicial") },
                                enabled = backupState !is RockchipBackupState.Running,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = sectorCountText,
                                onValueChange = { sectorCountText = it.filter(Char::isDigit).take(7) },
                                label = { Text("Quantidade de setores (máx. $UI_MAXIMUM_SECTORS)") },
                                enabled = backupState !is RockchipBackupState.Running,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }

                        item {
                            Button(
                                onClick = { createBackup.launch("rockchip-lba-${parsedStartSector ?: 0}.img") },
                                enabled = baselinePassed && rangeValid && backupState !is RockchipBackupState.Running,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Escolher destino e iniciar backup") }
                        }

                        when (val state = backupState) {
                            RockchipBackupState.Idle -> Unit
                            is RockchipBackupState.Running -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CircularProgressIndicator()
                                        val progress = state.progress
                                        Text(
                                            if (progress == null) "Preparando destino e sessão USB..."
                                            else "${progress.completedSectors}/${progress.totalSectors} setores (${progress.completedBytes}/${progress.totalBytes} bytes)",
                                        )
                                        Button(onClick = backupViewModel::cancel) { Text("Cancelar") }
                                    }
                                }
                            }
                            is RockchipBackupState.Completed -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Backup concluído", style = MaterialTheme.typography.titleMedium)
                                        Text("Bytes: ${state.result.byteCount}")
                                        Text("SHA-256: ${state.result.sha256}")
                                        Button(
                                            onClick = {
                                                exportManifest.launch(
                                                    "rockchip-lba-${state.result.startSector}-${state.result.sectorCount}-sectors.manifest.txt",
                                                )
                                            },
                                            enabled = !state.manifestExportRunning,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text("Exportar manifesto de integridade")
                                        }
                                        if (state.manifestExportRunning) CircularProgressIndicator()
                                        state.manifestExportMessage?.let { message -> Text(message) }
                                    }
                                }
                            }
                            is RockchipBackupState.Failed -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Falha no backup", style = MaterialTheme.typography.titleMedium)
                                        Text(state.message)
                                        if (state.destinationMayContainPartialData) {
                                            Text("O arquivo de destino pode conter dados parciais e não deve ser tratado como backup válido.")
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
        backupViewModel.cancel()
        if (::usbViewModel.isInitialized) usbViewModel.cancelRefresh()
        if (::usbAttachmentMonitor.isInitialized) usbAttachmentMonitor.close()
        if (::usbBackend.isInitialized) {
            lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) { usbBackend.close() }
        }
        super.onDestroy()
    }

    private companion object {
        const val UI_MAXIMUM_SECTORS = 4_096L // 2 MiB at 512 bytes/sector.
        const val BACKUP_TIMEOUT_MILLIS = 5L * 60L * 1000L
    }
}
