# Changelog — `feature/libvlc-player-repair`

- Added `V3BackendGateway.ensureSession` and `withSessionRetry` for Home/Search session parity.
- Updated `AnimeRepository.refreshAnimeDetails` to record `discoveredBackendEpisodeIds` for V3 movies.
- Integrated `org.videolan.android:libvlc-all:3.6.5` (`PlaybackEngine`, `LibVlcEngineAdapter`, `VLCVideoLayout` in `PlayerScreen`, `PlayerManager.startLibVlcPlayback`).
- Added `videoCodec`, `activeVideoDecoder`, `frameRate`, `droppedFrames`, and `audioCodec` to `PlayerManager.PlaybackDiagnostics`.
- Tuned `SearchViewModel` debounce to `180ms` for queries `>= 2` characters.
- Configured `arm64-v8a` ABI filter, `useLegacyPackaging = true`, and R8 shrinking (`~29M` release APK).
- Added `LibVlcAndMovieRepairTest` and `docs/` audit/compatibility documentation.
