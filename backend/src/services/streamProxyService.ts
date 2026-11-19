import crypto from "crypto";
import dns from "dns";
import http from "http";
import https from "https";
import net from "net";
import zlib from "zlib";
import { Request, Response } from "express";
import { env } from "../config/env";
import { decodeSigningSecret } from "../providers/moviebox/crypto";

const TOKEN_VERSION = "v1";
const MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
const MAX_REDIRECTS = 5;

const HOP_BY_HOP_HEADERS = new Set([
  "connection",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
]);

export type ProxyTokenKind = "media" | "subtitle" | "dir";

export type ProxyTokenPayload = {
  target: string;
  cookie?: string;
  kind: ProxyTokenKind;
  exp: number;
};

type UpstreamResponse = {
  statusCode: number;
  headers: http.IncomingHttpHeaders;
  body?: Buffer;
  response?: http.IncomingMessage;
};

export type DnsResolveFn = (
  hostname: string
) => Promise<Array<{ address: string; family: number }>>;

export interface StreamProxyOptions {
  allowInsecureHttpForTest?: boolean;
  allowPrivateIpForTest?: boolean;
  dnsLookupFn?: DnsResolveFn;
}

class ProxySecurityError extends Error {
  public readonly statusCode: number;
  public readonly code: string;

  constructor(code: string, message: string, statusCode: number = 403) {
    super(message);
    this.name = "ProxySecurityError";
    this.code = code;
    this.statusCode = statusCode;
  }
}

function base64Url(value: string | Buffer): string {
  return Buffer.from(value).toString("base64url");
}

function sign(value: string): string | null {
  const key = decodeSigningSecret(env.streamProxySecret);
  if (!key) return null;
  return crypto.createHmac("sha256", key).update(value, "utf8").digest("base64url");
}

function isIpv4PrivateOrReserved(ip: string): boolean {
  const parts = ip.split(".").map((part) => Number.parseInt(part, 10));
  if (parts.length !== 4 || parts.some((p) => !Number.isFinite(p) || p < 0 || p > 255)) {
    return true;
  }
  const [o1, o2, o3] = parts;
  if (o1 === 0) return true; // 0.0.0.0/8
  if (o1 === 10) return true; // 10.0.0.0/8
  if (o1 === 100 && o2 >= 64 && o2 <= 127) return true; // 100.64.0.0/10 CGNAT
  if (o1 === 127) return true; // 127.0.0.0/8 loopback
  if (o1 === 169 && o2 === 254) return true; // 169.254.0.0/16 link-local / cloud metadata
  if (o1 === 172 && o2 >= 16 && o2 <= 31) return true; // 172.16.0.0/12
  if (o1 === 192 && o2 === 0 && (o3 === 0 || o3 === 2)) return true; // 192.0.0.0/24 & TEST-NET-1
  if (o1 === 192 && o2 === 168) return true; // 192.168.0.0/16
  if (o1 === 198 && (o2 === 18 || o2 === 19)) return true; // 198.18.0.0/15 benchmark
  if (o1 === 198 && o2 === 51 && o3 === 100) return true; // TEST-NET-2
  if (o1 === 203 && o2 === 0 && o3 === 113) return true; // TEST-NET-3
  if (o1 >= 224) return true; // Multicast & reserved
  return false;
}

