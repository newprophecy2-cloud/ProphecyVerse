import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import {
  buildCanonicalString,
  buildProviderHeaders,
  generateXClientToken,
  generateXTrSignature,
  sortedQueryString,
} from "../src/providers/moviebox/crypto";
import {
  createSessionFromToken,
  isSessionValid,
  parseJwtClaims,
} from "../src/providers/moviebox/session";
import {
  cleanMovieBoxTitle,
  isDeprecationNoticeUrl,
  mapCaptionsToOptions,
  mapDetailsToMediaDetails,
  mapHomepageToCatalog,
  mapPlayInfoToStreams,
  mapSearchToCatalog,
  resolveDashManifestFromPolicy,
} from "../src/providers/moviebox/mapper";
import { createApiApp } from "../src/app";

test("sortedQueryString orders parameters deterministically", () => {
  const qs = sortedQueryString(
    "https://api6.aoneroom.com/wefeed-mobile-bff/subject-api/play-info/v2?se=2&subjectId=999&ep=5"
  );
  assert.equal(qs, "ep=5&se=2&subjectId=999");
});

test("generateXClientToken produces timestamp,md5(reversed_timestamp)", () => {
  const token = generateXClientToken(1700000000123);
  const parts = token.split(",");
  assert.equal(parts.length, 2);
  assert.equal(parts[0], "1700000000123");
  assert.match(parts[1], /^[a-f0-9]{32}$/);
});

test("generateXTrSignature computes canonical HMAC-MD5 and buildProviderHeaders never adds x-forwarded-for", () => {
  const canonical = buildCanonicalString(
    "POST",
    "application/json",
    "application/json",
    "https://api6.aoneroom.com/wefeed-mobile-bff/subject-api/search/v2?b=2&a=1",
    '{"keyword":"one piece"}',
    1700000000000
  );
  assert.ok(canonical.startsWith("POST\napplication/json\napplication/json\n23\n1700000000000\n"));
  assert.ok(canonical.endsWith("/wefeed-mobile-bff/subject-api/search/v2?a=1&b=2"));

  const sig = generateXTrSignature(
    "GET",
    "application/json",
    "application/json",
    "https://api6.aoneroom.com/wefeed-mobile-bff/subject-api/get?subjectId=123",
    undefined,
    1700000000000,
    "unit-test-secret"
  );
  assert.ok(sig && sig.startsWith("1700000000000|2|"));

  const headers = buildProviderHeaders({
    method: "GET",
    url: "https://api6.aoneroom.com/wefeed-mobile-bff/subject-api/get?subjectId=123",
    timestampMs: 1700000000000,
  });
  assert.equal(headers["x-forwarded-for"], undefined);
  assert.equal(headers["X-Forwarded-For"], undefined);
  assert.ok(headers["x-client-token"]);
});

test("JWT session claim parser and expiry checks validate tokens accurately", () => {
  const futureExp = Math.floor(Date.now() / 1000) + 3600;
  const payloadB64 = Buffer.from(
    JSON.stringify({ userId: "42", exp: futureExp }),
    "utf8"
  ).toString("base64url");
  const validJwt = `header.${payloadB64}.sig`;

  const claims = parseJwtClaims(validJwt);
  assert.equal(claims.userId, "42");
  assert.equal(claims.expiresAt, futureExp);

  const session = createSessionFromToken(validJwt);
  assert.equal(isSessionValid(session), true);

  const expiredSession = { ...session, expiresAt: Math.floor(Date.now() / 1000) + 10 };
  assert.equal(isSessionValid(expiredSession), false);
});

test("cleanMovieBoxTitle strips resolution/language tags and preserves years", () => {
  assert.equal(cleanMovieBoxTitle("[4K] Inception - Hindi Dub"), "Inception");
  assert.equal(cleanMovieBoxTitle("One Piece (1999) - S1-24"), "One Piece (1999)");
  assert.equal(cleanMovieBoxTitle("Dune_1080p"), "Dune");
});

