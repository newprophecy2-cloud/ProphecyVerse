import {
  Anime,
  Episode,
  MovieBoxDubOption,
  MovieBoxStreamVariant,
  MovieBoxSubtitleTrack,
} from "../types";

interface ApiEnvelope<T> {
  status: "ok" | "error";
  data?: T;
  code?: string;
  message?: string;
  retryable?: boolean;
}

export class BackendApiError extends Error {
  public readonly code: string;
  public readonly retryable: boolean;
  public readonly statusCode: number;

  constructor(code: string, message: string, retryable: boolean, statusCode: number) {
    super(message);
    this.name = "BackendApiError";
    this.code = code;
    this.retryable = retryable;
    this.statusCode = statusCode;
  }
}

function sanitizeUiMessage(msg: string): string {
  return msg
    .replace(/moviebox\s+provider/gi, "streaming service")
    .replace(/\bby\s+moviebox\b/gi, "")
    .replace(/moviebox/gi, "Prophecy Verse")
    .replace(/\bresolved\b/gi, "loaded")
    .replace(/\s{2,}/g, " ")
    .trim();
}

async function fetchBackendJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(url, {
    method: "GET",
    headers: { Accept: "application/json" },
    signal,
  });

  const contentType = res.headers.get("content-type") || "";
  const rawText = await res.text();
  let payload: ApiEnvelope<T> | null = null;
  if (rawText) {
    try {
      payload = JSON.parse(rawText) as ApiEnvelope<T>;
    } catch {
      payload = null;
    }
  }

  if (!payload) {
    const isHtml = contentType.includes("text/html") || rawText.trim().startsWith("<");
    throw new BackendApiError(
      isHtml ? "SERVER_WARMING_UP" : "API_ERROR",
      isHtml
        ? "API server returned an HTML page instead of JSON (server may still be starting or a proxy intercepted the response). Please retry."
        : `Backend returned an invalid response (${res.status}).`,
      true,
      res.status
    );
  }

  if (!res.ok || payload.status === "error") {
    throw new BackendApiError(
      payload.code || "API_ERROR",
      sanitizeUiMessage(payload.message || `Backend request failed (${res.status})`),
      Boolean(payload.retryable),
      res.status
    );
  }

  return payload.data as T;
}

interface BackendCatalogItem {
  id: string;
  provider: "moviebox";
  title: string;
  mediaType: "movie" | "series";
  year?: string;
  posterUrl?: string;
  seasonCount?: number;
  rating?: string;
  genres?: string[];
  description?: string;
}

interface BackendEpisode {
  id: string;
  subjectId: string;
  season: number;
  number: number;
  title: string;
  overview?: string;
  thumbnailUrl?: string;
  duration?: string;
}

interface BackendSeason {
  number: number;
  maxEpisode: number;
  episodes: BackendEpisode[];
}

interface BackendMediaDetails {
  id: string;
  provider: "moviebox";
  title: string;
  originalTitle?: string;
  mediaType: "movie" | "series";
  year?: string;
  description?: string;
  tagline?: string;
  imdbRating?: string;
  country?: string;
  director?: string;
  stars?: string;
  prints?: string;
  audios?: string;
  posterUrl?: string;
  backdropUrl?: string;
  duration?: string;
  genres: string[];
  seasons: BackendSeason[];
  dubs: MovieBoxDubOption[];
}

export interface BackendPlaybackResponse {
  subjectId: string;
  season: number;
  episode: number;
  resourceId?: string;
  streams: MovieBoxStreamVariant[];
  subtitles: MovieBoxSubtitleTrack[];
  audioTracks: MovieBoxDubOption[];
  placeholderCount: number;
  fetchedAt: number;
}

const FALLBACK_POSTER =
  "https://images.unsplash.com/photo-1578632767115-351597cf2477?q=80&w=600";
const FALLBACK_BANNER =
  "https://images.unsplash.com/photo-1607604276583-eef5d076aa5f?q=80&w=1600";

export function mapCatalogItemToAnime(item: BackendCatalogItem, index: number = 0): Anime {
  const yearNum = item.year ? Number.parseInt(item.year, 10) || 2024 : 2024;
  const isSeries = item.mediaType === "series";
  const poster = item.posterUrl || FALLBACK_POSTER;

  return {
    id: item.id,
    provider: "moviebox",
    title: item.title,
    englishTitle: item.title,
    japaneseTitle: item.title,
    description:
      item.description ||
      "Stream directly in HD on Prophecy Verse.",
    genres: item.genres && item.genres.length > 0 ? item.genres : [isSeries ? "Series" : "Movie"],
    studio: "Prophecy Verse",
    releaseYear: yearNum,
    season: "Catalog",
    status: isSeries ? "Series" : "Movie",
    rating: item.rating || "NR",
    score: item.rating ? Number.parseFloat(item.rating) || undefined : undefined,
    popularity: Math.max(1, 1000 - index),
    duration: isSeries ? "Series" : "Feature",
    episodesCount: item.seasonCount ? item.seasonCount * 12 : isSeries ? 1 : 1,
    trailerUrl: "",
    banner: poster || FALLBACK_BANNER,
    poster,
    type: isSeries ? "TV" : "Movie",
    isFeatured: index === 0,
    isTrending: index < 12,
    isRecommended: index >= 4 && index < 20,
    isVisible: true,
  };
}

