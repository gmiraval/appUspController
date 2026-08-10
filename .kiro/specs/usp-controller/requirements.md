# Requirements: Local TR-369 USP Controller Android PoC

## 1. Overview

This document defines the functional and non-functional requirements for a proof-of-concept Android application that acts as a local TR-369 USP (User Services Platform) Controller. The app discovers, connects to, and manages a secondary ONT/CPE device on the same LAN using USP over WebSockets with Protocol Buffer serialization.

---

## 2. Assumptions

| # | Assumption |
|---|-----------|
| A1 | The target ONT/CPE agent is on the same local network (Layer 2 reachable) as the Android device. |
| A2 | The ONT agent exposes a WebSocket MTP endpoint (cleartext `ws://` for PoC; TLS out of scope). |
| A3 | The ONT agent advertises itself via mDNS/DNS-SD with service type `_usp-agt-ws._tcp.local.` per BBF TR-369. |
| A4 | The ONT agent implements at minimum the `Get` and `Set` USP messages for the target data model paths. |
| A5 | No E2E security (USP Record integrity/confidentiality via TLS at record layer) is required for the PoC. The `NoSessionContext` record type is used. |
| A6 | The Android device runs API level 26+ (Android 8.0 Oreo or higher). |
| A7 | Only a single ONT agent connection is managed at a time. |
| A8 | The USP Endpoint ID of the Controller is statically configured in the app (e.g., `os::usp-controller-android`). |

---

## 3. Functional Requirements

### FR-1: Agent Discovery via mDNS/DNS-SD

| ID | Requirement |
|----|-------------|
| FR-1.1 | The app SHALL discover USP agents on the local network by browsing for DNS-SD service type `_usp-agt-ws._tcp.local.`. |
| FR-1.2 | The app SHALL resolve discovered services to extract the agent's hostname/IP, port, and TXT record fields (`path`, `eid`). |
| FR-1.3 | The app SHALL display a list of discovered agents with their Endpoint IDs and allow the user to select one for connection. |
| FR-1.4 | The app SHALL support manual entry of a WebSocket URL as a fallback when mDNS discovery fails. |
| FR-1.5 | Discovery SHALL timeout after 10 seconds if no agents are found and inform the user. |

### FR-2: WebSocket Transport (MTP)

| ID | Requirement |
|----|-------------|
| FR-2.1 | The app SHALL establish a WebSocket connection to the selected agent using the OkHttp WebSocket client. |
| FR-2.2 | The WebSocket connection SHALL use binary framing (`ByteString`) to exchange serialized USP Record Protocol Buffer messages. |
| FR-2.3 | The app SHALL support cleartext WebSocket connections (`ws://`) via Android Network Security Config. |
| FR-2.4 | The app SHALL implement connection lifecycle management: connect, reconnect (with exponential backoff up to 30s), and graceful disconnect. |
| FR-2.5 | The app SHALL expose the current connection state (Disconnected, Connecting, Connected, Reconnecting, Error) to the UI layer via observable state. |
| FR-2.6 | The app SHALL implement a request-response correlation mechanism using the USP message ID to match responses to pending requests. |
| FR-2.7 | The app SHALL timeout pending USP requests after 15 seconds and surface the timeout to the UI. |

### FR-3: USP Message Construction & Parsing (Protobuf)

| ID | Requirement |
|----|-------------|
| FR-3.1 | The app SHALL use the official Broadband Forum `.proto` schema files (`usp-msg-1-3.proto`, `usp-record-1-3.proto`) compiled via the `com.google.protobuf` Gradle plugin. |
| FR-3.2 | The app SHALL construct valid `Usp.Msg` messages of type `GET` containing one or more parameter paths. |
| FR-3.3 | The app SHALL construct valid `Usp.Msg` messages of type `SET` containing parameter path-value pairs. |
| FR-3.4 | The app SHALL wrap every `Usp.Msg` inside a `Usp.Record` envelope using `NoSessionContext` with correct `from_id` (Controller EID) and `to_id` (Agent EID). |
| FR-3.5 | The app SHALL deserialize incoming `Usp.Record` payloads, extract the inner `Usp.Msg`, and route it to the appropriate handler based on message type (GetResp, SetResp, Error). |
| FR-3.6 | The app SHALL generate unique message IDs (UUID v4) for each outgoing USP message. |

### FR-4: Data Model Interaction

