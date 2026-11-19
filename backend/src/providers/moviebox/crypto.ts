import crypto from "crypto";
import { env } from "../../config/env";
import { MOVIEBOX_CONFIG } from "./config";

export function md5Hex(input: string | Buffer): string {
  return crypto.createHash("md5").update(input).digest("hex");
}

export type SigningSecretFormat =
  | "none"
  | "base64"
  | "hex"
  | "escaped"
  | "utf8"
  | "invalid";

export interface MovieBoxAuthDiagnostics {
  signingSecretConfigured: boolean;
  signingSecretValid: boolean;
  signingSecretFormat: SigningSecretFormat;
  authTokenConfigured: boolean;
  authTokenValidFormat: boolean;
  buildTagConfigured: boolean;
  clientInfoConfigured: boolean;
  clientInfoValidJson: boolean;
  customUserAgentConfigured: boolean;
  hostCount: number;
  authMode: "signing_and_token" | "signing_secret" | "auth_token_only" | "unconfigured";
  missingRequirements: string[];
}

export function decodeSigningSecret(rawSecret: string): Buffer | null {
  const trimmed = rawSecret.trim();
  if (!trimmed) return null;

  if (trimmed.startsWith("base64:")) {
    const b64 = trimmed.slice("base64:".length).trim();
    if (!b64) return null;
    const buf = Buffer.from(b64, "base64");
    return buf.length > 0 ? buf : null;
  }

  if (trimmed.startsWith("hex:")) {
    const hex = trimmed.slice("hex:".length).replace(/[\s:_-]+/g, "");
    if (!hex || hex.length % 2 !== 0 || !/^[0-9a-fA-F]+$/.test(hex)) {
      return null;
    }
    const buf = Buffer.from(hex, "hex");
    return buf.length > 0 ? buf : null;
  }

  if (trimmed.startsWith("escaped:") || /^(?:\\x[0-9a-fA-F]{2})+$/.test(trimmed)) {
    const raw = trimmed.startsWith("escaped:") ? trimmed.slice("escaped:".length).trim() : trimmed;
    if (!raw || !/^(?:\\x[0-9a-fA-F]{2})+$/.test(raw)) {
      return null;
    }
    const hex = raw.replace(/\\x/g, "");
    const buf = Buffer.from(hex, "hex");
    return buf.length > 0 ? buf : null;
  }

  if (trimmed.startsWith("utf8:")) {
    const utf = trimmed.slice("utf8:".length);
    return utf.length > 0 ? Buffer.from(utf, "utf8") : null;
  }

  return Buffer.from(trimmed, "utf8");
}

export function getSigningSecretFormat(rawSecret: string): SigningSecretFormat {
  const trimmed = rawSecret.trim();
  if (!trimmed) return "none";
  if (decodeSigningSecret(trimmed) === null) return "invalid";
  if (trimmed.startsWith("base64:")) return "base64";
  if (trimmed.startsWith("hex:")) return "hex";
  if (trimmed.startsWith("escaped:") || /^(?:\\x[0-9a-fA-F]{2})+$/.test(trimmed)) return "escaped";
  return "utf8";
}

export function hasRequestSigningSecret(): boolean {
  return decodeSigningSecret(env.movieboxSigningSecret) !== null;
}

export function hasAuthorizedSigningCredentials(): boolean {
  return hasRequestSigningSecret() || Boolean(env.movieboxAuthToken);
}

export function getMovieBoxAuthDiagnostics(): MovieBoxAuthDiagnostics {
  const signingSecretConfigured = Boolean(env.movieboxSigningSecret);
  const signingSecretFormat = getSigningSecretFormat(env.movieboxSigningSecret);
  const signingSecretValid = signingSecretFormat !== "none" && signingSecretFormat !== "invalid";

  const authTokenConfigured = Boolean(env.movieboxAuthToken);
  const authTokenValidFormat =
    authTokenConfigured && env.movieboxAuthToken.split(".").length >= 2;

  const buildTagConfigured = Boolean(env.movieboxCertSha256 && env.movieboxBuildSalt);
  const clientInfoConfigured = Boolean(env.movieboxClientInfo);
  let clientInfoValidJson = false;
  if (clientInfoConfigured) {
    try {
      const parsed = JSON.parse(env.movieboxClientInfo);
      clientInfoValidJson = Boolean(parsed && typeof parsed === "object" && !Array.isArray(parsed));
    } catch {
      clientInfoValidJson = false;
    }
  }

  const customUserAgentConfigured =
    Boolean(env.movieboxUserAgent) && env.movieboxUserAgent !== "ProphecyVerse-Server/1.0";

  let authMode: MovieBoxAuthDiagnostics["authMode"] = "unconfigured";
  if (signingSecretValid && authTokenConfigured) {
    authMode = "signing_and_token";
  } else if (signingSecretValid) {
    authMode = "signing_secret";
  } else if (authTokenConfigured) {
    authMode = "auth_token_only";
  }

  const missingRequirements: string[] = [];
  if (!signingSecretConfigured) {
    missingRequirements.push("MOVIEBOX_SIGNING_SECRET");
  } else if (!signingSecretValid) {
    missingRequirements.push("Valid MOVIEBOX_SIGNING_SECRET format (base64:/hex:/escaped:/utf8:)");
  }
  if (!signingSecretValid && !authTokenConfigured) {
    missingRequirements.push("Or authorized MOVIEBOX_AUTH_TOKEN (note: upstream BFF still verifies x-tr-signature)");
  }
  if (clientInfoConfigured && !clientInfoValidJson) {
    missingRequirements.push("Valid JSON object for MOVIEBOX_CLIENT_INFO");
  }

  return {
    signingSecretConfigured,
    signingSecretValid,
    signingSecretFormat,
    authTokenConfigured,
    authTokenValidFormat,
    buildTagConfigured,
    clientInfoConfigured,
    clientInfoValidJson,
    customUserAgentConfigured,
    hostCount: env.movieboxHosts.length,
    authMode,
    missingRequirements,
  };
}

