# USP Controller - Android PoC

A proof-of-concept Android application that acts as a **local TR-369 USP (User Services Platform) Controller** to monitor and configure a secondary ONT/CPE device on the same local network.

## Overview

This app implements a subset of the [Broadband Forum TR-369](https://www.broadband-forum.org/technical/download/TR-369.pdf) specification, communicating with a USP Agent over WebSockets using Protocol Buffer serialization. It discovers agents via mDNS/DNS-SD and provides a real-time dashboard for device monitoring and Wi-Fi configuration.

### Target Parameters

| Parameter | Path | Access |
|-----------|------|--------|
| CPU Usage | `Device.DeviceInfo.ProcessStatus.CPUUsage` | Read |
| Memory Total | `Device.DeviceInfo.MemoryStatus.Total` | Read |
| Memory Free | `Device.DeviceInfo.MemoryStatus.Free` | Read |
| Wi-Fi SSID | `Device.WiFi.SSID.{i}.SSID` | Read |
| Wi-Fi Passphrase | `Device.WiFi.AccessPoint.{i}.Security.KeyPassphrase` | Read/Write |

---

## Architecture

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

## Transport State Machine

The WebSocket MTP client manages connection lifecycle through 5 distinct states with automatic reconnection on transient failures:

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
             │              │               │ auto-retry (retries < 10)
             │ onFailure()  │               ▼
             │ onClosed()   │      ┌────────────────┐
             └──────────────┼─────►│  RECONNECTING  │
                            │      └───────┬────────┘
                            │              │ delay(backoff)
                            │              │ then connect()
                            └──────────────┘
```

### Reconnection Parameters

| Parameter | Value |
|-----------|-------|
| Initial delay | 1 second |
| Backoff multiplier | 2x |
| Max delay | 30 seconds |
| Max retries | 10 |
| Jitter | +/- 20% |

---

## USP Message Flow

```
┌──────────────┐         ┌─────────────────┐         ┌──────────────┐
│  Controller  │         │   WebSocket     │         │  USP Agent   │
│  (This App)  │         │   Connection    │         │  (ONT/CPE)   │
└──────┬───────┘         └────────┬────────┘         └──────┬───────┘
       │                          │                          │
       │  USP Record (binary)     │                          │
       │  ┌─────────────────┐     │                          │
       │  │ from: controller│     │                          │
       │  │ to: agent       │     │                          │
       │  │ payload: Get    │     │                          │
       │  │   [param_paths] │     │                          │
       │  └─────────────────┘     │                          │
       │─────────────────────────►│─────────────────────────►│
       │                          │                          │
       │                          │  USP Record (binary)     │
       │                          │  ┌─────────────────┐     │
       │                          │  │ from: agent     │     │
       │                          │  │ to: controller  │     │
       │                          │  │ payload: GetResp│     │
       │                          │  │   [key=value]   │     │
       │                          │  └─────────────────┘     │
       │◄─────────────────────────│◄─────────────────────────│
       │                          │                          │
```

### Record Envelope Structure

Every USP message is wrapped in a `Record` envelope before transmission:

```
Record {
  version: "1.3"
  to_id: "<agent endpoint id>"
  from_id: "os::usp-controller-android"
  payload_security: PLAINTEXT
  no_session_context {
    payload: <serialized Usp.Msg bytes>
  }
}
```

---

## Compose UI State Lifecycle

```
┌────────────────────────────────────────────────────────────┐
│                     DashboardScreen                          │
│                                                             │
│  val uiState by viewModel.uiState                          │
│           .collectAsStateWithLifecycle()                    │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  ConnectionStatusBar(state)                          │   │
│  │  [Green=Connected | Yellow=Connecting | Red=Error]   │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  MetricsCard                                         │   │
│  │   CPU: ████████░░ 80%                                │   │
│  │   RAM: 512MB / 1024MB                                │   │
│  │   SSID: "MyNetwork"                                  │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  WifiConfigPanel                                     │   │
│  │   [New Passphrase: ________] [Send]                  │   │
│  └─────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────┘
         │ user events              ▲ StateFlow emissions
         ▼                          │
┌─────────────────┐        ┌────────────────┐
│   MainViewModel  │◄──────►│  UspRepository │
│  (viewModelScope)│        │  (IO Dispatcher)│
└─────────────────┘        └────────┬───────┘
                                    │
                            ┌───────▼────────┐
                            │ WebSocket MTP  │
                            │ + Protobuf     │
                            └────────────────┘
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 1.9 |
| UI | Jetpack Compose + Material3 |
| Architecture | MVVM + Clean Architecture |
| DI | Hilt |
| Networking | OkHttp 4.12 (WebSocket) |
| Serialization | Protocol Buffers (javalite 3.25) |
| Discovery | Android NsdManager (mDNS/DNS-SD) |
| Async | Kotlin Coroutines + Flow |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |

---

## Project Structure

```
com.uspcontroller.app/
├── di/                          # Hilt DI modules
├── data/
│   ├── discovery/               # mDNS/DNS-SD agent discovery
│   ├── transport/               # WebSocket MTP client & state
│   ├── protobuf/                # USP message builder/parser
│   └── repository/              # USP data repository
├── domain/
│   ├── model/                   # Data classes (DeviceMetrics, etc.)
│   └── usecase/                 # Business logic (Poll, SetWifi)
├── ui/
│   ├── screens/                 # Compose screens
│   ├── components/              # Reusable UI components
│   └── theme/                   # Material3 theme
└── UspControllerApp.kt          # Hilt application entry point
```

---

## Building

```bash
# Prerequisites: Android SDK (platform 34+), JDK 17+
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Testing with a USP Agent

For local testing, the ONT/CPE agent must:

1. **Advertise via mDNS** with service type `_usp-agt-ws._tcp.local.` and TXT record containing `eid` (Endpoint ID) and `path` (WebSocket path).
2. **Accept WebSocket connections** on the advertised port (cleartext `ws://`).
3. **Handle USP `Get` and `Set` messages** for the target TR-181 parameters.

Alternatively, use the manual connection mode by entering the WebSocket URL directly (e.g., `ws://192.168.1.100:8080/usp`).

---

## Scope & Limitations (PoC)

This is a proof-of-concept with intentional limitations:

- No TLS/DTLS encryption (cleartext only)
- No USP Session Context (uses NoSessionContext records)
- No USP Subscriptions or Notifications
- Single agent connection only
- No persistent storage
- No authentication between Controller and Agent

---

## References

- [TR-369 USP Specification](https://www.broadband-forum.org/technical/download/TR-369.pdf)
- [TR-181 Device Data Model](https://usp-data-models.broadband-forum.org/)
- [BBF USP Proto Schemas (GitHub)](https://github.com/BroadbandForum/usp)

---

## License

This project uses the official Broadband Forum USP Protocol Buffer schemas, which are licensed under the BSD-3-Clause license. See the proto files for full license text.
