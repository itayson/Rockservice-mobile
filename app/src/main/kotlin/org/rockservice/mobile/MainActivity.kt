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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.rockservice.core.usb.AndroidUsbAttachmentMonitor
import org.rockservice.core.usb.AndroidUsbHostBackend
import org.rockservice.core.usb.UsbTargetSelectionPolicy
import org.rockservice.feature.devicedetection.CapabilityDetector

class MainActivity : ComponentActivity() {
    private lateinit var usbBackend: AndroidUsbHostBackend
    private lateinit var usbAttachmentMonitor: AndroidUsbAttachmentMonitor
    private val usbAttachmentEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbBackend = AndroidUsbHostBackend(applicationContext)
        usbAttachmentMonitor = AndroidUsbAttachmentMonitor(applicationContext) {
            // The broadcast is only a hint. The UI always performs a fresh enumeration.
            usbAttachmentEvents.tryEmit(Unit)
        }
        usbAttachmentMonitor.start()

        setContent {
            MaterialTheme {
                val capabilities = remember { CapabilityDetector(this).detect() }
                val scope = rememberCoroutineScope()
                val refreshMutex = remember { Mutex() }
                val usbState = remember {
                    mutableStateOf<UsbDiagnosticsUiState>(UsbDiagnosticsUiState.Loading)
                }
                val selectedTransportId = remember { mutableStateOf<String?>(null) }

                suspend fun refreshUsbDiagnostics() {
                    refreshMutex.withLock {
                        usbState.value = UsbDiagnosticsUiState.Loading
                        val refreshed = scanUsbDiagnostics(usbBackend)
                        if (refreshed is UsbDiagnosticsUiState.Ready) {
                            selectedTransportId.value = UsbTargetSelectionPolicy.reconcile(
                                selectedTransportId = selectedTransportId.value,
                                devices = refreshed.devices.map { device -> device.descriptor },
                            )
                        }
                        usbState.value = refreshed
                    }
                }

                LaunchedEffect(Unit) {
                    refreshUsbDiagnostics()
                    usbAttachmentEvents.collect {
                        refreshUsbDiagnostics()
                    }
                }

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
                                selectedTransportId.value?.let { transportId ->
                                    Text("Alvo selecionado: $transportId")
                                }
                                Button(
                                    onClick = {
                                        scope.launch { refreshUsbDiagnostics() }
                                    },
                                ) {
                                    Text("Atualizar diagnóstico USB")
                                }
                            }
                        }

                        when (val state = usbState.value) {
                            UsbDiagnosticsUiState.Loading -> {
                                item { CircularProgressIndicator() }
                            }

                            is UsbDiagnosticsUiState.Error -> {
                                item {
                                    Card {
                                        Column(Modifier.padding(16.dp)) {
                                            Text("Falha no diagnóstico USB")
                                            Text(state.message)
                                        }
                                    }
                                }
                            }

                            is UsbDiagnosticsUiState.Ready -> {
                                if (state.devices.isEmpty()) {
                                    item { Text("Nenhum dispositivo USB detectado.") }
                                } else {
                                    items(state.devices, key = { it.transportId }) { device ->
                                        val isSelected = selectedTransportId.value == device.transportId
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
                                                    onClick = {
                                                        selectedTransportId.value = UsbTargetSelectionPolicy.select(
                                                            candidate = device.descriptor,
                                                            devices = state.devices.map { item -> item.descriptor },
                                                        )
                                                    },
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
