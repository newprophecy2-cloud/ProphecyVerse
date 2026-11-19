import React, { useRef, useState, useEffect } from "react";
import Hls from "hls.js";
import * as dashjs from "dashjs";
import {
  Play,
  Pause,
  Volume2,
  VolumeX,
  Maximize,
  Minimize,
  RotateCcw,
  RotateCw,
  Settings,
  Loader,
  SkipForward,
  SkipBack,
  Lock,
  Unlock,
  Monitor,
  Languages,
  Tv,
  Subtitles,
  Check,
  Globe,
  Minimize2,
  Maximize2,
  X,
  Sparkles,
  ArrowLeft,
  Sun,
  RefreshCw,
} from "lucide-react";
import { Episode, MovieBoxStreamVariant, MovieBoxSubtitleTrack } from "../types";
import { getLanguageMetadata } from "../services/dbService";
import { resolveVideoSource } from "../services/videoSourceResolver";
import { parseVttOrSrtCues, SubtitleCue } from "../services/subtitleParser";

export type { SubtitleCue };
export { parseVttOrSrtCues };

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
  isStreamInitializing?: boolean;
  streamErrorMessage?: string | null;
  onRetryPlayback?: () => void;
}

export interface DetectedAudioTrack {
  id: string | number;
  nativeIndex: number;
  name: string;
  lang: string;
  isHls: boolean;
  isDash?: boolean;
}

export function normalizeAudioLanguageName(
  rawNameOrLang?: string,
  fallbackIndex = 0
): string {
  if (!rawNameOrLang) return `Audio Track ${fallbackIndex + 1}`;
  const str = String(rawNameOrLang).trim();
  const lower = str.toLowerCase();
  if (lower.includes("ja") || lower.includes("jpn") || lower.includes("japanese"))
    return "Japanese";
  if (lower.includes("hi") || lower.includes("hin") || lower.includes("hindi"))
    return "Hindi";
  if (lower.includes("en") || lower.includes("eng") || lower.includes("english"))
    return "English";
  if (lower.includes("ta") || lower.includes("tam") || lower.includes("tamil"))
    return "Tamil";
  if (lower.includes("ko") || lower.includes("kor") || lower.includes("korean"))
    return "Korean";
  if (lower.includes("es") || lower.includes("spa") || lower.includes("spanish"))
    return "Spanish";
  if (lower.includes("ar") || lower.includes("ara") || lower.includes("arabic"))
    return "Arabic";
  if (
    lower.includes("bn") ||
    lower.includes("ben") ||
    lower.includes("bangla") ||
    lower.includes("bengali")
  )
    return "Bangla";
  if (lower.includes("fr") || lower.includes("fre") || lower.includes("fra") || lower.includes("french"))
    return "French";
  if (lower.includes("de") || lower.includes("ger") || lower.includes("deu") || lower.includes("german"))
    return "German";
  return str.charAt(0).toUpperCase() + str.slice(1);
}

export function getAudioTrackDisplayInfo(
  rawNameOrLang?: string,
  _langOrIndex?: string | number
): { name: string; flag: string; fullLabel: string } {
  const norm = normalizeAudioLanguageName(rawNameOrLang, 0);
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
  else if (
    lower.includes("tamil") ||
    lower.includes("telugu") ||
    lower.includes("marathi")
  )
    flag = "🇮🇳";

  return {
    name: norm,
    flag,
    fullLabel: `${flag} ${norm}`,
  };
}

export function findDefaultAudioTrackIndex(tracks: DetectedAudioTrack[]): number {
  if (!tracks || tracks.length === 0) return 0;
  const engIdx = tracks.findIndex(
    (t) =>
      t.name.toLowerCase().includes("english") ||
      (t.lang && (t.lang.toLowerCase() === "en" || t.lang.toLowerCase() === "eng"))
  );
  if (engIdx >= 0) return engIdx;

  const jpnIdx = tracks.findIndex(
    (t) =>
      t.name.toLowerCase().includes("japanese") ||
      (t.lang && (t.lang.toLowerCase() === "ja" || t.lang.toLowerCase() === "jpn"))
  );
  if (jpnIdx >= 0) return jpnIdx;

  return 0;
}