export function isPrivateOrReservedIp(rawIp: string): boolean {
  const withoutZone = rawIp.split("%")[0]?.trim().toLowerCase() || "";
  if (!withoutZone) return true;

  const ipVersion = net.isIP(withoutZone);
  if (ipVersion === 4) {
    return isIpv4PrivateOrReserved(withoutZone);
  }
  if (ipVersion !== 6) {
    return true;
  }

  if (withoutZone === "::" || withoutZone === "::1") return true;

  // Handle IPv4-mapped IPv6 addresses: ::ffff:127.0.0.1 or ::ffff:7f00:0001
  if (withoutZone.startsWith("::ffff:")) {
    const tail = withoutZone.slice("::ffff:".length);
    if (net.isIPv4(tail)) {
      return isIpv4PrivateOrReserved(tail);
    }
    const hexParts = tail.split(":");
    if (hexParts.length === 2) {
      const hi = Number.parseInt(hexParts[0], 16);
      const lo = Number.parseInt(hexParts[1], 16);
      if (Number.isFinite(hi) && Number.isFinite(lo)) {
        const mappedV4 = `${(hi >> 8) & 0xff}.${hi & 0xff}.${(lo >> 8) & 0xff}.${lo & 0xff}`;
        return isIpv4PrivateOrReserved(mappedV4);
      }
    }
    return true;
  }

  // Expand first hextet to catch fc00::/7 (ULA), fe80::/10 (link-local), ff00::/8 (multicast)
  const firstSegment = withoutZone.split(":")[0] || "0";
  const firstHextet = Number.parseInt(firstSegment, 16);
  if (!Number.isFinite(firstHextet)) return true;
  if ((firstHextet & 0xfe00) === 0xfc00) return true; // fc00::/7
  if ((firstHextet & 0xffc0) === 0xfe80) return true; // fe80::/10
  if ((firstHextet & 0xff00) === 0xff00) return true; // ff00::/8

  return false;
}

function defaultDnsLookup(
  hostname: string
): Promise<Array<{ address: string; family: number }>> {
  return dns.promises.lookup(hostname, { all: true });
}

function safeCookie(raw: string | undefined): string | undefined {
  if (!raw) return undefined;
  const value = raw.trim();
  if (!value || value.length > 8192 || /[\r\n]/.test(value)) return undefined;
  return value;
}

function encodePayload(payload: ProxyTokenPayload): string | null {
  const encoded = base64Url(JSON.stringify(payload));
  const signature = sign(`${TOKEN_VERSION}.${encoded}`);
  return signature ? `${TOKEN_VERSION}.${encoded}.${signature}` : null;
}

function proxyPath(token: string): string {
  return `/api/v1/stream-proxy?token=${encodeURIComponent(token)}`;
}

function decompressBufferIfNeeded(
  buffer: Buffer,
  contentEncodingHeader: string | string[] | undefined
): Buffer {
  const encoding = String(
    Array.isArray(contentEncodingHeader) ? contentEncodingHeader[0] : contentEncodingHeader || ""
  )
    .trim()
    .toLowerCase();

  if (!encoding || encoding === "identity") {
    return buffer;
  }
  try {
    if (encoding === "gzip" || encoding === "x-gzip") {
      return zlib.gunzipSync(buffer);
    }
    if (encoding === "deflate") {
      return zlib.inflateSync(buffer);
    }
    if (encoding === "br") {
      return zlib.brotliDecompressSync(buffer);
    }
  } catch {
    return buffer;
  }
  return buffer;
}

