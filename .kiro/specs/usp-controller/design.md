# Design: Local TR-369 USP Controller Android PoC

## 1. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android App                               │
│                                                                  │
│  ┌──────────┐    ┌─────────────┐    ┌────────────────────────┐  │
│  │  Compose  │◄──►│  ViewModel  │◄──►│  USP Repository        │  │
│  │    UI     │    │  (StateFlow)│    │                        │  │
│  └──────────┘    └─────────────┘    │  ┌──────────────────┐  │  │
│                                      │  │ Message Builder  │  │  │
│                                      │  │ (Get/Set/Record) │  │  │
│                                      │  └────────┬─────────┘  │  │
│                                      │           │             │  │
│                                      │  ┌────────▼─────────┐  │  │
│                                      │  │  Protobuf Codec  │  │  │
│                                      │  │  (Serialize/De)  │  │  │
│                                      │  └────────┬─────────┘  │  │
│                                      │           │             │  │
│                                      │  ┌────────▼─────────┐  │  │
│                                      │  │  WebSocket MTP   │  │  │
│                                      │  │  (OkHttp Client) │  │  │
│                                      │  └────────┬─────────┘  │  │
│                                      └───────────┼─────────────┘  │
│                                                  │                │
│  ┌──────────────────┐                            │                │
│  │  mDNS Discovery  │ (NsdManager)               │                │
│  └────────┬─────────┘                            │                │
└───────────┼──────────────────────────────────────┼────────────────┘
            │                                      │
            ▼                                      ▼
   ┌─────────────────┐                  ┌─────────────────────┐
   │  DNS-SD Multicast│                  │  ONT/CPE Agent      │
   │  _usp-agt-ws.   │                  │  WebSocket Endpoint │
   │  _tcp.local.    │                  │  (USP Agent)        │
   └─────────────────┘                  └─────────────────────┘
```

---

## 2. Package Structure

```
com.uspcontroller.app/
├── di/                          # Hilt DI modules
│   └── AppModule.kt
├── data/
│   ├── discovery/
│   │   ├── MdnsDiscoveryService.kt
│   │   └── AgentInfo.kt
│   ├── transport/
│   │   ├── WebSocketMtpClient.kt
│   │   ├── ConnectionState.kt
│   │   └── MtpMessage.kt
│   ├── protobuf/
│   │   ├── UspMessageBuilder.kt
│   │   ├── UspRecordWrapper.kt
│   │   └── UspResponseParser.kt
│   └── repository/
│       └── UspRepository.kt
├── domain/
│   ├── model/
│   │   ├── DeviceMetrics.kt
│   │   ├── WifiConfig.kt
│   │   └── UspError.kt
│   └── usecase/
│       ├── PollDeviceMetricsUseCase.kt
│       └── SetWifiPassphraseUseCase.kt
├── ui/
│   ├── MainViewModel.kt
│   ├── UiState.kt
│   ├── screens/
│   │   └── DashboardScreen.kt
│   ├── components/
│   │   ├── ConnectionStatusBar.kt
│   │   ├── MetricsCard.kt
│   │   ├── WifiConfigPanel.kt
│   │   └── DiscoverySheet.kt
│   └── theme/
│       └── Theme.kt
└── UspControllerApp.kt          # Application class (Hilt entry)
```

---

## 3. Transport Layer: WebSocket MTP State Machine

### 3.1 State Diagram

```
                    ┌───────────────┐
         app start  │ DISCONNECTED  │◄──── user disconnect / fatal error
                    └───────┬───────┘
                            │ connect()
                            ▼
                    ┌───────────────┐
                    │  CONNECTING   │
                    └───────┬───────┘
                            │
               ┌────────────┼────────────────┐
               │ onOpen()   │                │ onFailure()
               ▼            │                ▼
      ┌─────────────┐      │       ┌────────────────┐
      │  CONNECTED  │      │       │     ERROR      │
      └──────┬──────┘      │       └───────┬────────┘
             │              │               │ auto-retry (if retries < max)
             │ onFailure()  │               ▼
             │ onClosed()   │      ┌────────────────┐
             └──────────────┼─────►│  RECONNECTING  │
                            │      └───────┬────────┘
                            │              │ delay(backoff)
                            │              │ then connect()
                            └──────────────┘