test("isDeprecationNoticeUrl detects legacy macdn /other/ clips", () => {
  assert.equal(
    isDeprecationNoticeUrl("https://macdn.aoneroom.com/other/1c7de0bd3393702d9191801f15f88f8d.mp4"),
    true
  );
  assert.equal(
    isDeprecationNoticeUrl("https://cdn.example.com/streams/movie_1080p.mp4"),
    false
  );
});

test("resolveDashManifestFromPolicy decodes urlprefix and CloudFront-Policy manifests", () => {
  const prefixB64 = Buffer.from("https://cdn.example.com/dash/12345/", "utf8").toString("base64url");
  assert.equal(
    resolveDashManifestFromPolicy(`Edge-Cache-Cookie=urlprefix=${prefixB64}:Expires=999`),
    "https://cdn.example.com/dash/12345/index.mpd"
  );

  const policyB64 = Buffer.from(
    JSON.stringify({ Statement: [{ Resource: "https://cf.example.com/dash/987/*" }] }),
    "utf8"
  ).toString("base64");
  assert.equal(
    resolveDashManifestFromPolicy(`CloudFront-Policy=${policyB64}`),
    "https://cf.example.com/dash/987/index.mpd"
  );
});

test("MovieBox mappers transform homepage, search, details, streams, and captions payloads", () => {
  const home = mapHomepageToCatalog({
    data: {
      items: [
        {
          banner: {
            banners: [
              {
                subject: {
                  subjectId: "101",
                  title: "Interstellar",
                  subjectType: 1,
                  releaseDate: "2014-11-07",
                  cover: { url: "https://img.example.com/101.jpg" },
                },
              },
            ],
          },
          subjects: [
            {
              subjectId: "102",
              title: "Dark",
              subjectType: 2,
              releaseDate: "2017-12-01",
              season: 3,
            },
          ],
        },
      ],
    },
  });
  assert.equal(home.length, 2);
  assert.equal(home[0]?.mediaType, "movie");
  assert.equal(home[1]?.mediaType, "series");
  assert.equal(home[1]?.seasonCount, 3);

  const search = mapSearchToCatalog({
    data: {
      results: [{ subjects: [{ subjectId: "101", title: "Interstellar", subjectType: 1 }] }],
      pager: { hasMore: true, totalCount: 15 },
    },
  });
  assert.equal(search.items.length, 1);
  assert.equal(search.hasMore, true);
  assert.equal(search.totalCount, 15);

  const details = mapDetailsToMediaDetails({
    data: {
      subject: {
        subjectId: "102",
        title: "Dark",
        subjectType: 2,
        seasons: [{ se: 1, maxEp: 2 }],
        dubs: [{ subjectId: "202", lanName: "English" }],
      },
    },
  });
  assert.ok(details);
  assert.equal(details.seasons.length, 1);
  assert.equal(details.seasons[0]?.episodes.length, 2);
  assert.equal(details.dubs[0]?.subjectId, "202");

  const streams = mapPlayInfoToStreams({
    data: {
      streams: [
        {
          id: "55",
          format: "MP4",
          resolutions: "1080,720",
          url: "https://cdn.example.com/video/1080.mp4",
        },
      ],
    },
  });
  assert.equal(streams.length, 2);
  assert.equal(streams[0]?.quality, "1080p");
  assert.equal(streams[0]?.browserCompatible, true);

  const captions = mapCaptionsToOptions({
    extCaptions: [
      { id: "1", lanName: "English", lan: "en", size: 4200, url: "https://cdn.example.com/en.srt" },
      { id: "2", lanName: "English", lan: "en", size: 20, url: "https://cdn.example.com/tiny.srt" },
    ],
  });
  assert.equal(captions.length, 1);
  assert.equal(captions[0]?.format, "srt");
});