function isManifest(target: string, headers: http.IncomingHttpHeaders): boolean {
  const type = String(headers["content-type"] || "").toLowerCase();
  const clean = target.split(/[?#]/)[0]?.toLowerCase() || "";
  return (
    type.includes("mpegurl") ||
    type.includes("dash+xml") ||
    clean.endsWith(".m3u8") ||
    clean.endsWith(".mpd")
  );
}

function setCors(res: Response): void {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Range, If-Range, Content-Type, Accept");
  res.setHeader(
    "Access-Control-Expose-Headers",
    "Accept-Ranges, Content-Length, Content-Range, Content-Type, ETag"
  );
}

function copyResponseHeaders(res: Response, headers: http.IncomingHttpHeaders): void {
  for (const [name, value] of Object.entries(headers)) {
    const lower = name.toLowerCase();
    if (HOP_BY_HOP_HEADERS.has(lower)) continue;
    if (lower === "set-cookie" || lower === "content-security-policy") continue;
    if (typeof value === "string" || Array.isArray(value)) {
      res.setHeader(name, value);
    }
  }
  res.setHeader("Accept-Ranges", "bytes");
}

function splitDashTemplatePrefix(
  rawAttr: string,
  baseTarget: string
): { dirUrl: string; templateSuffix: string } | null {
  const templateIdx = rawAttr.indexOf("$");
  if (templateIdx === -1) return null;

  const beforeTemplate = rawAttr.slice(0, templateIdx);
  const lastSlash = beforeTemplate.lastIndexOf("/");
  const relDir = lastSlash >= 0 ? beforeTemplate.slice(0, lastSlash + 1) : "./";
  const templateSuffix = lastSlash >= 0 ? rawAttr.slice(lastSlash + 1) : rawAttr;

  try {
    const dirUrl = new URL(relDir, baseTarget).toString();
    return { dirUrl, templateSuffix };
  } catch {
    return null;
  }
}

export class StreamProxyService {
  private readonly allowInsecureHttp: boolean;
  private readonly allowPrivateIp: boolean;
  private readonly dnsLookup: DnsResolveFn;

  constructor(options: StreamProxyOptions = {}) {
    this.allowInsecureHttp = Boolean(options.allowInsecureHttpForTest);
    this.allowPrivateIp = Boolean(options.allowPrivateIpForTest);
    this.dnsLookup = options.dnsLookupFn || defaultDnsLookup;
  }

  public isHostAllowed(hostname: string): boolean {
    const host = hostname.toLowerCase().replace(/\.$/, "");
    if (!host) return false;
    return env.movieboxMediaHosts.some((allowed) => {
      const normalized = allowed.toLowerCase().replace(/^\.+/, "");
      return host === normalized || host.endsWith(`.${normalized}`);
    });
  }

  public isTargetUrlAllowed(rawUrl: string): boolean {
    try {
      const parsed = new URL(rawUrl);
      const allowedProtocol =
        parsed.protocol === "https:" || (this.allowInsecureHttp && parsed.protocol === "http:");
      if (!allowedProtocol) return false;
      if (parsed.username || parsed.password) return false;
      if (!this.allowInsecureHttp && parsed.port && parsed.port !== "443") return false;

      const host = parsed.hostname.toLowerCase().replace(/^\[|\]$/g, "");
      if (!this.allowPrivateIp && net.isIP(host) !== 0) {
        return false;
      }
      return this.isHostAllowed(host);
    } catch {
      return false;
    }
  }

  private async resolveSafeAddress(rawUrl: string): Promise<{
    url: URL;
    address: string;
    family: number;
  }> {
    if (!this.isTargetUrlAllowed(rawUrl)) {
      throw new ProxySecurityError(
        "STREAM_PROXY_HOST_FORBIDDEN",
        "Target media host or protocol is not allowed by server policy.",
        403
      );
    }

    const parsed = new URL(rawUrl);
    const hostname = parsed.hostname.replace(/^\[|\]$/g, "");

    if (net.isIP(hostname) !== 0) {
      if (!this.allowPrivateIp && isPrivateOrReservedIp(hostname)) {
        throw new ProxySecurityError(
          "STREAM_PROXY_SSRF_BLOCKED",
          "Target IP address is private or reserved.",
          403
        );
      }
      return {
        url: parsed,
        address: hostname,
        family: net.isIPv6(hostname) ? 6 : 4,
      };
    }

    const records = await this.dnsLookup(hostname);
    if (!records || records.length === 0) {
      throw new ProxySecurityError(
        "STREAM_PROXY_DNS_ERROR",
        "Unable to resolve upstream media host.",
        502
      );
    }

    for (const rec of records) {
      if (!this.allowPrivateIp && isPrivateOrReservedIp(rec.address)) {
        throw new ProxySecurityError(
          "STREAM_PROXY_SSRF_BLOCKED",
          "Upstream hostname resolved to a private or reserved network address.",
          403
        );
      }
    }

    return {
      url: parsed,
      address: records[0].address,
      family: records[0].family || (net.isIPv6(records[0].address) ? 6 : 4),
    };
  }

  public decodeToken(token: string, expectedKind?: ProxyTokenKind): ProxyTokenPayload | null {
    const parts = token.split(".");
    if (parts.length !== 3 || parts[0] !== TOKEN_VERSION) return null;
    const [version, encoded, suppliedSignature] = parts;
    const expected = sign(`${version}.${encoded}`);
    if (!expected || suppliedSignature.length !== expected.length) return null;
    if (!crypto.timingSafeEqual(Buffer.from(suppliedSignature), Buffer.from(expected))) {
      return null;
    }

    try {
      const payload = JSON.parse(
        Buffer.from(encoded, "base64url").toString("utf8")
      ) as ProxyTokenPayload;
      if (
        !payload ||
        (payload.kind !== "media" && payload.kind !== "subtitle" && payload.kind !== "dir") ||
        (expectedKind && payload.kind !== expectedKind) ||
        typeof payload.target !== "string" ||
        typeof payload.exp !== "number" ||
        payload.exp < Math.floor(Date.now() / 1000) ||
        !this.isTargetUrlAllowed(payload.target)
      ) {
        return null;
      }
      return payload;
    } catch {
      return null;
    }
  }

  public createProxyUrl(
    rawUrl: string,
    kind: "media" | "subtitle",
    cookie?: string
  ): string | null {
    if (!this.isTargetUrlAllowed(rawUrl)) return null;
    const token = encodePayload({
      target: rawUrl,
      cookie: safeCookie(cookie),
      kind,
      exp: Math.floor(Date.now() / 1000) + env.streamProxyTtlSeconds,
    });
    return token ? proxyPath(token) : null;
  }

  public createDirProxyToken(dirUrl: string, cookie?: string): string | null {
    if (!this.isTargetUrlAllowed(dirUrl)) return null;
    return encodePayload({
      target: dirUrl,
      cookie: safeCookie(cookie),
      kind: "dir",
      exp: Math.floor(Date.now() / 1000) + env.streamProxyTtlSeconds,
    });
  }

  public rewriteHlsManifest(body: string, target: string, cookie?: string): string {
    const makeProxy = (raw: string) => {
      const trimmed = raw.trim();
      if (!trimmed) return raw;
      try {
        const absolute = new URL(trimmed, target).toString();
        if (!this.isTargetUrlAllowed(absolute)) return raw;
        const token = encodePayload({
          target: absolute,
          cookie,
          kind: "media",
          exp: Math.floor(Date.now() / 1000) + env.streamProxyTtlSeconds,
        });
        return token ? proxyPath(token) : raw;
      } catch {
        return raw;
      }
    };

    return body
      .split(/\r?\n/)
      .map((line) => {
        if (!line.trim()) return line;
        if (line.startsWith("#")) {
          return line.replace(/URI="([^"]+)"/g, (_match, uri: string) => `URI="${makeProxy(uri)}"`);
        }
        return makeProxy(line.trim());
      })
      .join("\n");
  }

  public rewriteDashManifest(body: string, target: string, cookie?: string): string {
    let resolvedBase = target;
    const baseMatch = body.match(/<BaseURL[^>]*>([^<]+)<\/BaseURL>/i);
    if (baseMatch && baseMatch[1]) {
      try {
        const candidateBase = new URL(baseMatch[1].trim(), target).toString();
        if (this.isTargetUrlAllowed(candidateBase)) {
          resolvedBase = candidateBase;
        }
      } catch {
        // keep MPD URL as base
      }
    }

    const makeExactProxy = (raw: string): string => {
      try {
        const absolute = new URL(raw.trim(), resolvedBase).toString();
        if (!this.isTargetUrlAllowed(absolute)) return raw;
        const token = encodePayload({
          target: absolute,
          cookie,
          kind: "media",
          exp: Math.floor(Date.now() / 1000) + env.streamProxyTtlSeconds,
        });
        return token ? proxyPath(token) : raw;
      } catch {
        return raw;
      }
    };

    const makeTemplateAwareProxy = (raw: string): string => {
      const trimmed = raw.trim();
      if (!trimmed) return raw;
      if (!trimmed.includes("$")) {
        return makeExactProxy(trimmed);
      }

      const split = splitDashTemplatePrefix(trimmed, resolvedBase);
      if (!split) return raw;

      const dirToken = this.createDirProxyToken(split.dirUrl, cookie);
      if (!dirToken) return raw;
      return `/api/v1/stream-proxy/dir/${encodeURIComponent(dirToken)}/${split.templateSuffix}`;
    };

    return body
      .replace(
        /(<BaseURL[^>]*>)([^<]+)(<\/BaseURL>)/gi,
        (_match, start: string, value: string, end: string) => {
          const trimmed = value.trim();
          // Rewrite exact file BaseURLs; if BaseURL is a directory prefix for SegmentTemplate, strip or proxy as dir
          if (trimmed.endsWith("/")) {
            const dirToken = this.createDirProxyToken(
              new URL(trimmed, target).toString(),
              cookie
            );
            return dirToken
              ? `${start}/api/v1/stream-proxy/dir/${encodeURIComponent(dirToken)}/${end}`
              : `${start}${value}${end}`;
          }
          return `${start}${makeExactProxy(trimmed)}${end}`;
        }
      )
      .replace(
        /(\b(?:media|initialization|sourceURL)=")([^"]+)(")/gi,
        (_match, start: string, value: string, end: string) =>
          `${start}${makeTemplateAwareProxy(value)}${end}`
      );
  }

  private async requestSingleHop(
    targetUrl: string,
    cookie: string | undefined,
    req: Request,
    streamBody: boolean
  ): Promise<{
    statusCode: number;
    headers: http.IncomingHttpHeaders;
    body?: Buffer;
    response?: http.IncomingMessage;
    redirectLocation?: string;
  }> {
    const resolved = await this.resolveSafeAddress(targetUrl);

    return new Promise((resolve, reject) => {
      const transport = resolved.url.protocol === "https:" ? https : http;
      const headers: Record<string, string> = {
        Accept: typeof req.headers.accept === "string" ? req.headers.accept : "*/*",
        "User-Agent": env.movieboxUserAgent,
      };

      if (env.streamReferer) {
        headers.Referer = env.streamReferer;
      }

      if (!streamBody) {
        headers["Accept-Encoding"] = "identity";
      } else {
        for (const name of ["range", "if-range", "if-none-match", "if-modified-since"]) {
          const value = req.headers[name];
          if (typeof value === "string" && value.length <= 512) {
            headers[name] = value;
          }
        }
      }

      const safeCk = safeCookie(cookie);
      if (safeCk) {
        headers.Cookie = safeCk;
      }

      let settled = false;
      const requestOptions: https.RequestOptions = {
        method: req.method === "HEAD" ? "HEAD" : "GET",
        headers,
        timeout: env.streamProxyTimeoutMs,
        lookup: (_hostname, opts, cb) => {
          if (opts && typeof opts === "object" && (opts as dns.LookupOptions).all) {
            (
              cb as unknown as (
                err: NodeJS.ErrnoException | null,
                addresses: dns.LookupAddress[]
              ) => void
            )(null, [{ address: resolved.address, family: resolved.family }]);
            return;
          }
          cb(null, resolved.address, resolved.family);
        },
      };

      const upstreamReq = transport.request(resolved.url, requestOptions, (upstreamRes) => {
        if (settled) return;
        const statusCode = upstreamRes.statusCode || 502;
        const location = upstreamRes.headers.location;

        if ([301, 302, 303, 307, 308].includes(statusCode) && typeof location === "string" && location) {
          settled = true;
          upstreamRes.resume();
          resolve({
            statusCode,
            headers: upstreamRes.headers,
            redirectLocation: location,
          });
          return;
        }

        const contentType = String(upstreamRes.headers["content-type"] || "").toLowerCase();
        const bufferResponse =
          !streamBody || contentType.includes("mpegurl") || contentType.includes("dash+xml");

        if (bufferResponse) {
          const chunks: Buffer[] = [];
          let size = 0;
          upstreamRes.on("data", (chunk: Buffer | string) => {
            const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
            size += buf.length;
            if (size <= MAX_MANIFEST_BYTES) {
              chunks.push(buf);
            } else {
              upstreamRes.destroy(new Error("Manifest or subtitle exceeds configured size limit"));
            }
          });
          upstreamRes.on("end", () => {
            if (settled) return;
            settled = true;
            const rawBuf = Buffer.concat(chunks);
            const decompressed = decompressBufferIfNeeded(
              rawBuf,
              upstreamRes.headers["content-encoding"]
            );
            resolve({
              statusCode,
              headers: upstreamRes.headers,
              body: decompressed,
            });
          });
          upstreamRes.on("error", (error) => {
            if (settled) return;
            settled = true;
            reject(error);
          });
        } else {
          settled = true;
          resolve({ statusCode, headers: upstreamRes.headers, response: upstreamRes });
        }
      });

      upstreamReq.on("timeout", () => {
        if (settled) return;
        settled = true;
        upstreamReq.destroy(new Error("Upstream media request timed out"));
        reject(new Error("Upstream media request timed out"));
      });
      upstreamReq.on("error", (error) => {
        if (settled) return;
        settled = true;
        reject(error);
      });
      upstreamReq.end();
    });
  }

  private async requestUpstreamWithRedirects(
    initialTarget: string,
    cookie: string | undefined,
    req: Request,
    streamBody: boolean
  ): Promise<{ upstream: UpstreamResponse; finalUrl: string }> {
    let currentUrl = initialTarget;
    for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
      const hopRes = await this.requestSingleHop(currentUrl, cookie, req, streamBody);
      if (hopRes.redirectLocation) {
        if (hop === MAX_REDIRECTS) {
          throw new ProxySecurityError(
            "STREAM_PROXY_TOO_MANY_REDIRECTS",
            "Upstream media exceeded maximum redirect limit.",
            502
          );
        }
        const nextUrl = new URL(hopRes.redirectLocation, currentUrl).toString();
        if (!this.isTargetUrlAllowed(nextUrl)) {
          throw new ProxySecurityError(
            "STREAM_PROXY_REDIRECT_FORBIDDEN",
            "Upstream redirect pointed to an untrusted host or insecure protocol.",
            403
          );
        }
        currentUrl = nextUrl;
        continue;
      }
      return {
        upstream: {
          statusCode: hopRes.statusCode,
          headers: hopRes.headers,
          body: hopRes.body,
          response: hopRes.response,
        },
        finalUrl: currentUrl,
      };
    }

    throw new ProxySecurityError(
      "STREAM_PROXY_TOO_MANY_REDIRECTS",
      "Upstream media exceeded maximum redirect limit.",
      502
    );
  }

  private async proxyResolvedTarget(
    targetUrl: string,
    kind: ProxyTokenKind,
    cookie: string | undefined,
    req: Request,
    res: Response
  ): Promise<void> {
    const shouldBuffer = kind === "subtitle" || isManifest(targetUrl, {});
    const { upstream, finalUrl } = await this.requestUpstreamWithRedirects(
      targetUrl,
      cookie,
      req,
      !shouldBuffer
    );

    if (upstream.statusCode < 200 || upstream.statusCode >= 400) {
      if (upstream.response) upstream.response.resume();
      res.status(upstream.statusCode >= 400 ? upstream.statusCode : 502).end();
      return;
    }

    if (upstream.body !== undefined) {
      let body = upstream.body;
      let contentType = String(upstream.headers["content-type"] || "application/octet-stream");
      const lowerType = contentType.toLowerCase();
      const cleanPath = finalUrl.split(/[?#]/)[0]?.toLowerCase() || "";

      if (isManifest(finalUrl, upstream.headers)) {
        const text = body.toString("utf8");
        const isHls = lowerType.includes("mpegurl") || cleanPath.endsWith(".m3u8");
        body = isHls
          ? Buffer.from(this.rewriteHlsManifest(text, finalUrl, cookie), "utf8")
          : Buffer.from(this.rewriteDashManifest(text, finalUrl, cookie), "utf8");
        contentType = isHls
          ? "application/vnd.apple.mpegurl; charset=utf-8"
          : "application/dash+xml; charset=utf-8";
      } else if (kind === "subtitle") {
        if (cleanPath.endsWith(".vtt")) {
          contentType = "text/vtt; charset=utf-8";
        } else if (cleanPath.endsWith(".srt")) {
          contentType = "text/plain; charset=utf-8";
        }
      }

      res.status(upstream.statusCode).setHeader("Content-Type", contentType);
      res.setHeader("Content-Length", String(body.length));
      res.setHeader("Cache-Control", "private, no-store");
      res.end(req.method === "HEAD" ? undefined : body);
      return;
    }

    copyResponseHeaders(res, upstream.headers);
    res.setHeader("Cache-Control", "private, no-store");
    res.status(upstream.statusCode);
    if (req.method === "HEAD" || !upstream.response) {
      upstream.response?.resume();
      res.end();
      return;
    }
    req.on("close", () => upstream.response?.destroy());
    upstream.response.pipe(res);
  }

  public async handle(req: Request, res: Response): Promise<void> {
    setCors(res);
    if (req.method === "OPTIONS") {
      res.status(204).end();
      return;
    }
    if (req.method !== "GET" && req.method !== "HEAD") {
      res.status(405).setHeader("Allow", "GET, HEAD, OPTIONS").end();
      return;
    }

    const token = typeof req.query.token === "string" ? req.query.token : "";
    const payload = this.decodeToken(token);
    if (!payload || payload.kind === "dir") {
      res.status(403).json({
        status: "error",
        code: "STREAM_PROXY_TOKEN_INVALID",
        message: "Invalid or expired stream proxy token.",
      });
      return;
    }

    try {
      await this.proxyResolvedTarget(payload.target, payload.kind, payload.cookie, req, res);
    } catch (error) {
      if (!res.headersSent) {
        if (error instanceof ProxySecurityError) {
          res.status(error.statusCode).json({
            status: "error",
            code: error.code,
            message: error.message,
            retryable: false,
          });
          return;
        }
        res.status(504).json({
          status: "error",
          code: "STREAM_PROXY_UPSTREAM_ERROR",
          message: error instanceof Error ? error.message : "Unable to fetch upstream media.",
          retryable: true,
        });
      } else {
        res.destroy(error instanceof Error ? error : undefined);
      }
    }
  }

  public async handleDirSegment(req: Request, res: Response): Promise<void> {
    setCors(res);
    if (req.method === "OPTIONS") {
      res.status(204).end();
      return;
    }
    if (req.method !== "GET" && req.method !== "HEAD") {
      res.status(405).setHeader("Allow", "GET, HEAD, OPTIONS").end();
      return;
    }

    const token = typeof req.params.token === "string" ? req.params.token : "";
    const payload = this.decodeToken(token, "dir");
    if (!payload) {
      res.status(403).json({
        status: "error",
        code: "STREAM_PROXY_TOKEN_INVALID",
        message: "Invalid or expired stream directory proxy token.",
      });
      return;
    }

    const rawRel = typeof req.params[0] === "string" ? req.params[0] : "";
    let decodedRel = "";
    try {
      decodedRel = decodeURIComponent(rawRel);
    } catch {
      res.status(400).json({
        status: "error",
        code: "INVALID_INPUT",
        message: "Malformed segment path.",
      });
      return;
    }

    if (
      !rawRel ||
      rawRel.startsWith("/") ||
      decodedRel.startsWith("/") ||
      rawRel.includes("\\") ||
      decodedRel.includes("\\") ||
      rawRel.includes("\0") ||
      decodedRel.includes("\0") ||
      rawRel.toLowerCase().includes("%2e%2e") ||
      rawRel.split("/").includes("..") ||
      decodedRel.split("/").includes("..") ||
      /^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(decodedRel)
    ) {
      res.status(403).json({
        status: "error",
        code: "STREAM_PROXY_PATH_FORBIDDEN",
        message: "Segment path traversal or scheme override is not permitted.",
      });
      return;
    }

    let resolvedUrl: URL;
    let basePrefixUrl: URL;
    try {
      basePrefixUrl = new URL(payload.target);
      resolvedUrl = new URL(rawRel, basePrefixUrl);
    } catch {
      res.status(400).json({
        status: "error",
        code: "INVALID_INPUT",
        message: "Invalid relative segment path.",
      });
      return;
    }

    if (
      resolvedUrl.origin !== basePrefixUrl.origin ||
      !resolvedUrl.pathname.startsWith(basePrefixUrl.pathname)
    ) {
      res.status(403).json({
        status: "error",
        code: "STREAM_PROXY_PATH_FORBIDDEN",
        message: "Segment escapes authorized manifest prefix.",
      });
      return;
    }

    try {
      await this.proxyResolvedTarget(
        resolvedUrl.toString(),
        "media",
        payload.cookie,
        req,
        res
      );
    } catch (error) {
      if (!res.headersSent) {
        if (error instanceof ProxySecurityError) {
          res.status(error.statusCode).json({
            status: "error",
            code: error.code,
            message: error.message,
            retryable: false,
          });
          return;
        }
        res.status(504).json({
          status: "error",
          code: "STREAM_PROXY_UPSTREAM_ERROR",
          message: error instanceof Error ? error.message : "Unable to fetch upstream segment.",
          retryable: true,
        });
      } else {
        res.destroy(error instanceof Error ? error : undefined);
      }
    }
  }
}

export const streamProxyService = new StreamProxyService();
