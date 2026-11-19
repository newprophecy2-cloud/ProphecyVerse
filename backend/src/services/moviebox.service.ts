import http from "http";
import https from "https";
import { env } from "../config/env";
import { MOVIEBOX_CONFIG } from "../providers/moviebox/config";
import {
  MovieBoxAuthDiagnostics,
  buildCanonicalString,
  decodeSigningSecret,
  generateXClientBuild,
  generateXClientToken,
  generateXTrSignature,
  getMovieBoxAuthDiagnostics,
  hasAuthorizedSigningCredentials,
  hasRequestSigningSecret,
} from "../providers/moviebox/crypto";
import { MovieBoxError, isRetryableHttpStatus } from "../providers/moviebox/errors";
import { createSessionFromToken, isSessionValid } from "../providers/moviebox/session";
import { MovieBoxSession } from "../providers/moviebox/types";

export interface MovieBoxSignRequestOptions {
  method: string;
  url: string;
  body?: string;
  timestampMs?: number;
  signingSecretOverride?: string;
  authToken?: string;
}

interface UpstreamResponse {
  status: number;
  headers: http.IncomingHttpHeaders;
  body: string;
}

function getHeaderValue(headers: http.IncomingHttpHeaders, name: string): string | undefined {
  const val = headers[name.toLowerCase()];
  if (Array.isArray(val)) return val[0];
  return typeof val === "string" ? val : undefined;
}

function performHttpRequest(
  rawUrl: string,
  method: "GET" | "POST",
  headers: Record<string, string>,
  body: string | undefined,
  timeoutMs: number
): Promise<UpstreamResponse> {
  return new Promise((resolve, reject) => {
    const parsed = new URL(rawUrl);
    const transport = parsed.protocol === "http:" ? http : https;
    const bodyBuffer =
      method === "POST" && body !== undefined ? Buffer.from(body, "utf8") : undefined;
    const reqHeaders: Record<string, string | number> = { ...headers };
    if (bodyBuffer) {
      reqHeaders["Content-Length"] = bodyBuffer.length;
    }

    let settled = false;
    const req = transport.request(
      parsed,
      {
        method,
        headers: reqHeaders,
        timeout: timeoutMs,
      },
      (res) => {
        const chunks: Buffer[] = [];
        res.on("data", (chunk: Buffer | string) => {
          chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
        });
        res.on("end", () => {
          if (settled) return;
          settled = true;
          resolve({
            status: res.statusCode ?? 0,
            headers: res.headers,
            body: Buffer.concat(chunks).toString("utf8"),
          });
        });
        res.on("error", (err) => {
          if (settled) return;
          settled = true;
          reject(err);
        });
      }
    );

    req.on("timeout", () => {
      if (settled) return;
      settled = true;
      const timeoutErr = new Error("Upstream request timed out");
      timeoutErr.name = "AbortError";
      req.destroy(timeoutErr);
      reject(timeoutErr);
    });

    req.on("error", (err) => {
      if (settled) return;
      settled = true;
      reject(err);
    });

    if (bodyBuffer) {
      req.write(bodyBuffer);
    }
    req.end();
  });
}

/**
 * Core MovieBox HTTP client implementing MovieBox-TUI-compatible request headers,
 * HMAC-MD5 request signing (`MOVIEBOX_SIGNING_SECRET`), host rotation (`MOVIEBOX_API_HOSTS`),
 * visitor/session token management, retry/failover, and MovieBox BFF endpoint calls.
 */
export class MovieBoxService {
  private session: MovieBoxSession | null = null;
  private initPromise: Promise<string> | null = null;
  private activeHostIndex = 0;

  constructor(private readonly hostsOverride?: string[]) {
    if (env.movieboxAuthToken) {
      this.session = createSessionFromToken(env.movieboxAuthToken);
    }
  }

  /**
   * Returns active MovieBox API hosts configured via `MOVIEBOX_API_HOSTS`
   * (or explicit override hosts for testing).
   */
  public getHosts(): string[] {
    if (this.hostsOverride && this.hostsOverride.length > 0) {
      return this.hostsOverride;
    }
    return env.movieboxHosts.length > 0 ? env.movieboxHosts : MOVIEBOX_CONFIG.hostPool;
  }

