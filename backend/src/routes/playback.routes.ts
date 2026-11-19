import { Router, Request, Response, NextFunction } from "express";
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
