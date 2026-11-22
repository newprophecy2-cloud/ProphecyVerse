# Player Test Matrix

| Category | Target | Verification Status | Notes |
|---|---|---|---|
| **Unit / State** | `PlaybackEngineState` & `PlaybackFailureCategory` | Verified in `LibVlcAndMovieRepairTest` | Covers URL validation, failure classification, Home/Search session parity, and Movie episode persistence |
| **Container / Codec** | MP4 / MKV / WebM, H.264 / HEVC / VP9 / AV1, AAC / AC-3 / E-AC-3 | Pending physical device run | Requires Android device/emulator playback verification |
| **Protocols** | HTTP / HTTPS / HLS / DASH / RTSP / RTMP | Pending physical device run | `LibVlcEngineAdapter` allows `http`, `https`, `rtsp`, `rtmp`, `file`, `content` |
| **Subtitles** | Embedded + external SRT / ASS / WebVTT | Pending physical device run | Attached via `Media.Slave(Type.Subtitle)` |
