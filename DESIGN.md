Open Overdrive — Design Notes

Overview
- Goal: Modern Android (API 35) app to discover, connect, and drive Anki Overdrive vehicles over BLE. Two primary surfaces: Discover/Connect and Driving Controls.
- Stack: Kotlin, Jetpack Compose, MVVM, Coroutines/Flow, DataStore. Multi-module to separate BLE, protocol, data, and features.

Modules
- app: Entry point, navigation between features (Compose Navigation).
- core-ble: BLE abstractions (scan/connect, GATT, notifications, write queue). AndroidBleClient implements the real stack; a FakeBleClient remains for UI scaffolding.
- core-protocol: Message builders/parsers for Anki vehicle protocol (UUIDs, opcodes, payloads; ≤20B GATT payloads). Exposes VehicleMsg (builders) and VehicleMsgParser (decoders).
- data: Persistence for car profiles and repository to merge scan results + stored info (colors, names, start line for lap detection).
- feature-discovery: Compose UI for permissions + scanning + connecting + color association (future).
- feature-drive: Compose UI for speed slider, accelerate, brake, lane left/right; indicators for battery and laps; session orchestration.

BLE Protocol (from drive-sdk)
- Service UUID: BE15BEEF-6186-407E-8381-0BD89C4D8DF4
- Characteristics:
  - Read/Notify: BE15BEE0-6186-407E-8381-0BD89C4D8DF4
  - Write/Command: BE15BEE1-6186-407E-8381-0BD89C4D8DF4
- Message format (per C SDK):
  - size: number of bytes of (msg_id + payload)
  - msg_id: 1 byte
  - payload: up to 18 bytes; little-endian for ints/floats
- Key opcodes:
  - 0x90: C2V_SDK_MODE (enable with flag 0x01 OVERRIDE_LOCALIZATION)
  - 0x24: C2V_SET_SPEED (int16 speed_mm_per_sec, int16 accel_mm_per_sec2, byte respect_limit)
  - 0x2C: C2V_SET_OFFSET_FROM_ROAD_CENTER (float offset_mm)
  - 0x25: C2V_CHANGE_LANE (uint16 h_speed, uint16 h_accel, float offset_mm, byte hop_intent, byte tag)
  - 0x26: C2V_CANCEL_LANE_CHANGE
  - 0x1A/0x1B: Battery request/response (uint16 battery_millivolts)
  - 0x16/0x17: Ping req/resp; 0x18/0x19 Version req/resp
  - 0x27/0x29/0x2A: Localization position/transition/intersection updates
  - 0x33: Set lights pattern (RGB and channels)
  - 0x0D: Disconnect

Protocol clarifications (from PROTOCOL-REVERSE.md)
- SDK mode + speed (V4 compat): some firmware (≥12385) expects an extra trailing byte on Set Speed. Detect version via 0x19 and opt-in when needed.
- Battery telemetry: 0x1B reports millivolts. Map 3.3–4.2 V → 0–100% for UI.
- Car status flags: 0x3F exposes onTrack, onCharger, lowBattery, chargedBattery.
- Localization details: 0x27 includes locationId, roadPieceId, lateral offset (mm), speed (mm/s) and parsing flags (reversed path). 0x29 adds transition data including wheel distances useful for finish-line heuristics.
- Lights: 0x33 sets per-channel patterns for RED/GREEN/BLUE/FRONTL/FRONTR/TAIL with simple effects (steady, fade, throb, flash). A simple RGB mode uses three steady channels.

Driving Semantics
- Speed: Typical range 0–1500 mm/s (tunable). Use accel 25,000–30,000 mm/s² for responsive feel.
- Lane change: Set current offset to 0, then issue change with hSpeed ~600 mm/s, hAccel ~8000 mm/s², offset ±44 mm (approx lane width). Car must be moving.
- Battery: Parse 0x1B as millivolts; map 3.3–4.2 V → 0–100% for display. Sample on connect and periodically (20–30s). Parse 0x3F to show charging/charged states when available.
- Laps: Heuristic based on localization updates. Allow user to “Mark Start/Finish” using current road_piece_id; increment lap on forward crossings of the marker. Track timestamps for lap time (future).

