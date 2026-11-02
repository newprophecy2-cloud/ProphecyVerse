
## Feature E — Android volume indicator

The player’s in-app vertical volume gesture now uses `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_RAISE/ADJUST_LOWER, FLAG_SHOW_UI)`, the same native stepwise mechanism used by physical volume buttons. Android therefore owns the system volume-panel timeout and repeated adjustments behave normally. Player initialization no longer forces the device media stream to maximum, so opening a video does not mutate the user’s volume or mute state. The existing in-player volume HUD remains a short-lived player overlay and does not replace or hide the Android system indicator.

Feature E changed:

- `app/src/main/java/com/example/player/PlayerManager.kt`
- `app/src/main/java/com/example/ui/screens/PlayerScreen.kt`

The Android SDK is still unavailable in this sandbox, so device-level verification of physical buttons, OEM volume-panel dismissal, mute, and repeated gestures must be completed on an SDK-equipped device/emulator.