  public getActiveHostIndex(): number {
    const hosts = this.getHosts();
    return this.activeHostIndex % hosts.length;
  }

  /**
   * Advances the active host index round-robin through configured `MOVIEBOX_API_HOSTS`.
   */
  public rotateHost(fromIndex?: number): number {
    const hosts = this.getHosts();
    const baseIdx = fromIndex !== undefined ? fromIndex : this.activeHostIndex;
    this.activeHostIndex = (baseIdx + 1) % hosts.length;
    return this.activeHostIndex;
  }

  /**
   * Defines the base MovieBox-TUI HTTP headers (`User-Agent`, `x-client-info`,
   * `x-client-token`, `x-client-status`, and optional `x-client-build`).
   */
  public getBaseHeaders(timestampMs: number = Date.now()): Record<string, string> {
    const headers: Record<string, string> = {
      "User-Agent": env.movieboxUserAgent,
      Accept: "application/json",
      "Content-Type": "application/json",
      Connection: "keep-alive",
      "x-client-token": generateXClientToken(timestampMs),
      "x-client-status": "0",
    };

    const buildTag = generateXClientBuild(timestampMs);
    if (buildTag) {
      headers["x-client-build"] = buildTag;
    }

    if (env.movieboxClientInfo) {
      try {
        const parsed = JSON.parse(env.movieboxClientInfo) as Record<string, unknown>;
        if (buildTag && parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
          parsed["X-Client-Build"] = buildTag;
        }
        headers["x-client-info"] = JSON.stringify(parsed);
      } catch {
        headers["x-client-info"] = env.movieboxClientInfo;
      }
    }

    return headers;
  }

  /**
   * Builds canonical request string and signs HTTP headers using `MOVIEBOX_SIGNING_SECRET`.
   */
  public signRequest(options: MovieBoxSignRequestOptions): Record<string, string> {
    const ts = options.timestampMs ?? Date.now();
    const headers = this.getBaseHeaders(ts);
    const accept = headers.Accept;
    const contentType = headers["Content-Type"];

    const secretRaw = options.signingSecretOverride ?? env.movieboxSigningSecret;
    if (decodeSigningSecret(secretRaw)) {
      const canonical = buildCanonicalString(
        options.method,
        accept,
        contentType,
        options.url,
        options.body,
        ts
      );
      void canonical;
      const signature = generateXTrSignature(
        options.method,
        accept,
        contentType,
        options.url,
        options.body,
        ts,
        secretRaw
      );
      if (signature) {
        headers["x-tr-signature"] = signature;
      }
    }

    if (options.authToken) {
      headers["Authorization"] = `Bearer ${options.authToken}`;
    }

    return headers;
  }

  public isConfigured(): boolean {
    return hasAuthorizedSigningCredentials();
  }

  public getAuthDiagnostics(): MovieBoxAuthDiagnostics {
    return getMovieBoxAuthDiagnostics();
  }

  public invalidateSession(): void {
    if (env.movieboxAuthToken) {
      this.session = createSessionFromToken(env.movieboxAuthToken);
      return;
    }
    this.session = null;
  }

  public async ensureSession(): Promise<string> {
    if (isSessionValid(this.session)) {
      return this.session!.token;
    }

    if (!this.isConfigured()) {
      throw new MovieBoxError(
        "PROVIDER_AUTH_REQUIRED",
        "MovieBox provider signing credentials (MOVIEBOX_SIGNING_SECRET or MOVIEBOX_AUTH_TOKEN) are not configured on the server.",
        424,
        false
      );
    }

    if (this.initPromise) {
      return this.initPromise;
    }

    this.initPromise = this.fetchFreshSession()
      .then((newSession) => {
        this.session = newSession;
        return newSession.token;
      })
      .finally(() => {
        this.initPromise = null;
      });

    return this.initPromise;
  }

