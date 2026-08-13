package com.uspcontroller.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uspcontroller.app.data.transport.ConnectionState
import com.uspcontroller.app.ui.MainViewModel
import com.uspcontroller.app.ui.components.ConnectionStatusBar
import com.uspcontroller.app.ui.components.DiscoverySheet
import com.uspcontroller.app.ui.components.MetricsCard
import com.uspcontroller.app.ui.components.WifiConfigPanel

/**
 * Main dashboard screen for the USP Controller app.
 *
 * Layout:
 * 1. ConnectionStatusBar (sticky top)
 * 2. Connect/Disconnect button
 * 3. MetricsCard (visible when connected)
 * 4. WifiConfigPanel (visible when connected)
 * 5. DiscoverySheet (visible when disconnected)
 *
 * Uses [collectAsStateWithLifecycle] for lifecycle-aware state collection.
 */
@Composable
fun DashboardScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show errors via Snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed || result == SnackbarResult.Dismissed) {
                viewModel.clearError()
            }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Sticky status bar at top
            ConnectionStatusBar(state = uiState.connectionState)

            // Scrollable content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Connect/Disconnect button
                val isConnected = uiState.connectionState is ConnectionState.Connected
                val isConnecting = uiState.connectionState is ConnectionState.Connecting ||
                        uiState.connectionState is ConnectionState.Reconnecting

                if (isConnected || isConnecting) {
                    Button(
                        onClick = { viewModel.disconnect() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Disconnect")
                    }
                }

                // Metrics dashboard (visible when connected and has data or loading)
                if (isConnected) {
                    MetricsCard(metrics = uiState.metrics)

                    Spacer(modifier = Modifier.height(0.dp))

                    WifiConfigPanel(
                        input = uiState.wifiPassphraseInput,
                        onInputChange = viewModel::updatePassphraseInput,
                        onSend = viewModel::sendSetPassphrase,
                        state = uiState.setOperationState
                    )
                }

                // Discovery panel (visible when disconnected)
                if (!isConnected && !isConnecting) {
                    DiscoverySheet(
                        agents = uiState.discoveredAgents,
                        isDiscovering = uiState.isDiscovering,
                        onSelectAgent = viewModel::connectToAgent,
                        onManualConnect = viewModel::connectManual,
                        onStartScan = viewModel::startDiscovery
                    )
                }
            }
        }
    }
}
