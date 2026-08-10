# Tasks: Local TR-369 USP Controller Android PoC

## Phase 1: Project Scaffolding & Build Configuration

### Task 1.1: Create Android Project Structure
- [ ] Initialize a new Android project with package `com.uspcontroller.app`
- [ ] Set `compileSdk = 34`, `minSdk = 26`, `targetSdk = 34`
- [ ] Configure Kotlin JVM target to 17
- [ ] Create the package directory structure as defined in design.md:
  - `di/`, `data/discovery/`, `data/transport/`, `data/protobuf/`, `data/repository/`
  - `domain/model/`, `domain/usecase/`, `ui/`, `ui/screens/`, `ui/components/`, `ui/theme/`

### Task 1.2: Configure Gradle Dependencies
- [ ] Add project-level `build.gradle.kts` with:
  - AGP plugin (version 8.2+)
  - Kotlin plugin (version 1.9+)
  - Hilt plugin (`com.google.dagger.hilt.android`)
  - Protobuf plugin (`com.google.protobuf` version 0.9.4)
- [ ] Add app-level `build.gradle.kts` with:
  - Jetpack Compose BOM (`2024.02.00` or latest stable)
  - `androidx.compose.ui:ui`, `material3`, `ui-tooling-preview`
  - `androidx.lifecycle:lifecycle-viewmodel-compose`
  - `androidx.lifecycle:lifecycle-runtime-compose` (for `collectAsStateWithLifecycle`)
  - `androidx.activity:activity-compose`
  - `com.squareup.okhttp3:okhttp:4.12.0`
  - `com.google.protobuf:protobuf-javalite:3.25.3`
  - `com.google.dagger:hilt-android:2.50`
  - `com.google.dagger:hilt-compiler:2.50` (kapt)
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3`
- [ ] Verify project syncs and compiles with `./gradlew assembleDebug`

### Task 1.3: Configure Protobuf Gradle Plugin
- [ ] Apply `com.google.protobuf` plugin in app module
- [ ] Configure `protobuf {}` block:
  - Set `protoc` artifact to `com.google.protobuf:protoc:3.25.3`
  - Configure `generateProtoTasks` with `java` builtin using `lite` option
- [ ] Create directory `app/src/main/proto/`
- [ ] Add the official BBF proto files:
  - `usp-msg-1-3.proto` (defines `Msg`, `Header`, `Body`, `Get`, `Set`, `GetResp`, `SetResp`, `Error`)
  - `usp-record-1-3.proto` (defines `Record`, `NoSessionContext`)
- [ ] Verify proto compilation generates Java classes with `./gradlew generateProto` or a full build
- [ ] Confirm generated classes are importable (e.g., `import usp_msg.Usp` or the correct package)

### Task 1.4: Configure Android Manifest & Network Security
- [ ] Add permissions to `AndroidManifest.xml`:
  ```xml
  <uses-permission android:name="android.permission.INTERNET" />
  <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
  ```
- [ ] Create `res/xml/network_security_config.xml` allowing cleartext traffic:
  ```xml
  <network-security-config>
      <base-config cleartextTrafficPermitted="true">
          <trust-anchors>
              <certificates src="system" />
          </trust-anchors>
      </base-config>
  </network-security-config>
  ```
- [ ] Reference in manifest: `android:networkSecurityConfig="@xml/network_security_config"`

---

## Phase 2: Domain Models & Core Data Types

### Task 2.1: Define Domain Models
- [ ] Create `domain/model/DeviceMetrics.kt`:
  ```kotlin
  data class DeviceMetrics(
      val cpuUsage: Int,        // percentage 0-100
      val memoryTotal: Long,    // in KB
      val memoryFree: Long,     // in KB
      val wifiSsid: String
  )
  ```
- [ ] Create `domain/model/WifiConfig.kt`:
  ```kotlin
  data class WifiConfig(
      val ssid: String,
      val passphrase: String
  )
  ```
- [ ] Create `domain/model/UspError.kt`:
  ```kotlin
  data class UspException(val code: Int, val uspMessage: String) : Exception(uspMessage)
  ```

### Task 2.2: Define Transport State Types
- [ ] Create `data/transport/ConnectionState.kt` with the sealed class:
  - `Disconnected`
  - `Connecting`
  - `Connected(agentEid: String)`
  - `Reconnecting(attempt: Int, nextRetryMs: Long)`
  - `Error(reason: String, recoverable: Boolean)`
- [ ] Create `data/discovery/AgentInfo.kt`:
  ```kotlin
  data class AgentInfo(
      val endpointId: String,
      val host: String,
      val port: Int,
      val wsPath: String = "/",
      val serviceName: String
  ) {
      val webSocketUrl: String get() = "ws://$host:$port$wsPath"
  }
  ```

---

## Phase 3: Protobuf Serialization Layer

### Task 3.1: Implement USP Message Builder
- [ ] Create `data/protobuf/UspMessageBuilder.kt`
- [ ] Implement `buildGetRequest(paths: List<String>, msgId: String): Usp.Msg`
  - Construct `Get` message with `ParamPath` entries for each path
  - Set `Header` with generated UUID `msgId` and `MsgType.GET`
  - Wrap in `Body.Request`
- [ ] Implement `buildSetRequest(params: Map<String, String>, msgId: String): Usp.Msg`
  - Parse each entry into `objPath` (everything up to last dot + trailing dot) and `paramName` (last segment)
  - Construct `Set.UpdateObject` with `UpdateParamSetting` (param, value, required=true)
  - Set `allowPartial = false`
  - Set `Header` with `MsgType.SET`
- [ ] Unit test: Verify serialized `Get` message contains correct paths
- [ ] Unit test: Verify serialized `Set` message has correct obj path splitting

### Task 3.2: Implement USP Record Wrapper
- [ ] Create `data/protobuf/UspRecordWrapper.kt`
- [ ] Implement `wrapMessage(msg: Usp.Msg, fromId: String, toId: String): ByteArray`
  - Build `Record` with version "1.3", `fromId`, `toId`
  - Set `payloadSecurity = PLAINTEXT`
  - Set `noSessionContext` to default instance
  - Set `payload` to `msg.toByteString()`
  - Return `record.toByteArray()`
- [ ] Implement `unwrapRecord(bytes: ByteArray): Pair<Record, Usp.Msg>`
  - Parse `Record` from bytes
  - Parse inner `Msg` from `record.payload`
  - Return pair
- [ ] Handle `InvalidProtocolBufferException` gracefully (wrap in Result or throw domain exception)

### Task 3.3: Implement USP Response Parser
- [ ] Create `data/protobuf/UspResponseParser.kt`
- [ ] Define `ParsedResponse` sealed class:
  - `GetResponse(params: Map<String, String>)`
  - `SetResponse(updatedPaths: List<String>)`
  - `UspErrorResponse(code: Int, message: String)`
- [ ] Implement `parse(msg: Usp.Msg): ParsedResponse`
  - For `GET_RESP`: iterate `reqPathResults` -> `resolvedPathResults` -> `resultParams`, build full path map
  - For `SET_RESP`: extract `updatedObjResults` -> `updatedInstResults` -> `affectedPath`
  - For `ERROR`: extract `errCode` and `errMsg`
  - Default: return `UspErrorResponse` with "Unexpected message type"

---

## Phase 4: mDNS Discovery Service

### Task 4.1: Implement mDNS Discovery
- [ ] Create `data/discovery/MdnsDiscoveryService.kt`
- [ ] Inject `Context` (Application context via Hilt)
- [ ] Obtain `NsdManager` from system services
- [ ] Implement `startDiscovery()`:
  - Call `nsdManager.discoverServices("_usp-agt-ws._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)`
  - In `onServiceFound`: call `nsdManager.resolveService()` to get host/port/TXT
  - Extract `eid` and `path` from TXT record attributes
  - Build `AgentInfo` and add to `_discoveredAgents: MutableStateFlow<List<AgentInfo>>`
- [ ] Implement `stopDiscovery()`:
  - Call `nsdManager.stopServiceDiscovery(listener)`
  - Handle `IllegalArgumentException` if already stopped
- [ ] Expose `discoveredAgents: StateFlow<List<AgentInfo>>`
- [ ] Implement 10-second discovery timeout using coroutine `delay`
- [ ] Acquire `WifiManager.MulticastLock` before discovery, release on stop

### Task 4.2: Handle Discovery Edge Cases
- [ ] Handle duplicate service discoveries (deduplicate by endpoint ID)
- [ ] Handle resolution failures (log and skip, don't crash)
- [ ] Handle missing TXT fields (use defaults: `eid = "unknown"`, `path = "/"`)
- [ ] Emit discovery status (idle, scanning, found, timeout) for UI consumption

---

## Phase 5: WebSocket MTP Client

### Task 5.1: Implement WebSocket Client Core
- [ ] Create `data/transport/WebSocketMtpClient.kt`
- [ ] Constructor takes `OkHttpClient` and `CoroutineScope`
- [ ] Define internal state:
  - `_connectionState: MutableStateFlow<ConnectionState>`
  - `_incomingMessages: MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)`
  - `pendingRequests: ConcurrentHashMap<String, CompletableDeferred<Usp.Msg>>`
  - `webSocket: WebSocket?` (nullable reference to active socket)
  - `retryCount: Int`
  - `isUserDisconnect: Boolean` flag
- [ ] Implement OkHttp `WebSocketListener`:
  - `onOpen`: Set state to `Connected`, reset retry count
  - `onMessage(webSocket, bytes: ByteString)`: Parse Record, extract msgId, complete matching deferred or emit to `incomingMessages`
  - `onFailure`: Set state to `Error` or `Reconnecting`, launch retry coroutine
  - `onClosing`: Send close frame back, set state
  - `onClosed`: If not user-initiated, trigger reconnect

### Task 5.2: Implement Connection Lifecycle
- [ ] Implement `connect(url: String)`:
  - Set state to `Connecting`
  - Build `Request` with URL and USP subprotocol header (`Sec-WebSocket-Protocol: v1.usp`)
  - Call `okHttpClient.newWebSocket(request, listener)`
- [ ] Implement `disconnect()`:
  - Set `isUserDisconnect = true`
  - Call `webSocket?.close(1000, "User disconnect")`
  - Set state to `Disconnected`
  - Cancel all pending requests with `CancellationException`
- [ ] Implement reconnection with exponential backoff:
  - Calculate delay: `min(1000 * 2^retryCount, 30000)` + jitter
  - If `retryCount < 10`: set state to `Reconnecting(attempt, delay)`, `delay()`, then `connect()`
  - If `retryCount >= 10`: set state to `Error(recoverable=false)`

### Task 5.3: Implement Request-Response Correlation
- [ ] Implement `sendAndAwait(record: ByteArray, msgId: String, timeout: Duration): Usp.Msg`:
  - Create `CompletableDeferred<Usp.Msg>()` and store in `pendingRequests[msgId]`
  - Send `ByteString.of(*record)` via `webSocket?.send()`
  - `withTimeout(timeout) { deferred.await() }`
  - Remove from `pendingRequests` in finally block
  - Throw on timeout, propagate cancellation
- [ ] Implement `sendFire(record: ByteArray)`:
  - Send without creating a deferred (fire-and-forget)
- [ ] In `onMessage` handler:
  - Call `UspRecordWrapper.unwrapRecord(bytes)`
  - Extract `msg.header.msgId`
  - If `pendingRequests.containsKey(msgId)`: complete the deferred
  - Otherwise: emit to `incomingMessages` shared flow

---

## Phase 6: Repository & Use Cases

### Task 6.1: Implement USP Repository
- [ ] Create `data/repository/UspRepository.kt`
- [ ] Constructor takes `WebSocketMtpClient` and `controllerEid: String`
- [ ] Store connected `agentEid` (updated when connection state changes to `Connected`)
- [ ] Implement `getParameters(agentEid: String, paths: List<String>): Result<Map<String, String>>`:
  - Build `Get` message via `UspMessageBuilder`
  - Wrap in Record via `UspRecordWrapper`
  - Call `mtpClient.sendAndAwait()`
  - Parse response via `UspResponseParser`
  - Return `Result.success(params)` or `Result.failure(UspException)`
- [ ] Implement `setParameter(agentEid: String, path: String, value: String): Result<Unit>`:
  - Build `Set` message
  - Wrap in Record
  - Send and await
  - Parse response
  - Return success or failure

### Task 6.2: Implement Polling Use Case
- [ ] Create `domain/usecase/PollDeviceMetricsUseCase.kt`
- [ ] Implement `execute(intervalMs: Long = 5000L): Flow<Result<DeviceMetrics>>`:
  - Infinite loop: call `repository.getParameters()` with the 4 target paths
  - Map response params to `DeviceMetrics` data class
  - Emit result
  - `delay(intervalMs)`
  - Use `flowOn(Dispatchers.IO)`
- [ ] Handle parse errors for numeric conversion (default to 0 / "Unknown")

### Task 6.3: Implement Set Wi-Fi Passphrase Use Case
- [ ] Create `domain/usecase/SetWifiPassphraseUseCase.kt`
- [ ] Implement `execute(agentEid: String, passphrase: String): Result<Unit>`:
  - Validate passphrase length (8-63 chars for WPA2)
  - Call `repository.setParameter(agentEid, "Device.WiFi.AccessPoint.1.Security.KeyPassphrase", passphrase)`
  - Return result

---

## Phase 7: Dependency Injection

### Task 7.1: Configure Hilt
- [ ] Create `UspControllerApp.kt` with `@HiltAndroidApp` annotation
- [ ] Register in `AndroidManifest.xml` as `android:name=".UspControllerApp"`
- [ ] Add `@AndroidEntryPoint` to `MainActivity`

### Task 7.2: Create DI Module
- [ ] Create `di/AppModule.kt` with `@Module @InstallIn(SingletonComponent::class)`
- [ ] Provide `OkHttpClient` as singleton (readTimeout = 0 for WebSocket)
- [ ] Provide `CoroutineScope` (`SupervisorJob() + Dispatchers.Default`) with `@ApplicationScope` qualifier
- [ ] Provide `WebSocketMtpClient` as singleton
- [ ] Provide `UspRepository` as singleton (inject controllerEid = `"os::usp-controller-android"`)
- [ ] Provide `MdnsDiscoveryService` as singleton (inject `@ApplicationContext`)
- [ ] Create `@ApplicationScope` qualifier annotation

---

## Phase 8: ViewModel & UI State Management

### Task 8.1: Define UI State
- [ ] Create `ui/UiState.kt` with:
  - `UiState` data class (connectionState, discoveredAgents, isDiscovering, metrics, wifiPassphraseInput, setOperationState, errorMessage)
  - `MetricsUiState` data class (cpuUsage, memoryTotal, memoryFree, wifiSsid, isLoading, lastUpdated)
  - `SetOperationState` sealed class (Idle, InProgress, Success, Failed)

### Task 8.2: Implement MainViewModel
- [ ] Create `ui/MainViewModel.kt` with `@HiltViewModel`
- [ ] Inject `MdnsDiscoveryService`, `WebSocketMtpClient`, `UspRepository`
- [ ] Expose `uiState: StateFlow<UiState>`
- [ ] In `init`: collect `mtpClient.connectionState` and update UI state; start/stop polling on Connected/Disconnected transitions
- [ ] Implement `startDiscovery()`:
  - Set `isDiscovering = true`
  - Call `discoveryService.startDiscovery()`
  - Collect `discoveredAgents` into UI state
  - After 10s timeout, set `isDiscovering = false`
- [ ] Implement `connectToAgent(agent: AgentInfo)`:
  - Store agent EID
  - Call `mtpClient.connect(agent.webSocketUrl)`
- [ ] Implement `connectManual(url: String, agentEid: String)`:
  - Validate URL format
  - Call `mtpClient.connect(url)`
- [ ] Implement `disconnect()`:
  - Call `mtpClient.disconnect()`
  - Call `discoveryService.stopDiscovery()`
- [ ] Implement `updatePassphraseInput(value: String)`:
  - Update `_uiState` with new input value
- [ ] Implement `sendSetPassphrase()`:
  - Validate input (8-63 chars)
  - Set `setOperationState = InProgress`
  - Launch coroutine: call `SetWifiPassphraseUseCase`
  - On success: set state to `Success`, clear input
  - On failure: set state to `Failed(error.message)`
  - After 3s delay: reset state to `Idle`
- [ ] Implement `clearError()`:
  - Set `errorMessage = null`

---

## Phase 9: Jetpack Compose UI

### Task 9.1: Set Up Theme & Activity
- [ ] Create `ui/theme/Theme.kt` with Material3 theme (light theme sufficient for PoC)
- [ ] Create `MainActivity.kt`:
  - `@AndroidEntryPoint`
  - `setContent { UspControllerTheme { DashboardScreen() } }`

### Task 9.2: Implement Connection Status Bar
- [ ] Create `ui/components/ConnectionStatusBar.kt`
- [ ] Composable: `ConnectionStatusBar(state: ConnectionState)`
- [ ] Display:
  - Color-coded background: Green (Connected), Yellow (Connecting/Reconnecting), Red (Disconnected/Error)
  - Icon: Wi-Fi or connection icon
  - Text: State label + agent EID when connected
  - Reconnecting: show attempt count and countdown

### Task 9.3: Implement Metrics Dashboard Card
- [ ] Create `ui/components/MetricsCard.kt`
- [ ] Composable: `MetricsCard(metrics: MetricsUiState)`
- [ ] Display:
  - CPU Usage: `LinearProgressIndicator` + percentage text
  - Memory: Total and Free values in human-readable format (KB/MB), with usage bar
  - Wi-Fi SSID: Text with Wi-Fi icon
  - Loading shimmer/placeholder when `isLoading = true` or values are null
  - "Last updated" timestamp

### Task 9.4: Implement Wi-Fi Config Panel
- [ ] Create `ui/components/WifiConfigPanel.kt`
- [ ] Composable: `WifiConfigPanel(input, onInputChange, onSend, state)`
- [ ] Display:
  - `OutlinedTextField` for passphrase input (password visual transformation with toggle visibility)
  - Character count indicator (8-63 range)
  - "Send" `Button` (disabled when input invalid or operation in progress)
  - `CircularProgressIndicator` when `state = InProgress`
  - Success/error feedback inline or via Snackbar

### Task 9.5: Implement Discovery Bottom Sheet
- [ ] Create `ui/components/DiscoverySheet.kt`
- [ ] Composable: `DiscoverySheet(agents, isDiscovering, onSelect, onManualEntry)`
- [ ] Display:
  - List of discovered agents (EID, host:port)
  - "Scanning..." indicator with animation
  - Tap to select and connect
  - Manual entry section: URL text field + "Connect" button
  - Empty state: "No agents found. Try manual entry."

### Task 9.6: Assemble Dashboard Screen
- [ ] Create `ui/screens/DashboardScreen.kt`
- [ ] Composable: `DashboardScreen(viewModel: MainViewModel = hiltViewModel())`
- [ ] Collect `uiState` via `collectAsStateWithLifecycle()`
- [ ] Layout (Column with vertical scroll):
  1. `ConnectionStatusBar` at top (sticky)
  2. Connect/Disconnect button
  3. `MetricsCard` (visible only when connected)
  4. `WifiConfigPanel` (visible only when connected)
  5. `DiscoverySheet` triggered by FAB or button when disconnected
- [ ] Show `Snackbar` for errors and set-operation results via `SnackbarHostState`
- [ ] Handle lifecycle: start/stop discovery with `DisposableEffect` or user action

---

## Phase 10: Integration & Verification

### Task 10.1: End-to-End Wiring
- [ ] Verify DI graph compiles (Hilt generates components without errors)
- [ ] Verify app launches and shows Disconnected state
- [ ] Verify "Scan" triggers mDNS discovery and populates agent list (test with mDNS advertiser)
- [ ] Verify manual connect with a known WebSocket URL transitions to Connected

### Task 10.2: Build Verification
- [ ] Run `./gradlew assembleDebug` — must succeed with zero errors
- [ ] Run `./gradlew lint` — address any critical lint issues
- [ ] Verify APK installs on emulator/device (API 26+)
- [ ] Verify no `NetworkOnMainThreadException` or `StrictMode` violations

### Task 10.3: Functional Smoke Test
- [ ] Connect to a USP agent (real or mock WebSocket server)
- [ ] Send `Get` for CPU usage — verify value appears in UI
- [ ] Send `Get` for memory — verify total and free appear
- [ ] Send `Get` for SSID — verify SSID string appears
- [ ] Send `Set` for passphrase — verify success snackbar
- [ ] Disconnect agent — verify reconnection attempts and UI state transitions
- [ ] Send `Get` for invalid path — verify error is displayed

### Task 10.4: Code Cleanup & Documentation
- [ ] Add KDoc comments to all public classes and functions
- [ ] Add a brief `README.md` in the project root explaining:
  - How to build (`./gradlew assembleDebug`)
  - How to configure a test USP agent
  - Architecture overview reference to design.md
- [ ] Remove any unused imports or dead code
- [ ] Ensure consistent code formatting (ktlint or IDE formatter)

---

## Dependency Graph (Task Execution Order)

```
Phase 1 (Scaffolding)
  └──► Phase 2 (Domain Models)
         ├──► Phase 3 (Protobuf Layer)
         │       └──► Phase 5 (WebSocket MTP) ──► Phase 6 (Repository)
         └──► Phase 4 (mDNS Discovery)                    │
                                                           ▼
                                              Phase 7 (DI) ──► Phase 8 (ViewModel)
                                                                      │
                                                                      ▼
                                                            Phase 9 (Compose UI)
                                                                      │
                                                                      ▼
                                                          Phase 10 (Integration)
```

---

## Estimated Effort

| Phase | Description | Estimated Hours |
|-------|-------------|-----------------|
| 1 | Project Scaffolding & Build Config | 2-3h |
| 2 | Domain Models | 0.5h |
| 3 | Protobuf Serialization Layer | 3-4h |
| 4 | mDNS Discovery | 2-3h |
| 5 | WebSocket MTP Client | 4-5h |
| 6 | Repository & Use Cases | 2h |
| 7 | Dependency Injection | 1h |
| 8 | ViewModel & State | 2-3h |
| 9 | Compose UI | 3-4h |
| 10 | Integration & Verification | 2-3h |
| **Total** | | **~22-28h** |
