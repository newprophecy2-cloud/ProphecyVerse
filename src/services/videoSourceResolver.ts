import { Episode, EpisodeLanguageData } from "../types";
import { getLanguageMetadata } from "./dbService";

export interface ResolvedVideoSource {
  sourceType: "moviebox" | "direct" | "unknown";
  streamUrl: string;
  format: "hls" | "dash" | "mp4";
  isHls: boolean;
  isDash: boolean;
  isDirect: boolean;
  subtitlesUrl: string;
  hasAudioTracks: boolean;
  audioTracks: any[];
  availableAudioTracks: string[];
  defaultAudio: string;
}

export function detectStreamFormatFromMetadata(
  rawUrl: string,
  episode?: Episode | null,
  langData?: EpisodeLanguageData
): "hls" | "dash" | "mp4" {
  const matchedVariant = episode?.streamVariants?.find((v) => v.url === rawUrl);
  if (matchedVariant) {
    if (matchedVariant.format === "hls") return "hls";
    if (matchedVariant.format === "dash") return "dash";
    if (matchedVariant.format === "mp4") return "mp4";
  }

  if (rawUrl && (rawUrl === langData?.hlsUrl || rawUrl === episode?.hlsUrl)) {
    return "hls";
  }
  if (rawUrl && (rawUrl === langData?.dashUrl || rawUrl === episode?.dashUrl)) {
    return "dash";
  }

  const clean = rawUrl.split(/[?#]/)[0]?.toLowerCase() || "";
  if (clean.endsWith(".m3u8") || clean.includes("/hls/")) {
    return "hls";
  }
  if (clean.endsWith(".mpd") || clean.includes("/dash/")) {
    return "dash";
  }
  return "mp4";
}

export function resolveVideoSource(
  episode: Episode | null | undefined,
  preferredLanguage?: string
): ResolvedVideoSource {
  const fallbackResult: ResolvedVideoSource = {
    sourceType: "unknown",
    streamUrl: "",
    format: "mp4",
    isHls: false,
    isDash: false,
    isDirect: false,
    subtitlesUrl: "",
    hasAudioTracks: false,
    audioTracks: [],
    availableAudioTracks: [],
    defaultAudio: "English",
  };

  if (!episode) return fallbackResult;

  const targetLang = preferredLanguage || episode.defaultLanguage || "English";
  const langMeta = getLanguageMetadata(targetLang);

  const langData: EpisodeLanguageData | undefined =
    episode.languages?.[targetLang] ||
    episode.languages?.[langMeta.name] ||
    episode.languages?.[langMeta.code];

  const rawVideoUrl = (
    langData?.hlsUrl ||
    langData?.dashUrl ||
    langData?.videoUrl ||
    episode.hlsUrl ||
    episode.dashUrl ||
    episode.videoUrl ||
    episode.streamVariants?.[0]?.url ||
    ""
  ).trim();

  const subtitlesUrl = langData?.subtitlesUrl || episode.subtitlesUrl || "";
  const audioTracks = langData?.audioTracks || episode.audioTracks || [];
  const availableAudioTracks =
    langData?.availableAudioTracks || episode.availableAudioTracks || [];
  const defaultAudio = langData?.defaultAudio || episode.defaultAudio || "English";
  const hasAudioTracks = Boolean(episode.hasMultipleAudio || audioTracks.length > 1);

  if (!rawVideoUrl) {
    return fallbackResult;
  }

  const format = detectStreamFormatFromMetadata(rawVideoUrl, episode, langData);
  const isHls = format === "hls";
  const isDash = format === "dash";

  return {
    sourceType: episode.sourceType === "moviebox" ? "moviebox" : "direct",
    streamUrl: rawVideoUrl,
    format,
    isHls,
    isDash,
    isDirect: !isHls && !isDash,
    subtitlesUrl,
    hasAudioTracks,
    audioTracks,
    availableAudioTracks,
    defaultAudio,
  };
}