```

### 3.2 State Definition

```kotlin
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val agentEid: String) : ConnectionState()
    data class Reconnecting(val attempt: Int, val nextRetryMs: Long) : ConnectionState()
    data class Error(val reason: String, val recoverable: Boolean) : ConnectionState()
}
```

### 3.3 Reconnection Strategy

| Parameter | Value |
|-----------|-------|
| Initial delay | 1 second |
| Backoff multiplier | 2x |
| Max delay | 30 seconds |
| Max retries | 10 (then transition to `Error(recoverable=false)`) |
| Jitter | +/- 20% randomization |

### 3.4 WebSocket Client Design (OkHttp)

```kotlin
class WebSocketMtpClient(
    private val okHttpClient: OkHttpClient,
    private val coroutineScope: CoroutineScope
) {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<ByteArray> = _incomingMessages.asSharedFlow()

    // Pending request correlation: msgId -> CompletableDeferred<UspMsg>
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<Usp.Msg>>()

    fun connect(url: String) { /* ... */ }
    fun disconnect() { /* ... */ }
    suspend fun sendAndAwait(record: ByteArray, msgId: String, timeout: Duration = 15.seconds): Usp.Msg
    fun sendFire(record: ByteArray) // fire-and-forget variant
}
```

The OkHttp `WebSocketListener` callbacks dispatch to the coroutine scope:

- `onOpen` -> emit `Connected`, flush any queued messages.
- `onMessage(ByteString)` -> parse Record, extract msgId, complete the matching `CompletableDeferred` or emit to `incomingMessages` for unsolicited notifications.
- `onFailure` -> emit `Error` or `Reconnecting`, trigger backoff loop.
- `onClosing`/`onClosed` -> emit `Disconnected` or start reconnect if not user-initiated.

---

## 4. Protocol Buffer Integration

### 4.1 Proto Schema Files

The project includes the official BBF proto files:

- `usp-msg-1-3.proto` — Defines `Msg`, `Header`, `Body`, `Request`, `Response`, `Get`, `Set`, `GetResp`, `SetResp`, `Error`.
- `usp-record-1-3.proto` — Defines `Record`, `NoSessionContext`, `SessionContext`.

These are placed in `app/src/main/proto/` and compiled by the Protobuf Gradle plugin into Java/Kotlin-lite classes.

### 4.2 Gradle Protobuf Configuration

```kotlin
// build.gradle.kts (app module)
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-javalite:3.25.3")
}
```

### 4.3 Serialization / Deserialization Wrapper

```kotlin
object UspRecordWrapper {

    fun wrapMessage(
        msg: Usp.Msg,
        fromId: String,   // Controller EID
        toId: String      // Agent EID
    ): ByteArray {
        val record = UspRecord.Record.newBuilder()
            .setVersion("1.3")
            .setFromId(fromId)
            .setToId(toId)
            .setPayloadSecurity(UspRecord.Record.PayloadSecurity.PLAINTEXT)
            .setNoSessionContext(UspRecord.NoSessionContext.getDefaultInstance())
            .setPayload(msg.toByteString())
            .build()
        return record.toByteArray()
    }

    fun unwrapRecord(bytes: ByteArray): Pair<UspRecord.Record, Usp.Msg> {
        val record = UspRecord.Record.parseFrom(bytes)
        val msg = Usp.Msg.parseFrom(record.payload)
        return record to msg
    }
}
```

### 4.4 USP Message Builders

```kotlin
object UspMessageBuilder {

    fun buildGetRequest(paths: List<String>, msgId: String = UUID.randomUUID().toString()): Usp.Msg {
        val getMsg = Usp.Get.newBuilder()
            .addAllParamPaths(paths.map { path ->
                Usp.Get.ParamPath.newBuilder().setParamPath(path).build()
            })
            .build()

        return Usp.Msg.newBuilder()
            .setHeader(Usp.Header.newBuilder()
                .setMsgId(msgId)
                .setMsgType(Usp.Header.MsgType.GET)
                .build())
            .setBody(Usp.Body.newBuilder()
                .setRequest(Usp.Request.newBuilder()
                    .setGet(getMsg)
                    .build())
                .build())
            .build()
    }

