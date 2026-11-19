import { env } from "../config/env";
import { movieBoxClient } from "../providers/moviebox/client";
import { MovieBoxError } from "../providers/moviebox/errors";
import {
  mapCollectionResolutions,
  mapDetailsToMediaDetails,
  mapHomepageToCatalog,
  mapSearchToCatalog,
  mapSuggestToStrings,
} from "../providers/moviebox/mapper";
import {
  MovieBoxCatalogItem,
  MovieBoxEpisode,
  MovieBoxMediaDetails,
  MovieBoxSeason,
} from "../providers/moviebox/types";
import { cacheService } from "./cacheService";

export class CatalogService {
  public async getHomepage(tabId: string = "0", page: number = 1): Promise<{
    items: MovieBoxCatalogItem[];
    tabId: string;
    page: number;
  }> {
    const cacheKey = `home:${tabId}:${page}`;
    const items = await cacheService.getOrFetch(cacheKey, env.cacheTtlMs, async () => {
      const payload = await movieBoxClient.getHomepage(tabId, page);
      return mapHomepageToCatalog(payload);
    });
    return { items, tabId, page };
  }

  public async search(query: string, page: number = 1, subjectType: number = 0): Promise<{
    items: MovieBoxCatalogItem[];
    page: number;
    hasMore: boolean;
    totalCount?: number;
  }> {
    const normalizedQuery = query.trim();
    if (!normalizedQuery) {
      return { items: [], page, hasMore: false, totalCount: 0 };
    }

    const cacheKey = `search:${normalizedQuery.toLowerCase()}:${page}:${subjectType}`;
    const result = await cacheService.getOrFetch(cacheKey, env.cacheTtlMs, async () => {
      const payload = await movieBoxClient.search(normalizedQuery, page, subjectType);
      return mapSearchToCatalog(payload);
    });

    return {
      items: result.items,
      page,
      hasMore: result.hasMore,
      totalCount: result.totalCount,
    };
  }

  public async suggest(query: string): Promise<string[]> {
    const normalizedQuery = query.trim();
    if (!normalizedQuery) {
      return [];
    }
    const cacheKey = `suggest:${normalizedQuery.toLowerCase()}`;
    return cacheService.getOrFetch(cacheKey, env.cacheTtlMs, async () => {
      const payload = await movieBoxClient.suggest(normalizedQuery);
      return mapSuggestToStrings(payload);
    });
  }

  public async getCollectionResolutions(subjectId: string): Promise<number[]> {
    const cacheKey = `resolutions:${subjectId}`;
    return cacheService.getOrFetch(cacheKey, env.cacheTtlMs, async () => {
      const payload = await movieBoxClient.fetchCollectionResolutions(subjectId);
      return mapCollectionResolutions(payload);
    });
  }

  public async getDetails(subjectId: string): Promise<MovieBoxMediaDetails> {
    const cacheKey = `details:${subjectId}`;
    return cacheService.getOrFetch(cacheKey, env.cacheTtlMs, async () => {
      const payload = await movieBoxClient.getDetails(subjectId);
      const details = mapDetailsToMediaDetails(payload);
      if (!details) {
        throw new MovieBoxError("RESOURCE_NOT_FOUND", "Subject not found.", 404, false);
      }
      return details;
    });
  }

  public async getSeasons(subjectId: string): Promise<MovieBoxSeason[]> {
    const details = await this.getDetails(subjectId);
    if (details.seasons.length > 0) {
      return details.seasons;
    }
    if (details.mediaType === "movie") {
      return [
        {
          number: 1,
          maxEpisode: 1,
          episodes: [
            {
              id: `${subjectId}_s0_e0`,
              subjectId,
              season: 0,
              number: 0,
              title: details.title,
              overview: details.description,
              thumbnailUrl: details.backdropUrl || details.posterUrl,
              duration: details.duration,
            },
          ],
        },
      ];
    }
    return [];
  }

  public async getEpisodes(subjectId: string, seasonFilter?: number): Promise<MovieBoxEpisode[]> {
    const seasons = await this.getSeasons(subjectId);
    const allEpisodes = seasons.flatMap((s) => s.episodes);
    if (typeof seasonFilter === "number" && seasonFilter > 0) {
      return allEpisodes.filter((ep) => ep.season === seasonFilter);
    }
    return allEpisodes;
  }

  public async getSingleEpisode(
    subjectId: string,
    season: number,
    episodeNumber: number
  ): Promise<MovieBoxEpisode> {
    const details = await this.getDetails(subjectId);
    if (details.mediaType === "movie" && season === 0 && episodeNumber === 0) {
      return {
        id: `${subjectId}_s0_e0`,
        subjectId,
        season: 0,
        number: 0,
        title: details.title,
        overview: details.description,
        thumbnailUrl: details.backdropUrl || details.posterUrl,
        duration: details.duration,
      };
    }

    const match = details.seasons
      .flatMap((s) => s.episodes)
      .find((ep) => ep.season === season && ep.number === episodeNumber);

    if (match) {
      return match;
    }

    throw new MovieBoxError(
      "RESOURCE_NOT_FOUND",
      `Episode S${season}E${episodeNumber} was not found for subject ${subjectId}.`,
      404,
      false
    );
  }
}

export const catalogService = new CatalogService();
