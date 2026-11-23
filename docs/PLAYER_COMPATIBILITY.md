# Player Compatibility

- **Media3 Engine:** `androidx.media3:media3-exoplayer:1.5.1` with `DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON` and `org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1` (`FfmpegAudioRenderer`).
- **LibVLC Engine:** `org.videolan.android:libvlc-all:3.6.5` via `LibVlcEngineAdapter` + `VLCVideoLayout`.
- **Diagnostics:** `PlayerManager.PlaybackDiagnostics` tracks `videoResolution`, `videoCodec`, `activeVideoDecoder`, `frameRate`, `droppedFrames`, `activeAudioDecoder`, `audioCodec`, `ttfbMs`, `rebufferCount`, and `failureCategory`.
- **Hardware Verification Note:** Live codec/container playback (H.264, HEVC, AV1, VP9, AC-3, E-AC-3, HLS, DASH, RTMP, SRT/ASS) requires testing on a physical Android device and has not been executed in this headless environment.