    fun buildSetRequest(
        params: Map<String, String>,  // path -> value
        msgId: String = UUID.randomUUID().toString()
    ): Usp.Msg {
        val updateObjs = params.map { (path, value) ->
            val paramPath = path.substringBeforeLast(".")
            val paramName = path.substringAfterLast(".")

            Usp.Set.UpdateObject.newBuilder()
                .setObjPath("$paramPath.")
                .addParamSettings(
                    Usp.Set.UpdateParamSetting.newBuilder()
                        .setParam(paramName)
                        .setValue(value)
                        .setRequired(true)
                        .build()
                )
                .build()
        }

        val setMsg = Usp.Set.newBuilder()
            .setAllowPartial(false)
            .addAllUpdateObjs(updateObjs)
            .build()

        return Usp.Msg.newBuilder()
            .setHeader(Usp.Header.newBuilder()
                .setMsgId(msgId)
                .setMsgType(Usp.Header.MsgType.SET)
                .build())
            .setBody(Usp.Body.newBuilder()
                .setRequest(Usp.Request.newBuilder()
                    .setSet(setMsg)
                    .build())
                .build())
            .build()
    }
}
```

### 4.5 Response Parser

```kotlin
object UspResponseParser {

    sealed class ParsedResponse {
        data class GetResponse(val params: Map<String, String>) : ParsedResponse()
        data class SetResponse(val updatedPaths: List<String>) : ParsedResponse()
        data class UspErrorResponse(val code: Int, val message: String) : ParsedResponse()
    }

    fun parse(msg: Usp.Msg): ParsedResponse {
        return when (msg.header.msgType) {
            Usp.Header.MsgType.GET_RESP -> {
                val params = mutableMapOf<String, String>()
                msg.body.response.getResp.reqPathResultsList.forEach { result ->
                    result.resolvedPathResultsList.forEach { resolved ->
                        resolved.resultParamsList.forEach { param ->
                            params["${resolved.resolvedPath}${param.key}"] = param.value
                        }
                    }
                }
                ParsedResponse.GetResponse(params)
            }
            Usp.Header.MsgType.SET_RESP -> {
                val paths = msg.body.response.setResp.updatedObjResultsList
                    .flatMap { it.updatedInstResultsList }
                    .map { it.affectedPath }
                ParsedResponse.SetResponse(paths)
            }
            Usp.Header.MsgType.ERROR -> {
                val err = msg.body.response.error  // or msg.body.error depending on schema
                ParsedResponse.UspErrorResponse(err.errCode, err.errMsg)
            }
            else -> ParsedResponse.UspErrorResponse(-1, "Unexpected message type: ${msg.header.msgType}")
        }
    }
}
```

---

## 5. mDNS Discovery Design

### 5.1 Android NsdManager Integration

```kotlin
class MdnsDiscoveryService(private val context: Context) {

    private val _discoveredAgents = MutableStateFlow<List<AgentInfo>>(emptyList())
    val discoveredAgents: StateFlow<List<AgentInfo>> = _discoveredAgents.asStateFlow()

    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun startDiscovery() {
        nsdManager.discoverServices(
            "_usp-agt-ws._tcp.",
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener
        )
    }

    fun stopDiscovery() {
        nsdManager.stopServiceDiscovery(discoveryListener)
    }

    // Resolve found service -> extract host, port, TXT record (eid, path)
    private fun resolveService(serviceInfo: NsdServiceInfo) { /* ... */ }
}