test("Express API routes return /api/health OK, 400 validation errors, and 424 PROVIDER_AUTH_REQUIRED when unconfigured", async () => {
  const { env } = await import("../src/config/env");
  const snapshot = { ...env };
  env.movieboxSigningSecret = "";
  env.movieboxAuthToken = "";
  const app = createApiApp();
  const server = http.createServer(app);

  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert.ok(address && typeof address === "object");
  const baseUrl = `http://127.0.0.1:${address.port}`;

  try {
    const healthRes = await fetch(`${baseUrl}/api/health`);
    assert.equal(healthRes.status, 200);
    const healthJson = (await healthRes.json()) as {
      status: string;
      data: {
        provider: string;
        movieboxConfigured: boolean;
        authDiagnostics: {
          signingSecretConfigured: boolean;
          authTokenConfigured: boolean;
          hostCount: number;
        };
      };
    };
    assert.equal(healthJson.status, "ok");
    assert.equal(healthJson.data.provider, "moviebox");
    assert.equal(typeof healthJson.data.movieboxConfigured, "boolean");
    assert.equal(typeof healthJson.data.authDiagnostics.signingSecretConfigured, "boolean");
    assert.equal(typeof healthJson.data.authDiagnostics.authTokenConfigured, "boolean");
    assert.ok(healthJson.data.authDiagnostics.hostCount >= 1);

    const badSearch = await fetch(`${baseUrl}/api/v1/anime/search?q=`);
    assert.equal(badSearch.status, 400);

    const badSubject = await fetch(`${baseUrl}/api/v1/anime/invalid-id!`);
    assert.equal(badSubject.status, 400);

    const unauthSearch = await fetch(`${baseUrl}/api/v1/anime/search?q=inception`);
    assert.equal(unauthSearch.status, 424);
    const unauthJson = (await unauthSearch.json()) as { code: string };
    assert.equal(unauthJson.code, "PROVIDER_AUTH_REQUIRED");

    const unauthCaptions = await fetch(`${baseUrl}/api/v1/captions/12345`);
    assert.equal(unauthCaptions.status, 424);
    const unauthCapJson = (await unauthCaptions.json()) as { code: string };
    assert.equal(unauthCapJson.code, "PROVIDER_AUTH_REQUIRED");
  } finally {
    Object.assign(env, snapshot);
    await new Promise<void>((resolve, reject) =>
      server.close((err) => (err ? reject(err) : resolve()))
    );
  }
});

test("decodeSigningSecret supports base64, hex, escaped bytes, utf8, and rejects invalid encoded inputs", async () => {
  const { decodeSigningSecret } = await import("../src/providers/moviebox/crypto");

  assert.equal(decodeSigningSecret("   "), null);
  assert.deepEqual(decodeSigningSecret("base64:aGVsbG8="), Buffer.from("hello", "utf8"));
  assert.deepEqual(decodeSigningSecret("hex:68656c6c6f"), Buffer.from("hello", "utf8"));
  assert.equal(decodeSigningSecret("hex:zz"), null);
  assert.deepEqual(decodeSigningSecret("escaped:\\x68\\x65\\x6c\\x6c\\x6f"), Buffer.from("hello", "utf8"));
  assert.deepEqual(decodeSigningSecret("\\x68\\x69"), Buffer.from("hi", "utf8"));
  assert.deepEqual(decodeSigningSecret("utf8:plain-key"), Buffer.from("plain-key", "utf8"));
  assert.deepEqual(decodeSigningSecret("plain-key"), Buffer.from("plain-key", "utf8"));
});

test("mapDetailsToMediaDetails preserves seasons attached to wrapper payload when subject is nested", () => {
  const details = mapDetailsToMediaDetails({
    subject: {
      subjectId: "900",
      title: "Nested Series",
      subjectType: 2,
    },
    seasons: {
      seasons: [{ se: 1, maxEp: 3 }],
    },
  });
  assert.ok(details);
  assert.equal(details.mediaType, "series");
  assert.equal(details.seasons.length, 1);
  assert.equal(details.seasons[0]?.episodes.length, 3);
});

