import { Episode, EpisodeLanguageData } from "../types";
import { getLanguageMetadata } from "./dbService";

export interface ResolvedVideoSource {
  sourceType: "moviebox" | "direct" | "unknown";
  streamUrl: string;
  isHls: boolean;
  isDash: boolean;
  isDirect: boolean;
  subtitlesUrl: string;
  hasAudioTracks: boolean;
  audioTracks: any[];
  availableAudioTracks: string[];
  defaultAudio: string;
}

export function resolveVideoSource(
  episode: Episode | null | undefined,
  preferredLanguage?: string
): ResolvedVideoSource {
  const fallbackResult: ResolvedVideoSource = {
    sourceType: "unknown",
    streamUrl: "",
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

  const clean = rawVideoUrl.split("?")[0]?.toLowerCase() || "";
  const isHls = clean.endsWith(".m3u8") || clean.includes("/hls/");
  const isDash = clean.endsWith(".mpd") || clean.includes("/dash/");

  return {
    sourceType: episode.sourceType === "moviebox" ? "moviebox" : "direct",
    streamUrl: rawVideoUrl,
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
