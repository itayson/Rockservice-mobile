package org.rockservice.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import org.rockservice.feature.devicedetection.CapabilityDetector

class MainActivity : ComponentActivity() {
    private lateinit var usbBackend: AndroidUsbHostBackend
    private lateinit var usbAttachmentMonitor: AndroidUsbAttachmentMonitor
    private lateinit var usbScanner: UsbDiagnosticsScanner
    private lateinit var usbViewModel: UsbDiagnosticsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbViewModel = ViewModelProvider(this)[UsbDiagnosticsViewModel::class.java]
        usbBackend = AndroidUsbHostBackend(applicationContext)
        usbScanner = AndroidUsbDiagnosticsScanner(usbBackend)
        usbAttachmentMonitor = AndroidUsbAttachmentMonitor(applicationContext) {
            // The broadcast is only a hint. The ViewModel always requests a fresh enumeration.
            usbViewModel.refresh(usbScanner)
        }
        usbAttachmentMonitor.start()
        usbViewModel.refresh(usbScanner)

        setContent {
            MaterialTheme {
                val capabilities = remember { CapabilityDetector(this).detect() }
                val usbScreenState by usbViewModel.state.collectAsState()

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
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            Text(
                                "Diagnóstico local e USB Host em modo passivo, sem escrita.",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }

                        item {
                            Text(
                                "Capacidades do dispositivo Android",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }

                        items(capabilities, key = { it.id }) { capability ->
                            Card {
                                Column(Modifier.padding(16.dp)) {
                                    Text(capability.title, style = MaterialTheme.typography.titleMedium)
                                    Text("Disponibilidade: ${capability.availability}")
                                    Text(capability.reason)
                                    Text("Risco: ${capability.riskLevel}")
                                }
                            }
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Dispositivos USB conectados",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    "Eventos de conexão apenas disparam uma nova enumeração. Nenhum broadcast é tratado como autorização de alvo.",
                                )
                                usbScreenState.selectedTransportId?.let { transportId ->
                                    Text("Alvo selecionado: $transportId")
                                }
                                Button(
                                    onClick = { usbViewModel.refresh(usbScanner) },
                                ) {
                                    Text("Atualizar diagnóstico USB")
                                }
                            }
                        }

                        when (val state = usbScreenState.diagnostics) {
                            UsbDiagnosticsState.Loading -> {
                                item { CircularProgressIndicator() }
                            }

                            is UsbDiagnosticsState.Error -> {
                                item {
                                    Card {
                                        Column(Modifier.padding(16.dp)) {
                                            Text("Falha no diagnóstico USB")
                                            Text(state.message)
                                        }
                                    }
                                }
                            }

                            is UsbDiagnosticsState.Ready -> {
                                if (state.devices.isEmpty()) {
                                    item { Text("Nenhum dispositivo USB detectado.") }
                                } else {
                                    items(
                                        items = state.devices,
                                        key = { snapshot -> requireNotNull(snapshot.descriptor.transportId) },
                                    ) { snapshot ->
                                        val device = snapshot.toUiModel()
                                        val isSelected = usbScreenState.selectedTransportId == device.transportId
                                        Card {
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
                                                    enabled = !isSelected,
                                                ) {
                                                    Text(if (isSelected) "Alvo selecionado" else "Selecionar alvo")
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
        }
    }

    override fun onDestroy() {
        if (::usbViewModel.isInitialized) {
            usbViewModel.cancelRefresh()
        }
        if (::usbAttachmentMonitor.isInitialized) {
            usbAttachmentMonitor.close()
        }
        if (::usbBackend.isInitialized) {
            lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                usbBackend.close()
            }
        }
        super.onDestroy()
    }
}