test("errorHandler maps proxy-intercepted and auth HTTP codes (401, 403, 407, 502, 503) to 424 and 504 to 408", async () => {
  const express = (await import("express")).default;
  const { errorHandler } = await import("../src/middleware/errorHandler");
  const { MovieBoxError } = await import("../src/providers/moviebox/errors");

  const testApp = express();
  testApp.get("/err/:status", (req, _res, next) => {
    const status = Number.parseInt(req.params.status, 10);
    next(new MovieBoxError("PROVIDER_AUTH_REQUIRED", "test", status, false));
  });
  testApp.use(errorHandler);

  const server = http.createServer(testApp);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const addr = server.address();
  assert.ok(addr && typeof addr === "object");
  const port = addr.port;

  try {
    for (const status of [401, 403, 407, 502, 503]) {
      const res: Response = await fetch(`http://127.0.0.1:${port}/err/${status}`);
      assert.equal(res.status, 424);
    }
    const timeoutRes: Response = await fetch(`http://127.0.0.1:${port}/err/504`);
    assert.equal(timeoutRes.status, 408);
  } finally {
    await new Promise<void>((resolve, reject) =>
      server.close((err) => (err ? reject(err) : resolve()))
    );
  }
});

test("getMovieBoxAuthDiagnostics accurately classifies credentials and reports safe missing requirements", async () => {
  const { env } = await import("../src/config/env");
  const { getMovieBoxAuthDiagnostics } = await import("../src/providers/moviebox/crypto");

  const snapshot = { ...env };
  try {
    env.movieboxSigningSecret = "";
    env.movieboxAuthToken = "";
    env.movieboxCertSha256 = "";
    env.movieboxBuildSalt = "";
    env.movieboxClientInfo = "";

    const unconf = getMovieBoxAuthDiagnostics();
    assert.equal(unconf.authMode, "unconfigured");
    assert.equal(unconf.signingSecretFormat, "none");
    assert.ok(unconf.missingRequirements.includes("MOVIEBOX_SIGNING_SECRET"));

    env.movieboxAuthToken = "hdr.payload.sig";
    const tokenOnly = getMovieBoxAuthDiagnostics();
    assert.equal(tokenOnly.authMode, "auth_token_only");
    assert.equal(tokenOnly.authTokenValidFormat, true);

    env.movieboxSigningSecret = "hex:zz-bad-hex";
    env.movieboxClientInfo = "{bad-json";
    const badHex = getMovieBoxAuthDiagnostics();
    assert.equal(badHex.signingSecretFormat, "invalid");
    assert.equal(badHex.signingSecretValid, false);
    assert.equal(badHex.clientInfoConfigured, true);
    assert.equal(badHex.clientInfoValidJson, false);
    assert.ok(badHex.missingRequirements.some((r) => r.includes("MOVIEBOX_CLIENT_INFO")));

    env.movieboxSigningSecret = "utf8:test-signing-key";
    env.movieboxClientInfo = '{"model":"Pixel"}';
    const both = getMovieBoxAuthDiagnostics();
    assert.equal(both.authMode, "signing_and_token");
    assert.equal(both.signingSecretValid, true);
    assert.equal(both.clientInfoValidJson, true);
    assert.equal(both.missingRequirements.length, 0);
  } finally {
    Object.assign(env, snapshot);
  }
});

