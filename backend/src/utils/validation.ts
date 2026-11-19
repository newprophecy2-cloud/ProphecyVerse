import { MovieBoxError } from "../providers/moviebox/errors";

const SAFE_ID_REGEX = /^[a-zA-Z0-9_-]{1,64}$/;

export function validateSubjectId(raw: unknown, fieldName: string = "id"): string {
  if (typeof raw !== "string") {
    throw new MovieBoxError("INVALID_INPUT", `Missing or invalid ${fieldName}.`, 400, false);
  }
  const trimmed = raw.trim();
  if (!SAFE_ID_REGEX.test(trimmed)) {
    throw new MovieBoxError(
      "INVALID_INPUT",
      `Invalid ${fieldName} format. Expected alphanumeric identifier.`,
      400,
      false
    );
  }
  return trimmed;
}

export function validatePositiveInt(
  raw: unknown,
  fallback: number,
  max: number = 10_000
): number {
  if (raw === undefined || raw === null || raw === "") return fallback;
  const num = typeof raw === "number" ? raw : Number.parseInt(String(raw), 10);
  if (!Number.isFinite(num) || num < 0 || num > max) {
    throw new MovieBoxError("INVALID_INPUT", "Numeric query parameter out of valid bounds.", 400, false);
  }
  return Math.floor(num);
}

export function validateSearchQuery(raw: unknown): string {
  if (typeof raw !== "string") {
    throw new MovieBoxError("INVALID_INPUT", "Query parameter 'q' is required.", 400, false);
  }
  const trimmed = raw.trim();
  if (!trimmed || trimmed.length > 120) {
    throw new MovieBoxError(
      "INVALID_INPUT",
      "Search query must be between 1 and 120 characters.",
      400,
      false
    );
  }
  return trimmed;
}

export function parseEpisodeDescriptor(
  episodeParam: string,
  seQuery?: unknown,
  epQuery?: unknown
): { season: number; episode: number } {
  if (seQuery !== undefined || epQuery !== undefined) {
    return {
      season: validatePositiveInt(seQuery, 0, 500),
      episode: validatePositiveInt(epQuery, 0, 5000),
    };
  }

  const composite = episodeParam.match(/^(?:[a-zA-Z0-9_-]+_)?s(\d+)_e(\d+)$/i);
  if (composite) {
    return {
      season: validatePositiveInt(composite[1], 1, 500),
      episode: validatePositiveInt(composite[2], 1, 5000),
    };
  }

  const numeric = Number.parseInt(episodeParam, 10);
  if (Number.isFinite(numeric) && numeric >= 0 && numeric <= 5000) {
    return {
      season: numeric === 0 ? 0 : 1,
      episode: numeric,
    };
  }

  throw new MovieBoxError("INVALID_INPUT", "Invalid episode identifier format.", 400, false);
}
