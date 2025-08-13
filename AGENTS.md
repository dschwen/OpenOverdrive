Agent Notes

- Build APK: run `./gradlew :app:assembleInternal`.
- Output path: `app/build/outputs/apk/internal/app-internal.apk`.
- Install via adb: `adb uninstall app.openoverdrive || true && adb install -r app/build/outputs/apk/internal/app-internal.apk`.

Environment

- Java: use JDK 17 (`java -version` should show 17).
- Android SDK: `local.properties` points to `~/Android/Sdk`.
- Gradle wrapper: if `gradle/wrapper/gradle-wrapper.jar` is missing, either run `gradle wrapper --gradle-version 8.13` (requires system Gradle) or sync in Android Studio once.

Emulator

- If `/dev/kvm` is unavailable, prefer a physical device or create an ARM64 AVD, or start with `emulator -avd <name> -accel off` (slow).
