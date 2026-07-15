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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.rockservice.feature.devicedetection.CapabilityDetector

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val capabilities = remember { CapabilityDetector(this).detect() }
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
                                "Bootstrap seguro: análise local e backend USB simulado.",
                                style = MaterialTheme.typography.titleMedium,
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
                    }
                }
            }
        }
    }
}
