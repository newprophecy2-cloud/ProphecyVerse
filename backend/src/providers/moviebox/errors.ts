export type MovieBoxErrorCode =
  | "PROVIDER_AUTH_REQUIRED"
  | "INVALID_INPUT"
  | "RESOURCE_NOT_FOUND"
  | "UPSTREAM_RATE_LIMITED"
  | "UPSTREAM_TIMEOUT"
  | "UPSTREAM_UNAVAILABLE"
  | "PLACEHOLDER_ONLY"
  | "PARSE_ERROR"
  | "INTERNAL_ERROR";

export const PLACEHOLDER_ONLY_MESSAGE =
  "MovieBox is serving a placeholder clip instead of this title";

export class MovieBoxError extends Error {
  public readonly code: MovieBoxErrorCode;
  public readonly statusCode: number;
  public readonly retryable: boolean;

  constructor(
    code: MovieBoxErrorCode,
    message: string,
    statusCode: number,
    retryable: boolean = false
  ) {
    super(message);
    this.name = "MovieBoxError";
    this.code = code;
    this.statusCode = statusCode;
    this.retryable = retryable;
  }
}

export function isRetryableHttpStatus(status: number): boolean {
  return [403, 406, 407, 429, 500, 502, 503, 504].includes(status);
}