Permissions & Lifecycle
- Android 12+ (API 31+): BLUETOOTH_SCAN and BLUETOOTH_CONNECT at runtime; POST_NOTIFICATIONS for foreground session.
- Nearby (multiplayer): also request BLUETOOTH_ADVERTISE; on Android 13+ (API 33+) NEARBY_WIFI_DEVICES; some devices still require coarse/fine location for discovery.
- Pre-12: ACCESS_FINE_LOCATION required for BLE scan.
- Use a foreground service during a drive session to improve link reliability; persistent notification with car name/address and quick disconnect.

Foreground service implementation
- DriveSessionService runs as a foreground service (type: connectedDevice) with an ongoing notification.
- Started on connection from the Drive screen and automatically stops on disconnect or when leaving.
- Notification includes a Disconnect action that triggers a clean disconnect.

BLE Client Design (core-ble)
- Scanning: BluetoothLeScanner with ScanFilter on service UUID BE15BEEF... Debounce and de-duplicate results.
- Connecting: BluetoothDevice.connectGatt; request MTU (e.g., 185); discover GATT; cache read/write characteristic handles.
- Notifications: Enable on read characteristic (CCCD write); emit a Flow<ByteArray> for messages; parse upstream in core-protocol.
- Writing: Serialize writes via a queue; prefer Write Without Response (WNR) for low latency; optional Write With Response for setup messages.
- State: StateFlow<ConnectionState> to surface connection state to UI.

Protocol Layer (core-protocol)
- Builders ensure size and little-endian layout match C structs. All payloads ≤ 18 bytes.
- Parsers handle battery/versions/minimal localization for now; can be extended (position speed, lane change ACKs, road piece IDs).

Data & Persistence (data)
- CarProfile: deviceAddress, displayName, colorArgb, lastSeenName, lastConnected, startRoadPieceId.
- Storage: DataStore (Preferences; JSON-serialized list for simplicity). A Proto schema can be introduced later.
- Repository: Combines scan results with profiles; operations to upsert color/name and forget device.

UI/UX (feature modules)
- Discovery:
  - Request runtime permissions; show explanatory rationale.
  - List visible cars; show known cars with color chips (future); connect action.
  - Allow color selection and forget from list item menu (future).
- Drive (Single‑Player):
  - Speed slider; accelerate/brake buttons; lane left/right buttons.
  - Indicators: Battery, current speed, lap count and last lap time.
  - Read Battery button for manual refresh (auto polling planned).
  - Disconnect/back button; session foreground notification (future).
  - Lights: apply profile color to engine RGB on connect; quick toggles for Front/Tail in Diagnostics.

Single vs Multiplayer Drive — UX Split

Motivation
- The Drive surface now serves two different jobs: casual solo driving and synchronized multiplayer races. Their UX differs enough to warrant two dedicated screens to reduce conditionals and confusion.

Navigation Flow
- Discover → Pick car → Car Lobby (name input + actions)
  - Single Player → SinglePlayerDriveScreen(address, name)
  - Host Game → start Nearby host → Start Match → MultiPlayerDriveScreen(address, name)
  - Join Game → start Nearby client → wait for Host Start → MultiPlayerDriveScreen(address, name)

Multiplayer Lobby
- Player name is persisted (SharedPreferences) and reused across sessions.
- Host/Join starts Nearby advertising or discovery; a concise status line shows current transport state for quick diagnosis.
- Host can send Start Match with countdown and target laps; clients navigate to Drive on Start and lock controls until “Go!”. Host may cancel countdown before “Go!”.
- Target laps options (1/3/5/10); value is included in the Start event payload and reflected in Drive HUD.
- Back behavior: system back and UI Back both disconnect the current car (BLE) and stop Nearby before leaving the lobby.

