package com.uspcontroller.app.data.transport

/**
 * Represents the current state of the WebSocket MTP connection to a USP Agent.
 *
 * This sealed class models the transport state machine:
 * Disconnected -> Connecting -> Connected
 *                            -> Error -> Reconnecting -> Connecting (loop)
 */
sealed class ConnectionState {

    /** No active connection. Initial state or after user-initiated disconnect. */
    object Disconnected : ConnectionState()

    /** WebSocket handshake in progress. */
    object Connecting : ConnectionState()

    /** Successfully connected to the USP Agent. */
    data class Connected(val agentEid: String) : ConnectionState()

    /**
     * Connection lost, attempting automatic reconnection.
     *
     * @param attempt Current retry attempt number (1-based).
     * @param nextRetryMs Milliseconds until next reconnection attempt.
     */
    data class Reconnecting(val attempt: Int, val nextRetryMs: Long) : ConnectionState()

    /**
     * Connection failed with an error.
     *
     * @param reason Human-readable error description.
     * @param recoverable If true, the client may attempt reconnection.
     *                    If false, user intervention is required.
     */
    data class Error(val reason: String, val recoverable: Boolean) : ConnectionState()

    /** Returns true if the connection is currently active and usable. */
    val isConnected: Boolean get() = this is Connected

    /** Returns a display-friendly label for the current state. */
    val displayLabel: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is Connecting -> "Connecting..."
            is Connected -> "Connected"
            is Reconnecting -> "Reconnecting (attempt $attempt)"
            is Error -> "Error: $reason"
        }
}