test("sanitizeLanguageLabel and languageToCode cover MovieBox-TUI's 40 languages and strip Arabic diacritics", async () => {
  const { languageToCode, sanitizeLanguageLabel, mapSuggestToStrings, mapCollectionResolutions } =
    await import("../src/providers/moviebox/mapper");

  assert.equal(languageToCode("in_id"), "id");
  assert.equal(languageToCode("日本語"), "ja");
  assert.equal(languageToCode("한국어"), "ko");
  assert.equal(languageToCode("中文"), "zh");
  assert.equal(languageToCode("العَرَبِيَّة"), "ar");

  assert.equal(sanitizeLanguageLabel("eng"), "English");
  assert.equal(sanitizeLanguageLabel("español"), "Spanish");
  assert.equal(sanitizeLanguageLabel("العَرَبِيَّة"), "Arabic");
  assert.equal(sanitizeLanguageLabel("in_id"), "Indonesian");
  assert.equal(sanitizeLanguageLabel("Filipino"), "Filipino");
  assert.equal(sanitizeLanguageLabel(""), "Unknown");

  const suggestions = mapSuggestToStrings({
    data: {
      results: [{ subjects: [{ subjectId: "1", title: "Attack on Titan" }, { subjectId: "2", title: "AoT Final" }] }],
    },
  });
  assert.deepEqual(suggestions, ["Attack on Titan", "AoT Final"]);

  assert.deepEqual(
    mapCollectionResolutions({ collectionResolutions: [{ resolution: 480 }, { resolution: 1080 }, { resolution: 720 }] }),
    [1080, 720, 480]
  );
  assert.deepEqual(
    mapCollectionResolutions({ list: [{ resolution: 720 }, { resolution: 360 }, { resolution: 720 }] }),
    [720, 360]
  );
  assert.deepEqual(mapCollectionResolutions({}), [1080, 720, 480, 360]);
});