SinglePlayerDriveScreen
- BLE: owns connect/handshake (auto‑connect on enter), writes and parses locally.
- Controls: full set (speed slider; Accelerate/Decelerate; Left/Right; Brake).
- Laps: manual “Mark Start/Reset” button; lap timing uses on‑device heuristics; no countdown lock.
- Session: “Disconnect” stops BLE and returns; no network state.

MultiPlayerDriveScreen
- BLE: same local BLE control as single‑player; additionally publishes telemetry (position/speed) best‑effort to host.
- Countdown & lock: controls are disabled during lobby‑coordinated countdown; enable exactly at “Go!”.
- Start marker: automatically captured at or immediately after “Go!” when the first piece is crossed (no Mark Start button).
- Match config: receives target laps from Host Start event; shows lap progress.
- Results: after finishing required laps, show ranking (place), total time, and best lap; keep updating as peers finish.
- Session: “Quit Match” stops BLE (safely), clears match state (start time, target laps), stops Nearby transport, and returns to lobby.

Additional Multiplayer Notes
- Time sync: Host periodically sends TimeSync; clients estimate host offset to align the local “Go!” time.
- Transport hygiene: stop discovery/advertising before toggling modes to avoid STATUS_ALREADY_ADVERTISING.

Rationale & Implementation Notes
- Separate composables (SinglePlayerDriveScreen vs MultiPlayerDriveScreen) reduce branching, make state ownership clearer (e.g., countdown, target laps, results), and simplify future features (e.g., items, penalties) without sprinkling multiplayer guards across single‑player.
- Common controls should live in a shared sub‑composable (DriveControls) parametrized by “enabled” and callbacks to send commands; both screens can consume it.
- NetSession provides shared flows for transport and match metadata (matchStartAtMs, targetLaps). Multiplayer screen owns listening to lap/finish events and building a lightweight “racers” map for results.
- Messages (current):
  - Event 1 StartMatch: payload [hostGoAt i64, countdownSec u8, targetLaps u8]
  - Event 2 CancelMatch
  - Event 3 LapUpdate: [laps u8, lastLapMs i64, elapsedMs i64]
  - Event 4 Finish: [laps u8, elapsedMs i64]
- Host may optionally echo results as a final summary event for clients that finish late or disconnect/reconnect.

Visual Differences Summary
- Single‑player: shows “Mark Start/Reset” and “Disconnect”.
- Multiplayer: hides Mark Start; changes “Disconnect” to “Quit Match”; shows target laps and a results list when finished.

Lap timing improvements
- Parse position (0x27) and transition (0x29) updates. Use parsing flags to detect reverse driving.
- Count laps only when crossing the marked piece in forward direction with a debounce; record last lap time for display.

Navigation and connection
- Discover tap performs BLE connect + initialization (notifications + SDK mode) and verifies by awaiting a response before navigating.
- Single‑player Drive verifies connection on entry and proceeds; if not already connected, it will connect as a fallback.
- Multiplayer: On Host Start, navigate all players to MultiPlayerDriveScreen and begin the countdown.

Connection feedback
- Discovery shows a compact “Connecting…” indicator while establishing the link and handshake.
- Drive shows ongoing connection state and battery once initialized.

Reliability & Safety
- Rate-limit commands (e.g., not more than 10 writes/sec), debounced speed slider writes.
- Lane-change cooldown and cancel; ensure non-zero forward speed before lane change.
- Reconnect strategy with exponential backoff.
- Defensive parsing; ignore malformed packets.

Testing Strategy
- Unit tests for VehicleMsg and VehicleMsgParser (build byte arrays, verify ids and fields).
- FakeBleClient for UI development; later add Android instrumented tests for BLE on a device.