data class AgentInfo(
    val endpointId: String,     // from TXT "eid" field
    val host: String,
    val port: Int,
    val wsPath: String,         // from TXT "path" field, default "/"
    val serviceName: String
) {
    val webSocketUrl: String get() = "ws://$host:$port$wsPath"
}
```

### 5.2 Discovery Lifecycle

1. User opens app or taps "Scan".
2. `startDiscovery()` browses for `_usp-agt-ws._tcp.local.`.
3. Each found service is resolved to obtain `AgentInfo`.
4. After 10s timeout or user selection, `stopDiscovery()` is called.
5. Selected `AgentInfo` is passed to `WebSocketMtpClient.connect(agentInfo.webSocketUrl)`.

---

## 6. Repository & Use Case Layer

### 6.1 UspRepository

The repository orchestrates the transport + protobuf layers:

```kotlin
class UspRepository(
    private val mtpClient: WebSocketMtpClient,
    private val controllerEid: String
) {
    suspend fun getParameters(agentEid: String, paths: List<String>): Result<Map<String, String>> {
        val msg = UspMessageBuilder.buildGetRequest(paths)
        val msgId = msg.header.msgId
        val record = UspRecordWrapper.wrapMessage(msg, controllerEid, agentEid)

        return try {
            val response = mtpClient.sendAndAwait(record, msgId)
            when (val parsed = UspResponseParser.parse(response)) {
                is ParsedResponse.GetResponse -> Result.success(parsed.params)
                is ParsedResponse.UspErrorResponse -> Result.failure(
                    UspException(parsed.code, parsed.message)
                )
                else -> Result.failure(IllegalStateException("Unexpected response type"))
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(UspException(-1, "Request timed out"))
        }
    }

    suspend fun setParameter(agentEid: String, path: String, value: String): Result<Unit> {
        val msg = UspMessageBuilder.buildSetRequest(mapOf(path to value))
        val msgId = msg.header.msgId
        val record = UspRecordWrapper.wrapMessage(msg, controllerEid, agentEid)

        return try {
            val response = mtpClient.sendAndAwait(record, msgId)
            when (val parsed = UspResponseParser.parse(response)) {
                is ParsedResponse.SetResponse -> Result.success(Unit)
                is ParsedResponse.UspErrorResponse -> Result.failure(
                    UspException(parsed.code, parsed.message)
                )
                else -> Result.failure(IllegalStateException("Unexpected response type"))
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(UspException(-1, "Request timed out"))
        }
    }
}
```

### 6.2 Polling Use Case

```kotlin
class PollDeviceMetricsUseCase(
    private val repository: UspRepository,
    private val agentEid: String
) {
    fun execute(intervalMs: Long = 5000L): Flow<Result<DeviceMetrics>> = flow {
        while (true) {
            val result = repository.getParameters(agentEid, listOf(
                "Device.DeviceInfo.ProcessStatus.CPUUsage",
                "Device.DeviceInfo.MemoryStatus.Total",
                "Device.DeviceInfo.MemoryStatus.Free",
                "Device.WiFi.SSID.1.SSID"
            ))
            emit(result.map { params ->
                DeviceMetrics(
                    cpuUsage = params["Device.DeviceInfo.ProcessStatus.CPUUsage"]?.toIntOrNull() ?: 0,
                    memoryTotal = params["Device.DeviceInfo.MemoryStatus.Total"]?.toLongOrNull() ?: 0,
                    memoryFree = params["Device.DeviceInfo.MemoryStatus.Free"]?.toLongOrNull() ?: 0,
                    wifiSsid = params["Device.WiFi.SSID.1.SSID"] ?: "Unknown"
                )
            })
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)
}
```

---

## 7. Jetpack Compose State Lifecycle

### 7.1 UI State Model

```kotlin
data class UiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val discoveredAgents: List<AgentInfo> = emptyList(),
    val isDiscovering: Boolean = false,
    val metrics: MetricsUiState = MetricsUiState(),
    val wifiPassphraseInput: String = "",
    val setOperationState: SetOperationState = SetOperationState.Idle,
    val errorMessage: String? = null
)

data class MetricsUiState(
    val cpuUsage: Int? = null,
    val memoryTotal: Long? = null,
    val memoryFree: Long? = null,
    val wifiSsid: String? = null,
    val isLoading: Boolean = false,
    val lastUpdated: Long = 0L
)

sealed class SetOperationState {
    object Idle : SetOperationState()
    object InProgress : SetOperationState()
    data class Success(val message: String) : SetOperationState()
    data class Failed(val error: String) : SetOperationState()
}
```

### 7.2 ViewModel

```kotlin
@HiltViewModel
class MainViewModel @Inject constructor(
    private val discoveryService: MdnsDiscoveryService,
    private val mtpClient: WebSocketMtpClient,
    private val uspRepository: UspRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        // Observe connection state changes
        viewModelScope.launch {
            mtpClient.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
                when (state) {
                    is ConnectionState.Connected -> startPolling(state.agentEid)
                    else -> stopPolling()
                }
            }
        }
    }

    fun startDiscovery() { /* ... */ }
    fun connectToAgent(agent: AgentInfo) { /* ... */ }
    fun disconnect() { /* ... */ }
    fun updatePassphraseInput(value: String) { /* ... */ }
    fun sendSetPassphrase() { /* ... */ }

    private fun startPolling(agentEid: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            PollDeviceMetricsUseCase(uspRepository, agentEid).execute().collect { result ->
                result.onSuccess { metrics ->
                    _uiState.update {
                        it.copy(metrics = MetricsUiState(
                            cpuUsage = metrics.cpuUsage,
                            memoryTotal = metrics.memoryTotal,
                            memoryFree = metrics.memoryFree,
                            wifiSsid = metrics.wifiSsid,
                            isLoading = false,
                            lastUpdated = System.currentTimeMillis()
                        ))
                    }
                }
                result.onFailure { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message)
                    }
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }
}
```

### 7.3 Compose Data Flow Diagram

```
┌────────────────────────────────────────────────────────────┐
│                     DashboardScreen                          │
│                                                             │
│  val uiState by viewModel.uiState.collectAsStateWithLife.. │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  ConnectionStatusBar(uiState.connectionState)        │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  MetricsCard(uiState.metrics)                        │   │
│  │   - CPU: ProgressBar + percentage                    │   │
│  │   - Memory: total / free with bar                    │   │
│  │   - SSID: text display                               │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  WifiConfigPanel(                                    │   │
│  │    input = uiState.wifiPassphraseInput,              │   │
│  │    onInputChange = viewModel::updatePassphraseInput, │   │
│  │    onSend = viewModel::sendSetPassphrase,            │   │
│  │    state = uiState.setOperationState                 │   │
│  │  )                                                   │   │
│  └─────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────┘
         │                          ▲
         │ user events              │ StateFlow emissions
         ▼                          │
