package com.uspcontroller.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uspcontroller.app.data.discovery.AgentInfo
import com.uspcontroller.app.data.discovery.MdnsDiscoveryService
import com.uspcontroller.app.data.transport.ConnectionState
import com.uspcontroller.app.data.transport.WebSocketMtpClient
import com.uspcontroller.app.domain.usecase.PollDeviceMetricsUseCase
import com.uspcontroller.app.domain.usecase.SetWifiPassphraseUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Main ViewModel for the USP Controller dashboard.
 *
 * Manages:
 * - mDNS agent discovery lifecycle
 * - WebSocket connection to selected agent
 * - Periodic polling of device metrics
 * - Wi-Fi passphrase Set operations
 * - UI state aggregation via [UiState]
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val discoveryService: MdnsDiscoveryService,
    private val mtpClient: WebSocketMtpClient,
    private val pollDeviceMetricsUseCase: PollDeviceMetricsUseCase,
    private val setWifiPassphraseUseCase: SetWifiPassphraseUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    /** Observable UI state for Compose collection. */
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Job for the metrics polling loop — cancelled on disconnect. */
    private var pollingJob: Job? = null

    /** The Endpoint ID of the currently connected agent. */
    private var connectedAgentEid: String = ""

    init {
        observeConnectionState()
        observeDiscoveredAgents()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection State Observation
    // ─────────────────────────────────────────────────────────────────────────

    private fun observeConnectionState() {
        viewModelScope.launch {
            mtpClient.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }

                when (state) {
                    is ConnectionState.Connected -> {
                        connectedAgentEid = state.agentEid
                        startPolling()
                    }
                    is ConnectionState.Disconnected,
                    is ConnectionState.Error -> {
                        stopPolling()
                    }
                    is ConnectionState.Reconnecting,
                    is ConnectionState.Connecting -> {
                        // Keep polling job alive during reconnection attempts
                    }
                }
            }
        }
    }

    private fun observeDiscoveredAgents() {
        viewModelScope.launch {
            discoveryService.discoveredAgents.collect { agents ->
                _uiState.update { it.copy(discoveredAgents = agents) }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Discovery
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Starts mDNS discovery for USP agents on the local network.
     * Discovery auto-stops after 10 seconds (handled by MdnsDiscoveryService).
     */
    fun startDiscovery() {
        _uiState.update { it.copy(isDiscovering = true) }
        discoveryService.startDiscovery()

        // Observe discovery state for timeout/completion
        viewModelScope.launch {
            discoveryService.discoveryState.collect { state ->
                when (state) {
                    is com.uspcontroller.app.data.discovery.DiscoveryState.Stopped,
                    is com.uspcontroller.app.data.discovery.DiscoveryState.Timeout,
                    is com.uspcontroller.app.data.discovery.DiscoveryState.Error -> {
                        _uiState.update { it.copy(isDiscovering = false) }
                    }
                    else -> { /* Scanning or Found — keep isDiscovering true */ }
                }
            }
        }
    }

    /**
     * Stops active discovery scan.
     */
    fun stopDiscovery() {
        discoveryService.stopDiscovery()
        _uiState.update { it.copy(isDiscovering = false) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Connects to a discovered USP agent.
     */
    fun connectToAgent(agent: AgentInfo) {
        connectedAgentEid = agent.endpointId
        discoveryService.stopDiscovery()
        _uiState.update { it.copy(isDiscovering = false) }
        mtpClient.connect(agent.webSocketUrl, agent.endpointId)
    }

    /**
     * Connects manually to a USP agent by URL.
     *
     * @param url The WebSocket URL (e.g., "ws://192.168.1.100:8080/usp").
     * @param agentEid Optional agent Endpoint ID for display purposes.
     */
    fun connectManual(url: String, agentEid: String = "manual-agent") {
        if (url.isBlank()) {
            _uiState.update { it.copy(errorMessage = "URL cannot be empty") }
            return
        }
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            _uiState.update { it.copy(errorMessage = "URL must start with ws:// or wss://") }
            return
        }

        connectedAgentEid = agentEid
        mtpClient.connect(url, agentEid)
    }

    /**
     * Disconnects from the current USP agent.
     */
    fun disconnect() {
        mtpClient.disconnect()
        stopPolling()
        _uiState.update {
            it.copy(
                metrics = MetricsUiState(),
                setOperationState = SetOperationState.Idle
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Metrics Polling
    // ─────────────────────────────────────────────────────────────────────────

    private fun startPolling() {
        stopPolling()

        _uiState.update { it.copy(metrics = it.metrics.copy(isLoading = true)) }

        pollingJob = viewModelScope.launch {
            pollDeviceMetricsUseCase.execute(connectedAgentEid).collect { result ->
                result.onSuccess { metrics ->
                    _uiState.update {
                        it.copy(
                            metrics = MetricsUiState(
                                cpuUsage = metrics.cpuUsage,
                                memoryTotal = metrics.memoryTotal,
                                memoryFree = metrics.memoryFree,
                                wifiSsid = metrics.wifiSsid,
                                isLoading = false,
                                lastUpdated = System.currentTimeMillis()
                            ),
                            errorMessage = null
                        )
                    }
                }
                result.onFailure { error ->
                    _uiState.update {
                        it.copy(
                            metrics = it.metrics.copy(isLoading = false),
                            errorMessage = error.message
                        )
                    }
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Wi-Fi Passphrase
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Updates the passphrase input field value.
     */
    fun updatePassphraseInput(value: String) {
        _uiState.update { it.copy(wifiPassphraseInput = value) }
    }

    /**
     * Sends a Set message to update the Wi-Fi passphrase on the agent.
     *
     * Validates input, sends the request, and manages operation state transitions.
     * Resets state to Idle after 3 seconds.
     */
    fun sendSetPassphrase() {
        val passphrase = _uiState.value.wifiPassphraseInput

        if (passphrase.length < 8) {
            _uiState.update {
                it.copy(setOperationState = SetOperationState.Failed("Minimum 8 characters required"))
            }
            resetSetStateAfterDelay()
            return
        }
        if (passphrase.length > 63) {
            _uiState.update {
                it.copy(setOperationState = SetOperationState.Failed("Maximum 63 characters allowed"))
            }
            resetSetStateAfterDelay()
            return
        }

        _uiState.update { it.copy(setOperationState = SetOperationState.InProgress) }

        viewModelScope.launch {
            val result = setWifiPassphraseUseCase.execute(connectedAgentEid, passphrase)

            result.onSuccess {
                _uiState.update {
                    it.copy(
                        setOperationState = SetOperationState.Success(),
                        wifiPassphraseInput = ""
                    )
                }
            }
            result.onFailure { error ->
                _uiState.update {
                    it.copy(
                        setOperationState = SetOperationState.Failed(
                            error.message ?: "Failed to update passphrase"
                        )
                    )
                }
            }

            resetSetStateAfterDelay()
        }
    }

    /**
     * Resets the set operation state back to Idle after a 3-second delay.
     */
    private fun resetSetStateAfterDelay() {
        viewModelScope.launch {
            delay(3000L)
            _uiState.update { it.copy(setOperationState = SetOperationState.Idle) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Error Handling
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Clears the current error message (e.g., after Snackbar dismissal).
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
