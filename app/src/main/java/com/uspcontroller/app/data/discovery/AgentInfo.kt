package com.uspcontroller.app.data.discovery

/**
 * Holds the resolved information of a USP Agent discovered via mDNS/DNS-SD.
 *
 * Populated from the DNS-SD service resolution:
 * - Service type: `_usp-agt-ws._tcp.local.`
 * - TXT record fields: `eid` (Endpoint ID), `path` (WebSocket path)
 *
 * @param endpointId The USP Endpoint ID of the agent (from TXT "eid" field).
 * @param host The resolved IP address or hostname of the agent.
 * @param port The TCP port the agent's WebSocket endpoint listens on.
 * @param wsPath The WebSocket path (from TXT "path" field, defaults to "/").
 * @param serviceName The mDNS service instance name.
 */
data class AgentInfo(
    val endpointId: String,
    val host: String,
    val port: Int,
    val wsPath: String = "/",
    val serviceName: String
) {
    /**
     * Constructs the full WebSocket URL for connecting to this agent.
     * Uses cleartext `ws://` as required for the PoC.
     */
    val webSocketUrl: String
        get() = "ws://$host:$port$wsPath"

    /**
     * A short display string for UI lists.
     */
    val displayName: String
        get() = "$serviceName ($endpointId)"
}