function formatTime(sec: number): string {
  if (!Number.isFinite(sec) || sec < 0) return "0:00";
  const totalSec = Math.floor(sec);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  if (h > 0) {
    return `${h}:${m < 10 ? "0" : ""}${m}:${s < 10 ? "0" : ""}${s}`;
  }
  return `${m}:${s < 10 ? "0" : ""}${s}`;
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
  isStreamInitializing = false,
  streamErrorMessage = null,
  onRetryPlayback,
}) => {
  const videoRef = useRef<HTMLVideoElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const controlsTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const hlsInstanceRef = useRef<Hls | null>(null);
  const dashInstanceRef = useRef<dashjs.MediaPlayerClass | null>(null);
  const failedVariantIdsRef = useRef<Set<string>>(new Set());
  const autoRetriedEpisodeRef = useRef<boolean>(false);

  const [isPlaying, setIsPlaying] = useState(false);
  const [duration, setDuration] = useState(0);
  const [currentTime, setCurrentTime] = useState(0);
  const [bufferedTime, setBufferedTime] = useState(0);
  const [volume, setVolume] = useState(0.8);
  const [isMuted, setIsMuted] = useState(false);
  const [playbackSpeed, setPlaybackSpeed] = useState<number>(() => {
    return parseFloat(localStorage.getItem("animestream_preferred_speed") || "1");
  });
  const [isLocked, setIsLocked] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [isCinemaMode, setIsCinemaMode] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [showControls, setShowControls] = useState(true);
  const [playbackError, setPlaybackError] = useState<string | null>(null);

  const [brightness, setBrightness] = useState(1);
  const [gestureToast, setGestureToast] = useState<{
    icon: "sun" | "vol";
    label: string;
  } | null>(null);
  const [seekRipple, setSeekRipple] = useState<{
    type: "left" | "right";
    amount: number;
    id: number;
  } | null>(null);
  const lastTapTimeRef = useRef<number>(0);

  const pendingSeekPositionRef = useRef<number | null>(null);
  const wasPlayingBeforeSwitchRef = useRef<boolean>(false);

  const [autoPlayEnabled, setAutoPlayEnabled] = useState<boolean>(() => {
    const stored = localStorage.getItem("animestream_autoplay");
    return stored !== null ? stored === "true" : true;
  });
  const [countdownSeconds, setCountdownSeconds] = useState<number | null>(null);
  const countdownTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const [selectedVariantId, setSelectedVariantId] = useState<string | null>(null);
  const [selectedQuality, setSelectedQuality] = useState<string>(() => {
    return localStorage.getItem("animestream_preferred_quality") || "Auto";
  });
  const [availableQualities, setAvailableQualities] = useState<
    { height: number; levelIndex: number; label: string }[]
  >([]);

  const initialLangMeta = getLanguageMetadata(episode.defaultLanguage || "English");
  const [selectedAudioLang, setSelectedAudioLang] = useState<string>(initialLangMeta.name);
  const [detectedAudioTracks, setDetectedAudioTracks] = useState<DetectedAudioTrack[]>([]);
  const [selectedAudioTrackIndex, setSelectedAudioTrackIndex] = useState<number>(0);
  const [showAudioMenu, setShowAudioMenu] = useState(false);
  const [audioErrorMessage, setAudioErrorMessage] = useState<string | null>(null);

  const [showSettingsMenu, setShowSettingsMenu] = useState(false);
  const [settingsTab, setSettingsTab] = useState<
    "main" | "quality" | "audio" | "speed" | "subtitles"
  >("main");

  const availableSubtitles: MovieBoxSubtitleTrack[] = episode.subtitles || [];
  const [selectedSubId, setSelectedSubId] = useState<string>(() => {
    const pref = localStorage.getItem("animestream_preferred_sub_lang") || "en";
    if (pref === "off") return "off";
    return "auto";
  });
  const [subtitleCues, setSubtitleCues] = useState<SubtitleCue[]>([]);
  const [activeSubtitleText, setActiveSubtitleText] = useState("");
  const subtitleCacheRef = useRef<Map<string, SubtitleCue[]>>(new Map());

  // Reset per-episode selections cleanly
  useEffect(() => {
    if (countdownTimerRef.current) clearInterval(countdownTimerRef.current);
    setCountdownSeconds(null);
    setSelectedVariantId(null);
    setPlaybackError(null);
    failedVariantIdsRef.current = new Set();
    autoRetriedEpisodeRef.current = false;
  }, [episode.id, episode.episodeNumber, episode.resourceId]);

  useEffect(() => {
    return () => {
      if (countdownTimerRef.current) clearInterval(countdownTimerRef.current);
    };
  }, []);

  // Determine active stream variant & URL
  const streamVariants: MovieBoxStreamVariant[] = episode.streamVariants || [];
  const selectedVariant =
    streamVariants.find((v) => v.id === selectedVariantId) || null;
  const resolvedSource = resolveVideoSource(episode, selectedAudioLang);

  const videoSrc = selectedVariant?.url || resolvedSource.streamUrl;
  const effectiveFormat: "hls" | "dash" | "mp4" = (() => {
    if (selectedVariant) {
      if (selectedVariant.format === "hls") return "hls";
      if (selectedVariant.format === "dash") return "dash";
      return "mp4";
    }
    if (resolvedSource.isHls) return "hls";
    if (resolvedSource.isDash) return "dash";
    return "mp4";
  })();
  const hasValidVideoSource = Boolean(videoSrc);

  // Determine active subtitle URL
  const activeSubtitleTrack: MovieBoxSubtitleTrack | undefined = (() => {
    if (selectedSubId === "off") return undefined;
    if (selectedSubId === "auto") {
      return (
        availableSubtitles.find((s) =>
          s.name.toLowerCase().includes("english")
        ) || availableSubtitles[0]
      );
    }
    return availableSubtitles.find((s) => s.id === selectedSubId);
  })();
  const activeSubtitlesUrl =
    selectedSubId === "off"
      ? ""
      : activeSubtitleTrack?.url || episode.subtitlesUrl || "";

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
        if (countdownTimerRef.current) clearInterval(countdownTimerRef.current);
        setCountdownSeconds(null);
        if (onNextEpisode) onNextEpisode();
      } else {
        setCountdownSeconds(secsLeft);
      }
    }, 1000);
  };

  const cancelAutoPlayCountdown = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (countdownTimerRef.current) clearInterval(countdownTimerRef.current);
    setCountdownSeconds(null);
  };

  const playNextNow = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (countdownTimerRef.current) clearInterval(countdownTimerRef.current);
    setCountdownSeconds(null);
    if (onNextEpisode) onNextEpisode();
  };

  const populateHlsAudioTracks = (hlsInstance: Hls) => {
    if (!hlsInstance || !hlsInstance.audioTracks || hlsInstance.audioTracks.length === 0) {
      setDetectedAudioTracks([]);
      return;
    }
    const tracks: DetectedAudioTrack[] = hlsInstance.audioTracks.map(
      (tr: any, idx: number) => ({
        id: tr.id ?? idx,
        nativeIndex: idx,
        name: normalizeAudioLanguageName(tr.name || tr.lang || tr.label, idx),
        lang: tr.lang || "",
        isHls: true,
      })
    );
    setDetectedAudioTracks(tracks);
    const targetIdx = findDefaultAudioTrackIndex(tracks);
    try {
      hlsInstance.audioTrack = targetIdx;
    } catch {}
    setSelectedAudioTrackIndex(targetIdx);
    if (tracks[targetIdx]) {
      setSelectedAudioLang(tracks[targetIdx].name);
    }
  };

  const populateNativeAudioTracks = () => {
    const video = videoRef.current;
    if (!video) return;
    const nativeList = (video as any).audioTracks;
    if (!nativeList || nativeList.length <= 1) return;

    const tracks: DetectedAudioTrack[] = [];
    for (let i = 0; i < nativeList.length; i++) {
      const tr = nativeList[i];
      tracks.push({
        id: tr.id || i,
        nativeIndex: i,
        name: normalizeAudioLanguageName(tr.label || tr.language, i),
        lang: tr.language || "",
        isHls: false,
      });
    }
    setDetectedAudioTracks(tracks);
    const targetIdx = findDefaultAudioTrackIndex(tracks);
    for (let i = 0; i < nativeList.length; i++) {
      nativeList[i].enabled = i === targetIdx;
    }
    setSelectedAudioTrackIndex(targetIdx);
    if (tracks[targetIdx]) {
      setSelectedAudioLang(tracks[targetIdx].name);
    }
  };

  const handleSelectAudioTrack = (trackIndex: number) => {
    if (trackIndex < 0 || trackIndex >= detectedAudioTracks.length) return;
    const track = detectedAudioTracks[trackIndex];
    if (videoRef.current) {
      pendingSeekPositionRef.current = videoRef.current.currentTime;
      wasPlayingBeforeSwitchRef.current = !videoRef.current.paused;
    }
    try {
      if (track.isHls && hlsInstanceRef.current) {
        hlsInstanceRef.current.audioTrack = track.nativeIndex;
      } else if (track.isDash && dashInstanceRef.current) {
        const dashAudio = dashInstanceRef.current.getTracksFor("audio") || [];
        const matched = dashAudio[track.nativeIndex];
        if (matched) {
          dashInstanceRef.current.setCurrentTrack(matched);
        }
      } else if (
        videoRef.current &&
        (videoRef.current as any).audioTracks &&
        (videoRef.current as any).audioTracks.length > 0
      ) {
        const nativeList = (videoRef.current as any).audioTracks;
        for (let i = 0; i < nativeList.length; i++) {
          nativeList[i].enabled = i === track.nativeIndex;
        }
      }
      setSelectedAudioTrackIndex(trackIndex);
      setSelectedAudioLang(track.name);
      localStorage.setItem("animestream_preferred_audio_lang", track.name);
    } catch {
      setAudioErrorMessage("This audio track is unavailable.");
      setTimeout(() => setAudioErrorMessage(null), 3500);
    }
    setShowAudioMenu(false);
    setShowSettingsMenu(false);
  };

  const resetControlsTimeout = () => {
    setShowControls(true);
    if (controlsTimeoutRef.current) clearTimeout(controlsTimeoutRef.current);
    if (isPlaying) {
      controlsTimeoutRef.current = setTimeout(() => {
        setShowControls(false);
        setShowAudioMenu(false);
        setShowSettingsMenu(false);
      }, 3500);
    }
  };

  useEffect(() => {
    resetControlsTimeout();
    return () => {
      if (controlsTimeoutRef.current) clearTimeout(controlsTimeoutRef.current);
    };
  }, [isPlaying]);

  // Initialize Video / HLS / DASH playback
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    if (hlsInstanceRef.current) {
      hlsInstanceRef.current.destroy();
      hlsInstanceRef.current = null;
    }
    if (dashInstanceRef.current) {
      try {
        dashInstanceRef.current.reset();
      } catch {}
      dashInstanceRef.current = null;
    }

    if (!videoSrc) {
      setIsLoading(isStreamInitializing);
      return;
    }

    setIsLoading(true);
    setPlaybackError(null);
    setIsPlaying(false);
    setAvailableQualities([]);

    const onWaiting = () => {
      if (video.readyState < 3 && !video.paused) setIsLoading(true);
    };
    const onStalled = () => {
      if (video.readyState < 3 && !video.paused) setIsLoading(true);
    };
    const onPlaying = () => {
      setIsLoading(false);
      setPlaybackError(null);
      setIsPlaying(true);
    };
    const onPlay = () => setIsPlaying(true);
    const onPause = () => setIsPlaying(false);
    const onSeeking = () => setIsLoading(true);
    const onSeeked = () => setIsLoading(false);
    const onLoadedData = () => setIsLoading(false);
    const onCanPlayThrough = () => setIsLoading(false);
    const onCanPlay = () => {
      setIsLoading(false);
      if (pendingSeekPositionRef.current !== null) {
        const pos = pendingSeekPositionRef.current;
        pendingSeekPositionRef.current = null;
        try {
          video.currentTime = pos;
        } catch {}
        if (wasPlayingBeforeSwitchRef.current) {
          video.play().catch(() => {});
        }
      }
      populateNativeAudioTracks();
    };
    const onLoadedMetadata = () => {
      setDuration(video.duration || 0);
      video.playbackRate = playbackSpeed;
      video.volume = isMuted ? 0 : volume;
      populateNativeAudioTracks();
      if (initialPosition > 0 && pendingSeekPositionRef.current === null) {
        try {
          video.currentTime = initialPosition;
        } catch {}
      }
    };
    const onProgress = () => {
      if (video.buffered.length > 0) {
        try {
          setBufferedTime(video.buffered.end(video.buffered.length - 1));
        } catch {}
      }
    };
    const activeVariant = selectedVariant || streamVariants[0] || null;
    let hlsNetworkRecoveryCount = 0;
    let hlsMediaRecoveryCount = 0;

    const handleFatalStreamFailure = (
      fallbackMessage: string,
      options: { isNetworkOrAuth?: boolean; isCodecOrFormat?: boolean } = {}
    ) => {
      if (activeVariant) {
        failedVariantIdsRef.current.add(activeVariant.id);
      }

      const nextFallback = streamVariants.find(
        (v) =>
          v.id !== activeVariant?.id &&
          !failedVariantIdsRef.current.has(v.id) &&
          v.url !== videoSrc
      );

      if (nextFallback) {
        pendingSeekPositionRef.current = video.currentTime > 0 ? video.currentTime : null;
        wasPlayingBeforeSwitchRef.current = !video.paused;
        setSelectedVariantId(nextFallback.id);
        setSelectedQuality(nextFallback.quality);
        return;
      }

      if (
        options.isNetworkOrAuth &&
        !autoRetriedEpisodeRef.current &&
        onRetryPlayback
      ) {
        autoRetriedEpisodeRef.current = true;
        onRetryPlayback();
        return;
      }

      setIsLoading(false);
      setIsPlaying(false);
      const isHevcStream = Boolean(
        activeVariant?.codec && /hevc|h\.?265|hev1|hvc1/i.test(activeVariant.codec)
      );
      if (options.isCodecOrFormat && isHevcStream) {
        setPlaybackError(
          "This stream uses H.265/HEVC video, which this browser cannot decode. Try another dub/source or a browser with HEVC hardware support."
        );
        return;
      }
      setPlaybackError(fallbackMessage);
    };

    const onVideoError = () => {
      const mediaErr = video.error;
      let errText = "Failed to load video stream.";
      let isNetworkOrAuth = false;
      let isCodecOrFormat = false;
      if (mediaErr) {
        switch (mediaErr.code) {
          case 1:
            errText = "Video loading aborted.";
            break;
          case 2:
            errText = "Network error while loading media stream.";
            isNetworkOrAuth = true;
            break;
          case 3:
            errText = "Media decoding error or unsupported stream codec.";
            isCodecOrFormat = true;
            break;
          case 4:
            isCodecOrFormat = true;
            errText =
              "Video format or codec is not supported in this browser, or the media request was blocked.";
            break;
          default:
            errText = `Playback error (Code ${mediaErr.code})`;
        }
      }
      handleFatalStreamFailure(errText, { isNetworkOrAuth, isCodecOrFormat });
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

    if (effectiveFormat === "hls") {
      if (Hls.isSupported()) {
        const hls = new Hls({
          enableWorker: true,
          lowLatencyMode: false,
        });
        hlsInstanceRef.current = hls;
        hls.loadSource(videoSrc);
        hls.attachMedia(video);

        hls.on(Hls.Events.MANIFEST_PARSED, (_e, data) => {
          setIsLoading(false);
          const levels = (data.levels || []).map((lvl, idx) => ({
            height: lvl.height || 0,
            levelIndex: idx,
            label: lvl.height ? `${lvl.height}p` : `Level ${idx + 1}`,
          }));
          setAvailableQualities(levels);
          populateHlsAudioTracks(hls);
        });

        hls.on(Hls.Events.AUDIO_TRACK_SWITCHED, (_e, data) => {
          if (hls.audioTracks && hls.audioTracks[data.id]) {
            const tr = hls.audioTracks[data.id];
            setSelectedAudioTrackIndex(data.id);
            setSelectedAudioLang(
              normalizeAudioLanguageName(tr.name || tr.lang, data.id)
            );
          }
        });

        hls.on(Hls.Events.ERROR, (_e, data) => {
          if (!data.fatal) return;
          if (data.type === Hls.ErrorTypes.NETWORK_ERROR && hlsNetworkRecoveryCount < 1) {
            hlsNetworkRecoveryCount += 1;
            hls.startLoad();
            return;
          }
          if (data.type === Hls.ErrorTypes.MEDIA_ERROR && hlsMediaRecoveryCount < 1) {
            hlsMediaRecoveryCount += 1;
            hls.recoverMediaError();
            return;
          }
          handleFatalStreamFailure(`HLS playback error: ${data.details || data.type}`, {
            isNetworkOrAuth: data.type === Hls.ErrorTypes.NETWORK_ERROR,
            isCodecOrFormat: data.type === Hls.ErrorTypes.MEDIA_ERROR,
          });
        });
      } else if (video.canPlayType("application/vnd.apple.mpegurl")) {
        video.src = videoSrc;
        video.load();
      } else {
        handleFatalStreamFailure("This browser does not support HLS playback.", {
          isCodecOrFormat: true,
        });
      }
    } else if (effectiveFormat === "dash") {
      try {
        const player = dashjs.MediaPlayer().create();
        dashInstanceRef.current = player;

        player.on(dashjs.MediaPlayer.events.STREAM_INITIALIZED, () => {
          setIsLoading(false);
          try {
            const reps = player.getRepresentationsByType("video") || [];
            const mappedQualities = reps
              .map((rep, idx) => ({
                height: rep.height || 0,
                levelIndex: typeof rep.index === "number" ? rep.index : idx,
                label: rep.height
                  ? `${rep.height}p`
                  : `${Math.round((rep.bitrateInKbit || rep.bandwidth / 1000) || 0)}kbps`,
              }))
              .sort((a, b) => b.height - a.height);
            if (mappedQualities.length > 0) {
              setAvailableQualities(mappedQualities);
            }

            const dashAudio = player.getTracksFor("audio") || [];
            if (dashAudio.length > 0) {
              const tracks: DetectedAudioTrack[] = dashAudio.map((tr, idx) => ({
                id: tr.id ?? idx,
                nativeIndex: idx,
                name: normalizeAudioLanguageName(
                  tr.labels?.[0]?.text || tr.lang || undefined,
                  idx
                ),
                lang: tr.lang || "",
                isHls: false,
                isDash: true,
              }));
              setDetectedAudioTracks(tracks);
              const targetIdx = findDefaultAudioTrackIndex(tracks);
              if (dashAudio[targetIdx]) {
                player.setCurrentTrack(dashAudio[targetIdx]);
              }
              setSelectedAudioTrackIndex(targetIdx);
              if (tracks[targetIdx]) {
                setSelectedAudioLang(tracks[targetIdx].name);
              }
            }
          } catch {
            // ignore track inspection errors
          }
        });

        player.on(dashjs.MediaPlayer.events.ERROR, (ev: any) => {
          const errCode = ev?.error?.code;
          const errMsg =
            typeof ev?.error?.message === "string" ? ev.error.message : "";
          const isCodec = /codec|mediasource|sourcebuffer|capability/i.test(errMsg);
          const isNetwork = errCode === 25 || errCode === 27 || /download|network|403|404/i.test(errMsg);
          handleFatalStreamFailure(
            errMsg
              ? `DASH playback error: ${errMsg}`
              : "DASH stream failed to load or decode in this browser.",
            { isCodecOrFormat: isCodec, isNetworkOrAuth: isNetwork }
          );
        });

        player.initialize(video, videoSrc, false);
      } catch {
        handleFatalStreamFailure("Failed to initialize DASH player.", {
          isCodecOrFormat: true,
        });
      }
    } else {
      video.src = videoSrc;
      video.load();
    }

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
      if (hlsInstanceRef.current) {
        hlsInstanceRef.current.destroy();
        hlsInstanceRef.current = null;
      }
      if (dashInstanceRef.current) {
        try {
          dashInstanceRef.current.reset();
        } catch {}
        dashInstanceRef.current = null;
      }
    };
  }, [videoSrc, effectiveFormat, isStreamInitializing]);

  // Fetch & parse real SRT/VTT subtitles
  useEffect(() => {
    if (!activeSubtitlesUrl || selectedSubId === "off") {
      setSubtitleCues([]);
      return;
    }

    const cacheKey = `sub_${episode.id}_${activeSubtitlesUrl}`;
    if (subtitleCacheRef.current.has(cacheKey)) {
      setSubtitleCues(subtitleCacheRef.current.get(cacheKey)!);
      return;
    }

    let cancelled = false;
    fetch(activeSubtitlesUrl)
      .then((res) => (res.ok ? res.text() : ""))
      .then((textData) => {
        if (cancelled || !textData) return;
        const parsed = parseVttOrSrtCues(textData);
        if (parsed.length > 0) {
          subtitleCacheRef.current.set(cacheKey, parsed);
          setSubtitleCues(parsed);
        }
      })
      .catch(() => {
        // ignore subtitle fetch errors
      });

    return () => {
      cancelled = true;
    };
  }, [activeSubtitlesUrl, selectedSubId, episode.id]);

  useEffect(() => {
    if (selectedSubId === "off" || subtitleCues.length === 0) {
      setActiveSubtitleText("");
      return;
    }
    const activeCue = subtitleCues.find(
      (c) => currentTime >= c.start && currentTime <= c.end
    );
    setActiveSubtitleText(activeCue ? activeCue.text : "");
  }, [currentTime, selectedSubId, subtitleCues]);

  const handleSelectVariant = (variant: MovieBoxStreamVariant) => {
    // If selecting another quality option that shares the exact same manifest stream in HLS/DASH, switch level in-place
    if (
      variant.url === videoSrc &&
      availableQualities.length > 1 &&
      (variant.format === "dash" || variant.format === "hls") &&
      variant.format === effectiveFormat
    ) {
      const targetH = variant.resolution;
      const match = availableQualities.find((q) => q.height === targetH);
      if (match) {
        setSelectedVariantId(variant.id);
        handleSelectQuality(variant.quality);
        return;
      }
    }

    if (videoRef.current) {
      pendingSeekPositionRef.current = videoRef.current.currentTime;
      wasPlayingBeforeSwitchRef.current = !videoRef.current.paused;
    }
    setSelectedVariantId(variant.id);
    setSelectedQuality(variant.quality);
    localStorage.setItem("animestream_preferred_quality", variant.quality);
    setShowSettingsMenu(false);
  };

  const handleSelectQuality = (qualityLabel: string) => {
    setSelectedQuality(qualityLabel);
    localStorage.setItem("animestream_preferred_quality", qualityLabel);
    if (hlsInstanceRef.current) {
      if (qualityLabel === "Auto") {
        hlsInstanceRef.current.currentLevel = -1;
      } else {
        const targetH = parseInt(qualityLabel.replace("p", ""), 10);
        const match = availableQualities.find((q) => q.height === targetH);
        hlsInstanceRef.current.currentLevel = match ? match.levelIndex : -1;
      }
    } else if (dashInstanceRef.current) {
      if (qualityLabel === "Auto") {
        dashInstanceRef.current.updateSettings({
          streaming: { abr: { autoSwitchBitrate: { video: true } } },
        });
      } else {
        const targetH = parseInt(qualityLabel.replace("p", ""), 10);
        const match = availableQualities.find((q) => q.height === targetH);
        if (match) {
          dashInstanceRef.current.updateSettings({
            streaming: { abr: { autoSwitchBitrate: { video: false } } },
          });
          dashInstanceRef.current.setRepresentationForTypeByIndex(
            "video",
            match.levelIndex,
            true
          );
        }
      }
    }
    setShowSettingsMenu(false);
  };

  const togglePlay = () => {
    if (isLocked || !videoRef.current) return;
    if (isPlaying) {
      videoRef.current.pause();
      setIsPlaying(false);
    } else {
      videoRef.current
        .play()
        .then(() => setIsPlaying(true))
        .catch(() => {});
    }
  };

  const skipTime = (amount: number) => {
    if (isLocked || !videoRef.current) return;
    videoRef.current.currentTime = Math.max(
      0,
      videoRef.current.currentTime + amount
    );
  };

  const handleVolumeChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (isLocked) return;
    const vol = parseFloat(e.target.value);
    setVolume(vol);
    setIsMuted(vol === 0);
    if (videoRef.current) {
      videoRef.current.volume = vol;
      videoRef.current.muted = vol === 0;
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
    setShowSettingsMenu(false);
  };

  const handleTimeUpdate = () => {
    if (!videoRef.current) return;
    const cur = videoRef.current.currentTime;
    setCurrentTime(cur);
    if (videoRef.current.buffered.length > 0) {
      setBufferedTime(
        videoRef.current.buffered.end(videoRef.current.buffered.length - 1)
      );
    }
    if (onProgressUpdate && duration > 0) {
      onProgressUpdate(cur, duration);
    }
  };

  const handleProgressScrub = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (isLocked || !videoRef.current) return;
    const nextTime = parseFloat(e.target.value);
    videoRef.current.currentTime = nextTime;
    setCurrentTime(nextTime);
  };

  const toggleFullscreen = async () => {
    if (!containerRef.current) return;
    try {
      if (!document.fullscreenElement) {
        await containerRef.current.requestFullscreen();
        setIsFullscreen(true);
      } else {
        await document.exitFullscreen();
        setIsFullscreen(false);
      }
    } catch {}
  };

  const togglePip = async () => {
    if (!videoRef.current) return;
    try {
      if (document.pictureInPictureElement) {
        await document.exitPictureInPicture();
      } else if (document.pictureInPictureEnabled) {
        await videoRef.current.requestPictureInPicture();
      }
    } catch {}
  };

  const handleRetry = () => {
    setPlaybackError(null);
    if (onRetryPlayback) {
      onRetryPlayback();
    } else if (videoRef.current && videoSrc) {
      videoRef.current.load();
    }
  };

  const touchStartRef = useRef<{
    x: number;
    y: number;
    val: number;
    side: "left" | "right";
  } | null>(null);

  const handleContainerTouchStart = (e: React.TouchEvent<HTMLDivElement>) => {
    if (isLocked || e.touches.length !== 1) return;
    const touch = e.touches[0];
    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect) return;
    const xRel = (touch.clientX - rect.left) / rect.width;
    if (xRel < 0.35) {
      touchStartRef.current = {
        x: touch.clientX,
        y: touch.clientY,
        val: brightness,
        side: "left",
      };
    } else if (xRel > 0.65) {
      touchStartRef.current = {
        x: touch.clientX,
        y: touch.clientY,
        val: volume,
        side: "right",
      };
    }
  };

  const handleContainerTouchMove = (e: React.TouchEvent<HTMLDivElement>) => {
    if (!touchStartRef.current || e.touches.length !== 1) return;
    const touch = e.touches[0];
    const deltaY = touchStartRef.current.y - touch.clientY;
    if (Math.abs(deltaY) > 12) {
      if (touchStartRef.current.side === "left") {
        const nextB = Math.max(
          0.2,
          Math.min(1.5, touchStartRef.current.val + deltaY * 0.005)
        );
        setBrightness(nextB);
        setGestureToast({
          icon: "sun",
          label: `Brightness ${Math.round((nextB / 1.5) * 100)}%`,
        });
      } else {
        const nextV = Math.max(
          0,
          Math.min(1, touchStartRef.current.val + deltaY * 0.005)
        );
        setVolume(nextV);
        setIsMuted(nextV === 0);
        if (videoRef.current) videoRef.current.volume = nextV;
        setGestureToast({
          icon: "vol",
          label: `Volume ${Math.round(nextV * 100)}%`,
        });
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
      if (ratio < 0.35) {
        skipTime(-10);
        setSeekRipple({ type: "left", amount: 10, id: now });
        setTimeout(() => setSeekRipple(null), 500);
      } else if (ratio > 0.65) {
        skipTime(10);
        setSeekRipple({ type: "right", amount: 10, id: now });
        setTimeout(() => setSeekRipple(null), 500);
      }
    }
    lastTapTimeRef.current = now;
  };

  return (
    <div
      className={`flex flex-col w-full ${
        isCinemaMode ? "max-w-none" : "max-w-7xl mx-auto"
      } mb-8 transition-all duration-300`}
    >
      <div
        ref={containerRef}
        onMouseMove={resetControlsTimeout}
        onClick={handleDoubleTapClick}
        onTouchStart={handleContainerTouchStart}
        onTouchMove={handleContainerTouchMove}
        onTouchEnd={handleContainerTouchEnd}
        className="relative aspect-video w-full rounded-2xl bg-black border border-white/10 overflow-hidden shadow-2xl group flex items-center justify-center select-none"
      >
        {isMini && (
          <div className="absolute top-0 inset-x-0 bg-gradient-to-b from-black/85 via-black/40 to-transparent p-2.5 flex items-center justify-between z-30 opacity-90 group-hover:opacity-100 transition-opacity">
            <div className="flex items-center gap-1.5 px-2 py-0.5 rounded-md bg-black/60 border border-white/10 text-[11px] font-bold text-white truncate max-w-[65%]">
              <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse shrink-0" />
              <span className="truncate">
                {episode.episodeNumber > 0
                  ? `EP ${episode.episodeNumber}: ${episode.title}`
                  : episode.title}
              </span>
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

        {!isMini && (
          <div
            className={`absolute top-0 inset-x-0 bg-gradient-to-b from-black/85 via-black/40 to-transparent p-3 sm:p-4 flex items-center justify-between z-30 transition-all duration-300 ${
              showControls || !isPlaying
                ? "opacity-100"
                : "opacity-0 pointer-events-none"
            }`}
          >
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
                  {animeTitle || episode.animeTitle || "Prophecy Verse"}
                </span>
                <h2 className="text-xs sm:text-sm md:text-base font-extrabold text-white truncate drop-shadow">
                  {episode.episodeNumber > 0
                    ? `EP ${episode.episodeNumber}: ${episode.title}`
                    : episode.title}
                </h2>
              </div>
            </div>

            <div className="flex items-center gap-1.5 sm:gap-2 shrink-0">
              <span className="px-2.5 py-1 rounded-lg bg-amber-500/20 text-amber-400 font-extrabold text-xs font-mono border border-amber-500/30 shadow-sm">
                {episode.seasonNumber
                  ? `S${episode.seasonNumber} • EP${episode.episodeNumber}`
                  : selectedVariant?.quality || episode.quality || "HD"}
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
                {isFullscreen ? (
                  <Minimize className="w-4 h-4 sm:w-4.5 sm:h-4.5" />
                ) : (
                  <Maximize className="w-4 h-4 sm:w-4.5 sm:h-4.5" />
                )}
              </button>
            </div>
          </div>
        )}

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

        {seekRipple && (
          <div
            className={`absolute inset-y-0 w-1/3 flex items-center justify-center z-35 pointer-events-none transition-all ${
              seekRipple.type === "left"
                ? "left-0 bg-gradient-to-r from-amber-500/20 to-transparent rounded-r-full"
                : "right-0 bg-gradient-to-l from-amber-500/20 to-transparent rounded-l-full"
            }`}
          >
            <div className="flex flex-col items-center gap-1 text-amber-400 font-extrabold">
              {seekRipple.type === "left" ? (
                <>
                  <RotateCcw className="w-10 h-10" />
                  <span className="text-sm font-mono tracking-widest">-10s</span>
                </>
              ) : (
                <>
                  <RotateCw className="w-10 h-10" />
                  <span className="text-sm font-mono tracking-widest">+10s</span>
                </>
              )}
            </div>
          </div>
        )}

        {gestureToast && (
          <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 bg-black/80 border border-white/20 text-white text-xs sm:text-sm font-bold font-mono px-4 py-2.5 rounded-2xl backdrop-blur-md shadow-2xl z-40 flex items-center gap-2.5 pointer-events-none">
            {gestureToast.icon === "sun" ? (
              <Sun className="w-5 h-5 text-amber-400" />
            ) : (
              <Volume2 className="w-5 h-5 text-amber-400" />
            )}
            <span>{gestureToast.label}</span>
          </div>
        )}

        <video
          ref={videoRef}
          onTimeUpdate={handleTimeUpdate}
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
          style={{
            filter: brightness !== 1 ? `brightness(${brightness})` : undefined,
          }}
          playsInline
        />

        {countdownSeconds !== null && nextEpisode && (
          <div className="absolute inset-0 bg-black/92 backdrop-blur-xl z-40 flex flex-col items-center justify-center p-6 text-center select-none">
            <div className="flex flex-col items-center max-w-sm w-full gap-4">
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
              </div>
              <div className="flex items-center gap-3 w-full mt-1">
                <button
                  onClick={cancelAutoPlayCountdown}
                  className="flex-1 py-2.5 px-4 rounded-xl bg-white/10 hover:bg-white/20 text-white font-bold text-xs uppercase tracking-wider border border-white/10 transition-all cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  onClick={playNextNow}
                  className="flex-1 py-2.5 px-4 rounded-xl bg-amber-500 hover:bg-amber-400 text-black font-extrabold text-xs uppercase tracking-wider flex items-center justify-center gap-1.5 shadow-lg shadow-amber-500/20 cursor-pointer"
                >
                  <SkipForward className="w-4 h-4 fill-current" />
                  Play Now
                </button>
              </div>
            </div>
          </div>
        )}

        {!hasValidVideoSource && (
          <div className="absolute inset-0 flex flex-col items-center justify-center bg-black/95 backdrop-blur-md z-35 px-4 text-center select-none">
            <div className="flex flex-col items-center gap-4 max-w-md">
              <div className="relative flex items-center justify-center w-16 h-16 rounded-full bg-amber-500/10 border border-amber-500/30">
                {isStreamInitializing ? (
                  <Loader className="w-8 h-8 text-amber-400 animate-spin" />
                ) : (
                  <RefreshCw className="w-7 h-7 text-amber-400" />
                )}
              </div>
              <h4 className="font-extrabold text-white text-lg font-display">
                {isStreamInitializing
                  ? "Loading"
                  : "Stream Unavailable"}
              </h4>
              <p className="text-xs text-gray-400 leading-relaxed max-w-sm">
                {isStreamInitializing
                  ? "Loading playable media variants and external subtitles."
                  : streamErrorMessage ||
                    "No browser-playable stream URL is available for this selection yet."}
              </p>
              {!isStreamInitializing && onRetryPlayback && (
                <button
                  onClick={onRetryPlayback}
                  className="px-5 py-2.5 bg-amber-500 text-black font-extrabold text-xs uppercase tracking-widest rounded-xl hover:opacity-95 transition-all cursor-pointer"
                >
                  Loading
                </button>
              )}
            </div>
          </div>
        )}

        {playbackError && (
          <div className="absolute inset-0 flex flex-col items-center justify-center bg-black/90 backdrop-blur-md z-35 px-4 text-center">
            <div className="flex flex-col items-center gap-4 max-w-md">
              <div className="w-12 h-12 rounded-full bg-red-500/10 flex items-center justify-center text-red-500">
                <Settings className="w-6 h-6" />
              </div>
              <h4 className="font-bold text-white text-base">Playback Status</h4>
              <p className="text-xs text-gray-400 leading-relaxed">
                {playbackError}
              </p>
              <button
                onClick={handleRetry}
                className="mt-2 px-5 py-2.5 bg-amber-500 text-black font-extrabold text-xs uppercase tracking-widest rounded-xl hover:opacity-95 transition-all shadow-md cursor-pointer"
              >
                Retry Stream
              </button>
            </div>
          </div>
        )}

        {isLoading && hasValidVideoSource && !playbackError && (
          <div className="absolute inset-0 flex items-center justify-center bg-black/60 backdrop-blur-sm z-30 pointer-events-none">
            <div className="flex flex-col items-center gap-4">
              <Loader className="w-12 h-12 text-amber-400 animate-spin" />
              <span className="text-xs font-bold tracking-widest text-gray-200 uppercase font-mono">
                Loading Stream...
              </span>
            </div>
          </div>
        )}

        {selectedSubId !== "off" && activeSubtitleText && (
          <div
            className={`absolute left-1/2 -translate-x-1/2 px-4 py-1 text-center max-w-[92%] sm:max-w-[85%] md:max-w-[75%] z-25 pointer-events-none transition-all duration-200 ease-in-out ${
              showControls
                ? "bottom-20 sm:bottom-24 md:bottom-28"
                : "bottom-6 sm:bottom-10 md:bottom-12"
            }`}
          >
            <p
              className="text-white font-bold text-sm sm:text-base md:text-lg lg:text-xl leading-snug tracking-wide font-sans max-w-full break-words line-clamp-2 select-none"
              style={{
                WebkitTextStroke: "1px #000000",
                textShadow:
                  "-1.5px -1.5px 0 #000, 1.5px -1.5px 0 #000, -1.5px 1.5px 0 #000, 1.5px 1.5px 0 #000, 0 2px 4px rgba(0,0,0,0.9)",
              }}
            >
              {activeSubtitleText}
            </p>
          </div>
        )}

        {audioErrorMessage && (
          <div className="absolute top-16 left-1/2 -translate-x-1/2 bg-red-950/90 border border-red-500/40 text-red-200 text-xs px-4 py-2 rounded-xl backdrop-blur-md shadow-2xl z-50">
            {audioErrorMessage}
          </div>
        )}

        {!isPlaying && !isLoading && !playbackError && hasValidVideoSource && (
          <div className="absolute inset-0 flex items-center justify-center pointer-events-none z-15">
            <div className="p-5 rounded-full bg-black/50 backdrop-blur-md border border-white/15 scale-100 group-hover:scale-110 transition-all">
              <Play className="w-10 h-10 fill-current text-amber-400 pl-1" />
            </div>
          </div>
        )}

        <div
          className={`absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/95 via-black/60 to-transparent p-4 flex flex-col gap-3 transition-all duration-300 z-20 ${
            showControls || !isPlaying
              ? "opacity-100"
              : "opacity-0 pointer-events-none"
          }`}
        >
          <div className="flex items-center gap-2.5 sm:gap-3">
            <span className="text-[11px] font-mono text-gray-300 font-bold shrink-0">
              {formatTime(currentTime)}
            </span>
            <div className="relative flex-1 flex items-center h-3 cursor-pointer">
              <div className="absolute inset-x-0 h-1 sm:h-1.5 bg-white/15 rounded-lg overflow-hidden pointer-events-none">
                <div
                  className="h-full bg-white/30 transition-all duration-300"
                  style={{
                    width: `${
                      duration > 0 ? (bufferedTime / duration) * 100 : 0
                    }%`,
                  }}
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

          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2 sm:gap-3 md:gap-4">
              <button
                onClick={togglePlay}
                className="text-white hover:text-amber-400 transition-colors p-1 cursor-pointer"
                title={isPlaying ? "Pause" : "Play"}
              >
                {isPlaying ? (
                  <Pause className="fill-current w-5 h-5 sm:w-6 sm:h-6" />
                ) : (
                  <Play className="fill-current w-5 h-5 sm:w-6 sm:h-6" />
                )}
              </button>

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

              <div className="flex items-center gap-2">
                <button
                  onClick={toggleMute}
                  className="text-gray-300 hover:text-amber-400 transition-colors p-1 cursor-pointer"
                  title="Mute"
                >
                  {isMuted ? (
                    <VolumeX className="w-4 h-4 sm:w-5 sm:h-5 text-red-400" />
                  ) : (
                    <Volume2 className="w-4 h-4 sm:w-5 sm:h-5" />
                  )}
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

            <div className="flex items-center gap-1.5 sm:gap-2 md:gap-3">
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
                    <span className="hidden xs:inline">
                      {getAudioTrackDisplayInfo(selectedAudioLang).name}
                    </span>
                  </button>

                  {showAudioMenu && (
                    <div className="absolute bottom-11 right-0 bg-[#0d0e12]/95 border border-white/15 rounded-2xl p-2.5 flex flex-col gap-1 min-w-[200px] z-50 shadow-2xl backdrop-blur-xl">
                      <div className="text-[10px] font-bold uppercase tracking-wider text-gray-400 px-2 py-1 border-b border-white/10 flex items-center justify-between">
                        <span>Audio Tracks</span>
                        <span className="text-amber-400 font-mono">
                          ({detectedAudioTracks.length})
                        </span>
                      </div>
                      {detectedAudioTracks.map((tr, idx) => {
                        const displayInfo = getAudioTrackDisplayInfo(
                          tr.name,
                          tr.lang
                        );
                        return (
                          <button
                            key={tr.id || idx}
                            onClick={(e) => {
                              e.stopPropagation();
                              handleSelectAudioTrack(idx);
                            }}
                            className={`flex items-center justify-between px-2.5 py-2 rounded-xl text-xs font-semibold transition-all cursor-pointer ${
                              selectedAudioTrackIndex === idx
                                ? "bg-amber-500/20 text-amber-400 font-bold border border-amber-500/30"
                                : "text-gray-300 hover:bg-white/10 hover:text-white"
                            }`}
                          >
                            <span className="flex items-center gap-2">
                              <span className="text-sm">{displayInfo.flag}</span>
                              <span>{displayInfo.name}</span>
                            </span>
                            {selectedAudioTrackIndex === idx && (
                              <Check className="w-4 h-4 text-amber-400" />
                            )}
                          </button>
                        );
                      })}
                    </div>
                  )}
                </div>
              )}

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
                  <div className="absolute bottom-11 right-0 bg-[#0d0e12]/95 border border-white/15 rounded-2xl p-2 flex flex-col gap-1 min-w-[220px] z-50 shadow-2xl backdrop-blur-xl">
                    {settingsTab === "main" && (
                      <>
                        <div className="text-[10px] font-bold uppercase tracking-wider text-gray-400 px-2.5 py-1 border-b border-white/10">
                          Player Settings
                        </div>
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
                          <span className="text-[11px] font-bold text-amber-400 font-mono">
                            {selectedVariant?.quality || selectedQuality} ›
                          </span>
                        </button>
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
                          <span className="text-[11px] font-bold text-amber-400 font-mono">
                            {playbackSpeed}x ›
                          </span>
                        </button>
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
                            {selectedSubId === "off"
                              ? "OFF"
                              : activeSubtitleTrack?.name || "None"}{" "}
                            ›
                          </span>
                        </button>
                      </>
                    )}

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
                          <span className="text-[10px] font-bold uppercase tracking-wider text-gray-400">
                            Audio Track
                          </span>
                        </div>
                        {detectedAudioTracks.map((tr, idx) => {
                          const displayInfo = getAudioTrackDisplayInfo(
                            tr.name,
                            tr.lang
                          );
                          return (
                            <button
                              key={tr.id}
                              onClick={(e) => {
                                e.stopPropagation();
                                handleSelectAudioTrack(idx);
                              }}
                              className={`flex items-center justify-between px-2.5 py-1.5 rounded-lg text-xs font-medium transition-all cursor-pointer ${
                                selectedAudioTrackIndex === idx
                                  ? "bg-amber-500/20 text-amber-400 font-bold border border-amber-500/30"
                                  : "text-gray-300 hover:bg-white/10 hover:text-white"
                              }`}
                            >
                              <span className="flex items-center gap-1.5">
                                <span>{displayInfo.flag}</span>
                                <span>{displayInfo.name}</span>
                              </span>
                              {selectedAudioTrackIndex === idx && (
                                <Check className="w-3.5 h-3.5 text-amber-400" />
                              )}
                            </button>
                          );
                        })}
                      </>
                    )}

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
                          <span className="text-[10px] font-bold uppercase tracking-wider text-gray-400">
                            Stream Quality
                          </span>
                        </div>

                        {streamVariants.length > 0
                          ? streamVariants.map((variant) => {
                              const active =
                                selectedVariant?.id === variant.id ||
                                (!selectedVariant &&
                                  streamVariants[0]?.id === variant.id);
                              return (
                                <button
                                  key={variant.id}
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    handleSelectVariant(variant);
                                  }}
                                  className={`flex items-center justify-between px-2.5 py-1.5 rounded-lg text-xs font-mono transition-all cursor-pointer ${
                                    active
                                      ? "bg-amber-500/20 text-amber-400 font-bold border border-amber-500/30"
                                      : "text-gray-300 hover:bg-white/10 hover:text-white"
                                  }`}
                                >
                                  <span className="truncate">
                                    {variant.label}
                                  </span>
                                  {active && (
                                    <Check className="w-3.5 h-3.5 text-amber-400 shrink-0" />
                                  )}
                                </button>
                              );
                            })
                          : [
                              "Auto",
                              "1080p",
                              "720p",
                              "480p",
                              "360p",
                              "240p",
                            ].map((qLabel) => (
                              <button
                                key={qLabel}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  handleSelectQuality(qLabel);
                                }}
                                className={`flex items-center justify-between px-2.5 py-1.5 rounded-lg text-xs font-mono transition-all cursor-pointer ${
                                  selectedQuality === qLabel
                                    ? "bg-amber-500/20 text-amber-400 font-bold border border-amber-500/30"
                                    : "text-gray-300 hover:bg-white/10 hover:text-white"
                                }`}
                              >
                                <span>{qLabel}</span>
                                {selectedQuality === qLabel && (
                                  <Check className="w-3.5 h-3.5 text-amber-400" />
                                )}
                              </button>
                            ))}
                      </>
                    )}

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
                          <span className="text-[10px] font-bold uppercase tracking-wider text-gray-400">
                            Speed
                          </span>
                        </div>
                        {[0.5, 0.75, 1, 1.25, 1.5, 1.75, 2].map((sp) => (
                          <button
                            key={sp}
                            onClick={(e) => {
                              e.stopPropagation();
                              changeSpeed(sp);
                            }}
                            className={`flex items-center justify-between px-2.5 py-1.5 rounded-lg text-xs font-mono transition-all cursor-pointer ${
                              playbackSpeed === sp
                                ? "bg-amber-500/20 text-amber-400 font-bold"
                                : "text-gray-300 hover:bg-white/10 hover:text-white"
                            }`}
                          >
                            <span>{sp}x</span>
                            {playbackSpeed === sp && (
                              <Check className="w-3.5 h-3.5 text-amber-400" />
                            )}
                          </button>
                        ))}
                      </>
                    )}

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
                          <span className="text-[10px] font-bold uppercase tracking-wider text-gray-400">
                            Subtitles
                          </span>
                        </div>
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            setSelectedSubId("off");
                            localStorage.setItem(
                              "animestream_preferred_sub_lang",
                              "off"
                            );
                            setShowSettingsMenu(false);
                          }}
                          className={`flex items-center justify-between px-2.5 py-1.5 rounded-lg text-xs font-medium transition-all cursor-pointer ${
                            selectedSubId === "off"
                              ? "bg-amber-500/20 text-amber-400 font-bold border border-amber-500/30"
                              : "text-gray-300 hover:bg-white/10 hover:text-white"
                          }`}
                        >
                          <span>Off</span>
                          {selectedSubId === "off" && (
                            <Check className="w-3.5 h-3.5 text-amber-400" />
                          )}
                        </button>
                        {availableSubtitles.length > 0 ? (
                          availableSubtitles.map((sub) => {
                            const isActive =
                              activeSubtitleTrack?.id === sub.id &&
                              selectedSubId !== "off";
                            return (
                              <button
                                key={sub.id}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  setSelectedSubId(sub.id);
                                  localStorage.setItem(
                                    "animestream_preferred_sub_lang",
                                    sub.languageCode || sub.name
                                  );
                                  setShowSettingsMenu(false);
                                }}
                                className={`flex items-center justify-between px-2.5 py-1.5 rounded-lg text-xs font-medium transition-all cursor-pointer ${
                                  isActive
                                    ? "bg-amber-500/20 text-amber-400 font-bold border border-amber-500/30"
                                    : "text-gray-300 hover:bg-white/10 hover:text-white"
                                }`}
                              >
                                <span className="truncate">{sub.name}</span>
                                {isActive && (
                                  <Check className="w-3.5 h-3.5 text-amber-400 shrink-0" />
                                )}
                              </button>
                            );
                          })
                        ) : (
                          <div className="px-2.5 py-2 text-[11px] text-gray-500 font-mono">
                            No external subtitles returned
                          </div>
                        )}
                      </>
                    )}
                  </div>
                )}
              </div>

              <button
                onClick={toggleAutoPlay}
                className={`px-2 py-1 rounded-lg border text-xs font-mono font-bold flex items-center gap-1 transition-all cursor-pointer ${
                  autoPlayEnabled
                    ? "bg-amber-500/20 text-amber-400 border-amber-500/30"
                    : "bg-white/5 text-gray-400 border-white/5 hover:text-white"
                }`}
                title={
                  autoPlayEnabled
                    ? "Auto-Play ON (Starts next episode in 5s)"
                    : "Auto-Play OFF"
                }
              >
                <Sparkles
                  className={`w-3.5 h-3.5 ${
                    autoPlayEnabled ? "text-amber-400" : "text-gray-400"
                  }`}
                />
                <span className="hidden sm:inline">Auto</span>
              </button>

              {!isMini && (
                <button
                  onClick={() => setIsCinemaMode(!isCinemaMode)}
                  className={`p-1.5 rounded-lg transition-colors hidden md:block cursor-pointer ${
                    isCinemaMode
                      ? "bg-amber-500/20 text-amber-400"
                      : "text-gray-300 hover:text-amber-400"
                  }`}
                  title="Cinema Mode"
                >
                  <Tv className="w-4.5 h-4.5" />
                </button>
              )}

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

              <button
                onClick={togglePip}
                className="text-gray-300 hover:text-amber-400 transition-colors p-1 cursor-pointer hidden sm:block"
                title="Picture-in-Picture"
              >
                <Monitor className="w-4.5 h-4.5" />
              </button>

              <button
                onClick={toggleFullscreen}
                className="text-gray-300 hover:text-amber-400 transition-colors p-1 cursor-pointer"
                title="Fullscreen"
              >
                {isFullscreen ? (
                  <Minimize className="w-5 h-5" />
                ) : (
                  <Maximize className="w-5 h-5" />
                )}
              </button>
            </div>
          </div>
        </div>
      </div>

      {!isMini && (
        <div className="flex flex-col gap-1 mt-4 px-1">
          <div className="flex items-center justify-between">
            <h1 className="font-display font-extrabold text-xl text-white">
              {episode.episodeNumber > 0
                ? `EP ${episode.episodeNumber}: ${episode.title}`
                : episode.title}
            </h1>
            {episode.duration && (
              <span className="text-xs font-mono text-gray-400 bg-white/5 px-2.5 py-1 rounded-lg border border-white/5">
                {episode.duration}
              </span>
            )}
          </div>
          {episode.description && (
            <p className="text-sm text-gray-400 mt-2 leading-relaxed">
              {episode.description}
            </p>
          )}
        </div>
      )}
    </div>
  );
};