export function mapDetailsToAnime(details: BackendMediaDetails): Anime {
  const yearNum = details.year ? Number.parseInt(details.year, 10) || 2024 : 2024;
  const isSeries = details.mediaType === "series";
  const mappedSeasons = (details.seasons || []).map((s) => ({
    number: s.number,
    maxEpisode: s.maxEpisode,
    episodes: s.episodes.map((ep) => mapBackendEpisodeToEpisode(ep, details)),
  }));
  const totalEpisodes = mappedSeasons.reduce((acc, s) => acc + s.episodes.length, 0) || 1;
  const poster = details.posterUrl || FALLBACK_POSTER;
  const banner = details.backdropUrl || details.posterUrl || FALLBACK_BANNER;

  return {
    id: details.id,
    provider: "moviebox",
    title: details.title,
    englishTitle: details.title,
    japaneseTitle: details.originalTitle || details.title,
    description: details.description || details.tagline || "No synopsis available.",
    genres: details.genres || [],
    studio: details.director || details.country || "Prophecy Verse",
    releaseYear: yearNum,
    season: mappedSeasons.length > 0 ? `${mappedSeasons.length} Season(s)` : "Movie",
    status: isSeries ? "Series" : "Movie",
    rating: details.imdbRating || "NR",
    score: details.imdbRating ? Number.parseFloat(details.imdbRating) || undefined : undefined,
    duration: details.duration || (isSeries ? "Episode" : "Feature"),
    totalEpisodes,
    episodesCount: totalEpisodes,
    trailerUrl: "",
    banner,
    poster,
    type: isSeries ? "TV" : "Movie",
    isVisible: true,
    country: details.country,
    director: details.director,
    stars: details.stars,
    tagline: details.tagline,
    dubs: details.dubs || [],
    seasonsData: mappedSeasons,
  };
}

export function mapBackendEpisodeToEpisode(
  ep: BackendEpisode,
  details?: BackendMediaDetails
): Episode {
  return {
    id: ep.id,
    animeId: ep.subjectId,
    animeTitle: details?.title,
    seasonNumber: ep.season,
    episodeNumber: ep.number,
    title: ep.title || (ep.number === 0 ? details?.title || "Full Movie" : `Episode ${ep.number}`),
    description: ep.overview || details?.description || "",
    thumbnail: ep.thumbnailUrl || details?.backdropUrl || details?.posterUrl,
    thumbnailUrl: ep.thumbnailUrl || details?.backdropUrl || details?.posterUrl,
    releaseDate: details?.year || "",
    duration: ep.duration || details?.duration || "",
    sourceType: "moviebox",
    dubs: details?.dubs || [],
    isVisible: true,
  };
}

export function isHevcCodec(codec?: string): boolean {
  if (!codec) return false;
  return /hevc|h\.?265|hev1|hvc1/i.test(codec);
}

export function browserSupportsHevc(): boolean {
  if (typeof window === "undefined") return false;
  try {
    if (
      typeof MediaSource !== "undefined" &&
      typeof MediaSource.isTypeSupported === "function" &&
      (MediaSource.isTypeSupported('video/mp4; codecs="hev1.1.6.L93.90"') ||
        MediaSource.isTypeSupported('video/mp4; codecs="hvc1.1.6.L93.90"'))
    ) {
      return true;
    }
    if (typeof document !== "undefined") {
      const video = document.createElement("video");
      if (
        video.canPlayType('video/mp4; codecs="hev1.1.6.L93.90"') !== "" ||
        video.canPlayType('video/mp4; codecs="hvc1.1.6.L93.90"') !== ""
      ) {
        return true;
      }
    }
  } catch {
    return false;
  }
  return false;
}

export function isStreamVariantPlayableInBrowser(variant: MovieBoxStreamVariant): boolean {
  if (!variant.browserCompatible) return false;
  if (isHevcCodec(variant.codec) && !browserSupportsHevc()) {
    return false;
  }
  return true;
}

