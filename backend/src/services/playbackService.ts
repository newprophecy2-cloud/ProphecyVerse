import { env } from "../config/env";
import { movieBoxClient } from "../providers/moviebox/client";
import { MovieBoxError, PLACEHOLDER_ONLY_MESSAGE } from "../providers/moviebox/errors";
import {
  mapCaptionsToOptions,
  mapDetailsToMediaDetails,
  mapDetectorItemsToStreams,
  mapPlayInfoToStreams,
  mapResourceItemToStream,
  placeholderStreamCount,
  sanitizeLanguageLabel,
} from "../providers/moviebox/mapper";
import {
  MovieBoxAudioTrackOption,
  MovieBoxPlaybackResult,
  MovieBoxStreamVariant,
  MovieBoxSubtitleOption,
} from "../providers/moviebox/types";
import { cacheService } from "./cacheService";
import { streamProxyService } from "./streamProxyService";

const PLAYBACK_CACHE_TTL_MS = 120_000;

export class PlaybackService {
  public async getPlayback(
    subjectId: string,
    season: number = 0,
    episode: number = 0,
    forceRefresh: boolean = false
  ): Promise<MovieBoxPlaybackResult> {
    const cacheKey = `playback:${subjectId}:${season}:${episode}`;
    if (!forceRefresh) {
      return cacheService.getOrFetch(
        cacheKey,
        Math.min(env.cacheTtlMs, PLAYBACK_CACHE_TTL_MS),
        () => this.resolvePlayback(subjectId, season, episode)
      );
    }
    const fresh = await this.resolvePlayback(subjectId, season, episode);
    cacheService.set(cacheKey, fresh, Math.min(env.cacheTtlMs, PLAYBACK_CACHE_TTL_MS));
    return fresh;
  }

