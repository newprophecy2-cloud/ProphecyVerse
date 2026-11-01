# ProphecyVerse `fix` branch repair report

## Repository and branch

- Repository: `https://github.com/newprophecy2-cloud/ProphecyVerse.git`
- Default branch: `main`
- Starting commit: `7bca2b47dd23a16912e080266425b121decd05eb`
- Working branch: `fix`
- The separate Web Admin project from the previously supplied ZIP was not copied into this repository because it is not present in this repository and replacing this smaller project would overwrite its architecture.

## Applied changes

- Disabled cleartext HTTP traffic in the Android manifest. The app must use HTTPS for network requests.
- Removed the publicly exposed development/source prompt and nested source ZIP from `public/assets`; added a Git ignore rule for future public ZIP archives.
- Updated the GitHub Actions debug build workflow to run on pushes to both `main` and `fix`.
- Replaced a stale Robolectric assertion (`ProphecyVerse`) with the current resource value (`Prophecy Verse`).
- Fixed the Media3 `UnsafeOptInUsageError` in `PlayerScreen` using the AndroidX lint-recognized opt-in annotation.

## Admin and Firebase assessment

A repository-wide Android search found no admin login, dashboard, navigation route, admin ViewModel/repository, admin-only Firestore write, or Android-side service-account credential. Firebase Firestore/Auth integration is currently commented out in the app build configuration; no live Firebase rules or data were changed. The repository contains no active Web Admin application to remove or modify.

## Verification

The sandbox was configured with JDK 21.0.12.1, `javac`, Android SDK platform 36, build-tools 36.0.0, and platform-tools.

- `./gradlew :app:assembleDebug`: **PASS**
- `./gradlew :app:testDebugUnitTest`: **PASS — 25 tests, 0 failures, 0 errors**
- `./gradlew :app:lintDebug`: **PASS** after the Media3 opt-in correction
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- APK application ID: `com.aistudio.prophecyverse.vykqtb`
- APK version: `1.0`, version code `1`
- APK SHA-256 from final assemble: `7f109954cde3d713e1076b2688a999396c44fd3329b951639a31979802095cd2`
- Device/emulator tests: **NOT RUN**; no Android device or emulator was available.
- Release signing: **NOT RUN**; no authorized release keystore was supplied.

Lint still reports non-blocking warnings, including dependency freshness, locale formatting, PiP transition recommendations, and deprecated test APIs. Dependency versions were not upgraded because the instruction was to avoid arbitrary Gradle/AGP/Kotlin/Java or dependency upgrades.

## Remaining limitations

The repository’s current source does not implement the version manager, APK update flow, Firebase account/history sync, cloud notifications, or separate Web Admin Panel described in the external brief. Those features were not invented or imported from the unrelated repaired ZIP. They require product/backend source and configuration that are not present in this repository.
