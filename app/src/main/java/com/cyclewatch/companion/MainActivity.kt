package com.cyclewatch.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.lifecycleScope
import com.cyclewatch.companion.health.CyclePhase
import com.cyclewatch.companion.health.SymptomAnalyzer
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var phase by mutableStateOf<CyclePhase?>(null)
    private var status by mutableStateOf("")

    private val analyzer by lazy {
        SymptomAnalyzer(HealthConnectClient.getOrCreate(this))
    }

    private val permissionLauncher = registerForActivityResult(
        SymptomAnalyzer.permissionRequestContract()
    ) { granted ->
        if (granted.containsAll(SymptomAnalyzer.REQUIRED_PERMISSIONS)) {
            refreshPhase()
        } else {
            status = "Health Connect permissions were denied."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PhaseScreen(
                        phase = phase,
                        status = status,
                        onRefresh = ::refreshPhase,
                    )
                }
            }
        }

        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> refreshPhase()
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                status = "Health Connect needs an update on this device."
            else -> status = "Health Connect is not available on this device."
        }
    }

    private fun refreshPhase() {
        lifecycleScope.launch {
            if (!analyzer.hasAllPermissions()) {
                permissionLauncher.launch(SymptomAnalyzer.REQUIRED_PERMISSIONS)
                return@launch
            }
            status = "Analyzing the last 14 days…"
            phase = analyzer.determineCurrentPhase()
            status = ""
            // Next step: sync `phase` to the Wear OS app via the Data Layer API.
        }
    }
}

@Composable
private fun PhaseScreen(
    phase: CyclePhase?,
    status: String,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = phase?.displayName() ?: "Current phase unknown",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        if (status.isNotEmpty()) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        Button(onClick = onRefresh) {
            Text("Refresh")
        }
    }
}

private fun CyclePhase.displayName(): String = when (this) {
    CyclePhase.PHASE_MENSTRUAL -> "Menstrual phase"
    CyclePhase.PHASE_FERTILE -> "Fertile window"
    CyclePhase.PHASE_LUTEAL -> "Luteal phase"
    CyclePhase.PHASE_UNKNOWN -> "Not enough data"
}
