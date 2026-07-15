package org.rockservice.mobile

import android.os.Build
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.rockservice.core.usb.AndroidUsbAttachmentMonitor
import org.rockservice.core.usb.AndroidUsbDiagnosticsScanner
import org.rockservice.core.usb.AndroidUsbHostBackend
import org.rockservice.core.usb.UsbDiagnosticsScanner
import org.rockservice.core.usb.UsbDiagnosticsState
import org.rockservice.core.usb.UsbHardwareValidationHostInfo
import org.rockservice.core.usb.rockchip.AndroidRockchipReadOnlyMetadataClient

/** Guided USB validation and explicitly gated read-only Rockchip metadata probe. */
class HardwareValidationActivity : ComponentActivity() {
    private lateinit var usbBackend: AndroidUsbHostBackend
    private lateinit var usbAttachmentMonitor: AndroidUsbAttachmentMonitor
    private lateinit var usbScanner: UsbDiagnosticsScanner
    private lateinit var usbViewModel: UsbDiagnosticsViewModel
    private lateinit var validationViewModel: HardwareValidationViewModel
    private lateinit var metadataClient: AndroidRockchipReadOnlyMetadataClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbViewModel = ViewModelProvider(this)[UsbDiagnosticsViewModel::class.java]
        validationViewModel = ViewModelProvider(this)[HardwareValidationViewModel::class.java]
        usbBackend = AndroidUsbHostBackend(applicationContext)
        metadataClient = AndroidRockchipReadOnlyMetadataClient(applicationContext)
        usbScanner = AndroidUsbDiagnosticsScanner(usbBackend)
        usbAttachmentMonitor = AndroidUsbAttachmentMonitor(applicationContext) { event ->
            validationViewModel.recordAttachmentEvent(event.kind)
            usbViewModel.refresh(usbScanner)
        }
        usbAttachmentMonitor.start()
        usbViewModel.refresh(usbScanner)

