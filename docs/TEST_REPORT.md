# Test Report — ProphecyVerse (`feature/libvlc-player-repair`)

## 1. Unit & Integration Test Suite (`:app:testDebugUnitTest`)
- **Command:** `CI_UNSIGNED_RELEASE=true ANDROID_HOME=/root/android-sdk ./gradlew testDebugUnitTest assembleRelease --no-daemon`
- **Result:** `BUILD SUCCESSFUL`
- **Counts:** `40` test suites, `274` tests, `0` failures, `0` errors, `0` skipped.
- **Key Verified Flows:**
  - `V3MigrationAndIntegrityTest`: V3 DTO parsing, `CanonicalVerification` (`Verified` | `Unverified`), `verified_episodes` count enforcement (`366` vs `68` hidden), `PvSessionManager`, `V3ErrorMapper`.
  - `LibVlcAndMovieRepairTest`: Home auto-minting `X-PV-Session` + 1x `401` retry, Movie canonical episode binding (`NP-A-0673EC` -> `movie_NP-A-0673EC` -> `src-1`), and `LibVlcEngineAdapter` invalid URL rejection.

## 2. Release Build (`:app:assembleRelease`)
- **Unsigned Release APK:** `app/build/outputs/apk/release/app-release-unsigned.apk` (`29,698,432` bytes)
- **Signed Installable APK:** `dist/ProphecyVerse-v3-libvlc-arm64.apk` (`29,743,227` bytes / `28.4 MiB`, verified via `apksigner verify`, `arm64-v8a`, `useLegacyPackaging = true`, R8 minified + shrunk).

## 3. Untested on Physical Hardware
- Live device/emulator video/audio decoding (H.264/HEVC/AV1/VP9, AC-3/E-AC-3, HLS/DASH/RTMP, SRT/ASS) and device startup/memory profiling were not executed in this headless Linux environment.
