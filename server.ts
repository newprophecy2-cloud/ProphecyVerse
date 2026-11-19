import http from "http";
import fs from "fs";
import path from "path";
import express from "express";
import { createApiApp } from "./backend/src/app";
import { env } from "./backend/src/config/env";
import { movieBoxClient } from "./backend/src/providers/moviebox/client";

async function startServer() {
  const app = createApiApp();
  const distDir = path.resolve(process.cwd(), "dist");

  if (!env.isProduction) {
    const { createServer: createViteServer } = await import("vite");
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: "spa",
    });
    app.use(vite.middlewares);
  } else {
    if (fs.existsSync(distDir)) {
      app.use(express.static(distDir, { index: false }));
      app.get("*", (_req, res) => {
        res.sendFile(path.join(distDir, "index.html"));
      });
    } else {
      app.get("/", (_req, res) => {
        res.status(503).send("Frontend build artifacts not found. Run npm run build.");
      });
    }
  }

  const server = http.createServer(app);
  server.requestTimeout = 30_000;
  server.headersTimeout = 35_000;

  server.listen(env.port, "0.0.0.0", () => {
    const diag = movieBoxClient.getAuthDiagnostics();
    console.info(
      `ProphecyVerse server listening on http://0.0.0.0:${env.port} (${env.nodeEnv}) | MovieBox authMode=${diag.authMode}, hosts=${diag.hostCount}` +
        (diag.missingRequirements.length > 0
          ? `, missing=${diag.missingRequirements.join("; ")}`
          : ", configured=true")
    );
  });

  const shutdown = (signal: string) => {
    console.info(`Received ${signal}. Shutting down ProphecyVerse server gracefully...`);
    server.close(() => {
      console.info("HTTP server closed.");
      process.exit(0);
    });
    setTimeout(() => {
      console.error("Forced shutdown after timeout.");
      process.exit(1);
    }, 5000).unref();
  };

  process.on("SIGINT", () => shutdown("SIGINT"));
  process.on("SIGTERM", () => shutdown("SIGTERM"));
}

startServer().catch((err) => {
  console.error("Fatal error starting server:", err);
  process.exit(1);
});
