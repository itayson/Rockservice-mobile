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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.rockservice.core.usb.AndroidUsbHostBackend
import org.rockservice.feature.devicedetection.CapabilityDetector

class MainActivity : ComponentActivity() {
    private lateinit var usbBackend: AndroidUsbHostBackend

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbBackend = AndroidUsbHostBackend(applicationContext)

        setContent {
            MaterialTheme {
                val capabilities = remember { CapabilityDetector(this).detect() }
                val scope = rememberCoroutineScope()
                var usbState by remember {
                    mutableStateOf<UsbDiagnosticsUiState>(UsbDiagnosticsUiState.Loading)
                }

                LaunchedEffect(Unit) {
                    usbState = scanUsbDiagnostics(usbBackend)
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
                                    "A varredura abaixo enumera metadados, interfaces e endpoints. Ela não envia comandos ao dispositivo.",
                                )
                                Button(
                                    onClick = {
                                        scope.launch {
                                            usbState = UsbDiagnosticsUiState.Loading
                                            usbState = scanUsbDiagnostics(usbBackend)
                                        }
                                    },
                                ) {
                                    Text("Atualizar diagnóstico USB")
                                }
                            }
                        }

                        when (val state = usbState) {
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
                                        Card {
                                            Column(Modifier.padding(16.dp)) {
                                                Text(device.title, style = MaterialTheme.typography.titleMedium)
                                                Text(device.vendorProduct)
                                                Text(device.permissionLabel)
                                                Text(device.topologyLabel)
                                                Text(device.rockchipProbeLabel)
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
        if (::usbBackend.isInitialized) {
            lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                usbBackend.close()
            }
        }
        super.onDestroy()
    }
}