test("MovieBoxClient completes visitor-login, signature verification, 401 refresh, x-user rotation, resource recovery, and prioritizes 407 over 503", async () => {
  const { env } = await import("../src/config/env");
  const { generateXTrSignature } = await import("../src/providers/moviebox/crypto");
  const { MovieBoxClient } = await import("../src/providers/moviebox/client");

  const snapshot = { ...env };
  const secret = "utf8:mock-upstream-hmac-secret";
  env.movieboxSigningSecret = secret;
  env.movieboxAuthToken = "";

  const makeJwt = (uid: string) => {
    const exp = Math.floor(Date.now() / 1000) + 3600;
    const payload = Buffer.from(JSON.stringify({ userId: uid, exp }), "utf8").toString("base64url");
    return `hdr.${payload}.sig`;
  };

  let loginCalls = 0;
  let searchCalls = 0;
  let failoverMode: "normal" | "407_then_503" | "json_407" = "normal";

  const mockUpstream = http.createServer(async (req, res) => {
    const hostUrl = `http://127.0.0.1:${(mockUpstream.address() as any).port}${req.url || "/"}`;
    const chunks: Buffer[] = [];
    for await (const chunk of req) {
      chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
    }
    const rawBody = chunks.length > 0 ? Buffer.concat(chunks).toString("utf8") : undefined;

    if (failoverMode === "407_then_503") {
      res.statusCode = 407;
      res.end("Signature invalid");
      return;
    }
    if (failoverMode === "json_407") {
      res.statusCode = 200;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ code: 407, message: "Signature invalid", data: null }));
      return;
    }

    const sig = req.headers["x-tr-signature"];
    assert.equal(typeof sig, "string", "x-tr-signature header must be sent");
    const ts = Number.parseInt(String(sig).split("|")[0] || "0", 10);
    const expectedSig = generateXTrSignature(
      req.method || "GET",
      "application/json",
      "application/json",
      hostUrl,
      rawBody,
      ts,
      secret
    );
    assert.equal(sig, expectedSig, `x-tr-signature mismatch for ${req.method} ${req.url}`);

    res.setHeader("Content-Type", "application/json");

    if (req.url?.startsWith("/wefeed-mobile-bff/user-api/visitor-login")) {
      loginCalls += 1;
      res.end(JSON.stringify({ code: 0, data: { token: makeJwt(`u${loginCalls}`), uid: `u${loginCalls}` } }));
      return;
    }

    if (req.url?.startsWith("/wefeed-mobile-bff/subject-api/search/v2")) {
      searchCalls += 1;
      if (searchCalls === 1) {
        res.statusCode = 401;
        res.end(JSON.stringify({ code: 401, message: "Expired session" }));
        return;
      }
      res.setHeader("x-user", JSON.stringify({ token: makeJwt("rotated"), uid: "rotated" }));
      res.end(
        JSON.stringify({
          code: 0,
          data: {
            results: [{ subjects: [{ subjectId: "555", title: "Mock Show", subjectType: 2 }] }],
            pager: { hasMore: false, totalCount: 1 },
          },
        })
      );
      return;
    }

    if (req.url?.startsWith("/wefeed-mobile-bff/subject-api/get")) {
      res.end(
        JSON.stringify({
          code: 0,
          data: { subject: { subjectId: "555", title: "Mock Show", subjectType: 2 } },
        })
      );
      return;
    }

    if (req.url?.startsWith("/wefeed-mobile-bff/subject-api/season-info")) {
      res.end(
        JSON.stringify({
          code: 0,
          data: { seasons: [{ se: 1, maxEp: 2 }] },
        })
      );
      return;
    }

    res.statusCode = 404;
    res.end(JSON.stringify({ code: 404, message: "Not found" }));
  });

  const secondaryErrorServer = http.createServer((_req, res) => {
    res.statusCode = 503;
    res.end("Service Unavailable");
  });

  await new Promise<void>((r) => mockUpstream.listen(0, "127.0.0.1", r));
  await new Promise<void>((r) => secondaryErrorServer.listen(0, "127.0.0.1", r));

  const primaryBase = `http://127.0.0.1:${(mockUpstream.address() as any).port}`;
  const secondaryBase = `http://127.0.0.1:${(secondaryErrorServer.address() as any).port}`;

  try {
    const client = new MovieBoxClient([primaryBase]);
    const searchPayload = await client.search("mock");
    assert.equal(loginCalls, 2, "401 on first search should trigger fresh visitor-login retry");
    assert.equal(searchPayload.results[0].subjects[0].title, "Mock Show");

    const detailsPayload = await client.getDetails("555");
    assert.equal(detailsPayload.seasons.seasons[0].maxEp, 2);

    // 407 on host 1 followed by 503 on host 2 must preserve PROVIDER_AUTH_REQUIRED (407)
    const failoverClient = new MovieBoxClient([primaryBase, secondaryBase]);
    // Prime valid session first
    failoverMode = "normal";
    await failoverClient.ensureSession();

    failoverMode = "407_then_503";
    await assert.rejects(
      () => failoverClient.search("failover"),
      (err: any) => err?.code === "PROVIDER_AUTH_REQUIRED" && err?.statusCode === 407
    );

    failoverMode = "json_407";
    await assert.rejects(
      () => failoverClient.search("jsonfail"),
      (err: any) => err?.code === "PROVIDER_AUTH_REQUIRED" && err?.statusCode === 407
    );
  } finally {
    Object.assign(env, snapshot);
    await new Promise<void>((resolve, reject) =>
      mockUpstream.close((err) => (err ? reject(err) : resolve()))
    );
    await new Promise<void>((resolve, reject) =>
      secondaryErrorServer.close((err) => (err ? reject(err) : resolve()))
    );
  }
});