export function applyPlaybackToEpisode(
  episode: Episode,
  playback: BackendPlaybackResponse
): Episode {
  const rawStreams = playback.streams || [];
  const hevcSupported = browserSupportsHevc();

  const scoreStream = (s: MovieBoxStreamVariant): number => {
    let score = 0;
    if (s.browserCompatible) score += 100_000;
    const isHevc = isHevcCodec(s.codec);
    if (!isHevc || hevcSupported) score += 10_000;
    if (s.format === "hls") score += 300;
    else if (s.format === "mp4") score += 200;
    else if (s.format === "dash") score += 100;
    score += s.resolution || 0;
    return score;
  };

  const streams = [...rawStreams].sort((a, b) => scoreStream(b) - scoreStream(a));
  const primaryStream = streams[0];

  const subtitles = playback.subtitles || [];
  const primarySub =
    subtitles.find((s) => s.name.toLowerCase() === "english")?.url ||
    subtitles[0]?.url ||
    "";

  const audioTracks = (playback.audioTracks || []).map((dub, idx) => ({
    id: dub.subjectId || idx,
    trackIndex: idx,
    language: dub.language,
    name: dub.language,
    label: dub.label,
    subjectId: dub.subjectId,
    isDefault: dub.subjectId === playback.subjectId || idx === 0,
  }));

  let videoUrl = "";
  let hlsUrl = "";
  let dashUrl = "";
  if (primaryStream) {
    if (primaryStream.format === "hls") {
      hlsUrl = primaryStream.url;
    } else if (primaryStream.format === "dash") {
      dashUrl = primaryStream.url;
    } else {
      videoUrl = primaryStream.url;
    }
  }

  let playbackNotes: string | undefined;
  if (primaryStream && !primaryStream.browserCompatible) {
    playbackNotes =
      (primaryStream.notes ? sanitizeUiMessage(primaryStream.notes) : "") ||
      "This stream uses restricted CDN headers that may not play in standard browsers.";
  } else if (
    streams.length > 0 &&
    !hevcSupported &&
    streams.every((s) => isHevcCodec(s.codec))
  ) {
    playbackNotes =
      "Only H.265/HEVC video is available for this selection; playback requires a browser/device with HEVC decoding support.";
  }

  return {
    ...episode,
    sourceType: "moviebox",
    resourceId: playback.resourceId,
    videoUrl,
    hlsUrl,
    dashUrl,
    subtitlesUrl: primarySub,
    subtitles,
    streamVariants: streams,
    quality: primaryStream?.quality || "Auto",
    hasMultipleAudio: audioTracks.length > 1,
    audioTracks,
    availableAudioTracks: audioTracks.map((a) => a.language || a.label || "Default"),
    defaultAudio: audioTracks.find((a) => a.isDefault)?.language,
    playbackNotes,
  };
}

export async function fetchMovieBoxHomepage(
  tabId: string = "0",
  page: number = 1,
  signal?: AbortSignal
): Promise<Anime[]> {
  const data = await fetchBackendJson<{ items: BackendCatalogItem[] }>(
    `/api/v1/catalog/home?tabId=${encodeURIComponent(tabId)}&page=${page}`,
    signal
  );
  return (data.items || []).map((item, idx) => mapCatalogItemToAnime(item, idx));
}

export async function searchMovieBoxCatalog(
  query: string,
  page: number = 1,
  signal?: AbortSignal
): Promise<{ items: Anime[]; hasMore: boolean; totalCount?: number }> {
  const data = await fetchBackendJson<{
    items: BackendCatalogItem[];
    hasMore: boolean;
    totalCount?: number;
  }>(`/api/v1/anime/search?q=${encodeURIComponent(query)}&page=${page}`, signal);
  return {
    items: (data.items || []).map((item, idx) => mapCatalogItemToAnime(item, idx)),
    hasMore: Boolean(data.hasMore),
    totalCount: data.totalCount,
  };
}

export async function fetchMovieBoxDetails(
  subjectId: string,
  signal?: AbortSignal
): Promise<{ anime: Anime; episodes: Episode[] }> {
  const details = await fetchBackendJson<BackendMediaDetails>(
    `/api/v1/anime/${encodeURIComponent(subjectId)}`,
    signal
  );
  const anime = mapDetailsToAnime(details);

  let episodes: Episode[] = [];
  if (anime.seasonsData && anime.seasonsData.length > 0) {
    episodes = anime.seasonsData.flatMap((s) => s.episodes);
  } else {
    const epRes = await fetchBackendJson<{ episodes: BackendEpisode[] }>(
      `/api/v1/anime/${encodeURIComponent(subjectId)}/episodes`,
      signal
    );
    episodes = (epRes.episodes || []).map((ep) => mapBackendEpisodeToEpisode(ep, details));
  }

  return { anime, episodes };
}

export async function fetchMovieBoxPlayback(
  subjectId: string,
  season: number = 0,
  episode: number = 0,
  forceRefresh: boolean = false,
  signal?: AbortSignal
): Promise<BackendPlaybackResponse> {
  const refreshParam = forceRefresh ? "&refresh=1" : "";
  return fetchBackendJson<BackendPlaybackResponse>(
    `/api/v1/playback/${encodeURIComponent(subjectId)}?se=${season}&ep=${episode}${refreshParam}`,
    signal
  );
}

export async function fetchMovieBoxCaptions(
  subjectId: string,
  resourceId: string,
  siblings: string[] = [],
  season: number = 0,
  episode: number = 0,
  signal?: AbortSignal
): Promise<MovieBoxSubtitleTrack[]> {
  const params = new URLSearchParams({
    subjectId,
    se: String(season),
    ep: String(episode),
  });
  if (siblings.length > 0) {
    params.set("siblings", siblings.join(","));
  }
  const data = await fetchBackendJson<{ captions: MovieBoxSubtitleTrack[] }>(
    `/api/v1/captions/${encodeURIComponent(resourceId)}?${params.toString()}`,
    signal
  );
  return data.captions || [];
}