  private async fetchFreshSession(): Promise<MovieBoxSession> {
    const val = await this.requestHosts(
      "POST",
      MOVIEBOX_CONFIG.endpoints.visitorLogin,
      "{}",
      undefined
    );

    const token =
      typeof val?.token === "string" && val.token.trim() ? val.token.trim() : undefined;
    if (!token) {
      throw new MovieBoxError(
        "PROVIDER_AUTH_REQUIRED",
        "MovieBox visitor-login succeeded without returning an authorized session token.",
        502,
        true
      );
    }

    const rawUid = val?.uid ?? val?.userId;
    const explicitUid =
      typeof rawUid === "string"
        ? rawUid
        : typeof rawUid === "number"
        ? String(rawUid)
        : undefined;

    return createSessionFromToken(token, explicitUid);
  }

  private absorbXUserHeader(headers: http.IncomingHttpHeaders): void {
    const xUserRaw = getHeaderValue(headers, "x-user");
    if (!xUserRaw) return;
    try {
      const parsed = JSON.parse(xUserRaw) as Record<string, unknown>;
      const token = typeof parsed.token === "string" ? parsed.token.trim() : "";
      if (!token) return;
      const rawUid = parsed.uid ?? parsed.userId;
      const uid =
        typeof rawUid === "string"
          ? rawUid
          : typeof rawUid === "number"
          ? String(rawUid)
          : undefined;
      this.session = createSessionFromToken(token, uid);
    } catch {
      // ignore malformed x-user header
    }
  }

  public async get(pathAndQuery: string, timeoutMs?: number): Promise<any> {
    return this.request("GET", pathAndQuery, undefined, timeoutMs);
  }

  public async post(
    pathAndQuery: string,
    body: Record<string, unknown>,
    timeoutMs?: number
  ): Promise<any> {
    return this.request("POST", pathAndQuery, JSON.stringify(body), timeoutMs);
  }

  private async request(
    method: "GET" | "POST",
    pathAndQuery: string,
    body?: string,
    timeoutMs?: number
  ): Promise<any> {
    const token = await this.ensureSession();

    try {
      return await this.requestHosts(method, pathAndQuery, body, token, timeoutMs);
    } catch (err) {
      if (
        err instanceof MovieBoxError &&
        (err.statusCode === 401 || err.statusCode === 403 || err.code === "UPSTREAM_UNAVAILABLE") &&
        hasRequestSigningSecret()
      ) {
        this.invalidateSession();
        const freshToken = await this.ensureSession();
        return await this.requestHosts(method, pathAndQuery, body, freshToken, timeoutMs);
      }
      throw err;
    }
  }

  private buildSignatureRejectedError(): MovieBoxError {
    const message = hasRequestSigningSecret()
      ? "MovieBox upstream rejected request signature (HTTP 407). Verify server-side MOVIEBOX_SIGNING_SECRET and build/client configuration."
      : "MovieBox upstream requires a signed request (HTTP 407). Configure MOVIEBOX_SIGNING_SECRET on the server in addition to MOVIEBOX_AUTH_TOKEN.";
    return new MovieBoxError("PROVIDER_AUTH_REQUIRED", message, 407, false);
  }