Roadmap
1) Replace FakeBleClient with AndroidBleClient: scanning, connect, GATT discovery, CCCD setup.
2) Hook notifications into VehicleMsgParser; surface battery and localization to UI state.
3) Add lights control builder (0x33) and apply engine RGB from profile on connect; expose quick patterns in Diagnostics.
4) Add color association + persistence UI; enhance Discovery list.
5) Add foreground service for driving; quick actions in notification.
6) Expand localization parsing (flags, wheel distances) and add Car Status (charging) indicator.
7) Add Diagnostics panels for raw telemetry, version/status, and test commands.
8) Multiplayer: host-authoritative P2P over local transports (see Multiplayer Plan).
   - Split drive surfaces: implement SinglePlayerDriveScreen and MultiPlayerDriveScreen.
   - Extract shared controls to a DriveControls composable and gate enablement by countdown state.
   - Persist per‑match results for quick sharing and post‑race review.

Long‑Term Plan: Profiles, Telemetry, and Gameplay

- Custom Car Profiles
  - Extend CarProfile with performance characteristics: top speed, max accel/decel, preferred lane change speeds/accels, traction presets.
  - Persist per‑car stats (e.g., total distance, laps, best lap time, usage time, battery health estimates).
  - Allow multiple presets per car (e.g., “Race”, “Drift”, “Kids”), selectable from Drive.

- Multi‑Car Tracking (World Model)
  - Build a live “world” where each connected car has a state: piece index, longitudinal progress s along the track, lateral offset, speed, heading (clockwise), and timestamp.
  - Use localization messages:
    - 0x27 Location: (locationId, pieceId, offset_mm, speed_mmps, parsingFlags, laneChange ids)
    - 0x29 Transition: (pieceIdx, prevPieceIdx, offset_mm, drift, wheel distances, counters)
  - Maintain a per‑car Kalman or simple complementary filter to smooth speed/position between BLE updates.
  - Synchronize cars to a single timebase (monotonic clock) for fair interactions.

- Track Geometry Reconstruction
  - Known catalog approach (preferred): maintain a library of official Anki pieces with canonical geometry (length, angle, radius, lane offsets). As cars report piece IDs and transitions, reconstruct the loop by concatenating transforms. After one lap, close the loop and solve a small pose‑graph to minimize drift.
  - Inference fallback: if piece IDs are insufficient, infer primitive type per segment from curvature (dθ/ds) implied by offset/orientation changes over time: classify as straight vs arc; estimate arc radius by fitting centerline through samples. Use a lap to accumulate segments, then simplify to a consistent loop.
  - Orientation: keep a running SE(2) transform as pieces are added; anchor Start/Finish at (0,0,0). Handle both clockwise/counter‑clockwise assembly.
  - Output: a parametric track (centerline polyline + per‑segment arc metadata) with lane offsets; supports global coordinate queries (x, y, θ) from (piece, s) or absolute s.

- Gameplay: Weapons, Damage, Collisions
  - Hitscan (gun): when fired, trace forward along centerline from shooter’s s with spread and max range; find target cars within a lateral envelope (lane width + car width) and line‑of‑sight range. Apply damage and cooldowns.
  - Projectiles (missiles): simulate a projectile advancing along the track s(t) with speed; optionally home on target’s s with lead pursuit. Check proximity threshold for detonation (radial distance and lane proximity).
  - Collisions: detect near‑simultaneous s overlap between cars with small Δs and lateral distance below threshold; apply damage/slowdown and brief invulnerability.
  - Powerups: place items at specific s locations; on pickup, grant temporary effects (speed boost, shield). Persist seed per race.

- UI/UX for Gameplay
  - HUD overlays in Drive: per‑car health bars, ammo, crosshair/ahead indicator, minimap with dots for cars.
  - Race setup screen: choose game mode (time trial, battle), select cars, laps, items.
  - Spectator/Replay: record positions and events to replay or share.

