import { Router, Request, Response, NextFunction } from "express";
import { env } from "../config/env";
import { playbackService } from "../services/playbackService";
import { streamProxyService } from "../services/streamProxyService";
import { validatePositiveInt, validateSubjectId } from "../utils/validation";

const router = Router();

router.all("/stream-proxy", async (req: Request, res: Response, next: NextFunction) => {
  try {
    await streamProxyService.handle(req, res);
  } catch (err) {
    next(err);
  }
});

router.all("/stream-proxy/dir/:token/*", async (req: Request, res: Response, next: NextFunction) => {
  try {
    await streamProxyService.handleDirSegment(req, res);
  } catch (err) {
    next(err);
  }
});

router.get("/mock-media/:subjectId/:asset", (req: Request, res: Response) => {
  if (!env.movieboxMockMode) {
    res.status(404).json({
      status: "error",
      code: "RESOURCE_NOT_FOUND",
      message: "Mock media endpoint is disabled.",
      retryable: false,
    });
    return;
  }

  const subjectId = validateSubjectId(req.params.subjectId, "subjectId");
  const asset = req.params.asset;

  if (asset === "playlist.m3u8") {
    res.setHeader("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8");
    res.send(
      [
        "#EXTM3U",
        "#EXT-X-VERSION:3",
        "#EXT-X-TARGETDURATION:6",
        "#EXT-X-MEDIA-SEQUENCE:0",
        "#EXTINF:5.0,",
        `/api/v1/mock-media/${encodeURIComponent(subjectId)}/seg-0.ts`,
        "#EXT-X-ENDLIST",
      ].join("\n")
    );
    return;
  }

  if (asset === "seg-0.ts") {
    // Minimal 188-byte MPEG-TS sync packet placeholder for mock playlist testing
    const packet = Buffer.alloc(188, 0xff);
    packet[0] = 0x47;
    packet[1] = 0x40;
    packet[2] = 0x00;
    packet[3] = 0x10;
    res.setHeader("Content-Type", "video/mp2t");
    res.setHeader("Accept-Ranges", "bytes");
    res.setHeader("Content-Length", String(packet.length));
    res.send(req.method === "HEAD" ? undefined : packet);
    return;
  }

  if (asset === "sample.mp4") {
    // Minimal ftyp box bytes with RFC 7233 single-range 206 support for mock mode tests
    const sampleBytes = Buffer.from("000000186674797069736f6d0000020069736f6d69736f32", "hex");
    res.setHeader("Content-Type", "video/mp4");
    res.setHeader("Accept-Ranges", "bytes");
    const rangeHeader = typeof req.headers.range === "string" ? req.headers.range : "";
    const match = rangeHeader.match(/^bytes=(\d+)-(\d*)$/i);
    if (match) {
      const start = Number.parseInt(match[1], 10);
      const end = match[2] ? Math.min(Number.parseInt(match[2], 10), sampleBytes.length - 1) : sampleBytes.length - 1;
      if (start >= sampleBytes.length || end < start) {
        res.status(416).setHeader("Content-Range", `bytes */${sampleBytes.length}`).end();
        return;
      }
      const slice = sampleBytes.subarray(start, end + 1);
      res.status(206);
      res.setHeader("Content-Range", `bytes ${start}-${end}/${sampleBytes.length}`);
      res.setHeader("Content-Length", String(slice.length));
      res.end(req.method === "HEAD" ? undefined : slice);
      return;
    }
    res.setHeader("Content-Length", String(sampleBytes.length));
    res.end(req.method === "HEAD" ? undefined : sampleBytes);
    return;
  }

  if (asset === "subtitles-en.vtt") {
    res.setHeader("Content-Type", "text/vtt; charset=utf-8");
    res.send("WEBVTT\n\n00:00:00.200 --> 00:00:04.500\nProphecyVerse Mock English Subtitle\n");
    return;
  }

  if (asset === "subtitles-es.srt") {
    res.setHeader("Content-Type", "text/plain; charset=utf-8");
    res.send("1\n00:00:00,200 --> 00:00:04,500\nSubtitulo de prueba ProphecyVerse\n");
    return;
  }

  res.status(404).json({
    status: "error",
    code: "RESOURCE_NOT_FOUND",
    message: "Requested mock media asset not found.",
    retryable: false,
  });
});

router.get("/playback/:resourceId", async (req: Request, res: Response, next: NextFunction) => {
  try {
    const subjectId = req.query.subjectId
      ? validateSubjectId(req.query.subjectId, "subjectId")
      : validateSubjectId(req.params.resourceId, "subjectId");
    const season = validatePositiveInt(req.query.se, 0, 500);
    const episode = validatePositiveInt(req.query.ep, 0, 5000);
    const forceRefresh = req.query.refresh === "true" || req.query.refresh === "1";
    const playback = await playbackService.getPlayback(subjectId, season, episode, forceRefresh);
    res.json({ status: "ok", data: playback });
  } catch (err) {
    next(err);
  }
});

router.get("/captions/:resourceId", async (req: Request, res: Response, next: NextFunction) => {
  try {
    const resourceId = validateSubjectId(req.params.resourceId, "resourceId");
    const subjectId = req.query.subjectId
      ? validateSubjectId(req.query.subjectId, "subjectId")
      : resourceId;
    const season = validatePositiveInt(req.query.se, 0, 500);
    const episode = validatePositiveInt(req.query.ep, 0, 5000);
    const siblings =
      typeof req.query.siblings === "string"
        ? req.query.siblings
            .split(",")
            .map((s) => s.trim())
            .filter((s) => /^[a-zA-Z0-9_-]{1,64}$/.test(s))
            .slice(0, 5)
        : [];

    const captions = await playbackService.getCaptions(
      subjectId,
      resourceId,
      siblings,
      season,
      episode
    );
    res.json({ status: "ok", data: { subjectId, resourceId, captions } });
  } catch (err) {
    next(err);
  }
});

export default router;
