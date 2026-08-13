package com.uspcontroller.app.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Discovers USP Agents on the local network using mDNS/DNS-SD (Android NsdManager).
 *
 * Browses for service type `_usp-agt-ws._tcp.` as defined by TR-369 for
 * WebSocket-based USP agents. Resolves found services to extract host, port,
 * and TXT record fields (eid, path).
 *
 * Lifecycle:
 * 1. Call [startDiscovery] to begin scanning.
 * 2. Observe [discoveredAgents] for found agents.
 * 3. Observe [discoveryState] for status updates.
 * 4. Call [stopDiscovery] when done or on cleanup.
 *
 * Handles: deduplication by endpoint ID, resolution failures, missing TXT fields,
 * multicast lock acquisition/release, and 10-second timeout.
 */
class MdnsDiscoveryService(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "MdnsDiscovery"
        private const val SERVICE_TYPE = "_usp-agt-ws._tcp."
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
        private const val TXT_KEY_EID = "eid"
        private const val TXT_KEY_PATH = "path"
        private const val DEFAULT_EID = "unknown"
        private const val DEFAULT_PATH = "/"
    }

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var isDiscovering = false
    private var timeoutJob: Job? = null

    // Tracks endpoint IDs we've already resolved to avoid duplicates
    private val resolvedEids = mutableSetOf<String>()

    private val _discoveredAgents = MutableStateFlow<List<AgentInfo>>(emptyList())
    /** Observable list of unique discovered agents. */
    val discoveredAgents: StateFlow<List<AgentInfo>> = _discoveredAgents.asStateFlow()

    private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    /** Observable discovery process state for UI consumption. */
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    /**
     * Starts mDNS discovery for USP WebSocket agents.
     *
     * Acquires a multicast lock, begins NSD browsing, and sets a 10-second timeout.
     * If already discovering, this is a no-op.
     */
    fun startDiscovery() {
        if (isDiscovering) {
            Log.d(TAG, "Discovery already in progress, ignoring start request")
            return
        }

        // Reset state for fresh scan
        resolvedEids.clear()
        _discoveredAgents.value = emptyList()
        _discoveryState.value = DiscoveryState.Scanning

        acquireMulticastLock()

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            isDiscovering = true
            Log.d(TAG, "Started discovery for $SERVICE_TYPE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            _discoveryState.value = DiscoveryState.Error(-1, "Failed to start: ${e.message}")
            releaseMulticastLock()
            return
        }

        // Start timeout countdown
        timeoutJob = coroutineScope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (_discoveredAgents.value.isEmpty()) {
                _discoveryState.value = DiscoveryState.Timeout
                Log.d(TAG, "Discovery timed out with no agents found")
            }
            stopDiscovery()
        }
    }

    /**
     * Stops the active mDNS discovery scan.
     *
     * Releases the multicast lock, cancels the timeout, and transitions state to Stopped.
     * Safe to call multiple times or when discovery is not active.
     */
    fun stopDiscovery() {
        timeoutJob?.cancel()
        timeoutJob = null

        if (!isDiscovering) {
            return
        }

        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
            Log.d(TAG, "Stopped discovery")
        } catch (e: IllegalArgumentException) {
            // Listener was not registered or already unregistered — safe to ignore
            Log.w(TAG, "stopServiceDiscovery threw: ${e.message}")
        }

        isDiscovering = false
        releaseMulticastLock()

        // Only update state if not already in a terminal state (Timeout, Error)
        if (_discoveryState.value is DiscoveryState.Scanning ||
            _discoveryState.value is DiscoveryState.Found
        ) {
            _discoveryState.value = DiscoveryState.Stopped
        }
    }

    /**
     * Clears all discovered agents and resets state to Idle.
     */
    fun reset() {
        stopDiscovery()
        resolvedEids.clear()
        _discoveredAgents.value = emptyList()
        _discoveryState.value = DiscoveryState.Idle
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NSD Discovery Listener
    // ─────────────────────────────────────────────────────────────────────────

    private val discoveryListener = object : NsdManager.DiscoveryListener {

        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "Discovery started for: $serviceType")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
            resolveService(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            // Remove agent by service name if it was already resolved
            _discoveredAgents.update { agents ->
                agents.filter { it.serviceName != serviceInfo.serviceName }
            }
            val currentCount = _discoveredAgents.value.size
            if (currentCount > 0) {
                _discoveryState.value = DiscoveryState.Found(currentCount)
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "Discovery stopped for: $serviceType")
            isDiscovering = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Start discovery failed: errorCode=$errorCode")
            _discoveryState.value = DiscoveryState.Error(
                errorCode,
                "Failed to start discovery (code $errorCode)"
            )
            isDiscovering = false
            releaseMulticastLock()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Stop discovery failed: errorCode=$errorCode")
            isDiscovering = false
            releaseMulticastLock()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Service Resolution
    // ─────────────────────────────────────────────────────────────────────────

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        try {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${info.serviceName}: errorCode=$errorCode")
                    // Don't crash or change state — just skip this service
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    Log.d(TAG, "Resolved: ${info.serviceName} -> ${info.host?.hostAddress}:${info.port}")

                    val host = info.host?.hostAddress
                    if (host == null) {
                        Log.w(TAG, "Resolved service has null host, skipping: ${info.serviceName}")
                        return
                    }

                    // Extract TXT record attributes with safe defaults
                    val eid = extractTxtAttribute(info, TXT_KEY_EID) ?: DEFAULT_EID
                    val path = extractTxtAttribute(info, TXT_KEY_PATH) ?: DEFAULT_PATH

                    // Deduplicate by endpoint ID
                    synchronized(resolvedEids) {
                        if (eid != DEFAULT_EID && eid in resolvedEids) {
                            Log.d(TAG, "Duplicate agent skipped: $eid")
                            return
                        }
                        resolvedEids.add(eid)
                    }

                    val agentInfo = AgentInfo(
                        endpointId = eid,
                        host = host,
                        port = info.port,
                        wsPath = if (path.startsWith("/")) path else "/$path",
                        serviceName = info.serviceName
                    )

                    _discoveredAgents.update { current -> current + agentInfo }
                    _discoveryState.value = DiscoveryState.Found(_discoveredAgents.value.size)

                    Log.d(TAG, "Agent added: ${agentInfo.displayName} at ${agentInfo.webSocketUrl}")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Exception resolving service ${serviceInfo.serviceName}: ${e.message}")
            // Resolution failure is non-fatal — skip and continue
        }
    }

    /**
     * Extracts a TXT record attribute value from a resolved NsdServiceInfo.
     *
     * On API 21+ the attributes are available via [NsdServiceInfo.getAttributes].
     * Returns null if the key is not present or the value cannot be decoded.
     */
    private fun extractTxtAttribute(info: NsdServiceInfo, key: String): String? {
        return try {
            val bytes = info.attributes[key]
            bytes?.let { String(it, Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract TXT attribute '$key': ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Multicast Lock Management
    // ─────────────────────────────────────────────────────────────────────────

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return

        multicastLock = wifiManager.createMulticastLock("usp_mdns_lock").apply {
            setReferenceCounted(true)
            acquire()
        }
        Log.d(TAG, "Multicast lock acquired")
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                Log.d(TAG, "Multicast lock released")
            }
        }
        multicastLock = null
    }
}
