export type MovieBoxMediaType = "movie" | "series";

export interface MovieBoxSession {
  token: string;
  userId?: string;
  expiresAt?: number; // Unix seconds
  createdAt: number; // Unix seconds
}

export interface MovieBoxCatalogItem {
  id: string;
  provider: "moviebox";
  title: string;
  mediaType: MovieBoxMediaType;
  year?: string;
  posterUrl?: string;
  seasonCount?: number;
  rating?: string;
  genres?: string[];
  description?: string;
}

export interface MovieBoxEpisode {
  id: string;
  subjectId: string;
  season: number;
  number: number;
  title: string;
  overview?: string;
  thumbnailUrl?: string;
  duration?: string;
}

export interface MovieBoxSeason {
  number: number;
  maxEpisode: number;
  episodes: MovieBoxEpisode[];
}

export interface MovieBoxAudioTrackOption {
  subjectId: string;
  language: string;
  label: string;
}

export interface MovieBoxMediaDetails {
  id: string;
  provider: "moviebox";
  title: string;
  originalTitle?: string;
  mediaType: MovieBoxMediaType;
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
  seasons: MovieBoxSeason[];
  dubs: MovieBoxAudioTrackOption[];
}

export interface MovieBoxSubtitleOption {
  id: string;
  name: string;
  languageCode?: string;
  url: string;
  format: "srt" | "vtt" | "unknown";
  upstreamCookie?: string;
}

export interface MovieBoxStreamVariant {
  id: string;
  resourceId?: string;
  label: string;
  quality: string;
  resolution: number;
  format: "mp4" | "dash" | "hls" | "unknown";
  codec?: string;
  language?: string;
  sizeBytes?: number;
  url: string;
  requiresCustomHeaders: boolean;
  hasSignedCookie: boolean;
  browserCompatible: boolean;
  notes?: string;
  upstreamCookie?: string;
}

export interface MovieBoxPlaybackResult {
  subjectId: string;
  season: number;
  episode: number;
  resourceId?: string;
  streams: MovieBoxStreamVariant[];
  subtitles: MovieBoxSubtitleOption[];
  audioTracks: MovieBoxAudioTrackOption[];
  placeholderCount: number;
  fetchedAt: number;
}