        setContent {
            MaterialTheme {
                val usbState by usbViewModel.state.collectAsState()
                val validationState by validationViewModel.state.collectAsState()
                val hostInfo = remember {
                    UsbHardwareValidationHostInfo(
                        manufacturer = Build.MANUFACTURER.orEmpty(),
                        model = Build.MODEL.orEmpty(),
                        androidRelease = Build.VERSION.RELEASE.orEmpty(),
                        sdkInt = Build.VERSION.SDK_INT,
                    )
                }
                val exportReport = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("text/plain"),
                ) { uri ->
                    uri?.let { destination ->
                        validationViewModel.exportReport(applicationContext.contentResolver, destination)
                    }
                }

                Scaffold(
                    topBar = {
                        Surface(tonalElevation = 2.dp) {
                            Text(
                                "Validacao de Hardware Rockchip",
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
                                    Text("Teste passivo e seguro", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Primeiro valida USB e descritores sem reivindicar interfaces. Somente apos sucesso, o teste experimental pode executar consultas Rockchip de metadados allowlisted, sem escrita, erase, reset ou loader.",
                                    )
                                }
                            }
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Contexto do equipamento", style = MaterialTheme.typography.titleLarge)
                                OutlinedTextField(
                                    value = validationState.boardOrDeviceModel,
                                    onValueChange = validationViewModel::setBoardOrDeviceModel,
                                    label = { Text("Modelo da placa, TV Box ou TV") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = validationState.knownSoc,
                                    onValueChange = validationViewModel::setKnownSoc,
                                    label = { Text("SoC conhecido, se souber") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = validationState.otgAdapter,
                                    onValueChange = validationViewModel::setOtgAdapter,
                                    label = { Text("Cabo ou adaptador OTG usado") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                            }
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Dispositivos USB", style = MaterialTheme.typography.titleLarge)
                                Text("Conecte o equipamento por OTG, atualize e selecione apenas o alvo autorizado.")
                                Button(onClick = { usbViewModel.refresh(usbScanner) }) {
                                    Text("Atualizar dispositivos USB")
                                }
                            }
                        }

                        when (val diagnostics = usbState.diagnostics) {
                            UsbDiagnosticsState.Loading -> item { CircularProgressIndicator() }
                            is UsbDiagnosticsState.Error -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text("Falha na enumeracao USB", style = MaterialTheme.typography.titleMedium)
                                        Text(diagnostics.message)
                                    }
                                }
                            }
                            is UsbDiagnosticsState.Ready -> {
                                if (diagnostics.devices.isEmpty()) {
                                    item { Text("Nenhum dispositivo USB detectado.") }
                                } else {
                                    items(
                                        items = diagnostics.devices,
                                        key = { snapshot -> requireNotNull(snapshot.descriptor.transportId) },
                                    ) { snapshot ->
                                        val device = snapshot.toUiModel()
                                        val selected = usbState.selectedTransportId == device.transportId
                                        Card(modifier = Modifier.fillMaxWidth()) {
                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(device.title, style = MaterialTheme.typography.titleMedium)
                                                Text(device.vendorProduct)
                                                Text(device.permissionLabel)
                                                Text(device.topologyLabel)
                                                Text(device.rockchipProbeLabel)
                                                Button(
                                                    onClick = { usbViewModel.selectTarget(device.transportId) },
                                                    enabled = !selected,
                                                ) {
                                                    Text(if (selected) "Alvo selecionado" else "Selecionar para validar")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val readyDiagnostics = usbState.diagnostics as? UsbDiagnosticsState.Ready
                        val selectedSnapshot = readyDiagnostics?.devices?.singleOrNull { snapshot ->
                            snapshot.descriptor.transportId == usbState.selectedTransportId
                        }

                        item {
                            Button(
                                onClick = {
                                    selectedSnapshot?.let { snapshot ->
                                        validationViewModel.runValidation(hostInfo, snapshot, usbBackend)
                                    }
                                },
                                enabled = selectedSnapshot != null && validationState.runState !is HardwareValidationRunState.Running,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Executar validacao segura")
                            }
                        }

                        when (val runState = validationState.runState) {
                            HardwareValidationRunState.Idle -> item {
                                Text("A validacao ainda nao foi executada nesta sessao.")
                            }
                            HardwareValidationRunState.Running -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator()
                                        Text("Validando permissao, identidade do alvo e descritores USB...")
                                    }
                                }
                            }
                            is HardwareValidationRunState.Ready -> item {
                                val report = runState.report
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text("Resultado da validacao", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            if (report.descriptorCheck.succeeded) {
                                                "Validacao segura concluida com sucesso."
                                            } else {
                                                "A validacao encontrou uma falha reproduzivel."
                                            },
                                        )
                                        Text("VID:PID ${report.device.vendorId.toString(16).uppercase().padStart(4, '0')}:${report.device.productId.toString(16).uppercase().padStart(4, '0')}")
                                        Text("Probe: ${report.device.probeLevel}")
                                        Text("Descritores lidos: ${report.descriptorCheck.bytesRead} bytes")
                                        Text(report.descriptorCheck.detail)
                                        Button(
                                            onClick = { exportReport.launch(report.suggestedFileName) },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text("Exportar relatorio para enviar")
                                        }
                                        if (report.descriptorCheck.succeeded) {
                                            Button(
                                                onClick = {
                                                    selectedSnapshot?.let { snapshot ->
                                                        validationViewModel.runMetadataProbe(snapshot, metadataClient)
                                                    }
                                                },
                                                enabled = selectedSnapshot != null &&
                                                    validationState.metadataProbeState !is RockchipMetadataProbeState.Running,
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Text("Testar metadados Rockchip somente leitura")
                                            }
                                        }
                                        validationState.exportMessage?.let { message -> Text(message) }
                                    }
                                }
                            }
                        }

                        when (val probeState = validationState.metadataProbeState) {
                            RockchipMetadataProbeState.Idle -> Unit
                            RockchipMetadataProbeState.Running -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator()
                                        Text("Executando consultas de metadados em sessoes USB isoladas...")
                                    }
                                }
                            }
                            is RockchipMetadataProbeState.Ready -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text("Metadados Rockchip experimentais", style = MaterialTheme.typography.titleMedium)
                                        Text("Nenhum comando de escrita, erase, reset ou loader foi enviado.")
                                        probeState.report.entries.forEach { entry ->
                                            Text("${if (entry.succeeded) "OK" else "FALHA"} - ${entry.name}: ${entry.value}")
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
        if (::usbViewModel.isInitialized) usbViewModel.cancelRefresh()
        if (::usbAttachmentMonitor.isInitialized) usbAttachmentMonitor.close()
        if (::usbBackend.isInitialized) {
            lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) { usbBackend.close() }
        }
        super.onDestroy()
    }
}