  private async requestHosts(
    method: "GET" | "POST",
    pathAndQuery: string,
    body?: string,
    authToken?: string,
    timeoutMs: number = MOVIEBOX_CONFIG.requestTimeoutMs
  ): Promise<any> {
    const hosts = this.getHosts();
    const startIdx = this.getActiveHostIndex();
    let backoffMs: number = MOVIEBOX_CONFIG.retryBackoffMs;
    let lastError: MovieBoxError | null = null;
    let authRejectedError: MovieBoxError | null = null;

    for (let i = 0; i < hosts.length; i++) {
      if (i > 0) {
        await new Promise((resolve) => setTimeout(resolve, backoffMs));
        backoffMs = MOVIEBOX_CONFIG.retryBackoffMs;
      }

      const idx = (startIdx + i) % hosts.length;
      const base = hosts[idx];
      const url = `${base}${pathAndQuery}`;

      const headers = this.signRequest({
        method,
        url,
        body,
        authToken,
      });

      try {
        const res = await performHttpRequest(url, method, headers, body, timeoutMs);
        this.absorbXUserHeader(res.headers);

        if (isRetryableHttpStatus(res.status)) {
          this.rotateHost(idx);

          if (res.status === 407) {
            const sigErr = this.buildSignatureRejectedError();
            authRejectedError = sigErr;
            lastError = sigErr;
            continue;
          }

          if (res.status === 429) {
            const retryAfterRaw = getHeaderValue(res.headers, "retry-after");
            const retrySecs = retryAfterRaw ? Number.parseInt(retryAfterRaw, 10) : NaN;
            backoffMs = Number.isFinite(retrySecs)
              ? Math.min(retrySecs * 1000, MOVIEBOX_CONFIG.maxRateLimitDelayMs)
              : 400;
            lastError = new MovieBoxError(
              "UPSTREAM_RATE_LIMITED",
              "MovieBox upstream rate limit reached. Please retry shortly.",
              429,
              true
            );
            continue;
          }

          lastError = new MovieBoxError(
            "UPSTREAM_UNAVAILABLE",
            `MovieBox host returned status ${res.status}`,
            res.status,
            true
          );
          continue;
        }

        const ok = res.status >= 200 && res.status < 300;
        if (!ok) {
          if (res.status === 404) {
            throw new MovieBoxError(
              "RESOURCE_NOT_FOUND",
              "Requested MovieBox resource not found.",
              404,
              false
            );
          }
          if (res.status === 401) {
            throw new MovieBoxError(
              "PROVIDER_AUTH_REQUIRED",
              "MovieBox upstream session expired or unauthorized (HTTP 401).",
              401,
              true
            );
          }
          throw new MovieBoxError(
            "UPSTREAM_UNAVAILABLE",
            `MovieBox upstream error (HTTP ${res.status})`,
            502,
            res.status >= 500
          );
        }

        let parsed: any;
        try {
          parsed = JSON.parse(res.body);
        } catch {
          lastError = new MovieBoxError(
            "PARSE_ERROR",
            "Invalid JSON returned by MovieBox upstream.",
            502,
            true
          );
          continue;
        }

        if (
          typeof parsed?.code === "number" &&
          parsed.code !== 0 &&
          parsed.code !== 200 &&
          (parsed.data === undefined || parsed.data === null)
        ) {
          if (parsed.code === 407) {
            const sigErr = this.buildSignatureRejectedError();
            authRejectedError = sigErr;
            lastError = sigErr;
            continue;
          }
          if (parsed.code === 401 || parsed.code === 403) {
            throw new MovieBoxError(
              "PROVIDER_AUTH_REQUIRED",
              `MovieBox upstream rejected session (code ${parsed.code}).`,
              parsed.code,
              true
            );
          }
          lastError = new MovieBoxError(
            "UPSTREAM_UNAVAILABLE",
            typeof parsed.message === "string" && parsed.message
              ? `MovieBox upstream error: ${parsed.message}`
              : `MovieBox upstream error code ${parsed.code}`,
            502,
            true
          );
          continue;
        }

        this.activeHostIndex = idx;
        return parsed?.data !== undefined && parsed?.data !== null ? parsed.data : parsed;
      } catch (err: any) {
        if (err instanceof MovieBoxError && !err.retryable && err.statusCode === 404) {
          throw err;
        }
        if (err instanceof MovieBoxError && err.statusCode === 401) {
          throw err;
        }
        this.rotateHost(idx);
        if (err?.name === "AbortError") {
          lastError = new MovieBoxError(
            "UPSTREAM_TIMEOUT",
            "MovieBox upstream request timed out.",
            504,
            true
          );
        } else if (err instanceof MovieBoxError) {
          lastError = err;
        } else {
          lastError = new MovieBoxError(
            "UPSTREAM_UNAVAILABLE",
            "Unable to reach MovieBox upstream hosts.",
            502,
            true
          );
        }
      }
    }

    throw (
      authRejectedError ||
      lastError ||
      new MovieBoxError("UPSTREAM_UNAVAILABLE", "All MovieBox API hosts exhausted.", 503, true)
    );
  }

  // --- MovieBox Upstream API Endpoints ---

  public async getHomepage(tabId: string = "0", page: number = 1): Promise<any> {
    const query = new URLSearchParams({
      page: String(page),
      tabId,
      version: "",
    }).toString();
    return this.get(`${MOVIEBOX_CONFIG.endpoints.homepage}?${query}`);
  }

