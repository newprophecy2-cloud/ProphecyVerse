import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import zlib from "zlib";
import express from "express";
import { createApiApp } from "../src/app";
import { env, loadEnv } from "../src/config/env";
import { mapPlayInfoToStreams } from "../src/providers/moviebox/mapper";
import { MovieBoxService } from "../src/services/moviebox.service";
import { PlaybackService } from "../src/services/playbackService";
import { movieBoxClient } from "../src/providers/moviebox/client";
import { cacheService } from "../src/services/cacheService";
import { isPrivateOrReservedIp, StreamProxyService } from "../src/services/streamProxyService";
import { resolveVideoSource } from "../../src/services/videoSourceResolver";
import { applyPlaybackToEpisode } from "../../src/services/movieboxApiService";
import { parseVttOrSrtCues } from "../../src/services/subtitleParser";
import { cleanUndefined, getLanguageMetadata } from "../../src/services/dbService";
import type { Episode } from "../../src/types";

test("loadEnv includes hakunaymatata.com in default media hosts and generates non-empty fallback proxy secret", () => {
  const prevProxySecret = process.env.STREAM_PROXY_SECRET;
  const prevSigningSecret = process.env.MOVIEBOX_SIGNING_SECRET;
  const prevMediaHosts = process.env.MOVIEBOX_MEDIA_HOSTS;

  try {
    delete process.env.STREAM_PROXY_SECRET;
    delete process.env.MOVIEBOX_SIGNING_SECRET;
    delete process.env.MOVIEBOX_MEDIA_HOSTS;
    const loaded = loadEnv();
    assert.ok(loaded.movieboxMediaHosts.includes("hakunaymatata.com"));
    assert.ok(loaded.movieboxMediaHosts.includes("aoneroom.com"));
    assert.ok(loaded.movieboxMediaHosts.includes("inmoviebox.com"));
    assert.ok(loaded.streamProxySecret.length >= 16);
    assert.equal(loaded.streamReferer, "");
  } finally {
    if (prevProxySecret !== undefined) process.env.STREAM_PROXY_SECRET = prevProxySecret;
    if (prevSigningSecret !== undefined) process.env.MOVIEBOX_SIGNING_SECRET = prevSigningSecret;
    if (prevMediaHosts !== undefined) process.env.MOVIEBOX_MEDIA_HOSTS = prevMediaHosts;
  }
});

test("mapPlayInfoToStreams prefers valid direct index_web.mpd URL over signCookie policy index.mpd fallback", () => {
  const prefixB64 = Buffer.from("https://sbcdn3.hakunaymatata.com/dash/999/", "utf8").toString(
    "base64url"
  );
  const streams = mapPlayInfoToStreams({
    data: {
      streams: [
        {
          id: "dash-1",
          format: "DASH",
          resolutions: "1080,720",
          codecName: "hevc",
          url: "https://sbcdn3.hakunaymatata.com/dash/999/index_web.mpd",
          signCookie: `Edge-Cache-Cookie=urlprefix=${prefixB64}:Expires=999999`,
        },
        {
          id: "dash-deprecated",
          format: "DASH",
          resolutions: "720",
          url: "https://macdn.aoneroom.com/other/1c7de0bd3393702d9191801f15f88f8d.mp4",
          signCookie: `Edge-Cache-Cookie=urlprefix=${prefixB64}:Expires=999999`,
        },
      ],
    },
  });

  const firstDirect = streams.find((s) => s.id.startsWith("dash-1"));
  assert.equal(firstDirect?.url, "https://sbcdn3.hakunaymatata.com/dash/999/index_web.mpd");
  const recoveredPolicy = streams.find((s) => s.id.startsWith("dash-deprecated"));
  assert.equal(recoveredPolicy?.url, "https://sbcdn3.hakunaymatata.com/dash/999/index.mpd");
});

