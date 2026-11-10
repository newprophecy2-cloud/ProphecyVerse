# Shared Firestore Catalog schema (source-level contract)

The APK maps only fields present in the observed backend DTOs. Backend metadata remains authoritative; canonical and backend IDs remain separate. Stable document IDs are a lowercase type prefix plus the first 128 bits of SHA-256 over a stable backend identifier (or a backend-provided canonical season ID). Hashes cover metadata, not timestamps.

| Collection | Content | Document ID source | Backend data available |
|---|---|---|---|
| `shared_catalog_anime` | anime metadata | backend anime ID | search/detail DTO |
| `shared_catalog_movies` | movie metadata | backend movie ID | search/detail DTO; no fabricated season/episode rows |
| `shared_catalog_seasons` | actual season and parent | canonical season ID, otherwise parent backend ID + actual season number | nested detail DTO |
| `shared_catalog_episodes` | actual episode/number and parent | backend episode ID | nested detail / selected episode DTO |
| `shared_catalog_genres` | backend genre value | normalized genre string | detail DTO `genres` |
| `shared_catalog_categories` | reserved category records | normalized backend category key | no category-list field exists in supplied DTOs; none emitted |

Common fields: `documentId`, nullable `canonicalId`/`backendId`, `contentType`, `schemaVersion` (1), `metadataHash`, `createdAt`, `updatedAt`, `lastSyncedAt`, and `source` (`prophecyverse_backend`). Optional metadata: `title`, `description`, `posterUrl`, `bannerUrl`, `mediaType`, `releaseYear`, `genres`. Season/episode documents additionally carry actual season/episode numbers and `parentCanonicalId`/`parentBackendId` where known.

Server timestamp transforms populate created/updated/sync timestamps. `updatedAt` changes only when the metadata hash differs. A transaction checks the existing hash before writing; identical records do not create a write. Client-generated values cannot prove trusted origin or freshness. Shared data is cache-only and does not replace backend metadata in app catalog entities.

No playback URLs, source candidates, credentials, watch history, or user data are written. Room has a separate local shared metadata cache and bounded durable pending queue (maximum 500; at most 20 processed per invocation). Genre rows are emitted only for backend-provided genre names. No category values are invented.

The checked-in rules have no `shared_catalog_*` matches, so access is denied by default. No rules were changed or deployed. This source-level contract does not assert live database access; see `FIRESTORE_SECURITY_REVIEW.md`.