| ID | Requirement |
|----|-------------|
| FR-4.1 | The app SHALL periodically poll (configurable interval, default 5s) the following read-only parameters via `Get`: |
|     | - `Device.DeviceInfo.ProcessStatus.CPUUsage` |
|     | - `Device.DeviceInfo.MemoryStatus.Total` |
|     | - `Device.DeviceInfo.MemoryStatus.Free` |
|     | - `Device.WiFi.SSID.1.SSID` |
| FR-4.2 | The app SHALL allow the user to send a `Set` message to update `Device.WiFi.AccessPoint.1.Security.KeyPassphrase`. |
| FR-4.3 | The app SHALL display success or error feedback from `SetResp` messages to the user. |
| FR-4.4 | The app SHALL handle USP Error messages gracefully, displaying the error code and message from the response. |

### FR-5: User Interface (Jetpack Compose)

| ID | Requirement |
|----|-------------|
| FR-5.1 | The app SHALL display a connection status bar showing the current MTP state (color-coded: green=Connected, yellow=Connecting/Reconnecting, red=Disconnected/Error) and the connected agent's Endpoint ID. |
| FR-5.2 | The app SHALL display a real-time monitoring dashboard showing: CPU Usage (%), Total Memory (KB), Free Memory (KB), and current Wi-Fi SSID. |
| FR-5.3 | The app SHALL provide a text input field for the new Wi-Fi passphrase and a "Send" button to issue the `Set` command. |
| FR-5.4 | The app SHALL show a loading indicator on each monitored parameter while a `Get` request is in-flight. |
| FR-5.5 | The app SHALL display a toast or snackbar notification on successful `Set` operations or on errors. |
| FR-5.6 | The app SHALL provide a "Disconnect" / "Connect" toggle button. |

---

## 4. Non-Functional Requirements

| ID | Requirement |
|----|-------------|
| NFR-1 | All network I/O (WebSocket, mDNS) SHALL execute off the main (UI) thread using Kotlin Coroutines. |
| NFR-2 | The app SHALL use `StateFlow` / `SharedFlow` to propagate state changes reactively to the Compose UI. |
| NFR-3 | The app SHALL not crash on network errors; all exceptions SHALL be caught and surfaced in the UI. |
| NFR-4 | The APK target SDK SHALL be 34 (Android 14) with minimum SDK 26 (Android 8.0). |
| NFR-5 | The app architecture SHALL follow MVVM with a clear separation: UI (Compose) -> ViewModel -> Repository/UseCase -> DataSource (WebSocket + Protobuf). |
| NFR-6 | The app SHALL declare `android.permission.INTERNET` and `android.permission.CHANGE_WIFI_MULTICAST_STATE` in the manifest. |
| NFR-7 | The project SHALL build successfully with a single `./gradlew assembleDebug` invocation. |

---

## 5. Out of Scope (PoC Boundaries)

- TLS/DTLS encryption for USP Records or WebSocket (`wss://`).
- USP Session Context (sequence IDs, segmentation, retransmission).
- Full TR-181 data model browsing or object creation/deletion (`Add`/`Delete` messages).
- USP Subscriptions or Notification handling.
- Multi-agent management.
- Authentication or access control between Controller and Agent.
- Persistent storage of configuration or connection history.

---

## 6. Acceptance Criteria

| # | Criterion | Verification Method |
|---|-----------|-------------------|
| AC-1 | App discovers at least one mDNS-advertised USP agent on the LAN within 10 seconds. | Manual test with a USP agent advertising `_usp-agt-ws._tcp.local.`. |
| AC-2 | App establishes a WebSocket connection and transitions UI state to "Connected". | Observe status bar turning green upon connection. |
| AC-3 | App sends a valid `Get` request and correctly parses/displays CPU usage percentage. | Wireshark capture of valid USP Record + visual confirmation on dashboard. |
| AC-4 | App sends a valid `Get` request and displays Total and Free memory values. | Visual confirmation on dashboard with plausible values. |
| AC-5 | App sends a valid `Get` request and displays the current Wi-Fi SSID string. | Visual confirmation matching the actual SSID configured on the agent. |
| AC-6 | App sends a valid `Set` message for KeyPassphrase and receives a successful `SetResp`. | Snackbar displays success; agent's passphrase actually changes (verified externally). |
| AC-7 | App reconnects automatically after a transient network interruption (e.g., agent restart). | Kill and restart the agent's WebSocket listener; app recovers within backoff window. |
| AC-8 | App surfaces USP error responses (e.g., invalid path) with code and message in UI. | Send a `Get` for a non-existent path and observe error display. |
| AC-9 | Project builds with `./gradlew assembleDebug` without errors. | CI or local build verification. |
