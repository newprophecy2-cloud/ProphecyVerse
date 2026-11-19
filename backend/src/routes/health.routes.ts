import { Router } from "express";
import { movieBoxClient } from "../providers/moviebox/client";
import { env } from "../config/env";

const router = Router();

router.get("/health", (_req, res) => {
  res.json({
    status: "ok",
    data: {
      service: "prophecyverse-api",
      provider: "moviebox",
      mockMode: env.movieboxMockMode,
      movieboxConfigured: movieBoxClient.isConfigured(),
      authDiagnostics: movieBoxClient.getAuthDiagnostics(),
      environment: env.nodeEnv,
      uptimeSeconds: Math.floor(process.uptime()),
      timestamp: new Date().toISOString(),
    },
  });
});

export default router;