┌─────────────────┐        ┌────────────────┐
│   MainViewModel  │───────►│  UspRepository │
│   (viewModelScope)        │  (Dispatchers. │
│                  │◄───────│    IO)         │
└─────────────────┘        └────────┬───────┘
                                    │
                            ┌───────▼────────┐
                            │ WebSocketMtp   │
                            │ Client         │
                            └────────────────┘
```

### 7.4 Lifecycle Awareness

- **Discovery**: Started/stopped with the Compose screen lifecycle via `DisposableEffect` or `LaunchedEffect`.
- **Polling**: Tied to `viewModelScope` — automatically cancelled when the ViewModel is cleared.
- **WebSocket**: Managed by the repository/DI scope. On app backgrounding (lifecycle STOP), polling pauses. On resume, polling restarts. The WebSocket connection itself persists across config changes but is closed on ViewModel clear.
- **Compose recomposition safety**: All state is collected via `collectAsStateWithLifecycle()` which respects the lifecycle and avoids leaks.

---

## 8. Network Security Configuration

For cleartext `ws://` connections in the PoC:

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

Referenced in `AndroidManifest.xml`:
```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ... >
```

---

## 9. Dependency Injection (Hilt)

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)  // Infinite for WebSocket
        .build()

    @Provides @Singleton
    fun provideWebSocketMtpClient(
        okHttpClient: OkHttpClient,
        @ApplicationScope scope: CoroutineScope
    ): WebSocketMtpClient = WebSocketMtpClient(okHttpClient, scope)

    @Provides @Singleton
    fun provideUspRepository(
        mtpClient: WebSocketMtpClient
    ): UspRepository = UspRepository(mtpClient, controllerEid = "os::usp-controller-android")

    @Provides @Singleton
    fun provideMdnsDiscoveryService(
        @ApplicationContext context: Context
    ): MdnsDiscoveryService = MdnsDiscoveryService(context)

    @Provides @Singleton @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
```

---

## 10. Error Handling Strategy

| Layer | Strategy |
|-------|----------|
| WebSocket transport | Catch `IOException`, `WebSocketException`. Trigger reconnect for recoverable errors. Emit `ConnectionState.Error` for fatal. |
| Protobuf parsing | Catch `InvalidProtocolBufferException`. Emit error to UI, do not crash. |
| USP-level errors | Parse `Usp.Error` response. Surface `errCode` + `errMsg` to user via `UiState.errorMessage`. |
| Coroutine cancellation | Cooperative cancellation; all loops check `isActive`. Timeout via `withTimeout`. |
| UI layer | `errorMessage` displayed as Snackbar. Auto-dismissed after 5s or on user action. |

---

## 11. Threading Model Summary

| Operation | Dispatcher | Mechanism |
|-----------|-----------|-----------|
| WebSocket callbacks (OkHttp) | OkHttp internal thread | Bridge to coroutine via `Channel` / `trySend` |
| Protobuf serialize/deserialize | `Dispatchers.Default` | CPU-bound, lightweight |
| mDNS callbacks (NsdManager) | Main thread (system) | Post to `Dispatchers.IO` for processing |
| Polling loop | `Dispatchers.IO` | `flow { }.flowOn(Dispatchers.IO)` |
| UI state updates | `Dispatchers.Main` | `StateFlow` collected on Main |
| ViewModel logic | `viewModelScope` (Main) | Launches child coroutines on appropriate dispatchers |