test("MovieBoxService invalidates expired static MOVIEBOX_AUTH_TOKEN on 401 and refreshes via visitor-login", async () => {
  const snapshot = { ...env };
  const makeJwt = (uid: string) => {
    const exp = Math.floor(Date.now() / 1000) + 3600;
    const payload = Buffer.from(JSON.stringify({ userId: uid, exp }), "utf8").toString("base64url");
    return `hdr.${payload}.sig`;
  };

  let loginCalls = 0;
  let searchCalls = 0;
  const seenAuthHeaders: string[] = [];
  const staleStaticToken = makeJwt("stale-static");
  const freshToken = makeJwt("fresh-visitor");
  const mockApi = http.createServer((req, res) => {
    res.setHeader("Content-Type", "application/json");
    if (req.url?.startsWith("/wefeed-mobile-bff/user-api/visitor-login")) {
      loginCalls += 1;
      res.end(JSON.stringify({ code: 0, data: { token: freshToken, uid: "fresh" } }));
      return;
    }
    if (req.url?.startsWith("/wefeed-mobile-bff/subject-api/search/v2")) {
      searchCalls += 1;
      const authHeader = String(req.headers.authorization || "");
      seenAuthHeaders.push(authHeader);
      if (authHeader.includes(staleStaticToken)) {
        res.statusCode = 401;
        res.end(JSON.stringify({ code: 401, message: "Static token expired" }));
        return;
      }
      res.end(
        JSON.stringify({
          code: 0,
          data: {
            results: [{ subjects: [{ subjectId: "1", title: "Refreshed Title", subjectType: 1 }] }],
            pager: { hasMore: false, totalCount: 1 },
          },
        })
      );
      return;
    }
    res.statusCode = 404;
    res.end("{}");
  });

  await new Promise<void>((resolve) => mockApi.listen(0, "127.0.0.1", resolve));
  const addr = mockApi.address();
  assert.ok(addr && typeof addr === "object");

  try {
    env.movieboxSigningSecret = "utf8:test-secret";
    env.movieboxAuthToken = staleStaticToken;
    const svc = new MovieBoxService([`http://127.0.0.1:${addr.port}`]);
    const result = await svc.search("refreshed");
    assert.equal(loginCalls, 1);
    assert.equal(searchCalls, 2);
    assert.equal(seenAuthHeaders[0], `Bearer ${staleStaticToken}`);
    assert.equal(seenAuthHeaders[1], `Bearer ${freshToken}`);
    assert.equal(result.results[0].subjects[0].title, "Refreshed Title");
  } finally {
    Object.assign(env, snapshot);
    await new Promise<void>((resolve, reject) =>
      mockApi.close((err) => (err ? reject(err) : resolve()))
    );
  }
});

test("PlaybackService throws explicit UPSTREAM_UNAVAILABLE error naming blocked CDN host instead of empty array", async () => {
  const origGetDetails = movieBoxClient.getSubjectDetails.bind(movieBoxClient);
  const origGetPlayInfo = movieBoxClient.getPlayInfo.bind(movieBoxClient);
  const origGetResources = movieBoxClient.getEpisodeResourcesV2.bind(movieBoxClient);
  const origGetCaptions = movieBoxClient.getExtCaptions.bind(movieBoxClient);
  const snapshot = { ...env };
  cacheService.clear();

  try {
    env.movieboxMockMode = false;
    env.movieboxMediaHosts = ["aoneroom.com"]; // exclude untrusted.cdn.example
    movieBoxClient.getSubjectDetails = async () => ({ subject: { subjectId: "99", title: "Test", subjectType: 1 } });
    movieBoxClient.getPlayInfo = async () => ({
      streams: [
        {
          id: "s1",
          format: "MP4",
          resolutions: "720",
          url: "https://untrusted.cdn.example/video.mp4",
        },
      ],
    });
    movieBoxClient.getEpisodeResourcesV2 = async () => ({ list: [] });
    movieBoxClient.getExtCaptions = async () => ({ extCaptions: [] });

    const svc = new PlaybackService();
    await assert.rejects(
      () => svc.getPlayback("99", 0, 0, true),
      (err: any) =>
        err?.code === "UPSTREAM_UNAVAILABLE" &&
        err?.statusCode === 502 &&
        String(err?.message).includes("untrusted.cdn.example")
    );
  } finally {
    movieBoxClient.getSubjectDetails = origGetDetails;
    movieBoxClient.getPlayInfo = origGetPlayInfo;
    movieBoxClient.getEpisodeResourcesV2 = origGetResources;
    movieBoxClient.getExtCaptions = origGetCaptions;
    Object.assign(env, snapshot);
    cacheService.clear();
  }
});