  public async search(query: string, page: number = 1, subjectType: number = 0): Promise<any> {
    return this.post(MOVIEBOX_CONFIG.endpoints.search, {
      keyword: query,
      page,
      perPage: MOVIEBOX_CONFIG.perPageSearch,
      subjectType,
    });
  }

  public async suggest(query: string): Promise<any> {
    return this.search(query, 1, 0);
  }

  public async getSubjectDetails(subjectId: string): Promise<any> {
    const path = `${MOVIEBOX_CONFIG.endpoints.details}?subjectId=${encodeURIComponent(subjectId)}`;
    return this.get(path);
  }

  public async getDetails(subjectId: string): Promise<any> {
    const details = await this.getSubjectDetails(subjectId);

    const stype = Number(
      details?.subjectType ??
        details?.stype ??
        details?.subject?.subjectType ??
        details?.subject?.stype ??
        1
    );
    if (stype === 2 && details && typeof details === "object") {
      try {
        const seasonPath = `${MOVIEBOX_CONFIG.endpoints.seasonInfo}?subjectId=${encodeURIComponent(subjectId)}`;
        const seasonInfo = await this.get(seasonPath);
        details.seasons = seasonInfo;
      } catch {
        // Keep base details even if season-info fails
      }
    }
    return details;
  }

  public async fetchCollectionResolutions(subjectId: string): Promise<any> {
    const params = new URLSearchParams({
      subjectId,
      page: "1",
      perPage: String(MOVIEBOX_CONFIG.perPageResources),
    });
    return this.get(`${MOVIEBOX_CONFIG.endpoints.resourceV1}?${params.toString()}`);
  }

  public async getPlayInfo(
    subjectId: string,
    season: number = 0,
    episode: number = 0
  ): Promise<any> {
    const params = new URLSearchParams({ subjectId });
    if (season > 0 || episode > 0) {
      params.set("se", String(season));
      params.set("ep", String(episode));
    }
    return this.get(`${MOVIEBOX_CONFIG.endpoints.playInfoV2}?${params.toString()}`);
  }

  public async getEpisodeResourcesV2(
    subjectId: string,
    season: number,
    episode: number
  ): Promise<any> {
    const params = new URLSearchParams({
      subjectId,
      page: "1",
      perPage: String(MOVIEBOX_CONFIG.perPageResources),
      all: "0",
      startPosition: "1",
      endPosition: "1",
      pagerMode: "2",
      resolution: "0",
      se: String(season),
      epFrom: String(episode),
      epTo: String(episode),
      isVip: "true",
    });
    return this.get(`${MOVIEBOX_CONFIG.endpoints.resourceV2}?${params.toString()}`);
  }

  public async getResourcesPage(
    subjectId: string,
    season: number = 0,
    episode: number = 0,
    resolution: number = 0,
    page: number = 1
  ): Promise<any> {
    const params = new URLSearchParams({
      subjectId,
      page: String(page),
      perPage: String(MOVIEBOX_CONFIG.perPageResources),
    });
    if (season > 0 || episode > 0) {
      params.set("se", String(season));
      params.set("ep", String(episode));
    }
    if (resolution > 0) {
      params.set("resolution", String(resolution));
    }
    return this.get(`${MOVIEBOX_CONFIG.endpoints.resourceV1}?${params.toString()}`);
  }

  public async getResourcePosition(
    subjectId: string,
    resourceId: string,
    failedUrl: string
  ): Promise<any> {
    const params = new URLSearchParams({
      subjectId,
      resourceId,
      failUrl: failedUrl,
      failCode: "404",
      resourceNum: "0",
      isVip: "true",
    });
    return this.get(`${MOVIEBOX_CONFIG.endpoints.resourcePositionV2}?${params.toString()}`);
  }

  public async getExtCaptions(subjectId: string, resourceId: string): Promise<any> {
    const params = new URLSearchParams({ subjectId, resourceId });
    return this.get(
      `${MOVIEBOX_CONFIG.endpoints.extCaptions}?${params.toString()}`,
      MOVIEBOX_CONFIG.captionTimeoutMs
    );
  }
}

export const movieBoxService = new MovieBoxService();
