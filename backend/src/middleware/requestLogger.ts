import { Request, Response, NextFunction } from "express";

export function requestLogger(req: Request, res: Response, next: NextFunction): void {
  if (!req.path.startsWith("/api")) {
    next();
    return;
  }
  const start = Date.now();
  res.on("finish", () => {
    const durationMs = Date.now() - start;
    // Log only method, path (without sensitive query secrets), status, and timing
    console.info(`[API] ${req.method} ${req.path} -> ${res.statusCode} (${durationMs}ms)`);
  });
  next();
}