test("MOVIEBOX_MOCK_MODE=true exposes mock catalog, details, playback, subtitles, Range MP4, and health status", async () => {
  const snapshot = { ...env };
  cacheService.clear();
  env.movieboxMockMode = true;
  env.movieboxSigningSecret = "";
  env.movieboxAuthToken = "";

  const app = createApiApp();
  const server = http.createServer(app);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const addr = server.address();
  assert.ok(addr && typeof addr === "object");
  const baseUrl = `http://127.0.0.1:${addr.port}`;

  try {
    const health = (await (await fetch(`${baseUrl}/api/health`)).json()) as any;
    assert.equal(health.data.movieboxConfigured, true);
    assert.equal(health.data.mockMode, true);

    const search = (await (await fetch(`${baseUrl}/api/v1/anime/search?q=Neon`)).json()) as any;
    assert.ok(search.data.items.length >= 1);
    const id = search.data.items[0].id;

    const details = (await (await fetch(`${baseUrl}/api/v1/anime/${id}`)).json()) as any;
    assert.ok(details.data.seasons.length >= 1);

    const playback = (await (
      await fetch(`${baseUrl}/api/v1/playback/${id}?se=1&ep=1`)
    ).json()) as any;
    assert.ok(playback.data.streams.length >= 2);
    const hlsUrl = playback.data.streams.find((s: any) => s.format === "hls")?.url;
    const mp4Url = playback.data.streams.find((s: any) => s.format === "mp4")?.url;
    assert.ok(hlsUrl && mp4Url);

    const m3u8Res = await fetch(`${baseUrl}${hlsUrl}`);
    assert.equal(m3u8Res.status, 200);
    assert.match(await m3u8Res.text(), /#EXTM3U/);

    const segRes = await fetch(`${baseUrl}/api/v1/mock-media/${id}/seg-0.ts`);
    assert.equal(segRes.status, 200);
    assert.equal(segRes.headers.get("content-type"), "video/mp2t");

    const rangeRes = await fetch(`${baseUrl}${mp4Url}`, { headers: { Range: "bytes=0-7" } });
    assert.equal(rangeRes.status, 206);
    assert.match(rangeRes.headers.get("content-range") || "", /^bytes 0-7\/\d+$/);
    assert.equal((await rangeRes.arrayBuffer()).byteLength, 8);
  } finally {
    Object.assign(env, snapshot);
    cacheService.clear();
    await new Promise<void>((resolve, reject) =>
      server.close((err) => (err ? reject(err) : resolve()))
    );
  }
});

test("StreamProxyService enforces HTTPS, exact CDN allowlist, and SSRF IP blocking", () => {
  assert.equal(isPrivateOrReservedIp("127.0.0.1"), true);
  assert.equal(isPrivateOrReservedIp("10.1.2.3"), true);
  assert.equal(isPrivateOrReservedIp("100.64.0.1"), true);
  assert.equal(isPrivateOrReservedIp("169.254.169.254"), true);
  assert.equal(isPrivateOrReservedIp("172.16.5.4"), true);
  assert.equal(isPrivateOrReservedIp("192.168.0.1"), true);
  assert.equal(isPrivateOrReservedIp("::1"), true);
  assert.equal(isPrivateOrReservedIp("fc00::1"), true);
  assert.equal(isPrivateOrReservedIp("fe80::1"), true);
  assert.equal(isPrivateOrReservedIp("::ffff:127.0.0.1"), true);
  assert.equal(isPrivateOrReservedIp("8.8.8.8"), false);

  const strictProxy = new StreamProxyService();
  assert.equal(
    strictProxy.isTargetUrlAllowed("https://sbcdn3.hakunaymatata.com/dash/1/index_web.mpd"),
    true
  );
  assert.equal(
    strictProxy.isTargetUrlAllowed("https://bcdn.hakunaymatata.com/resource/movie.mp4"),
    true
  );
  assert.equal(
    strictProxy.isTargetUrlAllowed("http://sbcdn3.hakunaymatata.com/dash/1/index_web.mpd"),
    false
  );
  assert.equal(
    strictProxy.isTargetUrlAllowed("https://user:pass@sbcdn3.hakunaymatata.com/video.mp4"),
    false
  );
  assert.equal(
    strictProxy.isTargetUrlAllowed("https://sbcdn3.hakunaymatata.com:8443/video.mp4"),
    false
  );
  assert.equal(
    strictProxy.isTargetUrlAllowed("https://fakehakunaymatata.com/video.mp4"),
    false
  );
  assert.equal(
    strictProxy.isTargetUrlAllowed("https://169.254.169.254/latest/meta-data/"),
    false
  );
  assert.equal(strictProxy.isTargetUrlAllowed("https://127.0.0.1/video.mp4"), false);
});

test("StreamProxyService rewrites gzipped HLS/DASH manifests, handles Range 206, blocks traversal, and validates redirect hops", async () => {
  const snapshot = { ...env };
  env.streamProxySecret = "utf8:proxy-integration-test-key";
  env.movieboxMediaHosts = ["cdn1.test", "cdn2.test"];
  env.streamReferer = "";

  let lastUpstreamReferer: string | undefined;
  const cdnServer = http.createServer((req, res) => {
    lastUpstreamReferer = typeof req.headers.referer === "string" ? req.headers.referer : undefined;
    const url = req.url || "/";

    if (url === "/redirect-good") {
      res.statusCode = 302;
      res.setHeader("Location", `http://cdn2.test:${(cdnServer.address() as any).port}/video.mp4`);
      res.end();
      return;
    }
    if (url === "/redirect-evil") {
      res.statusCode = 302;
      res.setHeader("Location", "http://evil.attacker.example/secret.mp4");
      res.end();
      return;
    }
    if (url === "/hls/master.m3u8") {
      const m3u8 = [
        "#EXTM3U",
        '#EXT-X-KEY:METHOD=AES-128,URI="key.bin"',
        '#EXT-X-MAP:URI="init.mp4"',
        "#EXTINF:4.0,",
        "seg-001.ts",
        "#EXT-X-ENDLIST",
      ].join("\n");
      const gz = zlib.gzipSync(Buffer.from(m3u8, "utf8"));
      res.statusCode = 200;
      res.setHeader("Content-Type", "application/vnd.apple.mpegurl");
      res.setHeader("Content-Encoding", "gzip");
      res.end(gz);
      return;
    }
    if (url === "/dash/123/index_web.mpd") {
      const mpd = [
        '<?xml version="1.0"?>',
        "<MPD><Period><AdaptationSet>",
        '<SegmentTemplate initialization="init-stream$RepresentationID$.m4s" media="chunk-stream$RepresentationID$-$Number%05d$.m4s" />',
        '<Representation id="0" bandwidth="1200000" codecs="avc1.64001f" />',
        "</AdaptationSet></Period></MPD>",
      ].join("");
      res.statusCode = 200;
      res.setHeader("Content-Type", "application/dash+xml");
      res.end(mpd);
      return;
    }
    if (url === "/dash/123/chunk-stream0-00001.m4s") {
      const chunk = Buffer.from("DASH_CHUNK_BYTES_001");
      res.statusCode = 200;
      res.setHeader("Content-Type", "video/iso.segment");
      res.setHeader("Content-Length", String(chunk.length));
      res.end(chunk);
      return;
    }
    if (url === "/video.mp4") {
      const payload = Buffer.from("0123456789ABCDEF0123456789ABCDEF");
      res.setHeader("Content-Type", "video/mp4");
      res.setHeader("Accept-Ranges", "bytes");
      const range = String(req.headers.range || "");
      const m = range.match(/^bytes=(\d+)-(\d*)$/);
      if (m) {
        const start = Number.parseInt(m[1], 10);
        const end = m[2] ? Math.min(Number.parseInt(m[2], 10), payload.length - 1) : payload.length - 1;
        const slice = payload.subarray(start, end + 1);
        res.statusCode = 206;
        res.setHeader("Content-Range", `bytes ${start}-${end}/${payload.length}`);
        res.setHeader("Content-Length", String(slice.length));
        res.end(slice);
        return;
      }
      res.statusCode = 200;
      res.setHeader("Content-Length", String(payload.length));
      res.end(payload);
      return;
    }

    res.statusCode = 404;
    res.end("Not Found");
  });

  await new Promise<void>((resolve) => cdnServer.listen(0, "127.0.0.1", resolve));
  const cdnPort = (cdnServer.address() as any).port;

  // Test SSRF DNS rejection separately
  const ssrfService = new StreamProxyService({
    allowInsecureHttpForTest: true,
    allowPrivateIpForTest: false,
    dnsLookupFn: async (host) =>
      host === "cdn1.test" ? [{ address: "127.0.0.1", family: 4 }] : [{ address: "93.184.216.34", family: 4 }],
  });

  const testProxyService = new StreamProxyService({
    allowInsecureHttpForTest: true,
    allowPrivateIpForTest: true,
    dnsLookupFn: async () => [{ address: "127.0.0.1", family: 4 }],
  });

  const app = express();
  app.all("/api/v1/stream-proxy", (req, res) => void testProxyService.handle(req, res));
  app.all("/api/v1/stream-proxy/dir/:token/*", (req, res) => void testProxyService.handleDirSegment(req, res));
  app.all("/ssrf-proxy", (req, res) => void ssrfService.handle(req, res));
  const proxyServer = http.createServer(app);
  await new Promise<void>((resolve) => proxyServer.listen(0, "127.0.0.1", resolve));
  const proxyBase = `http://127.0.0.1:${(proxyServer.address() as any).port}`;

  try {
    // 1. SSRF DNS rejection
    const ssrfTok = new URL(
      `http://x${ssrfService.createProxyUrl(`http://cdn1.test:${cdnPort}/video.mp4`, "media")!}`
    ).searchParams.get("token")!;
    const ssrfRes = await fetch(`${proxyBase}/ssrf-proxy?token=${encodeURIComponent(ssrfTok)}`);
    assert.equal(ssrfRes.status, 403);
    assert.equal(((await ssrfRes.json()) as any).code, "STREAM_PROXY_SSRF_BLOCKED");

    // 2. CORS OPTIONS preflight
    const optRes = await fetch(`${proxyBase}/api/v1/stream-proxy`, { method: "OPTIONS" });
    assert.equal(optRes.status, 204);
    assert.equal(optRes.headers.get("access-control-allow-origin"), "*");
    assert.match(optRes.headers.get("access-control-allow-headers") || "", /Range/i);

    // 3. Gzipped HLS manifest decompression + URI rewriting
    const hlsProxyUrl = testProxyService.createProxyUrl(
      `http://cdn1.test:${cdnPort}/hls/master.m3u8`,
      "media"
    )!;
    const hlsRes = await fetch(`${proxyBase}${hlsProxyUrl}`);
    assert.equal(hlsRes.status, 200);
    assert.equal(hlsRes.headers.get("content-type"), "application/vnd.apple.mpegurl; charset=utf-8");
    const rewrittenHls = await hlsRes.text();
    assert.equal((rewrittenHls.match(/\/api\/v1\/stream-proxy\?token=/g) || []).length, 3);
    assert.doesNotMatch(rewrittenHls, /cdn1\.test/);

    // 4. DASH manifest rewriting with SegmentTemplate -> dir token + chunk fetch + traversal defense
    const dashProxyUrl = testProxyService.createProxyUrl(
      `http://cdn1.test:${cdnPort}/dash/123/index_web.mpd`,
      "media"
    )!;
    const dashRes = await fetch(`${proxyBase}${dashProxyUrl}`);
    assert.equal(dashRes.status, 200);
    assert.equal(dashRes.headers.get("content-type"), "application/dash+xml; charset=utf-8");
    const rewrittenDash = await dashRes.text();
    const mediaMatch = rewrittenDash.match(/media="([^"]+)"/);
    assert.ok(mediaMatch && mediaMatch[1].startsWith("/api/v1/stream-proxy/dir/"));
    assert.ok(mediaMatch[1].includes("$RepresentationID$-$Number%05d$.m4s"));

    const expandedSegPath = mediaMatch[1]
      .replace("$RepresentationID$", "0")
      .replace("$Number%05d$", "00001");
    const segRes = await fetch(`${proxyBase}${expandedSegPath}`);
    assert.equal(segRes.status, 200);
    assert.equal(await segRes.text(), "DASH_CHUNK_BYTES_001");

    const dirPrefix = mediaMatch[1].slice(0, mediaMatch[1].lastIndexOf("/") + 1);
    const traversalRes = await fetch(`${proxyBase}${dirPrefix}..%2F..%2Fvideo.mp4`);
    assert.equal(traversalRes.status, 403);
    assert.equal(((await traversalRes.json()) as any).code, "STREAM_PROXY_PATH_FORBIDDEN");

    // 5. Redirect hop validation (allowed vs forbidden) + Range 206 + no unsolicited Referer
    const goodRedirectUrl = testProxyService.createProxyUrl(
      `http://cdn1.test:${cdnPort}/redirect-good`,
      "media"
    )!;
    const rangeRes = await fetch(`${proxyBase}${goodRedirectUrl}`, {
      headers: { Range: "bytes=0-9" },
    });
    assert.equal(rangeRes.status, 206);
    assert.equal(rangeRes.headers.get("content-range"), "bytes 0-9/32");
    assert.equal(await rangeRes.text(), "0123456789");
    assert.equal(lastUpstreamReferer, undefined);

    const evilRedirectUrl = testProxyService.createProxyUrl(
      `http://cdn1.test:${cdnPort}/redirect-evil`,
      "media"
    )!;
    const evilRes = await fetch(`${proxyBase}${evilRedirectUrl}`);
    assert.equal(evilRes.status, 403);
    assert.equal(((await evilRes.json()) as any).code, "STREAM_PROXY_REDIRECT_FORBIDDEN");
  } finally {
    Object.assign(env, snapshot);
    await new Promise<void>((r) => proxyServer.close(() => r()));
    await new Promise<void>((r) => cdnServer.close(() => r()));
  }
});