- Networking & Multi‑Device
  - Topology: designate one phone as the host authority; peers join over a local, serverless transport.
  - Transports (no internet server):
    - Nearby Connections API (preferred): offline, high-throughput, star topology host⇄peers.
    - Wi‑Fi LAN sockets: mDNS/NSD discovery on same network; TCP for control, UDP for state.
    - Wi‑Fi Direct (P2P): host forms group; sockets over the P2P link; more setup friction.
  - Deterministic tick rate (e.g., 20–30 Hz) for consistent simulation; inputs feed into next tick; clients interpolate.
  - Time sync: host sends periodic time sync; clients estimate RTT and maintain clock offset.
  - Messages: join/leave, timeSync, input, carTelemetry (piece/offset/speed), worldState, events (laps, hits), chat/ping.
  - Authority: host resolves collisions, items, lap counts; clients control their own car via BLE but follow host directives (target speed, effects); host can clamp or override to enforce fairness.

Multiplayer Plan
- Goals: multiple phones each controlling one car locally over BLE; shared, consistent game state without an external server.
- Architecture: host‑authoritative star topology.
  - One device (Host) runs the world simulation at a fixed tick (20–30 Hz) and is the single source of truth for gameplay.
  - Each Client connects to its car over BLE and to the Host over a local transport; sends inputs and car telemetry; applies Host directives.
- Transport options (serverless):
  - Nearby Connections API (Strategy.P2P_STAR):
    - Pros: works offline, abstracts Bluetooth/Wi‑Fi, simple discovery/permissions.
    - Cons: Android‑only API, opaque link characteristics.
  - Wi‑Fi LAN + NSD/mDNS:
    - Pros: standard sockets, easier cross‑platform.
    - Cons: requires same network or hotspot; discovery varies by OEM.
  - Wi‑Fi Direct (P2P):
    - Pros: no AP required; direct peer group.
    - Cons: setup UX, permissions, and stability vary.
- Recommendation: start with Nearby Connections for a smooth offline experience; add LAN sockets as a fallback option.
- Time and latency:
  - Host issues time sync frames every ~1s; clients maintain an offset (NTP‑style) and smooth drift.
  - Budget: aim for end‑to‑end < 80 ms (input→host→peers) on local links; state tick 20–30 Hz; client interpolates/extrapolates up to 100 ms.
- Message schema and versioning:
  - Use a compact binary (e.g., CBOR or protobuf). Include a protocol version and per‑message type IDs.
  - Core messages:
    - Join: client info {name, carId?, appVersion}; server assigns peerId.
    - TimeSync: {tHost, seq}; client replies with {tClient, seq} to compute RTT.
    - Input: {peerId, throttle, laneChange, fire, tsClient}.
    - CarTelemetry: {peerId, pieceId, locationId, offsetMm, speedMmps, flags, tsClient}.
    - WorldState: {tick, perCar states, items, lap counters} (delta‑compressed; full snapshot on interval).
    - Event: {type, payload} (lap, collision, powerup, hit).
    - Chat/Ping: optional.
- Host/Client responsibilities:
  - Host: merges inputs + telemetry, advances world, resolves collisions/powerups, broadcasts WorldState and Events.
  - Client: applies Host’s target directives (e.g., max speed, slowdowns), still performs BLE control for its car; sends inputs and telemetry.
- Failure handling:
  - Reconnect on link drop; simple host re‑election (manual selection) if host leaves; resume from last full snapshot.
  - Guard against out‑of‑order/delayed packets with tick/sequence numbers; drop stale frames.
- Security/Trust: local sessions are trusted; minimal anti‑cheat (host clamps inputs, validates telemetry consistency).
- UX:
  - Lobby: Host creates/join code or visible Nearby session; Clients tap to join; show connected peers.
  - HUD: per‑car status, lap counts, simple chat/ready; host starts the match.

