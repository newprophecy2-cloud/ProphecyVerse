# Player Engine — LibVLC Integration

## 1. Upstream Source & Pinned Artifact
- **Official Android Source Repository:** https://github.com/videolan/vlc-android
- **Official Sample Repository:** https://code.videolan.org/videolan/libvlc-android-samples
- **LibVLC Overview:** https://www.videolan.org/vlc/libvlc.html
- **Pinned Maven Artifact:** `org.videolan.android:libvlc-all:3.6.5` (Maven Central)
- **ABIs:** `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`

## 2. Architecture
- `com.example.playback.core.PlaybackEngine` (`PlaybackModels.kt`): Engine-agnostic interface, `PlaybackRequest`, `PlaybackEngineState`, `PlaybackFailureCategory`, `EngineTrackInfo`.
- `com.example.playback.libvlc.LibVlcEngineAdapter`: Single-owner `LibVLC` + `MediaPlayer` + `VLCVideoLayout` adapter translating native `MediaPlayer.Event` callbacks into `PlaybackEngineState` and preserving `User-Agent` / `Referer` media options without logging secrets.
- `com.example.player.PlayerManager`: Owns lifecycle and exposes `startLibVlcPlayback`, `attachLibVlcVideoLayout`, `detachLibVlcVideoLayout`, and `libVlcActive: StateFlow<Boolean>` alongside existing Media3 playback & `PlaybackRecoveryCoordinator`.
- `com.example.ui.screens.PlayerScreen`: Renders `org.videolan.libvlc.util.VLCVideoLayout` when `libVlcActive` is `true` and detaches safely on disposal without leaking native resources.

## 3. Update Policy
Updates to `libvlc-all` are pinned in `gradle/libs.versions.toml` (`libvlc = "3.6.5"`) and validated via `:app:testDebugUnitTest` and APK ABI verification before release.