test("Frontend helpers: videoSourceResolver preserves HLS/DASH formats through proxy URLs, movieboxApiService handles HEVC preference, subtitleParser parses VTT/SRT, Firebase db helpers remain intact", () => {
  const dashEp: Episode = {
    id: "ep-1",
    animeId: "123",
    episodeNumber: 1,
    title: "Test Episode",
    description: "Test episode description",
    releaseDate: "2025",
    duration: "24m",
    dashUrl: "/api/v1/stream-proxy?token=v1.abc.def",
    streamVariants: [
      {
        id: "v1",
        label: "1080p DASH",
        quality: "1080p",
        resolution: 1080,
        format: "dash",
        url: "/api/v1/stream-proxy?token=v1.abc.def",
        requiresCustomHeaders: false,
        hasSignedCookie: false,
        browserCompatible: true,
      },
    ],
  };

  const resolvedDash = resolveVideoSource(dashEp);
  assert.equal(resolvedDash.format, "dash");
  assert.equal(resolvedDash.isDash, true);
  assert.equal(resolvedDash.isHls, false);

  const enriched = applyPlaybackToEpisode(dashEp, {
    subjectId: "123",
    season: 1,
    episode: 1,
    resourceId: "res-1",
    placeholderCount: 0,
    fetchedAt: Date.now(),
    audioTracks: [{ subjectId: "123", language: "English", label: "English Audio" }],
    subtitles: [
      { id: "sub-en", name: "English", languageCode: "en", url: "/api/v1/stream-proxy?token=sub", format: "vtt" },
    ],
    streams: [
      {
        id: "hevc-1080",
        label: "1080p HEVC",
        quality: "1080p",
        resolution: 1080,
        format: "mp4",
        codec: "hevc",
        url: "/api/v1/stream-proxy?token=v1.hevc",
        requiresCustomHeaders: false,
        hasSignedCookie: false,
        browserCompatible: true,
      },
      {
        id: "h264-720",
        label: "720p H264 HLS",
        quality: "720p",
        resolution: 720,
        format: "hls",
        codec: "h264",
        url: "/api/v1/stream-proxy?token=v1.hls",
        requiresCustomHeaders: false,
        hasSignedCookie: false,
        browserCompatible: true,
      },
    ],
  });
  assert.equal(enriched.hlsUrl, "/api/v1/stream-proxy?token=v1.hls");

  const cues = parseVttOrSrtCues(
    "\uFEFFWEBVTT\n\n00:00:01.000 --> 00:00:03.500 align:start position:0%\n{\\an8}<i>Hello &amp; Welcome</i>\n\n2\n00:00:04,000 --> 00:00:06,000\nIt&#39;s &quot;working&quot;"
  );
  assert.equal(cues.length, 2);
  assert.equal(cues[0]?.text, "Hello & Welcome");
  assert.equal(cues[1]?.text, 'It\'s "working"');

  assert.deepEqual(cleanUndefined({ a: 1, b: undefined, c: { d: undefined, e: 2 } }), {
    a: 1,
    c: { e: 2 },
  });
  assert.equal(getLanguageMetadata("Hindi").code, "hi");
});

