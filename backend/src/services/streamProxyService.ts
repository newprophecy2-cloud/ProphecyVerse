import crypto from "crypto";
import http from "http";
import https from "https";
import { Request, Response } from "express";
import { env } from "../config/env";
import { decodeSigningSecret } from "../providers/moviebox/crypto";

const TOKEN_VERSION = "v1";
const MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
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

type ProxyTokenPayload = {
  target: string;
  cookie?: string;
  kind: "media" | "subtitle";
  exp: number;
};

type UpstreamResponse = {
  statusCode: number;
  headers: http.IncomingHttpHeaders;
  body?: Buffer;
  response?: http.IncomingMessage;
};

function base64Url(value: string | Buffer): string {
  return Buffer.from(value).toString("base64url");
}

function sign(value: string): string | null {
  const key = decodeSigningSecret(env.streamProxySecret);
  if (!key) return null;
  return crypto.createHmac("sha256", key).update(value, "utf8").digest("base64url");
}

function mediaHostAllowed(rawUrl: string): boolean {
  try {
    const parsed = new URL(rawUrl);
    if (parsed.protocol !== "https:") return false;
    const host = parsed.hostname.toLowerCase();
    return env.movieboxMediaHosts.some((allowed) => {
      const normalized = allowed.toLowerCase().replace(/^\.+/, "");
      return host === normalized || host.endsWith(`.${normalized}`);
    });
  } catch {
    return false;
  }
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

function decodeToken(token: string): ProxyTokenPayload | null {
  const parts = token.split(".");
  if (parts.length !== 3 || parts[0] !== TOKEN_VERSION) return null;
  const [version, encoded, suppliedSignature] = parts;
  const expected = sign(`${version}.${encoded}`);
  if (!expected || suppliedSignature.length !== expected.length) return null;
  if (!crypto.timingSafeEqual(Buffer.from(suppliedSignature), Buffer.from(expected))) return null;

  try {
    const payload = JSON.parse(Buffer.from(encoded, "base64url").toString("utf8")) as ProxyTokenPayload;
    if (
      !payload ||
      (payload.kind !== "media" && payload.kind !== "subtitle") ||
      typeof payload.target !== "string" ||
      typeof payload.exp !== "number" ||
      payload.exp < Math.floor(Date.now() / 1000) ||
      !mediaHostAllowed(payload.target)
    ) {
      return null;
    }
    return payload;
  } catch {
    return null;
  }
}

function proxyPath(token: string): string {
  return `/api/v1/stream-proxy?token=${encodeURIComponent(token)}`;
}

function requestUpstream(
  payload: ProxyTokenPayload,
  req: Request,
  streamBody: boolean
): Promise<UpstreamResponse> {
  return new Promise((resolve, reject) => {
    const target = new URL(payload.target);
    const transport = target.protocol === "https:" ? https : http;
    const headers: Record<string, string> = {
      Accept: req.headers.accept || "*/*",
      "User-Agent": env.movieboxUserAgent,
      Referer: "https://www.inmoviebox.com/",
    };

    for (const name of ["range", "if-range", "if-none-match", "if-modified-since"]) {
      const value = req.headers[name];
      if (typeof value === "string" && value.length <= 512) headers[name] = value;
    }
    const cookie = safeCookie(payload.cookie);
    if (cookie) headers.Cookie = cookie;

    let settled = false;
    const upstreamReq = transport.request(
      target,
      { method: req.method === "HEAD" ? "HEAD" : "GET", headers, timeout: env.streamProxyTimeoutMs },
      (upstreamRes) => {
        if (settled) return;
        const statusCode = upstreamRes.statusCode || 502;
        const contentType = String(upstreamRes.headers["content-type"] || "").toLowerCase();
        const bufferResponse = !streamBody || contentType.includes("mpegurl") || contentType.includes("dash+xml");
        if (bufferResponse) {
          const chunks: Buffer[] = [];
          let size = 0;
          upstreamRes.on("data", (chunk: Buffer | string) => {
            const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
            size += buf.length;
            if (size <= MAX_MANIFEST_BYTES) chunks.push(buf);
            else upstreamRes.destroy(new Error("Manifest exceeds configured limit"));
          });
          upstreamRes.on("end", () => {
            if (settled) return;
            settled = true;
            resolve({ statusCode, headers: upstreamRes.headers, body: Buffer.concat(chunks) });
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
      }
    );

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

function isManifest(target: string, headers: http.IncomingHttpHeaders): boolean {
  const type = String(headers["content-type"] || "").toLowerCase();
  const clean = target.split("?")[0].toLowerCase();
  return type.includes("mpegurl") || type.includes("dash+xml") || clean.endsWith(".m3u8") || clean.endsWith(".mpd");
}

function rewriteHlsManifest(body: string, target: string, cookie?: string): string {
  const makeProxy = (raw: string) => {
    try {
      const absolute = new URL(raw, target).toString();
      if (!mediaHostAllowed(absolute)) return raw;
      const token = encodePayload({ target: absolute, cookie, kind: "media", exp: Math.floor(Date.now() / 1000) + env.streamProxyTtlSeconds });
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

function rewriteDashManifest(body: string, target: string, cookie?: string): string {
  const makeProxy = (raw: string) => {
    try {
      const absolute = new URL(raw, target).toString();
      if (!mediaHostAllowed(absolute) || raw.includes("$Number$") || raw.includes("$Time$")) return raw;
      const token = encodePayload({ target: absolute, cookie, kind: "media", exp: Math.floor(Date.now() / 1000) + env.streamProxyTtlSeconds });
      return token ? proxyPath(token) : raw;
    } catch {
      return raw;
    }
  };

  return body
    .replace(/(<BaseURL[^>]*>)([^<]+)(<\/BaseURL>)/gi, (_match, start: string, value: string, end: string) => `${start}${makeProxy(value.trim())}${end}`)
    .replace(/(\b(?:media|initialization|sourceURL)=")(.*?)(")/gi, (_match, start: string, value: string, end: string) => `${start}${makeProxy(value)}${end}`);
}

function setCors(res: Response): void {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Range, If-Range, Content-Type, Accept");
  res.setHeader("Access-Control-Expose-Headers", "Accept-Ranges, Content-Length, Content-Range, Content-Type, ETag");
  res.setHeader("Vary", "Origin");
}

function copyResponseHeaders(res: Response, headers: http.IncomingHttpHeaders): void {
  for (const [name, value] of Object.entries(headers)) {
    if (HOP_BY_HOP_HEADERS.has(name.toLowerCase()) || name.toLowerCase() === "set-cookie") continue;
    if (name.toLowerCase() === "content-security-policy") continue;
    if (typeof value === "string" || Array.isArray(value)) res.setHeader(name, value);
  }
  res.setHeader("Accept-Ranges", "bytes");
}

export class StreamProxyService {
  public createProxyUrl(rawUrl: string, kind: "media" | "subtitle", cookie?: string): string | null {
    if (!mediaHostAllowed(rawUrl)) return null;
    const token = encodePayload({
      target: rawUrl,
      cookie: safeCookie(cookie),
      kind,
      exp: Math.floor(Date.now() / 1000) + env.streamProxyTtlSeconds,
    });
    return token ? proxyPath(token) : null;
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
    const payload = decodeToken(token);
    if (!payload) {
      res.status(403).json({ status: "error", code: "STREAM_PROXY_TOKEN_INVALID", message: "Invalid or expired stream proxy token." });
      return;
    }

    try {
      const shouldBuffer = payload.kind === "subtitle" || isManifest(payload.target, {});
      const upstream = await requestUpstream(payload, req, !shouldBuffer);
      if (upstream.statusCode < 200 || upstream.statusCode >= 400) {
        if (upstream.response) upstream.response.resume();
        res.status(upstream.statusCode >= 400 ? upstream.statusCode : 502).end();
        return;
      }

      if (upstream.body) {
        let body = upstream.body;
        let contentType = String(upstream.headers["content-type"] || "application/octet-stream");
        if (isManifest(payload.target, upstream.headers)) {
          const text = body.toString("utf8");
          body = contentType.includes("mpegurl") || payload.target.toLowerCase().includes(".m3u8")
            ? Buffer.from(rewriteHlsManifest(text, payload.target, payload.cookie), "utf8")
            : Buffer.from(rewriteDashManifest(text, payload.target, payload.cookie), "utf8");
          contentType = contentType.includes("mpegurl") ? "application/vnd.apple.mpegurl" : "application/dash+xml";
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
    } catch (error) {
      if (!res.headersSent) {
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
}

export const streamProxyService = new StreamProxyService();
