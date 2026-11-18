export type UserRole = "Guest" | "User" | "Admin" | "Super Admin";

export interface UserProfile {
  uid: string;
  email: string;
  displayName: string;
  photoURL?: string;
  role: UserRole;
  createdAt: string;
}

export interface CharacterInfo {
  name: string;
  role: string;
  image: string;
  voiceActor?: string;
  voiceActorImage?: string;
}

export interface RelatedAnimeItem {
  id: string;
  title: string;
  relationType: string;
  image?: string;
}

export interface RecommendationItem {
  id: string;
  title: string;
  image: string;
  score?: number;
}

export interface Anime {
  id: string;
  title: string;
  englishTitle: string;
  japaneseTitle: string;
  nativeTitle?: string;
  romajiTitle?: string;
  synonyms?: string[];
  description: string;
  genres: string[];
  tags?: string[];
  themes?: string[];
  studio: string;
  producers?: string[];
  licensors?: string[];
  releaseYear: number;
  season: string;
  status: string; // e.g. "Finished", "Airing", "Upcoming", "Completed", "Hiatus", "Cancelled"
  seriesStatus?: "Airing" | "Finished" | "Upcoming" | "Cancelled" | "Hiatus" | "Not Yet Released" | "Completed" | "Officially Announced" | "Coming Soon";
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
  createdAt?: any;
  source?: string;
  ageRating?: string;
  officialWebsite?: string;
  socialLinks?: { twitter?: string; site?: string };
  characters?: CharacterInfo[];
  relatedAnime?: RelatedAnimeItem[];
  recommendations?: RecommendationItem[];
  coverColor?: string;
  lastUpdated?: number;

  // AniList Sync & Master Database Fields
  anilistMediaId?: number;
  currentSeason?: number;
  currentEpisode?: number;
  uploadedEpisodeCount?: number;
  hasDub?: boolean;
  hasSub?: boolean;
  lastUploadDate?: string;
  lastEdited?: string;
  nextAiringEpisode?: {
    episode: number;
    airingAt: number;
    timeUntilAiring: number;
  } | null;
  nextSeasonInfo?: {
    title: string;
    releaseDate?: string;
    status?: string;
    anilistMediaId?: number;
    poster?: string;
  } | null;
  hasNewSeasonAlert?: boolean;
}

export type SupportedLanguage = "Japanese" | "English" | "Hindi" | "Bangla" | "Spanish" | "Subbed" | "Dubbed";

export interface AudioTrackInfo {
  id: string | number;
  trackIndex?: number;
  languageCode?: string;
  language?: string;
  name?: string;
  label?: string;
  codec?: string;
  channels?: number;
  channelLayout?: string;
  sampleRate?: number;
  bitrate?: number;
  primary?: boolean;
  isDefault?: boolean;
}

export interface SubtitleTrackInfo {
  id: string | number;
  languageCode?: string;
  name?: string;
  url?: string;
}

export interface VersionHistoryItem {
  id: string;
  episodeId: string;
  animeId: string;
  animeTitle?: string;
  oldEpisodeNumber?: number;
  newEpisodeNumber: number;
  oldTitle?: string;
  newTitle: string;
  editedBy: string;
  editedAt: string;
  reason: string;
}

export interface EpisodeLanguageData {
  language: SupportedLanguage;
  sourceType?: "cloudflare" | "mux" | "direct" | string;
  storageKey?: string;
  videoUrl?: string;
  hlsUrl?: string;
  iframeUrl?: string;
  quality?: string;
  notes?: string;
  subtitlesUrl?: string;
  subtitleStatus?: string;
  duration?: string;
  uploadDate?: string;
  status?: "Draft" | "Waiting For Encoding" | "Published";
  uploadId?: string;
  assetId?: string;
  playbackId?: string;
  muxPlaybackId?: string;
  muxAssetId?: string;
  muxStatus?: string;
  muxPlaybackUrl?: string;
  hasMultipleAudio?: boolean;
  audioTracks?: AudioTrackInfo[];
  availableAudioTracks?: string[];
  defaultAudio?: string;
  // Cloudflare R2 specific structured properties
  source?: {
    type: string;
    provider: string;
  };
  storage?: {
    bucket?: string;
    objectKey?: string;
  };
  playback?: {
    provider?: string;
    url?: string;
  };
  file?: {
    name?: string;
    contentType?: string;
    size?: number;
  };
  upload?: {
    uploadSessionId?: string;
    uploadId?: string;
  };
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
  thumbnails?: string[];
  primaryThumbnail?: string;
  releaseDate: string;
  duration: string;
  sourceType?: "cloudflare" | "mux" | "direct" | string;
  storageKey?: string;
  videoUrl?: string; // Standard stream
  hlsUrl?: string;   // HLS streaming (.m3u8)
  iframeUrl?: string; // iframe streaming
  videoId?: string;
  status?: "Draft" | "Waiting For Encoding" | "Published";
  publishDate?: string;
  subtitlesUrl?: string;
  isVisible?: boolean;

  // Multi-Audio fields
  hasMultipleAudio?: boolean;
  audioTracks?: AudioTrackInfo[];
  availableAudioTracks?: string[];
  defaultAudio?: string;

  // Language mapping
  defaultLanguage?: SupportedLanguage;
  languages?: Record<string, EpisodeLanguageData>;

