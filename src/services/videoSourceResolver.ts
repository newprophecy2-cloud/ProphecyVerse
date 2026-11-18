import { Episode, EpisodeLanguageData } from "../types";
import { getLanguageMetadata } from "./dbService";
import { buildCloudflareVideoUrl } from "./cloudflareService";

export interface ResolvedVideoSource {
  sourceType: "cloudflare" | "mux" | "direct" | "unknown";
  streamUrl: string;
  isHls: boolean;
  isDirect: boolean;
  storageKey?: string;
  playbackId?: string;
  subtitlesUrl?: string;
  hasAudioTracks: boolean;
  audioTracks: any[];
  availableAudioTracks: string[];
  defaultAudio: string;
}

/**
 * Universal video source resolver supporting both legacy Mux documents and new Cloudflare R2 documents.
 * Normalizes video documents into a clean stream format for the player.
 */
export function resolveVideoSource(
  episode: Episode | null | undefined,
  preferredLanguage?: string
): ResolvedVideoSource {
  const fallbackResult: ResolvedVideoSource = {
    sourceType: "unknown",
    streamUrl: "",
    isHls: false,
    isDirect: false,
    storageKey: undefined,
    playbackId: undefined,
    subtitlesUrl: "",
    hasAudioTracks: false,
    audioTracks: [],
    availableAudioTracks: [],
    defaultAudio: "Japanese"
  };

  if (!episode) return fallbackResult;

  // 1. Resolve Language Metadata & Specific Language Data
  const targetLang = preferredLanguage || episode.defaultLanguage || "Japanese";
  const langMeta = getLanguageMetadata(targetLang);

  const langData: EpisodeLanguageData | undefined =
    episode.languages?.[targetLang] ||
    episode.languages?.[langMeta.name] ||
    episode.languages?.[langMeta.code];

  // 2. Extract Candidate IDs and Keys
  const storageKey = (
    langData?.storage?.objectKey ||
    episode.storage?.objectKey ||
    langData?.storageKey ||
    episode.storageKey ||
    ""
  ).trim();

  const rawVideoUrl = (
    langData?.playback?.url ||
    episode.playback?.url ||
    langData?.videoUrl ||
    episode.videoUrl ||
    langData?.hlsUrl ||
    episode.hlsUrl ||
    ""
  ).trim();
  
  const rawPlaybackId = (
    langData?.playbackId ||
    langData?.muxPlaybackId ||
    episode.playbackId ||
    episode.muxPlaybackId ||
    ""
  ).trim();

  const isValidMuxPlaybackId =
    !!rawPlaybackId &&
    !rawPlaybackId.startsWith("upload_") &&
    !rawPlaybackId.startsWith("asset_");

  const subtitlesUrl = langData?.subtitlesUrl || episode.subtitlesUrl || "";
  const audioTracks = langData?.audioTracks || episode.audioTracks || [];
  const availableAudioTracks = langData?.availableAudioTracks || episode.availableAudioTracks || [];
  const defaultAudio = langData?.defaultAudio || episode.defaultAudio || "Japanese";
  const hasAudioTracks = episode.hasMultipleAudio || audioTracks.length > 1;

  // 3. Determine Source Type
  const explicitSourceType =
    langData?.source?.type ||
    episode.source?.type ||
    langData?.sourceType ||
    episode.sourceType;

  // Case A: Explicit Cloudflare R2 or direct worker streaming
  if (
    explicitSourceType === "cloudflare" ||
    (storageKey && !rawVideoUrl.includes("stream.mux.com")) ||
    rawVideoUrl.includes("workers.dev")
  ) {
    const finalUrl = rawVideoUrl || (storageKey ? buildCloudflareVideoUrl(storageKey) : "");
    return {
      sourceType: "cloudflare",
      streamUrl: finalUrl,
      isHls: finalUrl.endsWith(".m3u8"),
      isDirect: !finalUrl.endsWith(".m3u8"),
      storageKey: storageKey || undefined,
      playbackId: undefined,
      subtitlesUrl,
      hasAudioTracks,
      audioTracks,
      availableAudioTracks,
      defaultAudio
    };
  }

  // Case B: Legacy Mux Stream
  if (isValidMuxPlaybackId && explicitSourceType !== "cloudflare") {
    return {
      sourceType: "mux",
      streamUrl: `https://stream.mux.com/${rawPlaybackId}.m3u8`,
      isHls: true,
      isDirect: false,
      storageKey: undefined,
      playbackId: rawPlaybackId,
      subtitlesUrl,
      hasAudioTracks,
      audioTracks,
      availableAudioTracks,
      defaultAudio
    };
  }

  // Case C: Direct / Fallback Stream URL
  if (rawVideoUrl) {
    const isMuxUrl = rawVideoUrl.includes("stream.mux.com");
    return {
      sourceType: isMuxUrl ? "mux" : (rawVideoUrl.includes("workers.dev") ? "cloudflare" : "direct"),
      streamUrl: rawVideoUrl,
      isHls: rawVideoUrl.endsWith(".m3u8") || isMuxUrl,
      isDirect: !rawVideoUrl.endsWith(".m3u8") && !isMuxUrl,
      storageKey: storageKey || undefined,
      playbackId: isValidMuxPlaybackId ? rawPlaybackId : undefined,
      subtitlesUrl,
      hasAudioTracks,
      audioTracks,
      availableAudioTracks,
      defaultAudio
    };
  }

  return fallbackResult;
}
