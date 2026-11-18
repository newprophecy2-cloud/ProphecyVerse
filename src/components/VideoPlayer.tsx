import React, { useRef, useState, useEffect } from "react";
import Hls from "hls.js";
import { 
  Play, Pause, Volume2, VolumeX, Maximize, Minimize, RotateCcw, RotateCw, 
  Settings, Loader, SkipForward, SkipBack, Lock, Unlock, Monitor, Languages,
  Tv, Subtitles, Check, Globe, Minimize2, Maximize2, X, Sparkles,
  ArrowLeft, AlertTriangle, Sun, RefreshCw
} from "lucide-react";
import { Episode } from "../types";
import { getLanguageMetadata } from "../services/dbService";
import { buildCloudflareVideoUrl } from "../services/cloudflareService";
import { resolveVideoSource } from "../services/videoSourceResolver";

interface VideoPlayerProps {
  episode: Episode;
  nextEpisode?: Episode | null;
  onNextEpisode?: () => void;
  onPreviousEpisode?: () => void;
  hasPreviousEpisode?: boolean;
  onEpisodeComplete?: () => void;
  onProgressUpdate?: (seconds: number, duration: number) => void;
  initialPosition?: number;
  isMini?: boolean;
  onMinimize?: () => void;
  onExpand?: () => void;
  onClose?: () => void;
  animeTitle?: string;
  onBack?: () => void;
}

export interface SubtitleCue {
  id: string;
  start: number;
  end: number;
  text: string;
}

export interface DetectedAudioTrack {
  id: string | number;
  nativeIndex: number;
  name: string;
  lang: string;
  isHls: boolean;
}

export function normalizeAudioLanguageName(rawNameOrLang?: string, fallbackIndex = 0): string {
  if (!rawNameOrLang) return `Audio Track ${fallbackIndex + 1}`;
  const str = String(rawNameOrLang).trim();
  const lower = str.toLowerCase();

  if (lower.includes("ja") || lower.includes("jpn") || lower.includes("japanese")) return "Japanese";
  if (lower.includes("hi") || lower.includes("hin") || lower.includes("hindi")) return "Hindi";
  if (lower.includes("en") || lower.includes("eng") || lower.includes("english")) return "English";
  if (lower.includes("ta") || lower.includes("tam") || lower.includes("tamil")) return "Tamil";
  if (lower.includes("ko") || lower.includes("kor") || lower.includes("korean")) return "Korean";
  if (lower.includes("es") || lower.includes("spa") || lower.includes("spanish")) return "Spanish";
  if (lower.includes("ar") || lower.includes("ara") || lower.includes("arabic")) return "Arabic";
  if (lower.includes("bn") || lower.includes("ben") || lower.includes("bangla") || lower.includes("bengali")) return "Bangla";
  if (lower.includes("fr") || lower.includes("fre") || lower.includes("fra") || lower.includes("french")) return "French";
  if (lower.includes("de") || lower.includes("ger") || lower.includes("deu") || lower.includes("german")) return "German";

  return str.charAt(0).toUpperCase() + str.slice(1);
}

export function getAudioTrackDisplayInfo(rawNameOrLang?: string, fallbackIndex = 0): { name: string; flag: string; fullLabel: string } {
  const norm = normalizeAudioLanguageName(rawNameOrLang, fallbackIndex);
  const lower = norm.toLowerCase();

  let flag = "🌐";
  if (lower.includes("hindi")) flag = "🇮🇳";
  else if (lower.includes("japanese")) flag = "🇯🇵";
  else if (lower.includes("english")) flag = "🇺🇸";
  else if (lower.includes("bangla")) flag = "🇧🇩";
  else if (lower.includes("spanish")) flag = "🇪🇸";
  else if (lower.includes("french")) flag = "🇫🇷";
  else if (lower.includes("german")) flag = "🇩🇪";
  else if (lower.includes("korean")) flag = "🇰🇷";
  else if (lower.includes("tamil") || lower.includes("telugu") || lower.includes("marathi")) flag = "🇮🇳";

  return {
    name: norm,
    flag,
    fullLabel: `${flag} ${norm}`
  };
}

export function findDefaultAudioTrackIndex(tracks: DetectedAudioTrack[]): number {
  if (!tracks || tracks.length === 0) return 0;
  // Website default priority: Hindi -> Japanese -> English -> First available track
  const hindiIdx = tracks.findIndex(t => t.name.toLowerCase().includes("hindi") || (t.lang && (t.lang.toLowerCase() === "hi" || t.lang.toLowerCase() === "hin")));
  if (hindiIdx >= 0) return hindiIdx;

  const jpnIdx = tracks.findIndex(t => t.name.toLowerCase().includes("japanese") || (t.lang && (t.lang.toLowerCase() === "ja" || t.lang.toLowerCase() === "jpn")));
  if (jpnIdx >= 0) return jpnIdx;

  const engIdx = tracks.findIndex(t => t.name.toLowerCase().includes("english") || (t.lang && (t.lang.toLowerCase() === "en" || t.lang.toLowerCase() === "eng")));
  if (engIdx >= 0) return engIdx;

  return 0;
}

export function parseVttOrSrtCues(rawContent: string): SubtitleCue[] {
  if (!rawContent || typeof rawContent !== "string") return [];
  const cues: SubtitleCue[] = [];
  const lines = rawContent.replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n");
  
  let currentStart = -1;
  let currentEnd = -1;
  let textLines: string[] = [];

  const timeRegex = /(?:(\d{2}):)?(\d{2}):(\d{2})[\.,](\d{3})/;

  const parseTime = (timeStr: string): number => {
    const match = timeStr.trim().match(timeRegex);
    if (!match) return 0;
    const hours = match[1] ? parseInt(match[1], 10) : 0;
    const minutes = parseInt(match[2], 10);
    const seconds = parseInt(match[3], 10);
    const ms = parseInt(match[4], 10);
    return hours * 3600 + minutes * 60 + seconds + ms / 1000;
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line || line.startsWith("WEBVTT") || line.startsWith("NOTE")) {
      if (currentStart >= 0 && currentEnd > currentStart && textLines.length > 0) {
        cues.push({
          id: `cue-${cues.length + 1}`,
          start: currentStart,
          end: currentEnd,
          text: textLines.join("\n").replace(/<[^>]*>/g, "").trim(),
        });
      }
      currentStart = -1;
      currentEnd = -1;
      textLines = [];
      continue;
    }

    if (line.includes("-->")) {
      const parts = line.split("-->");
      if (parts.length >= 2) {
        currentStart = parseTime(parts[0]);
        currentEnd = parseTime(parts[1].split(" ")[0]);
        textLines = [];
      }
    } else if (currentStart >= 0) {
      if (!/^\d+$/.test(line)) {
        textLines.push(line);
      }
    }
  }

  if (currentStart >= 0 && currentEnd > currentStart && textLines.length > 0) {
    cues.push({
      id: `cue-${cues.length + 1}`,
      start: currentStart,
      end: currentEnd,
      text: textLines.join("\n").replace(/<[^>]*>/g, "").trim(),
    });
  }

  return cues;
}

export const SUBTITLE_LANGUAGES = [
  { code: "off", label: "Off", flag: "🚫" },
  { code: "en", label: "English", flag: "🇺🇸" },
  { code: "hi", label: "Hindi", flag: "🇮🇳" },
  { code: "ja", label: "Japanese", flag: "🇯🇵" },
  { code: "bn", label: "Bangla", flag: "🇧🇩" },
  { code: "es", label: "Spanish", flag: "🇪🇸" },
  { code: "fr", label: "French", flag: "🇫🇷" },
  { code: "de", label: "German", flag: "🇩🇪" },
];

export function translateTextToTargetLanguage(text: string, targetLang: string): string {
  if (!text || targetLang === "off") return "";
  return text;
}

