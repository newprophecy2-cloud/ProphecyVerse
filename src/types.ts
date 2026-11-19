export type UserRole = "Guest" | "User";

export interface UserProfile {
  uid: string;
  email: string;
  displayName: string;
  photoURL?: string;
  role: UserRole | string;
  createdAt: string;
}

export interface MovieBoxDubOption {
  subjectId: string;
  language: string;
  label: string;
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
}

export interface MovieBoxSubtitleTrack {
  id: string;
  name: string;
  languageCode?: string;
  url: string;
  format: "srt" | "vtt" | "unknown";
}

export interface Anime {
  id: string;
  provider?: "moviebox";
  title: string;
  englishTitle: string;
  japaneseTitle: string;
  description: string;
  genres: string[];
  studio: string;
  releaseYear: number;
  season: string;
  status: string;
  rating: string;
  score?: number;
  popularity?: number;
  favorites?: number;
  duration: string;
  totalEpisodes?: number;
  episodesCount: number;
  trailerUrl: string;
  banner: string;
  poster: string;
  type: "TV" | "Movie" | "OVA" | "ONA";
  isFeatured?: boolean;
  isTrending?: boolean;
  isRecommended?: boolean;
  isVisible?: boolean;
  country?: string;
  director?: string;
  stars?: string;
  tagline?: string;
  dubs?: MovieBoxDubOption[];
  seasonsData?: Array<{
    number: number;
    maxEpisode: number;
    episodes: Episode[];
  }>;
}

export type SupportedLanguage =
  | "Japanese"
  | "English"
  | "Hindi"
  | "Bangla"
  | "Spanish"
  | string;

export interface AudioTrackInfo {
  id: string | number;
  trackIndex?: number;
  languageCode?: string;
  language?: string;
  name?: string;
  label?: string;
  subjectId?: string;
  isDefault?: boolean;
}

export interface SubtitleTrackInfo {
  id: string | number;
  languageCode?: string;
  name?: string;
  url?: string;
  format?: "srt" | "vtt" | "unknown";
}

export interface EpisodeLanguageData {
  language: SupportedLanguage;
  sourceType?: "moviebox" | "direct" | string;
  videoUrl?: string;
  hlsUrl?: string;
  dashUrl?: string;
  quality?: string;
  subtitlesUrl?: string;
  duration?: string;
  audioTracks?: AudioTrackInfo[];
  availableAudioTracks?: string[];
  defaultAudio?: string;
}

export interface Episode {
  id: string;
  animeId: string;
  animeTitle?: string;
  seasonNumber?: number;
  episodeNumber: number;
  title: string;
  description: string;
  thumbnail?: string;
  thumbnailUrl?: string;
  releaseDate: string;
  duration: string;
  sourceType?: "moviebox" | "direct" | string;
  videoUrl?: string;
  hlsUrl?: string;
  dashUrl?: string;
  subtitlesUrl?: string;
  subtitles?: MovieBoxSubtitleTrack[];
  streamVariants?: MovieBoxStreamVariant[];
  resourceId?: string;
  dubs?: MovieBoxDubOption[];
  isVisible?: boolean;
  hasMultipleAudio?: boolean;
  audioTracks?: AudioTrackInfo[];
  availableAudioTracks?: string[];
  defaultAudio?: string;
  defaultLanguage?: SupportedLanguage;
  languages?: Record<string, EpisodeLanguageData>;
  quality?: string;
  playbackNotes?: string;
  skipIntroStart?: number;
  skipIntroEnd?: number;
  skipOutroStart?: number;
  skipOutroEnd?: number;
}

export interface WatchHistory {
  id: string; // animeId + "_" + episodeNumber
  userId: string;
  animeId: string;
  seasonNumber?: number;
  episodeNumber: number;
  position: number;
  duration: number;
  title?: string;
  poster?: string;
  banner?: string;
  updatedAt: any;
}

export interface Watchlist {
  userId: string;
  animeId: string;
  addedAt: any;
}

export interface Favorite {
  userId: string;
  animeId: string;
  addedAt: any;
}

export interface Comment {
  id: string;
  animeId: string;
  episodeNumber?: number;
  userId: string;
  userName: string;
  userPhoto?: string;
  text: string;
  createdAt: any;
}

export interface Rating {
  id: string;
  userId: string;
  animeId: string;
  score: number;
  updatedAt: any;
}

export interface Notification {
  id: string;
  title: string;
  message: string;
  type: "episode" | "anime" | "system" | "maintenance";
  createdAt: any;
  readBy?: string[];
}

export interface Notice {
  id: string;
  titleBn: string;
  titleEn: string;
  contentBn: string;
  contentEn: string;
  language: "bn" | "en" | "all";
  priority: "high" | "normal" | "low";
  status: "active" | "draft" | "archived";
  displayType: "popup" | "banner" | "both";
  frequency: "show_once" | "show_every_visit" | "show_once_per_day";
  isPinned: boolean;
  scheduleStart?: string;
  scheduleEnd?: string;
  createdAt: number;
}