  private async resolvePlayback(
    subjectId: string,
    season: number,
    episode: number
  ): Promise<MovieBoxPlaybackResult> {
    const [detailsRes, playInfoRes, resourcesRes] = await Promise.allSettled([
      movieBoxClient.getSubjectDetails(subjectId),
      movieBoxClient.getPlayInfo(subjectId, season, episode),
      movieBoxClient.getEpisodeResourcesV2(subjectId, season, episode),
    ]);

    const streams: MovieBoxStreamVariant[] = [];
    const seenUrls = new Set<string>();
    let placeholders = 0;
    let audioTracks: MovieBoxAudioTrackOption[] = [];
    let primaryResourceId: string | undefined;

    const addVariant = (v: MovieBoxStreamVariant) => {
      const baseUrl = v.url.split("?")[0] || v.url;
      const dedupeKey = `${baseUrl}::${v.resolution}`;
      if (seenUrls.has(dedupeKey)) return;
      seenUrls.add(dedupeKey);
      if (!primaryResourceId && v.resourceId) {
        primaryResourceId = v.resourceId;
      }
      streams.push(v);
    };

    if (detailsRes.status === "fulfilled") {
      const parsedDetails = mapDetailsToMediaDetails(detailsRes.value);
      if (parsedDetails?.dubs) {
        audioTracks = parsedDetails.dubs;
      }
      for (const variant of mapDetectorItemsToStreams(detailsRes.value, season, episode)) {
        addVariant(variant);
      }
    }

    let playInfoError: unknown = null;
    if (playInfoRes.status === "fulfilled") {
      placeholders += placeholderStreamCount(playInfoRes.value);
      for (const variant of mapPlayInfoToStreams(playInfoRes.value)) {
        addVariant(variant);
      }
    } else {
      playInfoError = playInfoRes.reason;
    }

    let resourceItems: any[] = [];
    if (resourcesRes.status === "fulfilled") {
      resourceItems = Array.isArray(resourcesRes.value?.list) ? resourcesRes.value.list : [];
    } else if (streams.length === 0) {
      const page = episode > 0 ? Math.floor((episode - 1) / 20) + 1 : 1;
      try {
        const pageRes = await movieBoxClient.getResourcesPage(subjectId, season, episode, 0, page);
        resourceItems = Array.isArray(pageRes?.list) ? pageRes.list : [];
      } catch {
        resourceItems = [];
      }
    }

    for (const item of resourceItems) {
      const mapped = mapResourceItemToStream(item);
      if (
        (season !== 0 || episode !== 0) &&
        (mapped.season !== season || mapped.episode !== episode)
      ) {
        continue;
      }

      if (!primaryResourceId && mapped.resourceId) {
        primaryResourceId = mapped.resourceId;
      }

      if (mapped.variant) {
        addVariant(mapped.variant);
        continue;
      }

      if (mapped.deprecationUrl && mapped.resourceId) {
        placeholders += 1;
        try {
          const recoveredPayload = await movieBoxClient.getResourcePosition(
            subjectId,
            mapped.resourceId,
            mapped.deprecationUrl
          );
          const list = Array.isArray(recoveredPayload?.list) ? recoveredPayload.list : [];
          for (const candidate of list) {
            const candidateMapped = mapResourceItemToStream(candidate);
            if (
              candidateMapped.variant &&
              candidateMapped.resourceId === mapped.resourceId &&
              candidateMapped.season === mapped.season &&
              candidateMapped.episode === mapped.episode
            ) {
              const mergedRes = candidateMapped.resolution ?? mapped.resolution ?? candidateMapped.variant.resolution;
              const mergedCodec = candidateMapped.codec ?? mapped.codec;
              const mergedLanguage = candidateMapped.language ?? mapped.language;
              addVariant({
                ...candidateMapped.variant,
                id: `${mapped.resourceId}_${mergedRes}`,
                resolution: mergedRes,
                quality: `${mergedRes}p`,
                codec: mergedCodec,
                language: mergedLanguage,
                label: `${mergedRes}p ${mergedCodec || candidateMapped.variant.format.toUpperCase()}`,
              });
              break;
            }
          }
        } catch {
          // continue checking remaining items
        }
      }
    }

    if (streams.length === 0) {
      if (placeholders > 0) {
        throw new MovieBoxError("PLACEHOLDER_ONLY", PLACEHOLDER_ONLY_MESSAGE, 503, false);
      }
      if (playInfoError instanceof MovieBoxError) {
        throw playInfoError;
      }
      if (detailsRes.status === "rejected" && detailsRes.reason instanceof MovieBoxError) {
        throw detailsRes.reason;
      }
      throw new MovieBoxError(
        "RESOURCE_NOT_FOUND",
        "No playable stream sources returned by MovieBox for this title.",
        404,
        true
      );
    }

    streams.sort((a, b) => b.resolution - a.resolution || (b.sizeBytes || 0) - (a.sizeBytes || 0));

    let subtitles: MovieBoxSubtitleOption[] = [];
    if (primaryResourceId) {
      const siblingIds = audioTracks
        .map((d) => d.subjectId)
        .filter((sid) => sid && sid !== subjectId);
      subtitles = await this.getCaptions(
        subjectId,
        primaryResourceId,
        siblingIds,
        season,
        episode
      ).catch(() => []);
    }

    const proxiedStreams = streams
      .map((stream) => {
        const url = streamProxyService.createProxyUrl(
          stream.url,
          "media",
          stream.upstreamCookie
        );
        return url ? { ...stream, url, browserCompatible: true, requiresCustomHeaders: false } : null;
      })
      .filter((stream): stream is MovieBoxStreamVariant => Boolean(stream));
    const proxiedSubtitles = subtitles
      .map((subtitle) => {
        if (subtitle.url.startsWith("/api/v1/stream-proxy?token=")) return subtitle;
        const url = streamProxyService.createProxyUrl(
          subtitle.url,
          "subtitle",
          subtitle.upstreamCookie
        );
        return url ? { ...subtitle, url } : null;
      })
      .filter((subtitle): subtitle is MovieBoxSubtitleOption => Boolean(subtitle));

    return {
      subjectId,
      season,
      episode,
      resourceId: primaryResourceId,
      streams: proxiedStreams,
      subtitles: proxiedSubtitles,
      audioTracks,
      placeholderCount: placeholders,
      fetchedAt: Date.now(),
    };
  }