test("Express suggest, resolutions, and playback refresh routes validate parameters and enforce PROVIDER_AUTH_REQUIRED", async () => {
  const { env } = await import("../src/config/env");
  const snapshot = { ...env };
  env.movieboxSigningSecret = "";
  env.movieboxAuthToken = "";
  const app = createApiApp();
  const server = http.createServer(app);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const addr = server.address();
  assert.ok(addr && typeof addr === "object");
  const baseUrl = `http://127.0.0.1:${addr.port}`;

  try {
    const badSuggest = await fetch(`${baseUrl}/api/v1/anime/suggest?q=`);
    assert.equal(badSuggest.status, 400);

    const unauthSuggest = await fetch(`${baseUrl}/api/v1/anime/suggest?q=naruto`);
    assert.equal(unauthSuggest.status, 424);
    assert.equal(((await unauthSuggest.json()) as { code: string }).code, "PROVIDER_AUTH_REQUIRED");

    const badRes = await fetch(`${baseUrl}/api/v1/anime/bad!id/resolutions`);
    assert.equal(badRes.status, 400);

    const unauthRes = await fetch(`${baseUrl}/api/v1/anime/12345/resolutions`);
    assert.equal(unauthRes.status, 424);

    const unauthPlaybackRefresh = await fetch(`${baseUrl}/api/v1/playback/12345?se=1&ep=1&refresh=1`);
    assert.equal(unauthPlaybackRefresh.status, 424);
  } finally {
    Object.assign(env, snapshot);
    await new Promise<void>((resolve, reject) =>
      server.close((err) => (err ? reject(err) : resolve()))
    );
  }
});

test("PlaybackService resolves streams via resource-position recovery, sorts sanitized captions English-first, and respects forceRefresh", async () => {
  const { movieBoxClient } = await import("../src/providers/moviebox/client");
  const { playbackService } = await import("../src/services/playbackService");
  const { cacheService } = await import("../src/services/cacheService");
  const { env } = await import("../src/config/env");

  const origGetSubjectDetails = movieBoxClient.getSubjectDetails.bind(movieBoxClient);
  const origGetPlayInfo = movieBoxClient.getPlayInfo.bind(movieBoxClient);
  const origGetResources = movieBoxClient.getEpisodeResourcesV2.bind(movieBoxClient);
  const origGetPos = movieBoxClient.getResourcePosition.bind(movieBoxClient);
  const origGetCaps = movieBoxClient.getExtCaptions.bind(movieBoxClient);

  let playInfoCalls = 0;
  let posCalls = 0;
  const originalProxySecret = env.streamProxySecret;
  const originalMediaHosts = env.movieboxMediaHosts;
  cacheService.clear();

  try {
    env.streamProxySecret = "utf8:playback-test-secret";
    env.movieboxMediaHosts = ["example.com"];
    movieBoxClient.getSubjectDetails = async () => ({
      subject: { subjectId: "777", title: "Demo Series", subjectType: 2, dubs: [] },
    });
    movieBoxClient.getPlayInfo = async () => {
      playInfoCalls += 1;
      return { streams: [] };
    };
    movieBoxClient.getEpisodeResourcesV2 = async () => ({
      list: [
        {
          id: "res-1",
          se: 1,
          ep: 2,
          resolution: "1080p",
          codecName: "H264",
          language: "Japanese",
          resourceLink: "https://macdn.aoneroom.com/other/1c7de0bd3393702d9191801f15f88f8d.mp4",
        },
      ],
    });
    movieBoxClient.getResourcePosition = async () => {
      posCalls += 1;
      return {
        list: [
          {
            id: "res-1",
            se: 1,
            ep: 2,
            size: 12345678,
            resourceLink: `https://cdn.example.com/recovered_${posCalls}_1080.mp4`,
          },
        ],
      };
    };
    movieBoxClient.getExtCaptions = async () => ({
      extCaptions: [
        { id: "c1", lanName: "العَرَبِيَّة", lan: "ar", size: 1200, url: "https://cdn.example.com/ar.srt" },
        { id: "c2", lanName: "eng", lan: "en", size: 2200, url: "https://cdn.example.com/en.srt" },
        { id: "c3", lanName: "English", lan: "en", size: 2200, url: "https://cdn.example.com/en2.srt" },
      ],
    });

    const first = await playbackService.getPlayback("777", 1, 2, false);
    assert.equal(playInfoCalls, 1);
    assert.equal(posCalls, 1);
    assert.equal(first.resourceId, "res-1");
    assert.match(first.streams[0]?.url || "", /^\/api\/v1\/stream-proxy\?token=/);
    assert.doesNotMatch(first.streams[0]?.url || "", /cdn\.example\.com/);
    assert.equal(first.streams[0]?.resolution, 1080);
    assert.equal(first.streams[0]?.codec, "H264");
    assert.equal(first.streams[0]?.language, "Japanese");
    assert.equal(first.streams[0]?.sizeBytes, 12345678);
    assert.equal(first.subtitles[0]?.name, "English");
    assert.equal(first.subtitles.length, 2);

    const cached = await playbackService.getPlayback("777", 1, 2, false);
    assert.equal(playInfoCalls, 1, "Second call within TTL should hit playback cache");
    assert.equal(cached.streams[0]?.url, first.streams[0]?.url);

    const refreshed = await playbackService.getPlayback("777", 1, 2, true);
    assert.equal(playInfoCalls, 2, "forceRefresh=true should bypass playback cache");
    assert.match(refreshed.streams[0]?.url || "", /^\/api\/v1\/stream-proxy\?token=/);
    assert.doesNotMatch(refreshed.streams[0]?.url || "", /cdn\.example\.com/);
  } finally {
    movieBoxClient.getSubjectDetails = origGetSubjectDetails;
    movieBoxClient.getPlayInfo = origGetPlayInfo;
    movieBoxClient.getEpisodeResourcesV2 = origGetResources;
    movieBoxClient.getResourcePosition = origGetPos;
    movieBoxClient.getExtCaptions = origGetCaps;
    env.streamProxySecret = originalProxySecret;
    env.movieboxMediaHosts = originalMediaHosts;
    cacheService.clear();
  }
});

