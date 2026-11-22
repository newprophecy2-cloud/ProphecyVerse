# UI/UX Redesign Plan — Prophecy Verse V3

## 1. Design System (`com.example.ui.theme` & `com.example.ui.components`)
- **Dark-First Cinematic Palette:** Background `#0B0D12`, Surface `#141821`, Elevated Surface `#1C2230`, Primary `#8B7CFF`, Secondary `#55C8D8`, Accent `#F4B86A`, Primary Text `#F4F5FA`, Secondary Text `#A3A9B8`, Divider `#282E3B`, Error `#FF6B75`, Success `#55C995`.
- **Additive Light Theme:** Clean light surface palette preserving existing dark-first default.
- **Typography Scale:** Complete Material 3 `Typography` (`displaySmall`, `headlineMedium`, `headlineSmall`, `titleLarge`, `titleMedium`, `bodyLarge`, `bodyMedium`, `labelLarge`, `labelMedium`, `labelSmall`) supporting English & Bengali text without clipping.
- **Reusable Component System (`CommonUi.kt`):**
  - `AnimeCard`, `LandscapeAnimeCard`, `FeaturedHeroCard`
  - `SectionHeader`, `StatusPill` (`Verified`, `Manifest Validated`, `Media Probe Validated`, `Device Playback Untested`, `Unverified`, `Unavailable`), `QualityBadge`, `LanguageBadge`, `ProviderBadge`
  - `ShimmerCard`, `EmptyState`, `ErrorStateCard` (with categorized recovery action)
  - `ProphecyBottomBar` (Home, Search, Library, History, Profile — no A-Z catalog).

## 2. Screen-by-Screen Modernization
- **HomeScreen:** Cinematic featured hero banner + Continue Watching row + AniList Trending/Seasonal/Popular/Movies/K-Drama horizontal rails + shimmer skeletons.
- **SearchScreen:** Modern debounced search bar, genre/format filter chips, V3 canonical results grid, clear empty/error/retry states.
- **DetailScreen:** Immersive backdrop header, poster, canonical verified episode count (strictly `verified_episodes`, never adding `unverified_episodes`), expandable synopsis, genre pills, season selector chips (keyed by `season_id`), verified-only episode list, and source bottom sheet showing honest `playability_status` and `language_verification_status`.
- **PlayerScreen:** Media3 ExoPlayer with clean dark HUD, audio/subtitle/source bottom sheets, HTTP error classification (`403`/`404`/`429`/`5xx`), bounded retry, PiP support.
- **Library, History, Downloads, Profile, Settings:** Unified `SpaceDark` / `#0B0D12` surfaces, consistent cards, accessible touch targets (`>= 48dp`), and preserved Room + Firebase features.