  public async getCaptions(
    subjectId: string,
    resourceId: string,
    siblingIds: string[] = [],
    season: number = 0,
    episode: number = 0
  ): Promise<MovieBoxSubtitleOption[]> {
    if (!subjectId || !resourceId) {
      return [];
    }

    const cacheKey = `captions:${subjectId}:${resourceId}`;
    const cached = cacheService.get<MovieBoxSubtitleOption[]>(cacheKey);
    if (cached && (cached.length > 0 || siblingIds.length === 0)) {
      return cached;
    }

    const allCaptions: MovieBoxSubtitleOption[] = [];
    const seenUrls = new Set<string>();

    const appendUnique = (items: MovieBoxSubtitleOption[]) => {
      for (const item of items) {
        if (!seenUrls.has(item.url)) {
          seenUrls.add(item.url);
          allCaptions.push(item);
        }
      }
    };

    const primaryPromise = movieBoxClient
      .getExtCaptions(subjectId, resourceId)
      .then((res) => mapCaptionsToOptions(res))
      .catch((err) => {
        if (err instanceof MovieBoxError && err.code === "PROVIDER_AUTH_REQUIRED") {
          throw err;
        }
        return [] as MovieBoxSubtitleOption[];
      });

    const siblingPromises = siblingIds
      .filter((sid) => sid && sid !== subjectId)
      .slice(0, 3)
      .map(async (sibId) => {
        try {
          const page = episode > 0 ? Math.floor((episode - 1) / 20) + 1 : 1;
          const resPage = await movieBoxClient.getResourcesPage(sibId, season, episode, 0, page);
          const list = Array.isArray(resPage?.list) ? resPage.list : [];
          const match = list.find((item: any) => {
            const se = Number(item?.se ?? 0);
            const ep = Number(item?.ep ?? 0);
            return (season === 0 && episode === 0) || (se === season && ep === episode);
          });
          const sibResourceId = match?.resourceId ?? match?.id;
          if (!sibResourceId) return [];
          const capRes = await movieBoxClient.getExtCaptions(sibId, String(sibResourceId));
          return mapCaptionsToOptions(capRes);
        } catch {
          return [];
        }
      });

    const [primarySubs, ...siblingSubs] = await Promise.all([
      primaryPromise,
      ...siblingPromises,
    ]);

    appendUnique(primarySubs);
    for (const list of siblingSubs) {
      appendUnique(list);
    }

    // Deduplicate by normalized language label & put English first
    const seenLangs = new Set<string>();
    const deduplicated: MovieBoxSubtitleOption[] = [];
    for (const sub of allCaptions) {
      const normLang = sanitizeLanguageLabel(sub.name).toLowerCase();
      if (!seenLangs.has(normLang)) {
        seenLangs.add(normLang);
        deduplicated.push(sub);
      }
    }

    deduplicated.sort((a, b) => {
      const cleanA = sanitizeLanguageLabel(a.name);
      const cleanB = sanitizeLanguageLabel(b.name);
      const aEng = cleanA.toLowerCase() === "english";
      const bEng = cleanB.toLowerCase() === "english";
      if (aEng && !bEng) return -1;
      if (!aEng && bEng) return 1;
      return cleanA.localeCompare(cleanB);
    });

    const proxied = deduplicated
      .map((subtitle) => {
        const url = streamProxyService.createProxyUrl(
          subtitle.url,
          "subtitle",
          subtitle.upstreamCookie
        );
        return url ? { ...subtitle, url } : null;
      })
      .filter((subtitle): subtitle is MovieBoxSubtitleOption => Boolean(subtitle));

    if (proxied.length > 0) {
      cacheService.set(cacheKey, proxied, env.cacheTtlMs);
    }

    return proxied;
  }
}

export const playbackService = new PlaybackService();