test("MovieBoxService builds base headers (User-Agent, x-client-info), signs requests with MOVIEBOX_SIGNING_SECRET, and rotates MOVIEBOX_API_HOSTS", async () => {
  const { env } = await import("../src/config/env");
  const { MovieBoxService } = await import("../src/services/moviebox.service");

  const snapshot = { ...env };
  try {
    env.movieboxUserAgent = "MovieBoxTestAgent/2.0";
    env.movieboxClientInfo = '{"model":"Pixel 8","brand":"Google"}';
    env.movieboxSigningSecret = "utf8:service-test-secret";
    env.movieboxCertSha256 = "";
    env.movieboxBuildSalt = "";
    env.movieboxHosts = ["https://host1.example.com", "https://host2.example.com", "https://host3.example.com"];

    const svc = new MovieBoxService();
    assert.deepEqual(svc.getHosts(), env.movieboxHosts);
    assert.equal(svc.getActiveHostIndex(), 0);
    assert.equal(svc.rotateHost(), 1);
    assert.equal(svc.rotateHost(), 2);
    assert.equal(svc.rotateHost(), 0);

    const baseHeaders = svc.getBaseHeaders(1700000000500);
    assert.equal(baseHeaders["User-Agent"], "MovieBoxTestAgent/2.0");
    assert.equal(baseHeaders["x-client-info"], '{"model":"Pixel 8","brand":"Google"}');
    assert.ok(baseHeaders["x-client-token"]?.startsWith("1700000000500,"));
    assert.equal(baseHeaders["x-forwarded-for"], undefined);

    const signedHeaders = svc.signRequest({
      method: "POST",
      url: "https://host1.example.com/wefeed-mobile-bff/subject-api/search/v2",
      body: '{"keyword":"test"}',
      timestampMs: 1700000000500,
      authToken: "jwt-token-1",
    });
    assert.ok(signedHeaders["x-tr-signature"]?.startsWith("1700000000500|2|"));
    assert.equal(signedHeaders["Authorization"], "Bearer jwt-token-1");
  } finally {
    Object.assign(env, snapshot);
  }
});
