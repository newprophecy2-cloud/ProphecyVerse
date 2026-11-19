import dotenv from "dotenv";

dotenv.config();

const DEFAULT_MOVIEBOX_HOSTS = [
  "https://api6.aoneroom.com",
  "https://api5.aoneroom.com",
  "https://api4.aoneroom.com",
  "https://api4sg.aoneroom.com",
  "https://api3.aoneroom.com",
  "https://api6sg.aoneroom.com",
  "https://api.inmoviebox.com",
];

// Public compatibility constants used by MovieBox-Tui's request protocol.
// Operators can override every value through the environment for production.
const DEFAULT_MOVIEBOX_SIGNING_SECRET =
  "hex:efa891974eecd3148df63aa611602defd101259ba521022c57ae0566bd8e";
const DEFAULT_MOVIEBOX_CERT_SHA256 =
  "03d4b741632d136bc23e2de4c6adcdb7712f1980b619c183221801730bbd0410";
const DEFAULT_MOVIEBOX_BUILD_SALT = "oneroom";
const DEFAULT_MOVIEBOX_USER_AGENT =
  "com.community.oneroom/50020121 (Linux; U; Android 13; en_US; 23078RKD5C; Build/TQ2A.230405.003; Cronet/135.0.7012.3)";
const DEFAULT_MOVIEBOX_CLIENT_INFO = JSON.stringify({
  package_name: "com.community.oneroom",
  version_name: "4.0.01.0813.03",
  version_code: 50020121,
  os: "android",
  os_version: "13",
  install_ch: "ps",
  device_id: "prophecyverse-server",
  install_store: "ps",
  gaid: "00000000-0000-0000-0000-000000000000",
  brand: "Redmi",
  model: "23078RKD5C",
  system_language: "en",
  net: "NETWORK_WIFI",
  region: "US",
  timezone: "Asia/Dhaka",
  sp_code: "40401",
  "X-Play-Mode": "2",
});

function parsePositiveInt(raw: string | undefined, fallback: number): number {
  if (!raw) return fallback;
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function parseHosts(raw: string | undefined): string[] {
  if (!raw || !raw.trim()) return DEFAULT_MOVIEBOX_HOSTS;
  const valid = raw
    .split(",")
    .map((h) => h.trim().replace(/\/+$/, ""))
    .filter((h) => {
      try {
        const u = new URL(h);
        return u.protocol === "https:" || u.protocol === "http:";
      } catch {
        return false;
      }
    });
  return valid.length > 0 ? valid : DEFAULT_MOVIEBOX_HOSTS;
}

function parseMediaHosts(raw: string | undefined): string[] {
  if (!raw || !raw.trim()) return ["aoneroom.com", "inmoviebox.com"];
  const valid = raw
    .split(",")
    .map((host) => host.trim().toLowerCase().replace(/^\.+/, ""))
    .filter((host) => /^[a-z0-9.-]+$/.test(host) && host.includes("."));
  return valid.length > 0 ? valid : ["aoneroom.com", "inmoviebox.com"];
}

function parseCorsOrigins(raw: string | undefined): string[] {
  if (!raw || !raw.trim()) return [];
  return raw
    .split(",")
    .map((o) => o.trim().replace(/\/+$/, ""))
    .filter(Boolean);
}

export interface ServerEnv {
  nodeEnv: string;
  isProduction: boolean;
  port: number;
  corsOrigins: string[];
  movieboxHosts: string[];
  movieboxSigningSecret: string;
  movieboxCertSha256: string;
  movieboxBuildSalt: string;
  movieboxUserAgent: string;
  movieboxClientInfo: string;
  movieboxAuthToken: string;
  movieboxMediaHosts: string[];
  streamProxySecret: string;
  streamProxyTtlSeconds: number;
  streamProxyTimeoutMs: number;
  cacheTtlMs: number;
  rateLimitWindowMs: number;
  rateLimitMaxRequests: number;
}

function resolvePort(): number {
  const portArgIdx = process.argv.findIndex((arg) => arg === "--port" || arg === "-p");
  if (portArgIdx !== -1 && process.argv[portArgIdx + 1]) {
    const parsedArg = Number.parseInt(process.argv[portArgIdx + 1], 10);
    if (Number.isFinite(parsedArg) && parsedArg > 0) {
      return parsedArg;
    }
  }
  const eqArg = process.argv.find((arg) => arg.startsWith("--port="));
  if (eqArg) {
    const parsedEq = Number.parseInt(eqArg.slice("--port=".length), 10);
    if (Number.isFinite(parsedEq) && parsedEq > 0) {
      return parsedEq;
    }
  }
  return parsePositiveInt(process.env.PORT, 3000);
}

export function loadEnv(): ServerEnv {
  const nodeEnv = (process.env.NODE_ENV || "development").trim();
  return {
    nodeEnv,
    isProduction: nodeEnv === "production",
    port: resolvePort(),
    corsOrigins: parseCorsOrigins(process.env.CORS_ORIGIN || process.env.APP_URL),
    movieboxHosts: parseHosts(process.env.MOVIEBOX_API_HOSTS),
    movieboxSigningSecret: (
      process.env.MOVIEBOX_SIGNING_SECRET || DEFAULT_MOVIEBOX_SIGNING_SECRET
    ).trim(),
    movieboxCertSha256: (
      process.env.MOVIEBOX_CERT_SHA256 || DEFAULT_MOVIEBOX_CERT_SHA256
    ).trim(),
    movieboxBuildSalt: (process.env.MOVIEBOX_BUILD_SALT || DEFAULT_MOVIEBOX_BUILD_SALT).trim(),
    movieboxUserAgent: (
      process.env.MOVIEBOX_USER_AGENT || DEFAULT_MOVIEBOX_USER_AGENT
    ).trim(),
    movieboxClientInfo: (
      process.env.MOVIEBOX_CLIENT_INFO || DEFAULT_MOVIEBOX_CLIENT_INFO
    ).trim(),
    movieboxAuthToken: (process.env.MOVIEBOX_AUTH_TOKEN || "").trim(),
    movieboxMediaHosts: parseMediaHosts(process.env.MOVIEBOX_MEDIA_HOSTS),
    streamProxySecret: (process.env.STREAM_PROXY_SECRET || process.env.MOVIEBOX_SIGNING_SECRET || "").trim(),
    streamProxyTtlSeconds: parsePositiveInt(process.env.STREAM_PROXY_TTL_SECONDS, 3600),
    streamProxyTimeoutMs: parsePositiveInt(process.env.STREAM_PROXY_TIMEOUT_MS, 30_000),
    cacheTtlMs: parsePositiveInt(process.env.MOVIEBOX_CACHE_TTL_MS, 5 * 60 * 1000),
    rateLimitWindowMs: parsePositiveInt(process.env.RATE_LIMIT_WINDOW_MS, 60 * 1000),
    rateLimitMaxRequests: parsePositiveInt(process.env.RATE_LIMIT_MAX_REQUESTS, 120),
  };
}

export const env = loadEnv();
