package org.rockservice.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.rockservice.core.usb.AndroidUsbAttachmentMonitor
import org.rockservice.core.usb.AndroidUsbHostBackend

class AdbProbeActivity : ComponentActivity() {
    private lateinit var usbBackend: AndroidUsbHostBackend
    private lateinit var usbAttachmentMonitor: AndroidUsbAttachmentMonitor
    private lateinit var probeViewModel: AdbProbeViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbBackend = AndroidUsbHostBackend(applicationContext)
        probeViewModel = ViewModelProvider(
            this,
            AdbProbeViewModelFactory(applicationContext, usbBackend),
        )[AdbProbeViewModel::class.java]
        usbAttachmentMonitor = AndroidUsbAttachmentMonitor(applicationContext) {
            probeViewModel.cancelActiveOperation()
            probeViewModel.refresh()
        }
        usbAttachmentMonitor.start()
        probeViewModel.refresh()

        setContent {
            MaterialTheme {
                val state by probeViewModel.state.collectAsState()
                val operationRunning = state.operation is AdbProbeOperationState.Running
                Scaffold(
                    topBar = {
                        Surface(tonalElevation = 2.dp) {
                            Text(
                                "Validacao ADB por USB",
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
                                    Text("Conexao e autorizacao", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Este fluxo detecta a interface ADB, solicita permissao USB e valida somente a negociacao de conexao e autorizacao. Nenhum servico remoto e aberto automaticamente.",
                                    )
                                    Button(onClick = { probeViewModel.refresh() }, enabled = !operationRunning) {
                                        Text("Atualizar dispositivos")
                                    }
                                }
                            }
                        }

                        when (val scan = state.scan) {
                            AdbProbeScanState.Loading -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CircularProgressIndicator()
                                        Text("Inspecionando topologias USB em modo passivo...")
                                    }
                                }
                            }
                            is AdbProbeScanState.Error -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Falha na busca", style = MaterialTheme.typography.titleMedium)
                                        Text(scan.message)
                                    }
                                }
                            }
                            is AdbProbeScanState.Ready -> {
                                if (scan.candidates.isEmpty()) {
                                    item {
                                        Text("Nenhum dispositivo com uma interface ADB USB inequivoca foi detectado. Ative a depuracao USB no dispositivo alvo e reconecte o cabo.")
                                    }
                                } else {
                                    item { Text("Dispositivos detectados", style = MaterialTheme.typography.titleLarge) }
                                    items(
                                        items = scan.candidates,
                                        key = { candidate -> requireNotNull(candidate.descriptor.transportId) },
                                    ) { candidate ->
                                        Card(modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(candidate.displayName, style = MaterialTheme.typography.titleMedium)
                                                Text("VID:PID %04X:%04X".format(candidate.descriptor.vendorId, candidate.descriptor.productId))
                                                Text(
                                                    if (candidate.descriptor.hasPermission) "Permissao USB: concedida"
                                                    else "Permissao USB: sera solicitada ao iniciar",
                                                )
                                                Button(
                                                    onClick = { probeViewModel.probe(candidate) },
                                                    enabled = !operationRunning,
                                                    modifier = Modifier.fillMaxWidth(),
                                                ) {
                                                    Text("Validar conexao ADB")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        when (val operation = state.operation) {
                            AdbProbeOperationState.Idle -> Unit
                            is AdbProbeOperationState.Running -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CircularProgressIndicator()
                                        Text("Validacao em andamento", style = MaterialTheme.typography.titleMedium)
                                        Text(operation.stage)
                                        if (operation.awaitingDeviceAuthorization) {
                                            Text("Confirme o dialogo de depuracao USB no dispositivo conectado. A identidade publica pertence somente a esta instalacao do RockService Mobile.")
                                        }
                                        Button(onClick = { probeViewModel.cancelActiveOperation() }) { Text("Cancelar") }
                                    }
                                }
                            }
                            is AdbProbeOperationState.Connected -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("ADB conectado e autorizado", style = MaterialTheme.typography.titleMedium)
                                        Text("Versao negociada: 0x${operation.protocolVersion.toString(16).uppercase()}")
                                        Text("Max data negociado: ${operation.maxDataBytes} bytes")
                                        Text("Banner remoto: ${operation.banner}")
                                        Text("Nenhum servico remoto foi aberto durante esta validacao.")
                                    }
                                }
                            }
                            is AdbProbeOperationState.Error -> item {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Validacao ADB falhou", style = MaterialTheme.typography.titleMedium)
                                        Text(operation.message)
                                        if (operation.authorizationMayBePending) {
                                            Text("A autorizacao pode continuar pendente no dispositivo. Confirme o dialogo e tente novamente.")
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
        if (::probeViewModel.isInitialized) probeViewModel.cancelActiveOperation()
        if (::usbAttachmentMonitor.isInitialized) usbAttachmentMonitor.close()
        if (::usbBackend.isInitialized) {
            lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) { usbBackend.close() }
        }
        super.onDestroy()
    }
}
