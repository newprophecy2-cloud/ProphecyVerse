# Regression Test Plan — Prophecy Verse V3 APK

1. **V3 DTO & Mapping Tests:**
   - Parse live-shaped V3 `/search`, `/anime/{id}`, `/seasons`, `/seasons/{id}/episodes`, `/sources`, and `/resolve` JSON via Moshi.
   - Verify `ignoreUnknown` / nullable optional fields do not crash.
2. **Global Episode Integrity Tests:**
   - Given `total_episodes: 366, verified_episodes: 366, unverified_episodes: 68`, verify displayed count is `366` (never `434`).
   - Verify `unverified` episodes are filtered out of user-facing episode lists and `SyntheticFallback` is never treated as a canonical verification status.
3. **Session Management Tests:**
   - Verify `SessionManager` captures `session_id` from `/api/v1/anime/search`, attaches `X-PV-Session` to detail/season/episode/source/resolve calls, and refreshes on `401` without infinite loops.
4. **Source & Audio Distinction Tests:**
   - Verify `playability_status` (`manifest_validated`, `media_probe_validated`, `available`) and `verification_status` (`unverified`) are preserved without promotion.
5. **Legacy Removal Verification:**
   - `grep` confirms zero active references to `api.prophecyverse.tech` or `moviebox-tui-prophecy.onrender.com` in production code.
6. **Build & Existing Suite Verification:**
   - Run `ANDROID_HOME=/root/android-sdk ./gradlew testDebugUnitTest` and `ANDROID_HOME=/root/android-sdk ./gradlew assembleDebug` and confirm APK output exists.
