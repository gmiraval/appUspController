package com.uspcontroller.app.ui

import com.uspcontroller.app.data.discovery.AgentInfo
import com.uspcontroller.app.data.transport.ConnectionState

/**
 * Root UI state for the Dashboard screen.
 *
 * Collected by Compose via `collectAsStateWithLifecycle()` for reactive recomposition.
 */
data class UiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val discoveredAgents: List<AgentInfo> = emptyList(),
    val isDiscovering: Boolean = false,
    val metrics: MetricsUiState = MetricsUiState(),
    val wifiPassphraseInput: String = "",
    val setOperationState: SetOperationState = SetOperationState.Idle,
    val errorMessage: String? = null
)

/**
 * UI state for the device metrics dashboard card.
 *
 * Null values indicate the metric has not been fetched yet.
 */
data class MetricsUiState(
    val cpuUsage: Int? = null,
    val memoryTotal: Long? = null,
    val memoryFree: Long? = null,
    val wifiSsid: String? = null,
    val isLoading: Boolean = false,
    val lastUpdated: Long = 0L
) {
    /** Memory usage ratio (0.0-1.0) for progress indicators. */
    val memoryUsageRatio: Float
        get() {
            val total = memoryTotal ?: return 0f
            val free = memoryFree ?: return 0f
            return if (total > 0) {
                ((total - free).toFloat() / total.toFloat()).coerceIn(0f, 1f)
            } else 0f
        }

    /** Whether any metrics data has been loaded at least once. */
    val hasData: Boolean
        get() = cpuUsage != null || memoryTotal != null || wifiSsid != null
}

/**
 * State of the Wi-Fi passphrase Set operation.
 */
sealed class SetOperationState {
    /** No operation in progress. */
    object Idle : SetOperationState()

    /** Set request is being sent to the agent. */
    object InProgress : SetOperationState()

    /** Set request completed successfully. */
    data class Success(val message: String = "Passphrase updated successfully") : SetOperationState()

    /** Set request failed. */
    data class Failed(val error: String) : SetOperationState()
}
