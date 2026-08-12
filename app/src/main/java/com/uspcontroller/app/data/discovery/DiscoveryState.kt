package com.uspcontroller.app.data.discovery

/**
 * Represents the current state of the mDNS/DNS-SD discovery process.
 */
sealed class DiscoveryState {

    /** Discovery has not been started. */
    object Idle : DiscoveryState()

    /** Actively scanning for USP agents on the local network. */
    object Scanning : DiscoveryState()

    /**
     * At least one agent was found during the scan.
     *
     * @param count Number of unique agents discovered so far.
     */
    data class Found(val count: Int) : DiscoveryState()

    /** Discovery timed out without finding any agents. */
    object Timeout : DiscoveryState()

    /**
     * Discovery encountered an error.
     *
     * @param errorCode The NsdManager error code.
     * @param message A human-readable description.
     */
    data class Error(val errorCode: Int, val message: String) : DiscoveryState()

    /** Discovery was explicitly stopped by the user or the system. */
    object Stopped : DiscoveryState()
}
