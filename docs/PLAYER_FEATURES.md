# Player Features

## Implemented
- Single-owner `LibVlcEngineAdapter` implementing `PlaybackEngine`
- `VLCVideoLayout` surface attachment/detachment in `PlayerScreen`
- Play, pause, seek, playback speed (`0.25x`–`3.0x`), audio delay, subtitle delay
- Audio track and subtitle track enumeration and selection
- External subtitle attachment via `Media.Slave`
- HTTP `User-Agent` and `Referer` header forwarding via LibVLC `Media` options
- Explicit `PlaybackEngineState` (`IDLE`, `INITIALIZING`, `RESOLVING_SOURCE`, `SOURCE_READY`, `OPENING_MEDIA`, `BUFFERING`, `PLAYING`, `PAUSED`, `SEEKING`, `ENDED`, `RETRYING`, `ERROR`, `RELEASED`)
- Structured `PlaybackFailureCategory` classification without exposing secrets or full signed URLs
- Home/Search V3 session parity (`V3BackendGateway.ensureSession` + bounded `401` retry)
- Movie (`media_type == "movie"`) canonical episode persistence and source discovery
