import { MOVIEBOX_CONFIG } from "./config";
import { MovieBoxSession } from "./types";

export function parseJwtClaims(token: string): { userId?: string; expiresAt?: number } {
  const parts = token.split(".");
  if (parts.length < 2 || !parts[1]) {
    return {};
  }

  try {
    const b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padLen = (4 - (b64.length % 4)) % 4;
    const padded = b64 + "=".repeat(padLen);
    const decoded = Buffer.from(padded, "base64").toString("utf8");
    const claims = JSON.parse(decoded) as Record<string, unknown>;

    const rawUid = claims.userId ?? claims.uid ?? claims.sub;
    let userId: string | undefined;
    if (typeof rawUid === "string" && rawUid.trim()) {
      userId = rawUid.trim();
    } else if (typeof rawUid === "number" && Number.isFinite(rawUid)) {
      userId = String(rawUid);
    }

    const rawExp = claims.exp;
    let expiresAt: number | undefined;
    if (typeof rawExp === "number" && Number.isFinite(rawExp) && rawExp > 0) {
      expiresAt = Math.floor(rawExp);
    } else if (typeof rawExp === "string") {
      const parsed = Number.parseInt(rawExp, 10);
      if (Number.isFinite(parsed) && parsed > 0) {
        expiresAt = parsed;
      }
    }

    return { userId, expiresAt };
  } catch {
    return {};
  }
}

export function createSessionFromToken(token: string, explicitUserId?: string): MovieBoxSession {
  const claims = parseJwtClaims(token);
  return {
    token: token.trim(),
    userId: explicitUserId || claims.userId,
    expiresAt: claims.expiresAt,
    createdAt: Math.floor(Date.now() / 1000),
  };
}

export function isSessionValid(session: MovieBoxSession | null | undefined): boolean {
  if (!session || !session.token || !session.token.trim()) {
    return false;
  }
  const nowSec = Math.floor(Date.now() / 1000);
  if (typeof session.expiresAt === "number") {
    return nowSec + MOVIEBOX_CONFIG.sessionExpiryBufferSeconds < session.expiresAt;
  }
  return nowSec < session.createdAt + MOVIEBOX_CONFIG.sessionMaxAgeSeconds;
}