export function generateXClientToken(timestampMs: number): string {
  const tsStr = String(timestampMs);
  const reversedTs = tsStr.split("").reverse().join("");
  const hashVal = md5Hex(reversedTs);
  return `${tsStr},${hashVal}`;
}

export function sortedQueryString(rawUrl: string): string {
  try {
    const parsed = new URL(rawUrl);
    const params = new Map<string, string[]>();
    for (const [k, v] of parsed.searchParams.entries()) {
      const list = params.get(k) || [];
      list.push(v);
      params.set(k, list);
    }
    if (params.size === 0) return "";
    const keys = Array.from(params.keys()).sort();
    const parts: string[] = [];
    for (const key of keys) {
      const vals = params.get(key) || [];
      for (const val of vals) {
        parts.push(`${key}=${val}`);
      }
    }
    return parts.join("&");
  } catch {
    return "";
  }
}

export function buildCanonicalString(
  method: string,
  accept: string,
  contentType: string,
  rawUrl: string,
  body: string | undefined,
  timestampMs: number
): string {
  let canonicalUrl = rawUrl;
  try {
    const parsed = new URL(rawUrl);
    const query = sortedQueryString(rawUrl);
    canonicalUrl = query ? `${parsed.pathname}?${query}` : parsed.pathname;
  } catch {
    canonicalUrl = rawUrl;
  }

  let bodyHash = "";
  let bodyLength = "";
  if (typeof body === "string") {
    const bodyBuf = Buffer.from(body, "utf8");
    bodyLength = String(bodyBuf.length);
    const truncated =
      bodyBuf.length > MOVIEBOX_CONFIG.signatureBodyMaxBytes
        ? bodyBuf.subarray(0, MOVIEBOX_CONFIG.signatureBodyMaxBytes)
        : bodyBuf;
    bodyHash = md5Hex(truncated);
  }

  return [
    method.toUpperCase(),
    accept,
    contentType,
    bodyLength,
    String(timestampMs),
    bodyHash,
    canonicalUrl,
  ].join("\n");
}

export function generateXTrSignature(
  method: string,
  accept: string,
  contentType: string,
  rawUrl: string,
  body: string | undefined,
  timestampMs: number,
  secretOverride?: string
): string | null {
  const secretBuf = decodeSigningSecret(secretOverride ?? env.movieboxSigningSecret);
  if (!secretBuf || secretBuf.length === 0) {
    return null;
  }

  const canonical = buildCanonicalString(method, accept, contentType, rawUrl, body, timestampMs);
  const hmac = crypto.createHmac("md5", secretBuf);
  hmac.update(canonical, "utf8");
  const sigB64 = hmac.digest("base64");
  return `${timestampMs}|2|${sigB64}`;
}

export function generateXClientBuild(timestampMs: number): string | null {
  if (!env.movieboxCertSha256 || !env.movieboxBuildSalt) {
    return null;
  }
  const sec = Math.floor(timestampMs / 1000);
  const ms = String(timestampMs % 1000).padStart(3, "0");
  const randPart = crypto.randomInt(0, 1000);
  const stamp = `${sec}${ms}000${randPart}`;
  const hash = md5Hex(`${env.movieboxCertSha256}${env.movieboxBuildSalt}${stamp}`);
  return `${stamp}.${hash}`;
}

export function buildProviderHeaders(options: {
  method: string;
  url: string;
  body?: string;
  authToken?: string;
  timestampMs?: number;
}): Record<string, string> {
  const ts = options.timestampMs ?? Date.now();
  const accept = "application/json";
  const contentType = "application/json";

  const headers: Record<string, string> = {
    "User-Agent": env.movieboxUserAgent,
    Accept: accept,
    "Content-Type": contentType,
    Connection: "keep-alive",
    "x-client-token": generateXClientToken(ts),
    "x-client-status": "0",
  };

  const signature = generateXTrSignature(
    options.method,
    accept,
    contentType,
    options.url,
    options.body,
    ts
  );
  if (signature) {
    headers["x-tr-signature"] = signature;
  }

  const buildTag = generateXClientBuild(ts);
  if (buildTag) {
    headers["x-client-build"] = buildTag;
  }

  if (env.movieboxClientInfo) {
    try {
      const parsed = JSON.parse(env.movieboxClientInfo) as Record<string, unknown>;
      if (buildTag) {
        parsed["X-Client-Build"] = buildTag;
      }
      headers["x-client-info"] = JSON.stringify(parsed);
    } catch {
      headers["x-client-info"] = env.movieboxClientInfo;
    }
  }

  if (options.authToken) {
    headers["Authorization"] = `Bearer ${options.authToken}`;
  }

  return headers;
}
