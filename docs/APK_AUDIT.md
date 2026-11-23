# APK Audit — ProphecyVerse (`feature/libvlc-player-repair`)

- **Repository:** `newprophecy2-cloud/ProphecyVerse`
- **Base Branch:** `repair/apk-v3-root-cause` (`fcb680a`)
- **Working Branch:** `feature/libvlc-player-repair` (`d4f6e30`+)
- **Backend Reference (Read-Only):** `newprophecy2-cloud/MovieBox-Tui-Prophecy-` (`prophecy-backend-v3`)
- **Stack:** Kotlin `2.2.10`, Jetpack Compose (`composeBom = 2024.09.00`), AGP `9.1.1`, Gradle `9.3.1`, `compileSdk = 36`, `minSdk = 24`, Room `2.7.0` (v15), Retrofit `2.12.0` + OkHttp `4.10.0` + Moshi `1.15.2`, Media3 `1.5.1` + `media3-ffmpeg-decoder:1.5.0+1` + VideoLAN `libvlc-all:3.6.5`.

## Key Audit Findings
1. **Home vs. Search Session Divergence:** Search minted `X-PV-Session`, while Home entries hitting cached `backendAnimeId` bypassed `/api/v1/anime/search` and sent `null` `X-PV-Session` -> `HTTP 401 SESSION_REQUIRED`.
2. **Movie Canonical Episode Discovery:** `AnimeRepository.refreshAnimeDetails` discarded `rawDtoEpisodes` for `isMovie == true`, preventing `discoveredBackendEpisodeIds` from capturing V3 movie episode IDs (`NP-E-*-0001`).
3. **Player Engine & Diagnostics:** `PlayerManager` had Media3 `ExoPlayer` + `FfmpegAudioRenderer`, but lacked `LibVLC` (`libvlc-all:3.6.5` + `VLCVideoLayout`) integration and video/audio input format & dropped-frame diagnostics.
4. **APK Size:** Packaging all 4 ABIs uncompressed caused `app-debug.apk` to reach `228M`. Adding `arm64-v8a` ABI filtering, `useLegacyPackaging = true`, and R8 shrinking reduced the release APK to `29M`.
