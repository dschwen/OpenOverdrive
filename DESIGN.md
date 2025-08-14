Open Overdrive — Design Notes

Overview
- Goal: Modern Android (API 35) app to discover, connect, and drive Anki Overdrive vehicles over BLE. Two primary surfaces: Discover/Connect and Driving Controls.
- Stack: Kotlin, Jetpack Compose, MVVM, Coroutines/Flow, DataStore. Multi-module to separate BLE, protocol, data, and features.

Modules
- app: Entry point, navigation between features (Compose Navigation).
- core-ble: BLE abstractions (scan/connect, GATT, notifications, write queue). Provides a FakeBleClient for scaffolding; will be replaced by AndroidBleClient.
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
  - 0x1A/0x1B: Battery request/response (uint16 battery_level)
  - 0x16/0x17: Ping req/resp; 0x18/0x19 Version req/resp
  - 0x27/0x29/0x2A: Localization position/transition/intersection updates
  - 0x0D: Disconnect

Driving Semantics
- Speed: Typical range 0–1500 mm/s (tunable). Use accel 25,000–30,000 mm/s² for responsive feel.
- Lane change: Set current offset to 0, then issue change with hSpeed ~600 mm/s, hAccel ~8000 mm/s², offset ±44 mm (approx lane width). Car must be moving.
- Battery: Convert raw (0..1023) to %; sample on connect and periodically (20–30s) during drive.
- Laps: Heuristic based on localization updates. Allow user to “Mark Start/Finish” using current road_piece_id; increment lap on forward crossings of the marker. Track timestamps for lap time (future).

Permissions & Lifecycle
- Android 12+ (API 31+): BLUETOOTH_SCAN and BLUETOOTH_CONNECT at runtime; POST_NOTIFICATIONS for foreground session.
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
- Drive:
  - Speed slider; accelerate/brake buttons; lane left/right buttons.
  - Indicators: Battery, current speed, lap count and last lap time.
  - Read Battery button for manual refresh (auto polling planned).
  - Disconnect/back button; session foreground notification (future).

Lap timing improvements
- Parse position (0x27) and transition (0x29) updates. Use parsing flags to detect reverse driving.
- Count laps only when crossing the marked piece in forward direction with a debounce; record last lap time for display.

Snackbar and navigation
- When auto-connect triggers, show a snackbar indicating the target car.
- Current behavior navigates immediately after showing the snackbar; consider adding a small (e.g., 300–500 ms) delay before navigation so users can read the message more consistently. This is optional and deferred.

Auto-connect settings
- Auto-connect only on Discovery screen (default on) to avoid hijacking other screens.
- Optional short delay before navigating after the snackbar (default off).

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
3) Add color association + persistence UI; enhance Discovery list.
4) Add foreground service for driving; quick actions in notification.
5) Expand localization parsing for richer telemetry and lap timing.

Long‑Term Plan: Profiles, Telemetry, and Gameplay

- Custom Car Profiles
  - Extend CarProfile with performance characteristics: top speed, max accel/decel, preferred lane change speeds/accels, traction presets.
  - Persist per‑car stats (e.g., total distance, laps, best lap time, usage time, battery health estimates).
  - Allow multiple presets per car (e.g., “Race”, “Drift”, “Kids”), selectable from Drive.

- Multi‑Car Tracking (World Model)
  - Build a live “world” where each connected car has a state: piece index, longitudinal progress s along the track, lateral offset, speed, heading (clockwise), and timestamp.
  - Use localization messages:
    - 0x27 Location: (locationId, pieceId, offset_mm, speed_mmps, clockwise)
    - 0x29 Transition: (pieceIdx, prevPieceIdx, ...)
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
  - Optional host device runs the world model; peers connect over local network/WebRTC for inputs and views.
  - Deterministic tick rate (e.g., 20–50 Hz) for consistent simulation; BLE inputs feed into the next tick.

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


Notes & References
- Derived from Anki drive-sdk headers:
  - Service/Characteristic UUIDs in vehicle_gatt_profile.h
  - Opcodes and message structs in protocol.h/c
  - Example sequences in examples/vehicle-tool (set_offset + change_lane, speed, battery)