  // Cloudflare R2 Database Structure
  source?: {
    type: "cloudflare" | "mux" | "direct" | string;
    provider: "cloudflare_r2" | string;
  };
  storage?: {
    bucket?: string;
    objectKey?: string;
  };
  playback?: {
    provider?: string;
    url?: string;
  };
  file?: {
    name?: string;
    contentType?: string;
    size?: number;
  };
  upload?: {
    uploadSessionId?: string;
    uploadId?: string;
  };

  assetId?: string;
  playbackId?: string;
  uploadId?: string;
  quality?: string;
  notes?: string;

  // Legacy fields
  muxPlaybackId?: string;
  muxAssetId?: string;
  muxStatus?: string;
  muxPlaybackUrl?: string;

  // Skip Intro/Outro Markers
  skipIntroStart?: number;
  skipIntroEnd?: number;
  skipOutroStart?: number;
  skipOutroEnd?: number;

  // Premium & Analytics
  views?: number;
  isPremium?: boolean;
  tags?: string[];
  createdAt?: number | string;
  updatedAt?: number | string;

  // Future-Ready Architecture for AI Video Fingerprinting & Metadata Verification
  fingerprintHash?: string;
  aiMetadataVerified?: boolean;
  recognitionConfidence?: number;
  audioTrackHashes?: string[];
}

export interface UploadSessionDoc {
  uploadSessionId: string;
  animeId: string;
  animeTitle: string;
  seasonNumber: number;
  episodeNumber: number;
  language: string;
  filename: string;
  contentType: string;
  fileSize: number;
  fileFingerprint: string;
  objectKey: string;
  uploadId: string;
  totalParts: number;
  completedParts: number;
  status: "uploading" | "paused" | "completing" | "verifying" | "saving" | "Published" | "failed";
  createdAt?: any;
  updatedAt?: any;
}

export interface WatchHistory {
  id: string; // animeId + "_" + episodeNumber
  userId: string;
  animeId: string;
  episodeNumber: number;
  position: number; // in seconds
  duration: number; // in seconds
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
  id: string; // userId_animeId
  userId: string;
  animeId: string;
  score: number; // 1-10 stars
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

// --- Production-Ready Ads System & Management Types ---
export type AdType =
  | "banner"
  | "popup"
  | "interstitial"
  | "native"
  | "rewarded"
  | "pre-roll"
  | "mid-roll"
  | "post-roll"
  | "floating"
  | "sticky"
  | "sponsor"
  | "custom-html";

export type AdPlacement =
  | "home_top"
  | "home_featured"
  | "home_middle"
  | "home_bottom"
  | "home_native"
  | "anime_details_before_ep"
  | "anime_details_after_info"
  | "anime_details_after_ep"
  | "watch_below_player"
  | "watch_floating_sponsor"
  | "watch_preroll"
  | "search_native"
  | "profile_sponsor"
  | "global_popup"
  | "sticky_bottom";

export type PopupAnimation = "fade" | "zoom" | "slide" | "scale" | "bounce";

export type PopupTriggerRule =
  | "app_open"
  | "watch_episode"
  | "search"
  | "after_delay"
  | "exit_intent";

export interface AdCampaign {
  id: string;
  title: string;
  type: AdType;
  placement: AdPlacement;
  mediaType: "image" | "video" | "gif" | "html";
  mediaUrl?: string;
  htmlCode?: string;
  targetUrl: string;
  ctaText: string;
  ctaColor?: string;
  status: "active" | "paused" | "scheduled" | "archived";
  popupAnimation?: PopupAnimation;
  popupTrigger?: PopupTriggerRule;
  triggerDelaySeconds?: number;
  autoCloseSeconds?: number;
  impressions: number;
  clicks: number;
  scheduleStart?: string;
  scheduleEnd?: string;
  createdAt: number;
}

export interface AdGlobalSettings {
  masterAdsEnabled: boolean;
  bannerAdsEnabled: boolean;
  popupAdsEnabled: boolean;
  videoAdsEnabled: boolean;
  interstitialAdsEnabled: boolean;
  rewardedAdsEnabled: boolean;
  nativeAdsEnabled: boolean;
  sponsorAdsEnabled: boolean;
  floatingAdsEnabled: boolean;
  stickyAdsEnabled: boolean;
  frequencyCapSeconds: number;
  maxPopupsPerSession: number;
  searchAdFrequency: number;
  skipDelaySeconds: number;
}

// --- Missing Episode Alert Types (Local Storage Persistent) ---
export type MissingEpisodeReason =
  | "Video Not Available"
  | "Source Missing"
  | "Upload Failed"
  | "Copyright"
  | "Broken Video"
  | "Waiting For Better Quality"
  | "Temporary Skip"
  | "Other";

export type MissingEpisodePriority = "High" | "Medium" | "Low";

export type MissingEpisodeStatus = "Missing" | "Waiting" | "Uploading" | "Uploaded" | "Ignored";

export interface MissingEpisodeAlert {
  id: string;
  animeName: string;
  animeId?: string;
  season: number | string;
  episodeNumber: number;
  language: string;
  reason: MissingEpisodeReason;
  priority: MissingEpisodePriority;
  status: MissingEpisodeStatus;
  notes?: string;
  createdAt: number;
  updatedAt?: number;
}

