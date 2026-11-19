# ProphecyVerse — Unified MovieBox Provider Full-Stack App

ProphecyVerse is a single-repository full-stack web application (`React + TypeScript + Vite` frontend and `Node.js + Express + TypeScript` backend) backed exclusively by the **MovieBox Provider** for catalog discovery, details, playback resolution, and external captions, with **Firebase Auth + Firestore** for user accounts, watchlists, favorites, ratings, comments, and continue-watching progress.

---

## Architecture

- **Frontend (`src/`)**: React 19 + TypeScript + Tailwind CSS + `hls.js` + `dashjs` + Firebase Web SDK. Never communicates directly with MovieBox API hosts.
- **Backend (`backend/src/`, `server.ts`)**: Express server running on `PORT` (default `3000`) that serves `/api/*` endpoints and, in production, serves the built Vite bundle (`dist/`).
- **Stream proxy (`backend/src/services/streamProxyService.ts`)**: Signs short-lived same-origin URLs, allowlists HTTPS MovieBox CDN hostnames, forwards authorized upstream cookies and range validators server-side, and rewrites HLS/DASH manifests. Raw CDN URLs and cookies are not sent to the browser.
- **MovieBox Provider (`backend/src/providers/moviebox/`)**:
  - Server-side host rotation (`api6`, `api5`, `api4`, `api4sg`, `api3`, `api6sg`, `api.inmoviebox.com`)
  - Single-flight visitor session management (`visitor-login`)
  - Bounded TTL in-memory catalog & playback caching + in-flight request deduplication (`?refresh=1` bypass on `/api/v1/playback/:resourceId`)
  - Homepage feed, search, suggestions (`/api/v1/anime/suggest`), subject details, season episodes, collection resolutions (`/api/v1/anime/:id/resolutions`), `play-info/v2`, `resource/v2`, `resource-position/v2`, and `get-ext-captions`

---

## Required Server Environment Variables

Copy `.env.example` to `.env` on the server and configure:

- `PORT=3000`
- `NODE_ENV=production`
- `CORS_ORIGIN=https://your-domain.example`
- **MovieBox Server Credentials** (never committed to git, server-side only):
  - `MOVIEBOX_SIGNING_SECRET`: HMAC-MD5 signing key (`base64:...`, `hex:...`, `escaped:...`, `utf8:...`, or raw UTF-8 string). If omitted, the server uses the public MovieBox-Tui compatibility key so preview/local mode does not fail with `PROVIDER_AUTH_REQUIRED`; set an explicit secret in production when available.
  - `MOVIEBOX_AUTH_TOKEN`: Optional pre-issued authorized MovieBox session JWT (upstream BFF endpoints still require valid `x-tr-signature` via `MOVIEBOX_SIGNING_SECRET`).
  - `MOVIEBOX_CERT_SHA256`, `MOVIEBOX_BUILD_SALT`, `MOVIEBOX_USER_AGENT`, `MOVIEBOX_CLIENT_INFO`: Optional authorized server-side MovieBox client metadata/build header parameters.
  - `MOVIEBOX_MEDIA_HOSTS`: Comma-separated CDN hostnames allowed by the stream proxy, for example `aoneroom.com,inmoviebox.com`. Only HTTPS targets matching this list are accepted.
  - `STREAM_PROXY_SECRET`: Recommended separate random HMAC secret for proxy tokens. If intentionally omitted, the explicit `MOVIEBOX_SIGNING_SECRET` is used.
  - `STREAM_PROXY_TTL_SECONDS`: Signed URL lifetime, default `3600`.
  - `STREAM_PROXY_TIMEOUT_MS`: Upstream media request timeout, default `30000`.

> **Important**: The compatibility signing material is public protocol data from MovieBox-Tui, not a private user account token. `MOVIEBOX_AUTH_TOKEN` is never bundled and remains server-side only. Live upstream access can still be denied or rate-limited by MovieBox; the proxy does not bypass DRM, authentication, or upstream access controls—it only forwards media that the configured upstream session is authorized to access.

### Google AI Studio Preview Setup

1. Open the **Secrets** panel in Google AI Studio and add `MOVIEBOX_SIGNING_SECRET` (recommended for production; optionally add `MOVIEBOX_CERT_SHA256`, `MOVIEBOX_BUILD_SALT`, `MOVIEBOX_USER_AGENT`, `MOVIEBOX_CLIENT_INFO`, `MOVIEBOX_AUTH_TOKEN`).
2. Restart the dev server / preview container so `process.env` picks up the newly injected Secrets.
3. Verify `GET /api/health` shows `movieboxConfigured: true` and `authDiagnostics.signingSecretConfigured: true`.

---

## Local Development

```bash
npm install
npm run lint
npm run test:backend
npm run dev
```

Vite middleware is mounted by `server.ts` alongside `/api/health` and `/api/v1/*` on `http://localhost:3000`.

Playback responses use `/api/v1/stream-proxy?token=...` URLs. The proxy supports `GET`, `HEAD`, `OPTIONS`, byte ranges/seeking, subtitles, HLS manifests and DASH manifests where the upstream provides them. Keep the proxy secret and MovieBox credentials server-side; do not place them in `VITE_*` variables.

---

## Production Build & VPS Deployment

```bash
npm ci
npm run build
NODE_ENV=production node dist/server.cjs
```

### Ubuntu 24.04 VPS Setup

1. Run `sudo bash scripts/vps-setup.sh /var/www/prophecyverse`.
2. Create `/var/www/prophecyverse/.env` from `.env.example`.
3. Configure Nginx using `nginx/prophecyverse.conf.template` (replace `YOUR_DOMAIN`, link to `/etc/nginx/sites-enabled/`, run `nginx -t && systemctl reload nginx`).
4. Start with PM2:
   ```bash
   pm2 startOrReload ecosystem.config.cjs --update-env
   pm2 save
   ```
5. Automated deployment runs on pushes to `prophecyverse-website` via `.github/workflows/deploy-vps.yml` once `VPS_HOST`, `VPS_USERNAME`, `VPS_SSH_PRIVATE_KEY`, and `VPS_APP_PATH` GitHub Actions secrets are configured.
