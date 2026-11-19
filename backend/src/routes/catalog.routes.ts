import { Router, Request, Response, NextFunction } from "express";
import { catalogService } from "../services/catalogService";
import { playbackService } from "../services/playbackService";
import {
  parseEpisodeDescriptor,
  validatePositiveInt,
  validateSearchQuery,
  validateSubjectId,
} from "../utils/validation";

const router = Router();

router.get("/catalog/home", async (req: Request, res: Response, next: NextFunction) => {
  try {
    const tabId = typeof req.query.tabId === "string" && /^[a-zA-Z0-9_-]{1,20}$/.test(req.query.tabId)
      ? req.query.tabId
      : "0";
    const page = Math.max(1, validatePositiveInt(req.query.page, 1, 100));
    const data = await catalogService.getHomepage(tabId, page);
    res.json({ status: "ok", data });
  } catch (err) {
    next(err);
  }
});

router.get("/anime/suggest", async (req: Request, res: Response, next: NextFunction) => {
  try {
    const query = validateSearchQuery(req.query.q);
    const suggestions = await catalogService.suggest(query);
    res.json({ status: "ok", data: { suggestions } });
  } catch (err) {
    next(err);
  }
});

router.get("/anime/search", async (req: Request, res: Response, next: NextFunction) => {
  try {
    const query = validateSearchQuery(req.query.q);
    const page = Math.max(1, validatePositiveInt(req.query.page, 1, 100));
    const subjectType = validatePositiveInt(req.query.type, 0, 10);
    const data = await catalogService.search(query, page, subjectType);
    res.json({ status: "ok", data });
  } catch (err) {
    next(err);
  }
});

router.get("/anime/:id/resolutions", async (req: Request, res: Response, next: NextFunction) => {
  try {
    const id = validateSubjectId(req.params.id, "anime id");
    const resolutions = await catalogService.getCollectionResolutions(id);
    res.json({ status: "ok", data: { subjectId: id, resolutions } });
  } catch (err) {
    next(err);
  }
});

router.get("/anime/:id", async (req: Request, res: Response, next: NextFunction) => {
  try {
    const id = validateSubjectId(req.params.id, "anime id");
    const data = await catalogService.getDetails(id);
    res.json({ status: "ok", data });
  } catch (err) {
    next(err);
  }
});

router.get("/anime/:id/seasons", async (req: Request, res: Response, next: NextFunction) => {
  try {
    const id = validateSubjectId(req.params.id, "anime id");
    const seasons = await catalogService.getSeasons(id);
    res.json({ status: "ok", data: { subjectId: id, seasons } });
  } catch (err) {
    next(err);
  }
});

router.get("/anime/:id/episodes", async (req: Request, res: Response, next: NextFunction) => {
  try {
    const id = validateSubjectId(req.params.id, "anime id");
    const season = req.query.season !== undefined
      ? validatePositiveInt(req.query.season, 0, 500)
      : undefined;
    const episodes = await catalogService.getEpisodes(id, season);
    res.json({ status: "ok", data: { subjectId: id, episodes } });
  } catch (err) {
    next(err);
  }
});

router.get("/anime/:id/episodes/:episodeId", async (req: Request, res: Response, next: NextFunction) => {
  try {
    const id = validateSubjectId(req.params.id, "anime id");
    const { season, episode } = parseEpisodeDescriptor(
      req.params.episodeId,
      req.query.se,
      req.query.ep
    );
    const episodeData = await catalogService.getSingleEpisode(id, season, episode);
    res.json({ status: "ok", data: episodeData });
  } catch (err) {
    next(err);
  }
});

router.get("/anime/:id/episodes/:episodeId/full", async (req: Request, res: Response, next: NextFunction) => {
  try {
    const id = validateSubjectId(req.params.id, "anime id");
    const { season, episode } = parseEpisodeDescriptor(
      req.params.episodeId,
      req.query.se,
      req.query.ep
    );
    const [details, episodeData, playback] = await Promise.all([
      catalogService.getDetails(id),
      catalogService.getSingleEpisode(id, season, episode),
      playbackService.getPlayback(id, season, episode),
    ]);
    res.json({
      status: "ok",
      data: {
        details,
        episode: episodeData,
        playback,
      },
    });
  } catch (err) {
    next(err);
  }
});

export default router;