Implementation Steps (Multiplayer)
1) Add `core-net` module with transport abstraction and two impls: Nearby and LAN sockets.
2) Define message schema + versioning; implement encoder/decoder and tests.
3) Implement HostService: tick loop, world reducer, broadcaster; ClientService: input/telemetry sender, state applier.
4) Add Lobby UI (host/join), peer list, session lifecycle.
5) Integrate with Drive: client reads car telemetry (0x27/0x29) → sends to host; applies host target directives in control loop.
6) Field test with 2–3 phones on hotspot and home Wi‑Fi; measure RTT, loss, and smoothing.

Feasibility: Can we infer piece shape from current data?

- Yes, with caveats. We already receive piece IDs/indices and position updates (0x27/0x29). With a catalog of known pieces (length, curvature), geometry is trivial: stitch piece transforms in reported order and close the loop after 1 lap.
- If we lack a reliable catalog or IDs vary by firmware, we can infer coarse shape:
  - Straight vs curve: examine heading change across samples; near‑zero curvature implies straight; constant curvature implies circular arc.
  - Radius estimation: fit an arc to (x,y) reconstructed from offsets + incremental heading; accumulate over the segment to recover total angle.
  - Segment boundaries: use transition (0x29) events to segment samples robustly.
- Accuracy improves by combining multiple laps and applying a least‑squares adjustment (pose‑graph) to reduce drift before closing the loop.

Open Questions / Research
- Enumerate and validate all piece IDs the vehicles report; reconcile with physical SKUs.
- Verify localization rates and latency; choose a tick rate for smooth world updates.
- Determine if weapons/damage need anti‑cheat or authoritative host.
- Battery modeling: learn discharge curve vs mV for better state‑of‑charge; show health and predicted runtime.

Session Implementation Notes (2025-08)

- BLE Reliability & Handshake
  - On GATT services discovered, BLE layer enables notifications; Drive additionally retries notifications up to 3× and sends sdkMode(true) 3× with short delays to handle power-cycle cases.
  - Disconnect always issues a stop command first.

- Protocol Parsing
  - 0x27 Location parsed as <locationId, piece, offset_mm, speed_mmps, clockwise>.
  - Battery: percent heuristic (mV mapping 3.3–4.2V → 0–100% else low byte → %).

- Lap Counting
  - User marks a start piece; lap increments when transitioning from off→on marker with speed>100 mm/s and ≥3s since last lap.
  - Debug text shows current piece and marker; consider direction-aware counting.

- Discovery & Naming
  - Simplified list (no known-cars, no auto-connect). Overflow menu anchored near tap; confirmation on forget.
  - Device name: prefer manufacturer data (companyId 61374) to map model ids (e.g., Kourai, Boson, Rho, Katal, GroundShock, Skull); fall back to advertised name if it passes sanity checks to avoid garbled names.
  - Drive title uses displayName (passed via nav) instead of BT address.

- Color & UI
  - Two-stop gradient picker with HSV sliders, live preview, and preset swatches (incl. Silver). Gradient swatches/bars rendered in list.
  - Drive controls: 4-row grid (Accelerate; Left+Right; Decelerate; Brake) with color-coded buttons and haptics (stronger brake).

- Localization
  - Discovery strings localized (en, de, fr, es, ja, zh-CN) with prefixed resource keys to avoid collisions.

Future Considerations
  - Manufacturer-specific data parsing should identify the correct companyId and guard against non-Anki devices.
  - Consider persisting the per-car start marker and direction, and adding a minimap based on stitched piece geometry.
  - Optionally surface raw telemetry (piece/speed/clockwise) in a hidden debug panel.


Notes & References
- Derived from Anki drive-sdk headers:
  - Service/Characteristic UUIDs in vehicle_gatt_profile.h
  - Opcodes and message structs in protocol.h/c
  - Example sequences in examples/vehicle-tool (set_offset + change_lane, speed, battery)
