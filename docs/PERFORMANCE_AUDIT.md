# Performance Audit

- **Search Debounce:** Reduced from `250ms` to `180ms` for queries `>= 2` characters in `SearchViewModel.onQueryChange`, retaining `searchJob?.cancel()` and `searchRequestSequence` stale-response protection.
- **Search & Catalog Caching:** `MemoryCacheManager` + Room `SearchQuery`/`SearchResultRef` TTL cache prevents redundant network calls on repeated queries.
- **APK Binary Size:** Reduced from `238,268,608` bytes (`228M` debug) to `58,330,628` bytes (`56M` arm64 compressed debug) and `29,743,227` bytes (`29M` R8-minified signed release).
- **Runtime Device Profiling:** Startup, Home first-content, and live network latency on physical hardware remain unmeasured in this headless Linux environment.
