import { Request, Response, NextFunction } from "express";
import { env } from "../config/env";
import { MovieBoxError } from "../providers/moviebox/errors";

export function errorHandler(
  err: unknown,
  _req: Request,
  res: Response,
  _next: NextFunction
): void {
  if (res.headersSent) {
    return;
  }

  if (err instanceof MovieBoxError) {
    const safeStatus =
      err.statusCode === 504
        ? 408
        : [401, 403, 407, 502, 503].includes(err.statusCode)
        ? 424
        : err.statusCode;

    res.status(safeStatus).json({
      status: "error",
      code: err.code,
      message: err.message,
      retryable: err.retryable,
    });
    return;
  }

  const message =
    !env.isProduction && err instanceof Error
      ? err.message
      : "An unexpected server error occurred.";

  console.error("[Backend Error]", err instanceof Error ? err.message : "Unknown error");

  res.status(500).json({
    status: "error",
    code: "INTERNAL_ERROR",
    message,
    retryable: false,
  });
}