test("rateLimiter allocates high separate quota for /v1/stream-proxy media segment traffic", async () => {
  const { rateLimiter } = await import("../src/middleware/rateLimiter");
  const snapshot = { ...env };
  env.rateLimitMaxRequests = 2;
  env.rateLimitWindowMs = 60_000;

  const testApp = express();
  testApp.set("trust proxy", 1);
  testApp.use("/api", rateLimiter);
  testApp.get("/api/health", (_req, res) => res.json({ ok: true }));
  testApp.get("/api/v1/stream-proxy", (_req, res) => res.json({ ok: true }));

  const srv = http.createServer(testApp);
  await new Promise<void>((r) => srv.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${(srv.address() as any).port}`;
  const hdrs = { "X-Forwarded-For": "203.0.113.77" };

  try {
    assert.equal((await fetch(`${base}/api/health`, { headers: hdrs })).status, 200);
    assert.equal((await fetch(`${base}/api/health`, { headers: hdrs })).status, 200);
    assert.equal((await fetch(`${base}/api/health`, { headers: hdrs })).status, 429);

    // Media proxy requests must use separate high-limit bucket even after api bucket hit 429
    for (let i = 0; i < 5; i++) {
      assert.equal((await fetch(`${base}/api/v1/stream-proxy`, { headers: hdrs })).status, 200);
    }
  } finally {
    Object.assign(env, snapshot);
    await new Promise<void>((r) => srv.close(() => r()));
  }
});
