# Shared Catalog Firestore security review

## Checked-in rule findings

The supplied `firestore.rules` retains its existing access checks for users, installations, watch history, watchlists, notices, analytics, version/configuration records, and admin collections. There are no matches for `shared_catalog_anime`, `shared_catalog_movies`, `shared_catalog_seasons`, `shared_catalog_episodes`, `shared_catalog_genres`, or `shared_catalog_categories`. Under Firestore's default deny, shared catalog reads and writes therefore fail unless separately authorized in the Firebase project's active rules.

The APK repository uses the existing Firebase SDKs and authenticated-user gate for writes. It permits only the six approved collection paths, maps only known metadata fields, verifies document ID/content type/schema version/hash on reads, and uses a fixed outgoing field allowlist. It does not change or deploy rules, bypass Auth, add Admin SDK credentials, or weaken personal/admin protections. The Room cache remains usable if Firebase requests fail; permission errors become terminal queue states rather than infinite retries.

## Material trust limitation

A modified authenticated APK can fabricate metadata and its own hash. Firestore rules cannot prove that an APK's document genuinely came from `https://api.prophecyverse.tech`, nor can client timestamps establish trusted freshness or provide robust server-side rate limiting. Current shared-document conflict resolution uses a transaction and deterministic ID to avoid duplicate records and skips writes for identical hashes, but a stale backend response from an authorized client may still replace different content. Only a trusted backend publisher or server-verifiable signature/revision scheme can establish origin; neither exists in the supplied contract, and no backend changes are authorized here.

## Proposed rules work (not deployed)

No permissive rules diff is included because the project owner has not established an approved writer permission model or an active deployment ruleset. If public shared reads are desired, they should be narrowly read-only for the exact catalog collections. Writes must be limited to approved catalog paths, authenticated principals, exact field names and strict types/sizes, consistent document identity/type/schema, and appropriate immutable/monotonic update checks. These constraints reduce risk but still cannot authenticate backend provenance. No blanket authenticated-client write or public write rule is acceptable.

Before production rollout, the Firebase project owner must review the active rules, decide who may publish, and use Firebase Emulator tests for public/authorized reads, authorized writes, malformed/extra fields, unauthenticated writes, unrelated paths, stale/conflicting writes, and isolation of user/admin data. No such tests or deployment were available in this task. Do not treat this review as confirmation that sharing works.
