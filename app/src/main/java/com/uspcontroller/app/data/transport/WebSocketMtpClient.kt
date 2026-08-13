package com.uspcontroller.app.data.transport

import android.util.Log
import com.uspcontroller.app.data.protobuf.UspRecordWrapper
import com.uspcontroller.app.domain.model.UspException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import usp_msg.Usp
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * WebSocket-based Message Transfer Protocol (MTP) client for USP communication.
 *
 * Manages the WebSocket connection lifecycle to a USP Agent, including:
 * - Connection establishment with USP subprotocol negotiation
 * - Automatic reconnection with exponential backoff and jitter
 * - Request-response correlation using USP message IDs
 * - Binary frame handling for Protobuf-serialized USP Records
 *
 * Thread safety: All state mutations happen through thread-safe flows and
 * ConcurrentHashMap. OkHttp callbacks are bridged to the provided [CoroutineScope].
 */
@Singleton
class WebSocketMtpClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "WebSocketMtp"
        private const val USP_SUBPROTOCOL = "v1.usp"
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private const val BACKOFF_MULTIPLIER = 2.0
        private const val JITTER_FACTOR = 0.2
        private const val MAX_RETRIES = 10
        private val DEFAULT_REQUEST_TIMEOUT = 15.seconds
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public State
    // ─────────────────────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    /** Observable connection state for UI and business logic. */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    /** Flow of incoming USP Record bytes that are NOT correlated to a pending request. */
    val incomingMessages: SharedFlow<ByteArray> = _incomingMessages.asSharedFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // Internal State
    // ─────────────────────────────────────────────────────────────────────────

    /** Active WebSocket reference (null when disconnected). */
    private var webSocket: WebSocket? = null

    /** URL of the current/last connection target. */
    private var currentUrl: String? = null

    /** The agent's Endpoint ID (set on successful connection or from caller). */
    private var agentEid: String = ""

    /** Pending request-response correlation map: msgId -> CompletableDeferred<Usp.Msg>. */
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<Usp.Msg>>()

    /** Current retry attempt counter for reconnection. */
    private var retryCount = 0

    /** Flag to distinguish user-initiated disconnect from connection loss. */
    private var isUserDisconnect = false

    /** Job managing the reconnection delay loop. */
    private var reconnectJob: Job? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Connection Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initiates a WebSocket connection to the given URL.
     *
     * @param url The WebSocket URL of the USP Agent (e.g., "ws://192.168.1.100:8080/usp").
     * @param eid The Endpoint ID of the target agent (used for state reporting).
     */
    fun connect(url: String, eid: String = "") {
        if (_connectionState.value is ConnectionState.Connected) {
            Log.d(TAG, "Already connected, disconnect first before reconnecting")
            return
        }

        // Cancel any pending reconnect
        reconnectJob?.cancel()
        reconnectJob = null

        currentUrl = url
        agentEid = eid
        isUserDisconnect = false
        _connectionState.value = ConnectionState.Connecting

        val request = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", USP_SUBPROTOCOL)
            .build()

        webSocket = okHttpClient.newWebSocket(request, webSocketListener)
        Log.d(TAG, "Connecting to $url")
    }

    /**
     * Gracefully disconnects from the USP Agent.
     *
     * Cancels all pending requests and transitions to Disconnected state.
     * No automatic reconnection will be attempted after this call.
     */
    fun disconnect() {
        isUserDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        retryCount = 0

        webSocket?.close(1000, "User disconnect")
        webSocket = null

        cancelAllPendingRequests("Connection closed by user")
        _connectionState.value = ConnectionState.Disconnected
        Log.d(TAG, "Disconnected (user-initiated)")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Request-Response Correlation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends a USP Record and awaits the correlated response message.
     *
     * The [msgId] is used to match the response back to this request.
     * The method suspends until a response arrives or the timeout expires.
     *
     * @param record Serialized USP Record bytes to send.
     * @param msgId The USP message ID for correlation (must match Header.msg_id of the response).
     * @param timeout Maximum time to wait for a response. Defaults to 15 seconds.
     * @return The parsed [Usp.Msg] response from the agent.
     * @throws UspException If the request times out or the connection is not active.
     * @throws kotlinx.coroutines.CancellationException If cancelled.
     */
    suspend fun sendAndAwait(
        record: ByteArray,
        msgId: String,
        timeout: Duration = DEFAULT_REQUEST_TIMEOUT
    ): Usp.Msg {
        if (_connectionState.value !is ConnectionState.Connected) {
            throw UspException(-1, "Cannot send: not connected")
        }

        val deferred = CompletableDeferred<Usp.Msg>()
        pendingRequests[msgId] = deferred

        try {
            val sent = webSocket?.send(record.toByteString()) ?: false
            if (!sent) {
                pendingRequests.remove(msgId)
                throw UspException(-1, "Failed to send message over WebSocket")
            }

            Log.d(TAG, "Sent request msgId=$msgId, awaiting response (timeout=${timeout})")

            return withTimeout(timeout) {
                deferred.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            pendingRequests.remove(msgId)
            throw UspException(-1, "Request timed out after $timeout (msgId=$msgId)")
        } catch (e: Exception) {
            pendingRequests.remove(msgId)
            throw e
        }
    }

    /**
     * Sends a USP Record without waiting for a response (fire-and-forget).
     *
     * @param record Serialized USP Record bytes to send.
     * @return true if the send was enqueued successfully.
     */
    fun sendFire(record: ByteArray): Boolean {
        if (_connectionState.value !is ConnectionState.Connected) {
            Log.w(TAG, "sendFire called while not connected")
            return false
        }

        val sent = webSocket?.send(record.toByteString()) ?: false
        if (!sent) {
            Log.w(TAG, "sendFire: WebSocket send returned false")
        }
        return sent
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebSocket Listener
    // ─────────────────────────────────────────────────────────────────────────

    private val webSocketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket opened: ${response.code}")
            retryCount = 0
            _connectionState.value = ConnectionState.Connected(agentEid)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d(TAG, "Received binary message: ${bytes.size} bytes")
            handleIncomingBytes(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // USP over WebSocket should use binary frames, but handle text gracefully
            Log.w(TAG, "Received unexpected text message (${text.length} chars), ignoring")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: code=$code, reason=$reason")
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: code=$code, reason=$reason")
            this@WebSocketMtpClient.webSocket = null

            if (!isUserDisconnect) {
                initiateReconnect("Connection closed (code=$code: $reason)")
            } else {
                _connectionState.value = ConnectionState.Disconnected
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}", t)
            this@WebSocketMtpClient.webSocket = null

            cancelAllPendingRequests("Connection failed: ${t.message}")

            if (!isUserDisconnect) {
                initiateReconnect("Connection failed: ${t.message}")
            } else {
                _connectionState.value = ConnectionState.Error(
                    reason = t.message ?: "Unknown error",
                    recoverable = false
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Incoming Message Handling
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Processes incoming binary data: unwraps the USP Record, extracts the message ID,
     * and either completes a pending request or emits to the unsolicited message flow.
     */
    private fun handleIncomingBytes(bytes: ByteArray) {
        try {
            val (_, msg) = UspRecordWrapper.unwrapRecord(bytes)
            val msgId = msg.header.msgId

            // Check if this response correlates to a pending request
            val deferred = pendingRequests.remove(msgId)
            if (deferred != null) {
                Log.d(TAG, "Completed pending request: msgId=$msgId")
                deferred.complete(msg)
            } else {
                // Unsolicited message (notification, etc.) — emit to shared flow
                Log.d(TAG, "Unsolicited message received: msgId=$msgId, type=${msg.header.msgType}")
                _incomingMessages.tryEmit(bytes)
            }
        } catch (e: UspException) {
            Log.e(TAG, "Failed to parse incoming message: ${e.uspMessage}")
            // Cannot correlate — emit raw bytes for potential handling
            _incomingMessages.tryEmit(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error handling incoming message", e)
            _incomingMessages.tryEmit(bytes)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reconnection Logic
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initiates the reconnection process with exponential backoff.
     *
     * Strategy:
     * - Initial delay: 1 second
     * - Multiplier: 2x per attempt
     * - Max delay: 30 seconds
     * - Jitter: +/- 20% randomization
     * - Max retries: 10 (then transitions to unrecoverable Error)
     */
    private fun initiateReconnect(reason: String) {
        if (isUserDisconnect) return

        if (retryCount >= MAX_RETRIES) {
            Log.e(TAG, "Max retries ($MAX_RETRIES) exceeded. Giving up.")
            _connectionState.value = ConnectionState.Error(
                reason = "Max reconnection attempts exceeded. Last error: $reason",
                recoverable = false
            )
            cancelAllPendingRequests("Reconnection failed permanently")
            return
        }

        val baseDelay = min(
            (INITIAL_RETRY_DELAY_MS * Math.pow(BACKOFF_MULTIPLIER, retryCount.toDouble())).toLong(),
            MAX_RETRY_DELAY_MS
        )
        // Add jitter: +/- 20%
        val jitter = (baseDelay * JITTER_FACTOR * (Random.nextDouble() * 2.0 - 1.0)).toLong()
        val actualDelay = (baseDelay + jitter).coerceAtLeast(500L)

        retryCount++
        _connectionState.value = ConnectionState.Reconnecting(
            attempt = retryCount,
            nextRetryMs = actualDelay
        )

        Log.d(TAG, "Reconnecting in ${actualDelay}ms (attempt $retryCount/$MAX_RETRIES)")

        reconnectJob = coroutineScope.launch {
            delay(actualDelay)
            currentUrl?.let { url ->
                connect(url, agentEid)
            } ?: run {
                _connectionState.value = ConnectionState.Error(
                    reason = "No URL to reconnect to",
                    recoverable = false
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Cancels all pending request deferreds with the given reason.
     */
    private fun cancelAllPendingRequests(reason: String) {
        val count = pendingRequests.size
        if (count > 0) {
            Log.d(TAG, "Cancelling $count pending requests: $reason")
            pendingRequests.forEach { (_, deferred) ->
                deferred.completeExceptionally(UspException(-1, reason))
            }
            pendingRequests.clear()
        }
    }
}
