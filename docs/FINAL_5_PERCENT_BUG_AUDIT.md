# Final 5% Bug Audit

## Scope and baseline

The repository was cloned from `newprophecy2-cloud/ProphecyVerse`, branch `repair/apk-v3-root-cause`, starting at `b63c949fb1e022b5883939f1dca4a6fefaaab8e0`. The audit covered the Android Gradle configuration, Firebase messaging, Room entities/database, source cache and repository, pre-resolve planner/worker, ViewModel playback flow, Media3 integration, and download manager.

## Findings and implementation plan

1. **Foreground FCM image missing.** Root cause: `ProphecyMessagingService` built only a text notification and ignored both image fields. The implementation downloads a bounded-size HTTP(S) image on `Dispatchers.IO`, decodes it safely, and applies `NotificationCompat.BigPictureStyle`; failures fall back to text while preserving the existing channel and tap extras. Tests cover field priority, absent/invalid images, failure fallback, and tap regression through source-level and build verification.

2. **Intermittent pre-resolving.** Root cause: the existing rolling worker serialized work but did not explicitly model the fast/background windows and its planner could cross seasons. The planner now uses stable anime+season+episode identity, bounded serialized IO work, cancellation-safe generation checks, retry/backoff, and verified-only cache insertion.

3. **Unnecessary re-resolving.** Root cause: valid prepared sources were reusable only in the queue cache and duplicate callers did not share a durable state contract. Existing cache reuse and identity deduplication are preserved; expired entries are invalidated before retry and active queue work is serialized. The download path remains guarded by the active-job map.

4. **Technical error leakage.** Root cause: several UI paths interpolated exception messages directly. `UserFacingErrorMapper` now maps network, timeout, common HTTP, and unexpected failures to stable user copy; diagnostics remain in logs and technical strings are not sent to UI.

5. **Next 17 pre-resolve.** Root cause: the prior planner used a generic list and did not expose priority tiers. It now returns at most 17 same-season episodes, with explicit fast 7 and background 10 partitions; the worker processes fast items first and never launches 17 simultaneous requests.

6. **Previous 6 pre-resolve.** Root cause: no reusable planner window existed for prior episodes. The planner now exposes the previous six same-season records and keeps them separate from the forward queue so they cannot delay the immediate-next priority.

7. **Download before resolution.** Root cause: the database row was marked `DOWNLOADING` before source resolution and any resolution failure looked like a transfer failure. The lifecycle now starts at `PENDING_RESOLUTION`, transitions to `DOWNLOADING` only after a verified direct source exists, preserves the existing active-job deduplication, and reports a meaningful failure only after genuine resolution/transfer failure.

8. **Download progress accuracy.** Root cause: progress was only periodically persisted and lacked an ETA. Actual bytes read from the response stream remain the source of truth; the implementation persists the final measured byte count, publishes measured speed, and computes ETA only when both total length and speed are known. Range handling, interruption, unknown length, pause/cancel, and file finalization remain in the existing manager.

## Risks and test strategy

The main risks are signed-source expiry, Android background execution limits, server range behavior, and device-specific notification permission. Unit tests exercise pure queue planning and existing source/download contracts. Build and emulator/device verification are reported separately in the implementation report; no physical device or emulator is assumed available unless confirmed by the terminal environment.

## Codec protection

No Media3, ExoPlayer, decoder, MIME, renderer, FFmpeg, codec dependency, or playback-format code was modified.
