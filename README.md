# OpenOverdrive

OpenOverdrive is an open‑source Android app for discovering, connecting to, and driving Overdrive‑style Bluetooth LE cars. It focuses on a reliable drive experience, rich diagnostics, and a pleasant, haptics‑forward UI — all without proprietary dependencies.

> Not affiliated with or endorsed by any original Overdrive manufacturers. Trademarks belong to their respective owners.


## Features

- Discovery: Simple device list, manual connect, forget with confirmation, anchored overflow menu.
- Drive UI: Stable controls (accelerate/decelerate, brake, left/right), speed slider writes on release, lap marker button + counter, large colored button grid with haptics.
- Diagnostics: Toggle notifications, send ping, enable SDK mode, test speed, lane left/right; shows notification count and last packet, parsed battery percent and millivolts.
- Lap counting: Parses 0x27 as `<locationId, piece, offset, speed, clockwise>`; counts lap on off→on marker transition with speed >100 mm/s and 3s debounce; shows debug piece/marker.
- Battery parsing: Heuristic maps raw to percent: if 2500..5000 treat as mV (≈3.3–4.2 V → 0–100%), else use low byte as %.
- Naming: Display name from advertised name; if absent, manufacturer data is parsed to map model IDs → names (Kourai, Boson, Rho, Katal, GroundShock, Skull). Drive title shows the name.
- Color picker: Preset swatches (incl. Silver), interactive HSV, optional two‑stop gradient with live preview; gradient swatches/bars shown in list.
- Localization: English, German, French, Spanish, Japanese, Simplified Chinese.


## Build & Install

Prereqs

- Java: JDK 17 (`java -version` should show 17).
- Android SDK: `local.properties` points to `~/Android/Sdk`.
- Gradle wrapper: Repo provides `gradlew`. If `gradle/wrapper/gradle-wrapper.jar` is missing, generate it once with a system Gradle or sync in Android Studio.

Commands

```bash
# Build the installable internal variant (non-testOnly, debug-signed)
./gradlew :app:assembleInternal

# APK path
ls app/build/outputs/apk/internal/app-internal.apk

# Install on a device/emulator
adb uninstall de.schwen.openoverdrive || true \
  && adb install -r app/build/outputs/apk/internal/app-internal.apk
```

Notes

- Variant: `internal` build type is intended for direct tap‑installable APKs.
- If `outputs/apk` is empty, ensure you built `:app:assembleInternal` (or try `:app:packageInternal`). Intermediates may exist even if final APK is missing.


## Running on Device/Emulator

- Physical device: Recommended, especially for BLE stability and performance.
- Emulator: If `/dev/kvm` isn’t available, either use a physical device, create an ARM64 AVD, or start with `emulator -avd <name> -accel off` (will be slow). BLE support on emulators is limited.


## Permissions

- Bluetooth: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (+ legacy Bluetooth perms for older Android).
- Location (for ≤ Android 11): Required by the OS to discover BLE devices on older versions. `BLUETOOTH_SCAN` is marked `neverForLocation` to avoid location derivation.


## Troubleshooting

- Connect flakiness after power‑cycle: The app retries notifications and sends SDK mode 3× with delays. Use Diagnostics to verify notifications and SDK mode if needed.
- `adb` cannot see the device: Use a data‑capable cable, toggle USB debugging, install udev rules (e.g., `android-sdk-platform-tools-common`), try `sudo adb devices` to test permissions.
- Missing wrapper JAR: Run `gradle wrapper --gradle-version 8.13` once, or open/sync in Android Studio.
- Empty outputs folder: Build `:app:assembleInternal`. If needed, run `:app:packageInternal` directly.


## Module Overview

- `app`: Android application module, activities, UI, and build types.
- `feature-discovery`: Device scanning and selection.
- `feature-drive`: Driving controls, lap UI, haptics, color picker.
- `diagnostics` (within app/feature areas): Notification toggles, ping, SDK mode, counters.
- `drive-sdk`: Abstractions for car control and higher‑level commands.
- `core-ble`: BLE transport, scanning, device/session handling.
- `core-protocol`: Protocol framing, message parsing (e.g., 0x27 location packets).
- `data`: Persistence for profiles and local state.


## Development Tips

- Keep a physical car and track nearby for realistic testing.
- Validate lap counting with deliberate passes over a single marker and varied speeds.
- Use Diagnostics first to confirm notifications + SDK mode before driving.
- Strings: Many surfaces are localized; extract remaining hardcoded text as you extend UI.


## Roadmap

- Persist start/finish marker per car profile; optionally direction‑aware lap counting.
- Add manufacturer parser companyId guards (refine MSD scanning).
- Extend localization to Drive/Diagnostics strings; extract remaining hardcoded text.
- Optional: vertical speed slider variant (ensure reliability when re‑introduced).
- Track geometry: implement piece catalog + stitcher; add minimap prototype.
- Profiles: extend `CarProfile` with performance presets and stats.


## Contributing

Issues and PRs are welcome. Please keep changes focused and consistent with the existing architecture and Kotlin/Gradle style used here. When in doubt, prefer small, reviewable commits and note any user‑visible changes.


## Acknowledgements

Thanks to the community keeping these cars alive and hackable. This project exists to make driving them easy and fun on modern Android.