export const VideoPlayer: React.FC<VideoPlayerProps> = ({
  episode,
  nextEpisode,
  onNextEpisode,
  onPreviousEpisode,
  hasPreviousEpisode = false,
  onEpisodeComplete,
  onProgressUpdate,
  initialPosition = 0,
  isMini = false,
  onMinimize,
  onExpand,
  onClose,
  animeTitle,
  onBack,
}) => {
  const videoRef = useRef<HTMLVideoElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const controlsTimeoutRef = useRef<any>(null);

  const [isPlaying, setIsPlaying] = useState(false);
  const [duration, setDuration] = useState(0);
  const [currentTime, setCurrentTime] = useState(0);
  const [bufferedTime, setBufferedTime] = useState(0);
  const [volume, setVolume] = useState(0.8);
  const [isMuted, setIsMuted] = useState(false);
  const [playbackSpeed, setPlaybackSpeed] = useState<number>(() => {
    return parseFloat(localStorage.getItem("animestream_preferred_speed") || "1");
  });
  const [showSpeedMenu, setShowSpeedMenu] = useState(false);
  const [isLocked, setIsLocked] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [isPip, setIsPip] = useState(false);
  const [isCinemaMode, setIsCinemaMode] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [showControls, setShowControls] = useState(true);
  const [playbackError, setPlaybackError] = useState<string | null>(null);

  // Gesture & Double-Tap Seek feedback states
  const [brightness, setBrightness] = useState(1);
  const [gestureToast, setGestureToast] = useState<{ icon: "sun" | "vol"; label: string } | null>(null);
  const [seekRipple, setSeekRipple] = useState<{ type: "left" | "right"; amount: number; id: number } | null>(null);
  const lastTapTimeRef = useRef<number>(0);

  // Audio track switching position preservation refs
  const pendingSeekPositionRef = useRef<number | null>(null);
  const wasPlayingBeforeSwitchRef = useRef<boolean>(false);

  // Auto-Play State & 5-second Countdown
  const [autoPlayEnabled, setAutoPlayEnabled] = useState<boolean>(() => {
    const stored = localStorage.getItem("animestream_autoplay");
    return stored !== null ? stored === "true" : true;
  });
  const [countdownSeconds, setCountdownSeconds] = useState<number | null>(null);
  const countdownTimerRef = useRef<any>(null);

  // Reset countdown whenever episode changes
  useEffect(() => {
    if (countdownTimerRef.current) clearInterval(countdownTimerRef.current);
    setCountdownSeconds(null);
  }, [episode.id, episode.episodeNumber]);

  // Clean up timer on unmount
  useEffect(() => {
    return () => {
      if (countdownTimerRef.current) clearInterval(countdownTimerRef.current);
    };
  }, []);

  const toggleAutoPlay = (e?: React.MouseEvent) => {
    if (e) e.stopPropagation();
    const nextVal = !autoPlayEnabled;
    setAutoPlayEnabled(nextVal);
    localStorage.setItem("animestream_autoplay", String(nextVal));
    if (!nextVal && countdownSeconds !== null) {
      if (countdownTimerRef.current) clearInterval(countdownTimerRef.current);
      setCountdownSeconds(null);
    }
  };

  const startAutoPlayCountdown = () => {
    if (countdownTimerRef.current) clearInterval(countdownTimerRef.current);
    setCountdownSeconds(5);

    let secsLeft = 5;
    countdownTimerRef.current = setInterval(() => {
      secsLeft -= 1;
      if (secsLeft <= 0) {
        clearInterval(countdownTimerRef.current);
        setCountdownSeconds(null);
        if (onNextEpisode) {
          onNextEpisode();
        }
      } else {
        setCountdownSeconds(secsLeft);
      }
    }, 1000);
  };

  const cancelAutoPlayCountdown = (e?: React.MouseEvent) => {
    if (e) e.stopPropagation();
    if (countdownTimerRef.current) clearInterval(countdownTimerRef.current);
    setCountdownSeconds(null);
  };

  const playNextNow = (e?: React.MouseEvent) => {
    if (e) e.stopPropagation();
    if (countdownTimerRef.current) clearInterval(countdownTimerRef.current);
    setCountdownSeconds(null);
    if (onNextEpisode) {
      onNextEpisode();
    }
  };

  // Subtitles States & Multi-Language Selection
  const [subLanguage, setSubLanguage] = useState<string>(() => {
    return localStorage.getItem("animestream_preferred_sub_lang") || "en";
  });
  const [showSubMenu, setShowSubMenu] = useState(false);
  const [rawSubtitleText, setRawSubtitleText] = useState("");
  const [subtitleCues, setSubtitleCues] = useState<SubtitleCue[]>([]);

  // Calculate default language metadata
  const initialLangMeta = getLanguageMetadata(episode.defaultLanguage || "Japanese");
  const [selectedAudioLang, setSelectedAudioLang] = useState<string>(initialLangMeta.name);
  const [showAudioMenu, setShowAudioMenu] = useState(false);

  // Auto Audio Track Detection States
  const [detectedAudioTracks, setDetectedAudioTracks] = useState<DetectedAudioTrack[]>([]);
  const [selectedAudioTrackIndex, setSelectedAudioTrackIndex] = useState<number>(0);
  const [audioErrorMessage, setAudioErrorMessage] = useState<string | null>(null);
  const [showSettingsMenu, setShowSettingsMenu] = useState(false);
  const [settingsTab, setSettingsTab] = useState<"main" | "quality" | "audio" | "speed" | "subtitles">("main");

  // Active episode state with auto-sync capability
  const [currentEp, setCurrentEp] = useState<Episode>(episode);

  useEffect(() => {
    setCurrentEp(episode);
  }, [episode.id, episode.playbackId, episode.muxPlaybackId]);

  // Sync selectedAudioLang whenever episode defaultLanguage changes
  useEffect(() => {
    if (currentEp.defaultLanguage) {
      const meta = getLanguageMetadata(currentEp.defaultLanguage);
      setSelectedAudioLang(meta.name);
    }
  }, [currentEp.defaultLanguage, currentEp.id]);

  // Advanced Quality Selector States (240p - 1080p + Auto)
  const [selectedQuality, setSelectedQuality] = useState<string>(() => {
    return localStorage.getItem("animestream_preferred_quality") || "Auto";
  });
  const [availableQualities, setAvailableQualities] = useState<{ height: number; levelIndex: number; label: string }[]>([]);
  const [showQualityMenu, setShowQualityMenu] = useState(false);
  const hlsInstanceRef = useRef<any>(null);

  // Calculate active language data and video source using universal resolver
  const resolvedSource = resolveVideoSource(currentEp, selectedAudioLang);
  const currentLangMeta = getLanguageMetadata(selectedAudioLang);
  const activeLangData = currentEp.languages?.[selectedAudioLang] ||
    currentEp.languages?.[currentLangMeta.code] ||
    currentEp.languages?.[currentLangMeta.name];

  const videoSrc = resolvedSource.streamUrl;
  const isMux = resolvedSource.sourceType === "mux";
  const isCloudflare = resolvedSource.sourceType === "cloudflare";
  const hasValidVideoSource = !!videoSrc;

  const activeSubtitlesUrl = activeLangData?.subtitlesUrl || currentEp.subtitlesUrl || "";

  const SUPPORTED_AUDIO_LANGUAGES = Array.from(new Set([
    ...(currentEp.defaultLanguage ? [getLanguageMetadata(currentEp.defaultLanguage).name] : []),
    ...Object.keys(currentEp.languages || {}).map(k => getLanguageMetadata(k).name),
    "Japanese", "English", "Hindi", "Bangla", "Spanish"
  ])).filter(Boolean);

  const populateHlsAudioTracks = (hlsInstance: any) => {
    if (!hlsInstance || !hlsInstance.audioTracks) return;
    console.log("[AudioTrack] HLS audioTracks detected:", hlsInstance.audioTracks);

    if (hlsInstance.audioTracks.length > 0) {
      const tracks: DetectedAudioTrack[] = hlsInstance.audioTracks.map((tr: any, idx: number) => {
        const raw = tr.name || tr.lang || tr.label || `Track ${idx + 1}`;
        const normalized = normalizeAudioLanguageName(raw, idx);
        console.log(`[AudioTrack] Track ${idx}: ID=${tr.id}, Raw="${raw}", Normalized="${normalized}", Default=${tr.default}`);
        return {
          id: tr.id ?? idx,
          nativeIndex: idx,
          name: normalized,
          lang: tr.lang || "",
          isHls: true,
        };
      });

      setDetectedAudioTracks(tracks);
      console.log(`[AudioTrack] Total HLS Audio Tracks detected: ${tracks.length}`);

      const preferredLang = localStorage.getItem("animestream_preferred_audio_lang");
      let targetIdx = hlsInstance.audioTrack >= 0 ? hlsInstance.audioTrack : 0;
      if (preferredLang) {
        const matchIdx = tracks.findIndex(t => 
          t.name.toLowerCase() === preferredLang.toLowerCase() ||
          (t.lang && preferredLang.toLowerCase().includes(t.lang.toLowerCase()))
        );
        if (matchIdx >= 0) {
          targetIdx = matchIdx;
        } else {
          targetIdx = findDefaultAudioTrackIndex(tracks);
        }
      } else {
        targetIdx = findDefaultAudioTrackIndex(tracks);
      }

      if (hlsInstance.audioTrack !== targetIdx) {
        try {
          console.log(`[AudioTrack] Setting HLS initial audioTrack = ${targetIdx} (${tracks[targetIdx]?.name})`);
          hlsInstance.audioTrack = targetIdx;
        } catch (err) {
          console.warn("[AudioTrack] Error setting initial HLS audioTrack:", err);
        }
      }

      setSelectedAudioTrackIndex(targetIdx);
      if (tracks[targetIdx]) {
        setSelectedAudioLang(tracks[targetIdx].name);
        console.log(`[AudioTrack] Active Audio Track set to: ${tracks[targetIdx].name} (Index ${targetIdx})`);
      }
    } else {
      setupFallbackAudioTracks();
    }
  };

  const populateNativeAudioTracks = () => {
    const video = videoRef.current;
    if (!video) return;

    const nativeList = (video as any).audioTracks;
    if (nativeList && nativeList.length > 0) {
      console.log("[AudioTrack] Native video.audioTracks detected:", nativeList);
      const tracks: DetectedAudioTrack[] = [];
      let enabledIndex = 0;

      for (let i = 0; i < nativeList.length; i++) {
        const tr = nativeList[i];
        if (tr.enabled) enabledIndex = i;
        const raw = tr.label || tr.language || `Track ${i + 1}`;
        const normalized = normalizeAudioLanguageName(raw, i);
        console.log(`[AudioTrack] Native Track ${i}: Label="${tr.label}", Lang="${tr.language}", Normalized="${normalized}", Enabled=${tr.enabled}`);
        tracks.push({
          id: tr.id || i,
          nativeIndex: i,
          name: normalized,
          lang: tr.language || "",
          isHls: false,
        });
      }

      setDetectedAudioTracks(tracks);

      const preferredLang = localStorage.getItem("animestream_preferred_audio_lang");
      let targetIdx = enabledIndex;
      if (preferredLang) {
        const matchIdx = tracks.findIndex(t =>
          t.name.toLowerCase() === preferredLang.toLowerCase() ||
          (t.lang && preferredLang.toLowerCase().includes(t.lang.toLowerCase()))
        );
        if (matchIdx >= 0) {
          targetIdx = matchIdx;
        } else {
          targetIdx = findDefaultAudioTrackIndex(tracks);
        }
      } else {
        targetIdx = findDefaultAudioTrackIndex(tracks);
      }

      for (let i = 0; i < nativeList.length; i++) {
        nativeList[i].enabled = (i === targetIdx);
      }

      setSelectedAudioTrackIndex(targetIdx);
      if (tracks[targetIdx]) {
        setSelectedAudioLang(tracks[targetIdx].name);
        console.log(`[AudioTrack] Native Active Audio Track set to: ${tracks[targetIdx].name} (Index ${targetIdx})`);
      }
    } else {
      setupFallbackAudioTracks();
    }
  };

  const setupFallbackAudioTracks = () => {
    // Only detect fallback audio options if episode.languages contains separate valid stream URLs
    const validLanguageKeys = episode.languages ? Object.keys(episode.languages).filter(k => {
      const lData = episode.languages[k];
      return lData && (lData.videoUrl || lData.muxPlaybackUrl || lData.muxPlaybackId || lData.hlsUrl);
    }) : [];

    if (validLanguageKeys.length > 1) {
      const tracks: DetectedAudioTrack[] = validLanguageKeys.map((k, idx) => {
        const meta = getLanguageMetadata(k);
        return {
          id: k,
          nativeIndex: idx,
          name: meta.name,
          lang: meta.code,
          isHls: false,
        };
      });
      setDetectedAudioTracks(tracks);
      console.log("[AudioTrack] Separate multi-language stream URLs found in episode database:", tracks);

      const preferredLang = localStorage.getItem("animestream_preferred_audio_lang");
      let targetIdx = 0;
      if (preferredLang) {
        const matchIdx = tracks.findIndex(t => t.name.toLowerCase() === preferredLang.toLowerCase());
        if (matchIdx >= 0) targetIdx = matchIdx;
      }
      setSelectedAudioTrackIndex(targetIdx);
      if (tracks[targetIdx]) {
        setSelectedAudioLang(tracks[targetIdx].name);
      }
    } else {
      console.log("[AudioTrack] Source media contains 1 audio track and no separate language URLs in database.");
      setDetectedAudioTracks([]);
    }
  };

  const handleSelectAudioTrack = (trackIndex: number) => {
    if (trackIndex < 0 || trackIndex >= detectedAudioTracks.length) return;
    const track = detectedAudioTracks[trackIndex];

    console.log(`[AudioTrack] Switching audio track to #${trackIndex} ("${track.name}", nativeIndex=${track.nativeIndex}, isHls=${track.isHls})`);

    if (videoRef.current) {
      pendingSeekPositionRef.current = videoRef.current.currentTime;
      wasPlayingBeforeSwitchRef.current = !videoRef.current.paused;
    }

    try {
      if (track.isHls && hlsInstanceRef.current) {
        hlsInstanceRef.current.audioTrack = track.nativeIndex;
        setSelectedAudioTrackIndex(trackIndex);
        setSelectedAudioLang(track.name);
        localStorage.setItem("animestream_preferred_audio_lang", track.name);
        console.log(`[AudioTrack] HLS audioTrack assigned to ${track.nativeIndex} (${track.name})`);
      } else if (videoRef.current && (videoRef.current as any).audioTracks && (videoRef.current as any).audioTracks.length > 0) {
        const nativeList = (videoRef.current as any).audioTracks;
        for (let i = 0; i < nativeList.length; i++) {
          nativeList[i].enabled = (i === track.nativeIndex);
        }
        setSelectedAudioTrackIndex(trackIndex);
        setSelectedAudioLang(track.name);
        localStorage.setItem("animestream_preferred_audio_lang", track.name);
        console.log(`[AudioTrack] Native audioTrack enabled index ${track.nativeIndex} (${track.name})`);
      } else {
        setSelectedAudioTrackIndex(trackIndex);
        setSelectedAudioLang(track.name);
        localStorage.setItem("animestream_preferred_audio_lang", track.name);
        console.log(`[AudioTrack] Switched language stream selection to ${track.name}`);
      }
    } catch (err) {
      console.error("[AudioTrack] Audio track switch error:", err);
      setAudioErrorMessage("This audio track is unavailable.");
      setTimeout(() => setAudioErrorMessage(null), 3500);
    }

    setShowAudioMenu(false);
    setShowSettingsMenu(false);
  };

  // Auto hide controls timer
  const resetControlsTimeout = () => {
    setShowControls(true);
    if (controlsTimeoutRef.current) clearTimeout(controlsTimeoutRef.current);
    if (isPlaying) {
      controlsTimeoutRef.current = setTimeout(() => {
        setShowControls(false);
        setShowSpeedMenu(false);
        setShowSubMenu(false);
        setShowAudioMenu(false);
      }, 3500);
    }
  };

  useEffect(() => {
    resetControlsTimeout();
    return () => {
      if (controlsTimeoutRef.current) clearTimeout(controlsTimeoutRef.current);
    };
  }, [isPlaying]);

  const loadedSrcRef = useRef<string>("");

  // Load HLS or Cloudflare/Direct video stream
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    if (!videoSrc) {
      setIsLoading(false);
      setPlaybackError("No valid video stream URL found for this episode.");
      return;
    }

    // Prevent re-initializing the same source unnecessarily
    if (loadedSrcRef.current === videoSrc && video.src === videoSrc) {
      return;
    }

    setIsLoading(true);
    setPlaybackError(null);
    setIsPlaying(false);
    loadedSrcRef.current = videoSrc;

    const isM3U8 = videoSrc.endsWith(".m3u8") || videoSrc.includes("/hls/") || videoSrc.includes("manifest.m3u8") || videoSrc.includes("stream.mux.com");

    const onWaiting = () => {
      if (video.readyState < 3 && !video.paused) {
        setIsLoading(true);
      }
    };

    const onStalled = () => {
      if (video.readyState < 3 && !video.paused) {
        setIsLoading(true);
      }
    };

    const onPlaying = () => {
      setIsLoading(false);
      setPlaybackError(null);
      setIsPlaying(true);
      console.log(`[Player Load Success] Episode video stream active and playing from source: ${videoSrc}`);
    };

    const onPlay = () => {
      setIsPlaying(true);
    };

    const onPause = () => {
      setIsPlaying(false);
    };

    const onSeeking = () => {
      setIsLoading(true);
    };

    const onSeeked = () => {
      setIsLoading(false);
    };

    const onLoadedData = () => {
      setIsLoading(false);
    };

    const onCanPlay = () => {
      setIsLoading(false);
      if (pendingSeekPositionRef.current !== null) {
        const pos = pendingSeekPositionRef.current;
        pendingSeekPositionRef.current = null;
        try {
          video.currentTime = pos;
        } catch {}
        if (wasPlayingBeforeSwitchRef.current) {
          video.play().catch(err => console.log("Play rejected on track switch:", err));
        }
      }
      populateNativeAudioTracks();
    };

    const onCanPlayThrough = () => {
      setIsLoading(false);
    };

    const onLoadedMetadata = () => {
      setDuration(video.duration || 0);
      populateNativeAudioTracks();
      if (!isM3U8 && initialPosition > 0 && pendingSeekPositionRef.current === null) {
        try {
          video.currentTime = initialPosition;
        } catch {}
      }
    };

    const onProgress = () => {
      if (video.buffered.length > 0) {
        try {
          const bufEnd = video.buffered.end(video.buffered.length - 1);
          setBufferedTime(bufEnd);
        } catch {}
      }
    };

    const onVideoError = () => {
      setIsLoading(false);
      setIsPlaying(false);
      const mediaErr = video.error;
      let errText = "Failed to load video stream.";
      if (mediaErr) {
        switch (mediaErr.code) {
          case 1:
            errText = "Video loading was aborted by user or browser (Code 1: MEDIA_ERR_ABORTED).";
            break;
          case 2:
            errText = "Network error prevented video playback (Code 2: MEDIA_ERR_NETWORK). Please verify your internet connection.";
            break;
          case 3:
            errText = "Media decoding error - corrupted video stream (Code 3: MEDIA_ERR_DECODE).";
            break;
          case 4:
            errText = "Video format or codec not supported by browser (Code 4: MEDIA_ERR_SRC_NOT_SUPPORTED). Verify Cloudflare R2 video path.";
            break;
          default:
            errText = `Playback error (Code ${mediaErr.code}): ${mediaErr.message || "Unknown error"}`;
            break;
        }
      }
      setPlaybackError(errText);
    };

    video.addEventListener("waiting", onWaiting);
    video.addEventListener("stalled", onStalled);
    video.addEventListener("playing", onPlaying);
    video.addEventListener("play", onPlay);
    video.addEventListener("pause", onPause);
    video.addEventListener("seeking", onSeeking);
    video.addEventListener("seeked", onSeeked);
    video.addEventListener("loadeddata", onLoadedData);
    video.addEventListener("canplay", onCanPlay);
    video.addEventListener("canplaythrough", onCanPlayThrough);
    video.addEventListener("loadedmetadata", onLoadedMetadata);
    video.addEventListener("progress", onProgress);
    video.addEventListener("error", onVideoError);

    if ((video as any).audioTracks) {
      try {
        (video as any).audioTracks.addEventListener("addtrack", populateNativeAudioTracks);
        (video as any).audioTracks.addEventListener("change", populateNativeAudioTracks);
      } catch {}
    }

    let hls: any = null;

    const handleHlsFallback = (lastErrMessage?: string) => {
      setIsLoading(false);
      const nonHlsFallback = [
        activeLangData?.videoUrl,
        episode.videoUrl,
        resolvedSource.storageKey ? buildCloudflareVideoUrl(resolvedSource.storageKey) : ""
      ].find(url => url && !url.endsWith(".m3u8") && !url.includes("stream.mux.com"));

      if (nonHlsFallback && video) {
        video.src = nonHlsFallback;
        video.load();
      } else {
        setPlaybackError(
          lastErrMessage || "The video stream is currently unavailable. Please check your video source or retry."
        );
      }
    };

    const initPlayer = () => {
      if (isM3U8) {
        try {
          if (Hls.isSupported()) {
            hls = new Hls({
              maxBufferLength: 30,
              maxMaxBufferLength: 60,
              maxBufferSize: 60 * 1024 * 1024,
              maxBufferHole: 0.5,
              highBufferWatchdogPeriod: 2,
              enableWorker: true,
              lowLatencyMode: false,
              backBufferLength: 30,
              manifestLoadingTimeOut: 15000,
              manifestLoadingMaxRetry: 6,
              levelLoadingTimeOut: 15000,
              levelLoadingMaxRetry: 6,
              fragLoadingTimeOut: 20000,
              fragLoadingMaxRetry: 6,
              startLevel: -1,
              capLevelToPlayerSize: true,
            });

            hlsInstanceRef.current = hls;

            hls.loadSource(videoSrc);
            hls.attachMedia(video);

            hls.on(Hls.Events.MANIFEST_PARSED, () => {
              setIsLoading(false);
              setPlaybackError(null);
              if (pendingSeekPositionRef.current !== null && video) {
                const pos = pendingSeekPositionRef.current;
                pendingSeekPositionRef.current = null;
                video.currentTime = pos;
                if (wasPlayingBeforeSwitchRef.current) {
                  video.play().catch(() => {});
                }
              } else if (initialPosition > 0) {
                video.currentTime = initialPosition;
              }

              populateHlsAudioTracks(hls);

              if (hls && hls.levels && hls.levels.length > 0) {
                const quals = hls.levels.map((lvl: any, idx: number) => ({
                  levelIndex: idx,
                  height: lvl.height || 720,
                  label: `${lvl.height || 720}p`
                })).sort((a: any, b: any) => b.height - a.height);
                setAvailableQualities(quals);

                const pref = localStorage.getItem("animestream_preferred_quality") || "Auto";
                if (pref === "Auto") {
                  hls.currentLevel = -1;
                } else {
                  const targetH = parseInt(pref.replace("p", ""), 10);
                  const match = quals.find((q: any) => q.height === targetH);
                  if (match) hls.currentLevel = match.levelIndex;
                }
              }
            });

            hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, () => {
              populateHlsAudioTracks(hls);
            });

            hls.on(Hls.Events.AUDIO_TRACK_SWITCHED, (event: any, data: any) => {
              if (data && typeof data.id === "number" && hls.audioTracks[data.id]) {
                const tr = hls.audioTracks[data.id];
                const trackName = normalizeAudioLanguageName(tr.name || tr.lang, data.id);
                setSelectedAudioTrackIndex(data.id);
                setSelectedAudioLang(trackName);
              }
            });

            let networkRetryCount = 0;
            let mediaRetryCount = 0;

            hls.on(Hls.Events.ERROR, (event: any, data: any) => {
              const httpStatus = data.response?.code ? ` (HTTP ${data.response.code})` : "";
              const detailText = data.details || data.type || "Playback Error";

              if (data.fatal) {
                switch (data.type) {
                  case Hls.ErrorTypes.NETWORK_ERROR:
                    if (networkRetryCount < 3) {
                      networkRetryCount++;
                      hls?.startLoad();
                    } else {
                      hls?.destroy();
                      hls = null;
                      handleHlsFallback(`Network Error${httpStatus}: ${detailText}`);
                    }
                    break;
                  case Hls.ErrorTypes.MEDIA_ERROR:
                    if (mediaRetryCount < 3) {
                      mediaRetryCount++;
                      hls?.recoverMediaError();
                    } else {
                      hls?.destroy();
                      hls = null;
                      handleHlsFallback(`Media Error: ${detailText}`);
                    }
                    break;
                  default:
                    hls?.destroy();
                    hls = null;
                    handleHlsFallback(`Fatal Stream Error${httpStatus}: ${detailText}`);
                    break;
                }
              }
            });
          } else if (video.canPlayType("application/vnd.apple.mpegurl")) {
            video.src = videoSrc;
            video.load();
            if (initialPosition > 0) {
              video.currentTime = initialPosition;
            }
          } else {
            setPlaybackError("This browser does not support HLS (.m3u8) video playback.");
          }
        } catch (err: any) {
          handleHlsFallback(err?.message || "Failed to initialize HLS video stream.");
        }
      } else {
        // Direct MP4 or Cloudflare R2 video URL
        video.src = videoSrc;
        video.load();
      }
    };

    initPlayer();

    return () => {
      video.removeEventListener("waiting", onWaiting);
      video.removeEventListener("stalled", onStalled);
      video.removeEventListener("playing", onPlaying);
      video.removeEventListener("play", onPlay);
      video.removeEventListener("pause", onPause);
      video.removeEventListener("seeking", onSeeking);
      video.removeEventListener("seeked", onSeeked);
      video.removeEventListener("loadeddata", onLoadedData);
      video.removeEventListener("canplay", onCanPlay);
      video.removeEventListener("canplaythrough", onCanPlayThrough);
      video.removeEventListener("loadedmetadata", onLoadedMetadata);
      video.removeEventListener("progress", onProgress);
      video.removeEventListener("error", onVideoError);

      if ((video as any).audioTracks) {
        try {
          (video as any).audioTracks.removeEventListener("addtrack", populateNativeAudioTracks);
          (video as any).audioTracks.removeEventListener("change", populateNativeAudioTracks);
        } catch {}
      }

      if (hls) {
        hls.destroy();
        hlsInstanceRef.current = null;
      }
    };
  }, [videoSrc]);

  const subtitleCacheRef = useRef<Map<string, SubtitleCue[]>>(new Map());

  // Fetch real VTT/SRT subtitles with lazy-loading & background caching
  useEffect(() => {
    if (subLanguage === "off" || !activeSubtitlesUrl) {
      setSubtitleCues([]);
      return;
    }

    const cacheKey = `sub_${episode.id}_${activeSubtitlesUrl}`;

    // 1. Memory cache check
    if (subtitleCacheRef.current.has(cacheKey)) {
      setSubtitleCues(subtitleCacheRef.current.get(cacheKey)!);
      return;
    }

    // 2. LocalStorage cache check
    try {
      const stored = localStorage.getItem(cacheKey);
      if (stored) {
        const parsedStored: SubtitleCue[] = JSON.parse(stored);
        if (parsedStored && parsedStored.length > 0) {
          subtitleCacheRef.current.set(cacheKey, parsedStored);
          setSubtitleCues(parsedStored);
          return;
        }
      }
    } catch {}

    let isMounted = true;
    async function loadVttSubtitles() {
      try {
        let urlToFetch = activeSubtitlesUrl;
        if (urlToFetch.includes("assemblyai.com/v2/transcript/")) {
          const parts = urlToFetch.split("/");
          const id = parts[parts.length - 2] || parts[parts.length - 1];
          if (id && id !== "vtt") {
            urlToFetch = `/api/subtitles/assemblyai/${id}`;
          }
        }

        const res = await fetch(urlToFetch);
        if (res.ok) {
          let textData = "";
          const contentType = res.headers.get("content-type") || "";
          if (contentType.includes("json")) {
            const jsonData = await res.json();
            textData = jsonData.vttContent || jsonData.transcript || "";
          } else {
            textData = await res.text();
          }

          if (isMounted && textData) {
            const parsed = parseVttOrSrtCues(textData);
            if (parsed.length > 0) {
              subtitleCacheRef.current.set(cacheKey, parsed);
              try {
                localStorage.setItem(cacheKey, JSON.stringify(parsed));
              } catch {}
              setSubtitleCues(parsed);
            }
          }
        }
      } catch (err) {
        console.warn("Subtitle load notification:", err);
      }
    }

    loadVttSubtitles();
    return () => { isMounted = false; };
  }, [activeSubtitlesUrl, subLanguage, selectedAudioLang, episode.id]);

  // Determine current active subtitle line
  useEffect(() => {
    if (subLanguage === "off") {
      setRawSubtitleText("");
      return;
    }

    // 1. Check parsed VTT cues first for real timestamps
    if (subtitleCues.length > 0) {
      const activeCue = subtitleCues.find(c => currentTime >= c.start && currentTime <= c.end);
      if (activeCue) {
        setRawSubtitleText(activeCue.text);
        return;
      }
    }

    // 2. Sample cues fallback if VTT is not loaded yet
    if (currentTime > 2 && currentTime < 8) {
      setRawSubtitleText(`${episode.title || "Episode"} - Opening Scene`);
    } else if (currentTime > 25 && currentTime < 32) {
      setRawSubtitleText("\"No matter what obstacles stand in our way, we will never give up!\"");
    } else if (currentTime > 33 && currentTime < 40) {
      setRawSubtitleText("\"The destiny of our journey is drawing closer with every step.\"");
    } else if (currentTime > 45 && currentTime < 52) {
      setRawSubtitleText("\"The true strength awakens within those who protect their companions!\"");
    } else {
      setRawSubtitleText("");
    }
  }, [currentTime, subLanguage, subtitleCues, episode.title]);

  const activeDisplaySubtitle = translateTextToTargetLanguage(rawSubtitleText, subLanguage);

  const handleSelectQuality = (qualityLabel: string) => {
    setSelectedQuality(qualityLabel);
    localStorage.setItem("animestream_preferred_quality", qualityLabel);
    if (hlsInstanceRef.current) {
      if (qualityLabel === "Auto") {
        hlsInstanceRef.current.currentLevel = -1;
      } else {
        const targetH = parseInt(qualityLabel.replace("p", ""), 10);
        const match = availableQualities.find((q) => q.height === targetH);
        if (match) {
          hlsInstanceRef.current.currentLevel = match.levelIndex;
        } else {
          hlsInstanceRef.current.currentLevel = -1;
        }
      }
    }
    setShowQualityMenu(false);
  };

  const togglePlay = () => {
    if (isLocked) return;
    if (videoRef.current) {
      if (isPlaying) {
        videoRef.current.pause();
        setIsPlaying(false);
      } else {
        videoRef.current.play().then(() => {
          setIsPlaying(true);
        }).catch(err => console.log("Playback error:", err));
      }
    }
  };

  const skipTime = (amount: number) => {
    if (isLocked || !videoRef.current) return;
    videoRef.current.currentTime += amount;
  };

  const handleVolumeChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (isLocked) return;
    const vol = parseFloat(e.target.value);
    setVolume(vol);
    setIsMuted(vol === 0);
    if (videoRef.current) {
      videoRef.current.volume = vol;
    }
  };

  const toggleMute = () => {
    if (isLocked) return;
    const nextMuted = !isMuted;
    setIsMuted(nextMuted);
    if (videoRef.current) {
      videoRef.current.muted = nextMuted;
    }
  };

  const changeSpeed = (speed: number) => {
    if (isLocked) return;
    setPlaybackSpeed(speed);
    localStorage.setItem("animestream_preferred_speed", String(speed));
    if (videoRef.current) {
      videoRef.current.playbackRate = speed;
    }
    setShowSpeedMenu(false);
  };

  const handleSelectSubLanguage = (langCode: string) => {
    setSubLanguage(langCode);
    localStorage.setItem("animestream_preferred_sub_lang", langCode);
    setShowSubMenu(false);
  };

  const handleTimeUpdate = () => {
    if (videoRef.current) {
      const cur = videoRef.current.currentTime;
      setCurrentTime(cur);
      if (videoRef.current.buffered.length > 0) {
        const bufEnd = videoRef.current.buffered.end(videoRef.current.buffered.length - 1);
        setBufferedTime(bufEnd);
      }
      if (onProgressUpdate && duration > 0) {
        onProgressUpdate(cur, duration);
      }
    }
  };

  const touchStartRef = useRef<{ x: number; y: number; val: number; side: "left" | "right" } | null>(null);

  const handleContainerTouchStart = (e: React.TouchEvent<HTMLDivElement>) => {
    if (isLocked || e.touches.length !== 1) return;
    const touch = e.touches[0];
    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect) return;

    const xRel = (touch.clientX - rect.left) / rect.width;
    if (xRel < 0.35) {
      touchStartRef.current = { x: touch.clientX, y: touch.clientY, val: brightness, side: "left" };
    } else if (xRel > 0.65) {
      touchStartRef.current = { x: touch.clientX, y: touch.clientY, val: volume, side: "right" };
    }
  };

  const handleContainerTouchMove = (e: React.TouchEvent<HTMLDivElement>) => {
    if (!touchStartRef.current || e.touches.length !== 1) return;
    const touch = e.touches[0];
    const deltaY = touchStartRef.current.y - touch.clientY;

    if (Math.abs(deltaY) > 12) {
      if (touchStartRef.current.side === "left") {
        const nextB = Math.max(0.2, Math.min(1.5, touchStartRef.current.val + deltaY * 0.005));
        setBrightness(nextB);
        setGestureToast({ icon: "sun", label: `Brightness ${Math.round((nextB / 1.5) * 100)}%` });
      } else {
        const nextV = Math.max(0, Math.min(1, touchStartRef.current.val + deltaY * 0.005));
        setVolume(nextV);
        setIsMuted(nextV === 0);
        if (videoRef.current) videoRef.current.volume = nextV;
        setGestureToast({ icon: "vol", label: `Volume ${Math.round(nextV * 100)}%` });
      }
    }
  };

  const handleContainerTouchEnd = () => {
    touchStartRef.current = null;
    setTimeout(() => setGestureToast(null), 1200);
  };

  const handleDoubleTapClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (isLocked) return;
    resetControlsTimeout();

    const now = Date.now();
    const timeDiff = now - lastTapTimeRef.current;
    const rect = containerRef.current?.getBoundingClientRect();

    if (rect && timeDiff < 320) {
      const clickX = e.clientX - rect.left;
      const ratio = clickX / rect.width;

      if (ratio < 0.4) {
        skipTime(-10);
        setSeekRipple({ type: "left", amount: 10, id: now });
        setTimeout(() => setSeekRipple(null), 750);
      } else if (ratio > 0.6) {
        skipTime(10);
        setSeekRipple({ type: "right", amount: 10, id: now });
        setTimeout(() => setSeekRipple(null), 750);
      } else {
        togglePlay();
      }
    }
    lastTapTimeRef.current = now;
  };

  const handleLoadedMetadata = () => {
    if (videoRef.current) {
      setDuration(videoRef.current.duration);
      setIsLoading(false);
    }
  };

  const handleProgressScrub = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (isLocked || !videoRef.current) return;
    const val = parseFloat(e.target.value);
    videoRef.current.currentTime = val;
    setCurrentTime(val);
  };

  const toggleFullscreen = () => {
    if (isLocked) return;
    if (!containerRef.current) return;

    if (!document.fullscreenElement) {
      containerRef.current.requestFullscreen().then(() => {
        setIsFullscreen(true);
      }).catch(err => console.error(err));
    } else {
      document.exitFullscreen().then(() => {
        setIsFullscreen(false);
      });
    }
  };

  const togglePip = async () => {
    if (isLocked || !videoRef.current) return;
    try {
      if (document.pictureInPictureElement) {
        await document.exitPictureInPicture();
        setIsPip(false);
      } else {
        await videoRef.current.requestPictureInPicture();
        setIsPip(true);
      }
    } catch (err) {
      console.error("PIP Error:", err);
    }
  };

  const handleRetry = () => {
    setPlaybackError(null);
    setIsLoading(true);
    if (videoRef.current) {
      videoRef.current.load();
    }
  };

  const formatTime = (timeInSeconds: number) => {
    const mins = Math.floor(timeInSeconds / 60);
    const secs = Math.floor(timeInSeconds % 60);
    return `${mins}:${secs < 10 ? "0" : ""}${secs}`;
  };

  // Keyboard Shortcuts Support
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (document.activeElement?.tagName === "INPUT" || document.activeElement?.tagName === "TEXTAREA") {
        return;
      }
      switch (e.key.toLowerCase()) {
        case " ":
          e.preventDefault();
          togglePlay();
          break;
        case "f":
          e.preventDefault();
          toggleFullscreen();
          break;
        case "l":
          setIsLocked(prev => !prev);
          break;
        case "m":
          toggleMute();
          break;
        case "arrowright":
          skipTime(10);
          break;
        case "arrowleft":
          skipTime(-10);
          break;
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [isPlaying, isLocked, isMuted]);

  if (episode.iframeUrl) {
    return (
      <div className={`flex flex-col w-full ${isCinemaMode ? "max-w-none" : "max-w-7xl mx-auto"} mb-8`}>
        <div className="relative aspect-video w-full rounded-2xl bg-black border border-white/10 overflow-hidden shadow-2xl flex items-center justify-center">
          <iframe
            src={episode.iframeUrl || videoSrc}
            className="w-full h-full border-0 absolute inset-0"
            allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
            allowFullScreen
            referrerPolicy="no-referrer"
          />
        </div>
        <div className="flex flex-col gap-1 mt-4 px-1">
          <div className="flex items-center justify-between">
            <h1 className="font-display font-extrabold text-xl text-white">
              EP {episode.episodeNumber}: {episode.title}
            </h1>
            <span className="text-xs font-mono text-gray-400 bg-white/5 px-2.5 py-1 rounded-lg border border-white/5">
              {episode.duration}
            </span>
          </div>
          <p className="text-sm text-gray-400 mt-2 leading-relaxed">
            {episode.description}
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className={`flex flex-col w-full ${isCinemaMode ? "max-w-none" : "max-w-7xl mx-auto"} mb-8 transition-all duration-300`}>
      {/* Main Spacious Player Frame */}
      <div
        ref={containerRef}
        onMouseMove={resetControlsTimeout}
        onClick={handleDoubleTapClick}
        onTouchStart={handleContainerTouchStart}
        onTouchMove={handleContainerTouchMove}
        onTouchEnd={handleContainerTouchEnd}
        className="relative aspect-video w-full rounded-2xl bg-black border border-white/10 overflow-hidden shadow-2xl group flex items-center justify-center select-none"
      >
        {/* Top Overlay Bar for Mini Mode */}
        {isMini && (
          <div className="absolute top-0 inset-x-0 bg-gradient-to-b from-black/85 via-black/40 to-transparent p-2.5 flex items-center justify-between z-30 opacity-90 group-hover:opacity-100 transition-opacity">
            <div className="flex items-center gap-1.5 px-2 py-0.5 rounded-md bg-black/60 border border-white/10 text-[11px] font-bold text-white truncate max-w-[65%]">
              <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse shrink-0" />
              <span className="truncate">EP {episode.episodeNumber}: {episode.title}</span>
            </div>
            <div className="flex items-center gap-1">
              {onExpand && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    onExpand();
                  }}
                  className="p-1.5 rounded-lg bg-black/70 border border-white/15 text-gray-200 hover:text-amber-400 hover:bg-white/20 transition-all cursor-pointer shadow"
                  title="Expand to Full Player"
                >
                  <Maximize2 className="w-3.5 h-3.5" />
                </button>
              )}
              {onClose && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    onClose();
                  }}
                  className="p-1.5 rounded-lg bg-black/70 border border-white/15 text-gray-400 hover:text-red-400 hover:bg-white/20 transition-all cursor-pointer shadow"
                  title="Close Mini Player"
                >
                  <X className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
          </div>
        )}

        {/* Top Overlay Bar for Main Player Mode */}
        {!isMini && (
          <div
            className={`absolute top-0 inset-x-0 bg-gradient-to-b from-black/85 via-black/40 to-transparent p-3 sm:p-4 flex items-center justify-between z-30 transition-all duration-300 ${
              showControls || !isPlaying ? "opacity-100" : "opacity-0 pointer-events-none"
            }`}
          >
            {/* Top Left: Back Button, Anime Title, Episode Number */}
            <div className="flex items-center gap-2.5 sm:gap-3 min-w-0 pr-2">
              {onBack && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    onBack();
                  }}
                  className="p-1.5 sm:p-2 rounded-xl bg-black/50 hover:bg-white/20 text-white border border-white/10 transition-all cursor-pointer shadow shrink-0"
                  title="Go Back"
                >
                  <ArrowLeft className="w-4 h-4 sm:w-5 sm:h-5" />
                </button>
              )}
              <div className="flex flex-col min-w-0">
                <span className="text-[10px] sm:text-xs font-bold text-amber-400 font-mono tracking-wider uppercase truncate">
                  {animeTitle || episode.animeTitle || "Anime Stream"}
                </span>
                <h2 className="text-xs sm:text-sm md:text-base font-extrabold text-white truncate drop-shadow">
                  EP {episode.episodeNumber}: {episode.title}
                </h2>
              </div>
            </div>

            {/* Top Right: Season & Episode Badge + Lock Touch, PiP, Fullscreen */}
            <div className="flex items-center gap-1.5 sm:gap-2 shrink-0">
              <span className="px-2.5 py-1 rounded-lg bg-amber-500/20 text-amber-400 font-extrabold text-xs font-mono border border-amber-500/30 shadow-sm">
                S{episode.seasonNumber || 1} • EP{episode.episodeNumber}
              </span>

              <button
                onClick={(e) => {
                  e.stopPropagation();
                  setIsLocked(true);
                  setShowControls(false);
                }}
                className="p-1.5 sm:p-2 rounded-xl bg-black/50 hover:bg-white/20 text-gray-300 hover:text-amber-400 border border-white/10 transition-all cursor-pointer shadow"
                title="Lock Player Controls"
              >
                <Lock className="w-4 h-4 sm:w-4.5 sm:h-4.5" />
              </button>

              <button
                onClick={(e) => {
                  e.stopPropagation();
                  togglePip();
                }}
                className="p-1.5 sm:p-2 rounded-xl bg-black/50 hover:bg-white/20 text-gray-300 hover:text-amber-400 border border-white/10 transition-all cursor-pointer shadow hidden sm:block"
                title="Picture-in-Picture"
              >
                <Monitor className="w-4 h-4 sm:w-4.5 sm:h-4.5" />
              </button>

              <button
                onClick={(e) => {
                  e.stopPropagation();
                  toggleFullscreen();
                }}
                className="p-1.5 sm:p-2 rounded-xl bg-black/50 hover:bg-white/20 text-gray-300 hover:text-amber-400 border border-white/10 transition-all cursor-pointer shadow"
                title="Toggle Fullscreen"
              >
                {isFullscreen ? <Minimize className="w-4 h-4 sm:w-4.5 sm:h-4.5" /> : <Maximize className="w-4 h-4 sm:w-4.5 sm:h-4.5" />}
              </button>
            </div>
          </div>
        )}

        {/* Lock Overlay when isLocked === true */}
        {isLocked && (
          <div
            className="absolute top-4 left-4 z-50 animate-in fade-in cursor-pointer"
            onClick={(e) => e.stopPropagation()}
          >
            <button
              onClick={() => {
                setIsLocked(false);
                setShowControls(true);
              }}
              className="flex items-center gap-2 px-3.5 py-2 rounded-xl bg-amber-500 text-black font-extrabold text-xs uppercase tracking-wider shadow-2xl border border-amber-400/40 hover:scale-105 active:scale-95 transition-all cursor-pointer"
            >
              <Unlock className="w-4 h-4 fill-current" />
              <span>Controls Locked • Tap to Unlock</span>
            </button>
          </div>
        )}

        {/* Double-Tap Seek Ripple Overlay */}
        {seekRipple && (
          <div
            className={`absolute inset-y-0 w-1/3 flex items-center justify-center z-35 pointer-events-none transition-all ${
              seekRipple.type === "left"
                ? "left-0 bg-gradient-to-r from-amber-500/20 to-transparent rounded-r-full"
                : "right-0 bg-gradient-to-l from-amber-500/20 to-transparent rounded-l-full"
            }`}
          >
            <div className="flex flex-col items-center gap-1 text-amber-400 font-extrabold animate-in zoom-in-75 duration-200">
              {seekRipple.type === "left" ? (
                <>
                  <RotateCcw className="w-10 h-10 animate-spin" style={{ animationDuration: "0.5s", animationDirection: "reverse" }} />
                  <span className="text-sm font-mono tracking-widest">-10s</span>
                </>
              ) : (
                <>
                  <RotateCw className="w-10 h-10 animate-spin" style={{ animationDuration: "0.5s" }} />
                  <span className="text-sm font-mono tracking-widest">+10s</span>
                </>
              )}
            </div>
          </div>
        )}

        {/* Gesture Indicator Toast (Brightness/Volume) */}
        {gestureToast && (
          <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 bg-black/80 border border-white/20 text-white text-xs sm:text-sm font-bold font-mono px-4 py-2.5 rounded-2xl backdrop-blur-md shadow-2xl z-40 flex items-center gap-2.5 animate-in fade-in zoom-in-95 pointer-events-none">
            {gestureToast.icon === "sun" ? <Sun className="w-5 h-5 text-amber-400" /> : <Volume2 className="w-5 h-5 text-amber-400" />}
            <span>{gestureToast.label}</span>
          </div>
        )}

        <video
          ref={videoRef}
          onTimeUpdate={handleTimeUpdate}
          onLoadedMetadata={handleLoadedMetadata}
          onEnded={() => {
            setIsPlaying(false);
            if (onEpisodeComplete) onEpisodeComplete();
            if (autoPlayEnabled && nextEpisode && onNextEpisode) {
              startAutoPlayCountdown();
            }
          }}
          onPlay={() => setIsPlaying(true)}
          onPause={() => setIsPlaying(false)}
          onClick={togglePlay}
          className="w-full h-full object-contain cursor-pointer"
          style={{ filter: brightness !== 1 ? `brightness(${brightness})` : undefined }}
          playsInline
        />

        {/* 5-Second Auto-Play Countdown Overlay */}
        {countdownSeconds !== null && nextEpisode && (
          <div className="absolute inset-0 bg-black/92 backdrop-blur-xl z-40 flex flex-col items-center justify-center p-6 text-center animate-in fade-in duration-300 select-none">
            <div className="flex flex-col items-center max-w-sm w-full gap-4">
              
              {/* Circular Gauge Countdown */}
              <div className="relative w-20 h-20 flex items-center justify-center">
                <svg className="w-full h-full transform -rotate-90">
                  <circle
                    cx="40"
                    cy="40"
                    r="34"
                    className="stroke-white/10"
                    strokeWidth="6"
                    fill="transparent"
                  />
                  <circle
                    cx="40"
                    cy="40"
                    r="34"
                    className="stroke-amber-400 transition-all duration-1000 ease-linear"
                    strokeWidth="6"
                    strokeDasharray={213}
                    strokeDashoffset={213 - (213 * (5 - countdownSeconds)) / 5}
                    strokeLinecap="round"
                    fill="transparent"
                  />
                </svg>
                <div className="absolute inset-0 flex items-center justify-center">
                  <span className="font-display font-black text-2xl text-white">
                    {countdownSeconds}
                  </span>
                </div>
              </div>

              <div>
                <div className="flex items-center justify-center gap-1.5 text-[11px] font-mono font-bold text-amber-400 uppercase tracking-widest mb-1">
                  <Sparkles className="w-3.5 h-3.5" /> Next Episode Starting
                </div>
                <h3 className="font-extrabold text-white text-base sm:text-lg line-clamp-1">
                  EP {nextEpisode.episodeNumber}: {nextEpisode.title}
                </h3>
                {nextEpisode.description && (
                  <p className="text-xs text-gray-400 line-clamp-2 mt-1 px-2">
                    {nextEpisode.description}
                  </p>
                )}
              </div>

              {/* Countdown Controls */}
              <div className="flex items-center gap-3 w-full mt-1">
                <button
                  onClick={cancelAutoPlayCountdown}
                  className="flex-1 py-2.5 px-4 rounded-xl bg-white/10 hover:bg-white/20 text-white font-bold text-xs uppercase tracking-wider border border-white/10 transition-all cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  onClick={playNextNow}
                  className="flex-1 py-2.5 px-4 rounded-xl bg-amber-500 hover:bg-amber-400 text-black font-extrabold text-xs uppercase tracking-wider flex items-center justify-center gap-1.5 shadow-lg shadow-amber-500/20 hover:scale-105 active:scale-95 transition-all cursor-pointer"
                >
                  <SkipForward className="w-4 h-4 fill-current" />
                  Play Now
                </button>
              </div>

              {/* Auto-Play status footer */}
              <div className="flex items-center gap-2 pt-1 border-t border-white/10 w-full justify-center">
                <span className="text-[11px] text-gray-400">Auto-play is ON</span>
                <button
                  onClick={toggleAutoPlay}
                  className="text-[11px] font-bold text-amber-400 hover:underline cursor-pointer"
                >
                  Turn Off
                </button>
              </div>

            </div>
          </div>
        )}

        {/* Preparing Video Overlay (When Playback ID is missing or pending) */}
        {!hasValidVideoSource && (
          <div className="absolute inset-0 flex flex-col items-center justify-center bg-black/95 backdrop-blur-md z-35 px-4 text-center select-none">
            <div className="flex flex-col items-center gap-4 max-w-md">
              <div className="relative flex items-center justify-center w-16 h-16 rounded-full bg-amber-500/10 border border-amber-500/30">
                <Loader className="w-8 h-8 text-amber-400 animate-spin" />
              </div>
              <h4 className="font-extrabold text-white text-lg font-display">Preparing video stream...</h4>
              <p className="text-xs text-gray-400 leading-relaxed max-w-sm">
                Your video stream is being initialized. The player will start automatically as soon as the stream source is ready.
              </p>
              <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-amber-500/10 border border-amber-500/20 text-amber-400 text-[11px] font-mono font-bold">
                <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                <span>Connecting to stream source...</span>
              </div>
            </div>
          </div>
        )}

        {/* Playback Error Banner */}
        {playbackError && (
          <div className="absolute inset-0 flex flex-col items-center justify-center bg-black/90 backdrop-blur-md z-35 px-4 text-center">
            <div className="flex flex-col items-center gap-4 max-w-md">
              <div className="w-12 h-12 rounded-full bg-red-500/10 flex items-center justify-center text-red-500">
                <Settings className="w-6 h-6 animate-spin" style={{ animationDuration: "4s" }} />
              </div>
              <h4 className="font-bold text-white text-base">Playback Status</h4>
              <p className="text-xs text-gray-400 leading-relaxed">
                {playbackError}
              </p>
              <button
                onClick={handleRetry}
                className="mt-2 px-5 py-2.5 bg-amber-500 text-black font-extrabold text-xs uppercase tracking-widest rounded-xl hover:opacity-95 transform active:scale-95 transition-all shadow-md cursor-pointer"
              >
                Retry Stream
              </button>
            </div>
          </div>
        )}

        {/* Loading Indicator */}
        {isLoading && (
          <div className="absolute inset-0 flex items-center justify-center bg-black/60 backdrop-blur-sm z-30 pointer-events-none">
            <div className="flex flex-col items-center gap-4">
              <Loader className="w-12 h-12 text-amber-400 animate-spin" />
              <span className="text-xs font-bold tracking-widest text-gray-200 uppercase font-mono">Loading Stream...</span>
            </div>
          </div>
        )}

        {/* Subtitle Overlay Display - Plain white text, no background box */}
        {subLanguage !== "off" && activeDisplaySubtitle && (
          <div
            className={`absolute left-1/2 -translate-x-1/2 px-4 py-1 text-center max-w-[92%] sm:max-w-[85%] md:max-w-[75%] z-25 pointer-events-none transition-all duration-200 ease-in-out ${
              showControls ? "bottom-20 sm:bottom-24 md:bottom-28" : "bottom-6 sm:bottom-10 md:bottom-12"
            }`}
          >
            <p
              className="text-white font-bold text-sm sm:text-base md:text-lg lg:text-xl leading-snug tracking-wide font-sans max-w-full break-words line-clamp-2 select-none"
              style={{
                WebkitTextStroke: "1px #000000",
                textShadow: "-1.5px -1.5px 0 #000, 1.5px -1.5px 0 #000, -1.5px 1.5px 0 #000, 1.5px 1.5px 0 #000, 0 2px 4px rgba(0,0,0,0.9)"
              }}
            >
              {activeDisplaySubtitle}
            </p>
          </div>
        )}

        {/* Audio Track Error Toast */}
        {audioErrorMessage && (
          <div className="absolute top-16 left-1/2 -translate-x-1/2 bg-red-950/90 border border-red-500/40 text-red-200 text-xs px-4 py-2 rounded-xl backdrop-blur-md shadow-2xl z-50 animate-in fade-in">
            {audioErrorMessage}
          </div>
        )}

        {/* Big Center Play Icon Feedback */}
        {!isPlaying && !isLoading && !playbackError && (
          <div className="absolute inset-0 flex items-center justify-center pointer-events-none z-15">
            <div className="p-5 rounded-full bg-black/50 backdrop-blur-md border border-white/15 scale-100 group-hover:scale-110 transition-all">
              <Play className="w-10 h-10 fill-current text-amber-400 pl-1" />
            </div>
          </div>
        )}

        {/* Controls Overlay Bar */}
        <div
          className={`absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/95 via-black/60 to-transparent p-4 flex flex-col gap-3 transition-all duration-300 z-20 ${
            showControls || !isPlaying ? "opacity-100" : "opacity-0 pointer-events-none"
          }`}
        >
          {/* Progress Track with Buffer Bar */}
          <div className="flex items-center gap-2.5 sm:gap-3">
            <span className="text-[11px] font-mono text-gray-300 font-bold shrink-0">{formatTime(currentTime)}</span>
            <div className="relative flex-1 flex items-center group/scrubber h-3 cursor-pointer">
              {/* Buffer Bar Background */}
              <div className="absolute inset-x-0 h-1 sm:h-1.5 bg-white/15 rounded-lg overflow-hidden pointer-events-none">
                <div
                  className="h-full bg-white/30 transition-all duration-300"
                  style={{ width: `${duration > 0 ? (bufferedTime / duration) * 100 : 0}%` }}
                />
              </div>
              <input
                type="range"
                min={0}
                max={duration || 100}
                value={currentTime}
                onChange={handleProgressScrub}
                className="w-full h-1 sm:h-1.5 rounded-lg bg-transparent appearance-none cursor-pointer accent-amber-400 hover:h-2 sm:hover:h-2.5 transition-all z-10"
              />
            </div>
            <span className="text-[11px] font-mono text-gray-300 font-bold shrink-0">
              -{formatTime(Math.max(0, duration - currentTime))}
            </span>
          </div>

          {/* Control Buttons Bar */}
          <div className="flex items-center justify-between">
            {/* Left Controls */}
            <div className="flex items-center gap-2 sm:gap-3 md:gap-4">
              <button
                onClick={togglePlay}
                className="text-white hover:text-amber-400 transition-colors p-1 cursor-pointer"
                title={isPlaying ? "Pause (Space)" : "Play (Space)"}
              >
                {isPlaying ? <Pause className="fill-current w-5 h-5 sm:w-6 sm:h-6" /> : <Play className="fill-current w-5 h-5 sm:w-6 sm:h-6" />}
              </button>

              {/* Prev Episode */}
              {onPreviousEpisode && (
                <button
                  onClick={onPreviousEpisode}
                  disabled={!hasPreviousEpisode}
                  className="text-gray-300 hover:text-amber-400 disabled:opacity-30 disabled:pointer-events-none transition-colors p-1 cursor-pointer"
                  title="Previous Episode"
                >
                  <SkipBack className="w-4 h-4 sm:w-5 sm:h-5 fill-current" />
                </button>
              )}

              {/* Next Episode */}
              {onNextEpisode && (
                <button
                  onClick={onNextEpisode}
                  disabled={!nextEpisode}
                  className="text-gray-300 hover:text-amber-400 disabled:opacity-30 disabled:pointer-events-none transition-colors p-1 cursor-pointer"
                  title="Next Episode"
                >
                  <SkipForward className="w-4 h-4 sm:w-5 sm:h-5 fill-current" />
                </button>
              )}

              <button
                onClick={() => skipTime(-10)}
                className="text-gray-300 hover:text-amber-400 transition-colors p-1 cursor-pointer hidden xs:block"
                title="Rewind 10s"
              >
                <RotateCcw className="w-4 h-4 sm:w-5 sm:h-5" />
              </button>

              <button
                onClick={() => skipTime(10)}
                className="text-gray-300 hover:text-amber-400 transition-colors p-1 cursor-pointer hidden xs:block"
                title="Forward 10s"
              >
                <RotateCw className="w-4 h-4 sm:w-5 sm:h-5" />
              </button>

              {/* Volume Slider */}
              <div className="flex items-center gap-2 group/vol">
                <button
                  onClick={toggleMute}
                  className="text-gray-300 hover:text-amber-400 transition-colors p-1 cursor-pointer"
                  title="Mute (M)"
                >
                  {isMuted ? <VolumeX className="w-4 h-4 sm:w-5 sm:h-5 text-red-400" /> : <Volume2 className="w-4 h-4 sm:w-5 sm:h-5" />}
                </button>
                <input
                  type="range"
                  min={0}
                  max={1}
                  step={0.05}
                  value={isMuted ? 0 : volume}
                  onChange={handleVolumeChange}
                  className="w-14 sm:w-20 h-1 rounded bg-white/20 appearance-none cursor-pointer accent-amber-400 hidden sm:block"
                />
              </div>
            </div>

            {/* Right Controls */}
            <div className="flex items-center gap-1.5 sm:gap-2 md:gap-3">

              {/* Dedicated Audio Track Selector Button */}
              {detectedAudioTracks.length > 1 && (
                <div className="relative">
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setShowAudioMenu(!showAudioMenu);
                      setShowSettingsMenu(false);
                    }}
                    className="flex items-center gap-1.5 text-xs font-mono font-bold px-2.5 py-1.5 bg-amber-500/10 hover:bg-amber-500/20 text-amber-300 rounded-lg border border-amber-500/30 transition-all cursor-pointer shadow"
                    title="Switch Audio Track"
                  >
                    <Languages className="w-3.5 h-3.5 text-amber-400" />
                    <span className="hidden xs:inline">{getAudioTrackDisplayInfo(selectedAudioLang).name}</span>
                  </button>

                  {showAudioMenu && (
                    <div className="absolute bottom-11 right-0 bg-[#0d0e12]/95 border border-white/15 rounded-2xl p-2.5 flex flex-col gap-1 min-w-[200px] z-50 shadow-2xl backdrop-blur-xl animate-in fade-in zoom-in-95">
                      <div className="text-[10px] font-bold uppercase tracking-wider text-gray-400 px-2 py-1 border-b border-white/10 flex items-center justify-between">
                        <span>Audio Tracks</span>
                        <span className="text-amber-400 font-mono">({detectedAudioTracks.length} Available)</span>
                      </div>
                      {detectedAudioTracks.map((tr, idx) => {
                        const displayInfo = getAudioTrackDisplayInfo(tr.name, tr.lang);
                        return (
                          <button
                            key={tr.id || idx}
                            onClick={(e) => {
                              e.stopPropagation();
                              handleSelectAudioTrack(idx);
                            }}
                            className={`flex items-center justify-between px-2.5 py-2 rounded-xl text-xs font-semibold transition-all cursor-pointer ${
                              selectedAudioTrackIndex === idx || selectedAudioLang === tr.name
                                ? "bg-amber-500/20 text-amber-400 font-bold border border-amber-500/30"
                                : "text-gray-300 hover:bg-white/10 hover:text-white"
                            }`}
                          >
                            <span className="flex items-center gap-2">
                              <span className="text-sm">{displayInfo.flag}</span>
                              <span>{displayInfo.name}</span>
                            </span>
                            {(selectedAudioTrackIndex === idx || selectedAudioLang === tr.name) && (
                              <Check className="w-4 h-4 text-amber-400" />
                            )}
                          </button>
                        );
                      })}
                    </div>
                  )}
                </div>
              )}

              {/* Unified Player Settings Menu Button */}
              <div className="relative">
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    setShowSettingsMenu(!showSettingsMenu);
                    setSettingsTab("main");
                  }}
                  className="flex items-center gap-1 text-xs font-mono font-bold px-2.5 py-1.5 bg-white/10 hover:bg-white/20 text-amber-400 rounded-lg border border-amber-500/20 transition-all cursor-pointer shadow"
                  title="Player Settings"
                >
                  <Settings className="w-3.5 h-3.5" />
                  <span>Settings</span>
                </button>

                {showSettingsMenu && (
                  <div className="absolute bottom-11 right-0 bg-[#0d0e12]/95 border border-white/15 rounded-2xl p-2 flex flex-col gap-1 min-w-[210px] z-50 shadow-2xl backdrop-blur-xl animate-in fade-in zoom-in-95">
                    {settingsTab === "main" && (
                      <>
                        <div className="text-[10px] font-bold uppercase tracking-wider text-gray-400 px-2.5 py-1 border-b border-white/10">
                          Player Settings
                        </div>

                        {/* Audio option if multiple tracks exist */}
                        {detectedAudioTracks.length > 1 && (
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              setSettingsTab("audio");
                            }}
                            className="flex items-center justify-between px-2.5 py-2 rounded-xl text-xs font-medium text-gray-200 hover:bg-white/10 transition-all cursor-pointer"
                          >
                            <div className="flex items-center gap-2">
                              <Globe className="w-4 h-4 text-amber-400" />
                              <span>Audio Track</span>
                            </div>
                            <span className="text-[11px] font-bold text-amber-400 font-mono">
                              {getAudioTrackDisplayInfo(selectedAudioLang).fullLabel} ›
                            </span>
                          </button>
                        )}

                        {/* Quality option */}
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            setSettingsTab("quality");
                          }}
                          className="flex items-center justify-between px-2.5 py-2 rounded-xl text-xs font-medium text-gray-200 hover:bg-white/10 transition-all cursor-pointer"
                        >
                          <div className="flex items-center gap-2">
                            <Settings className="w-4 h-4 text-amber-400" />
                            <span>Quality</span>
                          </div>
                          <span className="text-[11px] font-bold text-amber-400 font-mono">{selectedQuality} ›</span>
                        </button>

                        {/* Speed option */}
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            setSettingsTab("speed");
                          }}
                          className="flex items-center justify-between px-2.5 py-2 rounded-xl text-xs font-medium text-gray-200 hover:bg-white/10 transition-all cursor-pointer"
                        >
                          <div className="flex items-center gap-2">
                            <RotateCw className="w-4 h-4 text-amber-400" />
                            <span>Speed</span>
                          </div>
                          <span className="text-[11px] font-bold text-amber-400 font-mono">{playbackSpeed}x ›</span>
                        </button>

                        {/* Subtitles option */}
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            setSettingsTab("subtitles");
                          }}
                          className="flex items-center justify-between px-2.5 py-2 rounded-xl text-xs font-medium text-gray-200 hover:bg-white/10 transition-all cursor-pointer"
                        >
                          <div className="flex items-center gap-2">
                            <Subtitles className="w-4 h-4 text-amber-400" />
                            <span>Subtitles</span>
                          </div>
                          <span className="text-[11px] font-bold text-amber-400 font-mono">
                            {subLanguage !== "off" ? SUBTITLE_LANGUAGES.find(s => s.code === subLanguage)?.label.split(" ")[0] || "ON" : "OFF"} ›
                          </span>
                        </button>
                      </>
                    )}

                    {/* Submenu: Audio */}
                    {settingsTab === "audio" && (
                      <>
                        <div className="flex items-center justify-between px-2 py-1 border-b border-white/10">
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              setSettingsTab("main");
                            }}
                            className="text-xs text-amber-400 hover:underline font-bold cursor-pointer"
                          >
                            ‹ Back
                          </button>
                          <span className="text-[10px] font-bold uppercase tracking-wider text-gray-400">Audio Track</span>
                        </div>
                        {detectedAudioTracks.map((tr, idx) => {
                          const displayInfo = getAudioTrackDisplayInfo(tr.name, tr.lang);
                          return (
                            <button
                              key={tr.id}
                              onClick={(e) => {
                                e.stopPropagation();
                                handleSelectAudioTrack(idx);
                              }}
                              className={`flex items-center justify-between px-2.5 py-1.5 rounded-lg text-xs font-medium transition-all cursor-pointer ${
                                selectedAudioTrackIndex === idx || selectedAudioLang === tr.name
                                  ? "bg-amber-500/20 text-amber-400 font-bold border border-amber-500/30"
                                  : "text-gray-300 hover:bg-white/10 hover:text-white"
                              }`}
                            >
                              <span className="flex items-center gap-1.5">
                                <span>{displayInfo.flag}</span>
                                <span>{displayInfo.name}</span>
                              </span>
                              {(selectedAudioTrackIndex === idx || selectedAudioLang === tr.name) && (
                                <Check className="w-3.5 h-3.5 text-amber-400" />
                              )}
                            </button>
                          );
                        })}
                      </>
                    )}

                    {/* Submenu: Quality */}
                    {settingsTab === "quality" && (
                      <>
                        <div className="flex items-center justify-between px-2 py-1 border-b border-white/10">
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              setSettingsTab("main");
                            }}
                            className="text-xs text-amber-400 hover:underline font-bold cursor-pointer"
                          >
                            ‹ Back
                          </button>
                          <span className="text-[10px] font-bold uppercase tracking-wider text-gray-400">Quality</span>
                        </div>
                        {["Auto", "1080p", "720p", "480p", "360p", "240p"].map((qLabel) => {
                          const isAvailable = qLabel === "Auto" || availableQualities.some(q => q.label === qLabel) || availableQualities.length === 0;
                          return (
                            <button
                              key={qLabel}
                              onClick={(e) => {
                                e.stopPropagation();
                                handleSelectQuality(qLabel);
                                setShowSettingsMenu(false);
                              }}
                              className={`flex items-center justify-between px-2.5 py-1.5 rounded-lg text-xs font-mono transition-all cursor-pointer ${
                                selectedQuality === qLabel
                                  ? "bg-amber-500/20 text-amber-400 font-bold border border-amber-500/30"
                                  : isAvailable
                                  ? "text-gray-300 hover:bg-white/10 hover:text-white"
                                  : "text-gray-500 opacity-60"
                              }`}
                            >
                              <span>{qLabel}</span>
                              {selectedQuality === qLabel && <Check className="w-3.5 h-3.5 text-amber-400" />}
                            </button>
                          );
                        })}
                      </>
                    )}

                    {/* Submenu: Speed */}
                    {settingsTab === "speed" && (
                      <>
                        <div className="flex items-center justify-between px-2 py-1 border-b border-white/10">
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              setSettingsTab("main");
                            }}
                            className="text-xs text-amber-400 hover:underline font-bold cursor-pointer"
                          >
                            ‹ Back
                          </button>
                          <span className="text-[10px] font-bold uppercase tracking-wider text-gray-400">Speed</span>
                        </div>
                        {[0.5, 0.75, 1, 1.25, 1.5, 1.75, 2].map((sp) => (
                          <button
                            key={sp}
                            onClick={(e) => {
                              e.stopPropagation();
                              changeSpeed(sp);
                              setShowSettingsMenu(false);
                            }}
                            className={`flex items-center justify-between px-2.5 py-1.5 rounded-lg text-xs font-mono transition-all cursor-pointer ${
                              playbackSpeed === sp
                                ? "bg-amber-500/20 text-amber-400 font-bold"
                                : "text-gray-300 hover:bg-white/10 hover:text-white"
                            }`}
                          >
                            <span>{sp}x</span>
                            {playbackSpeed === sp && <Check className="w-3.5 h-3.5 text-amber-400" />}
                          </button>
                        ))}
                      </>
                    )}

                    {/* Submenu: Subtitles */}
                    {settingsTab === "subtitles" && (
                      <>
                        <div className="flex items-center justify-between px-2 py-1 border-b border-white/10">
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              setSettingsTab("main");
                            }}
                            className="text-xs text-amber-400 hover:underline font-bold cursor-pointer"
                          >
                            ‹ Back
                          </button>
                          <span className="text-[10px] font-bold uppercase tracking-wider text-gray-400">Subtitles</span>
                        </div>
                        {SUBTITLE_LANGUAGES.map((item) => (
                          <button
                            key={item.code}
                            onClick={(e) => {
                              e.stopPropagation();
                              handleSelectSubLanguage(item.code);
                              setShowSettingsMenu(false);
                            }}
                            className={`flex items-center justify-between px-2.5 py-1.5 rounded-lg text-xs font-medium transition-all cursor-pointer ${
                              subLanguage === item.code
                                ? "bg-amber-500/20 text-amber-400 font-bold border border-amber-500/30"
                                : "text-gray-300 hover:bg-white/10 hover:text-white"
                            }`}
                          >
                            <div className="flex items-center gap-2">
                              <span>{item.flag}</span>
                              <span>{item.label}</span>
                            </div>
                            {subLanguage === item.code && <Check className="w-3.5 h-3.5 text-amber-400" />}
                          </button>
                        ))}
                      </>
                    )}
                  </div>
                )}
              </div>

              {/* Auto-Play Toggle button */}
              <button
                onClick={toggleAutoPlay}
                className={`px-2 py-1 rounded-lg border text-xs font-mono font-bold flex items-center gap-1 transition-all cursor-pointer ${
                  autoPlayEnabled
                    ? "bg-amber-500/20 text-amber-400 border-amber-500/30"
                    : "bg-white/5 text-gray-400 border-white/5 hover:text-white"
                }`}
                title={autoPlayEnabled ? "Auto-Play ON (Starts next episode in 5s)" : "Auto-Play OFF"}
              >
                <Sparkles className={`w-3.5 h-3.5 ${autoPlayEnabled ? "text-amber-400" : "text-gray-400"}`} />
                <span className="hidden sm:inline">Auto</span>
              </button>

              {/* Cinema Mode Toggle */}
              {!isMini && (
                <button
                  onClick={() => setIsCinemaMode(!isCinemaMode)}
                  className={`p-1.5 rounded-lg transition-colors hidden md:block cursor-pointer ${
                    isCinemaMode ? "bg-amber-500/20 text-amber-400" : "text-gray-300 hover:text-amber-400"
                  }`}
                  title="Cinema Mode (Expand Player)"
                >
                  <Tv className="w-4.5 h-4.5" />
                </button>
              )}

              {/* Minimize to Mini-Player */}
              {onMinimize && !isMini && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    onMinimize();
                  }}
                  className="p-1.5 rounded-lg text-gray-300 hover:text-amber-400 hover:bg-white/10 transition-all cursor-pointer"
                  title="Minimize to Mini-Player"
                >
                  <Minimize2 className="w-4.5 h-4.5 text-amber-400" />
                </button>
              )}

              {/* Picture in Picture */}
              <button
                onClick={togglePip}
                className="text-gray-300 hover:text-amber-400 transition-colors p-1 cursor-pointer hidden sm:block"
                title="Picture-in-Picture"
              >
                <Monitor className="w-4.5 h-4.5" />
              </button>

              {/* Fullscreen Toggle */}
              <button
                onClick={toggleFullscreen}
                className="text-gray-300 hover:text-amber-400 transition-colors p-1 cursor-pointer"
                title="Fullscreen (F)"
              >
                {isFullscreen ? <Minimize className="w-5 h-5" /> : <Maximize className="w-5 h-5" />}
              </button>

            </div>
          </div>
        </div>
      </div>

      {/* Episode Details Bar Under Player */}
      {!isMini && (
        <div className="flex flex-col gap-1 mt-4 px-1">
          <div className="flex items-center justify-between">
            <h1 className="font-display font-extrabold text-xl text-white">
              EP {episode.episodeNumber}: {episode.title}
            </h1>
            <span className="text-xs font-mono text-gray-400 bg-white/5 px-2.5 py-1 rounded-lg border border-white/5">
              {episode.duration}
            </span>
          </div>
          <p className="text-sm text-gray-400 mt-2 leading-relaxed">
            {episode.description}
          </p>
        </div>
      )}
    </div>
  );
};
