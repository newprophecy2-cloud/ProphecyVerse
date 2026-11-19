import express, { Express } from "express";
import { env } from "./config/env";
import { errorHandler } from "./middleware/errorHandler";
import { rateLimiter } from "./middleware/rateLimiter";
import { requestLogger } from "./middleware/requestLogger";
import healthRoutes from "./routes/health.routes";
import catalogRoutes from "./routes/catalog.routes";
import playbackRoutes from "./routes/playback.routes";

export function createApiApp(): Express {
  const app = express();

  app.set("trust proxy", 1);
  app.disable("x-powered-by");

  // Security headers
  app.use((_req, res, next) => {
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
    res.setHeader("X-XSS-Protection", "0");
    next();
  });

  // Strict CORS handling for API endpoints (stream-proxy routes set their own media CORS headers)
  app.use("/api", (req, res, next) => {
    if (req.path.startsWith("/v1/stream-proxy")) {
      next();
      return;
    }
    const origin = req.headers.origin;
    if (origin && env.corsOrigins.length > 0 && env.corsOrigins.includes(origin)) {
      res.setHeader("Access-Control-Allow-Origin", origin);
      res.setHeader("Vary", "Origin");
      res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
      res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }
    if (req.method === "OPTIONS") {
      res.status(204).end();
      return;
    }
    next();
  });

  app.use(express.json({ limit: "64kb" }));
  app.use(requestLogger);
  app.use("/api", rateLimiter);

  app.use("/api", healthRoutes);
  app.use("/api/v1", catalogRoutes);
  app.use("/api/v1", playbackRoutes);

  // Unknown API endpoints return structured 404 JSON
  app.use("/api/*", (_req, res) => {
    res.status(404).json({
      status: "error",
      code: "RESOURCE_NOT_FOUND",
      message: "Requested API endpoint does not exist.",
      retryable: false,
    });
  });

  app.use(errorHandler);
  return app;
}
