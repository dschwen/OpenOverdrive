Agent Notes

- Build APK: run `./gradlew :app:assembleInternal`.
- Output path: `app/build/outputs/apk/internal/app-internal.apk`.
- Install via adb: `adb uninstall de.schwen.openoverdrive || true && adb install -r app/build/outputs/apk/internal/app-internal.apk`.

Environment

- Java: use JDK 17 (`java -version` should show 17).
- Android SDK: `local.properties` points to `~/Android/Sdk`.
- Gradle wrapper: if `gradle/wrapper/gradle-wrapper.jar` is missing, either run `gradle wrapper --gradle-version 8.13` (requires system Gradle) or sync in Android Studio once.

Emulator

- If `/dev/kvm` is unavailable, prefer a physical device or create an ARM64 AVD, or start with `emulator -avd <name> -accel off` (slow).

Session Summary (2025-08)

- Variants: Added `internal` build type (signed with debug; non-testOnly) for tap-installable APKs.
- Gradle: Repaired `gradlew` launcher; wrapper jar may need to be generated/synced once.
- Permissions: Manifest includes BLUETOOTH_SCAN/CONNECT (+ legacy perms) and Location for ≤ Android 11. BLUETOOTH_SCAN is marked `neverForLocation`.
- Diagnostics: Screen to enable notifications, ping, battery, SDK mode toggle, test speed, lane left/right; shows notification count and last packet; parses and shows battery percent + mV.
- Drive UI: Reliable controls (speed slider write-on-release, Accelerate/Decelerate, Brake, Left/Right), lap marker + counter, big button grid with colors (green/orange/red/blue) and haptics (Brake is stronger). Disconnect sends stop first.
- Lap counting: Parses 0x27 as <locationId, piece, offset, speed, clockwise>; counts lap on off→on marker transition with speed >100 mm/s and 3s debounce; shows debug piece/marker.
- Battery parsing: Heuristic maps raw to percent: if 2500..5000 → treat as mV (3.3–4.2V → 0–100%), else use low byte as %.
- Discovery UI: Simplified (no known-cars, no auto-connect). Overflow menu is anchored; confirmation on forget. Localized strings (en,de,fr,es,ja,zh-CN).
- Color picker: Preset swatches incl. Silver, interactive HSV, optional two-stop gradient with live preview; gradient swatches/bars shown in list.
- Naming: Default displayName comes from advertised name; if absent, manufacturer data is parsed to map model IDs → names (Kourai, Boson, Rho, Katal, GroundShock, Skull). Drive title shows the name.

Troubleshooting

- If connect succeeds inconsistently after power-cycle: Drive now retries notifications and sends SDK mode 3× with delays on connect. Use Diagnostics to verify notifications and SDK mode if needed.
- If adb cannot see the device: ensure data-capable cable, toggle USB debugging, install udev rules (`android-sdk-platform-tools-common`), try `sudo adb devices` to test permissions.
- If outputs/apk folder is empty: build `:app:assembleInternal` or run `:app:packageInternal` explicitly; intermediates contain partial artifacts.

Next Steps / TODOs

- Persist start/finish marker per car profile; optionally direction-aware lap counting.
- Add manufacturer parser companyId guards (current logic scans all MSD entries; refine if needed).
- Extend localization to Drive/Diagnostics strings; extract remaining hardcoded text.
- Optional: vertical speed slider variant (ensure reliability when re-introduced).
- Track geometry: implement piece catalog + stitcher; add minimap prototype.
- Profiles: extend CarProfile with performance presets and stats.
