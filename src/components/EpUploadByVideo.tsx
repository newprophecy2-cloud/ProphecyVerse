import React, { useState, useEffect, useCallback } from "react";
import { useApp } from "../context/AppContext";
import { Episode, SupportedLanguage } from "../types";
import { saveOrMergeEpisodeLanguage, addNotification, fetchAllEpisodes } from "../services/dbService";
import { resolveMissingAlertOnUpload, detectMissingEpisodeNumbers } from "../services/missingEpisodeService";
import { analyzeVideoAudioTracks, DetectedMediaAudioTrack } from "../utils/mediaInspector";
import { AudioTrackAnalyzerUI } from "./admin/AudioTrackAnalyzerUI";
import { 
  Upload, 
  Video, 
  FileVideo, 
  CheckCircle2, 
  AlertCircle, 
  X, 
  RefreshCw, 
  Film, 
  Clock, 
  ShieldAlert, 
  Play, 
  Languages, 
  Trash2,
  StopCircle,
  SlidersHorizontal,
  ChevronDown,
  ChevronUp,
  Lock,
  Unlock,
  ListOrdered,
  CheckCircle,
  Minimize2,
  Maximize2,
  WifiOff,
  Pause,
  RotateCcw,
  History
} from "lucide-react";

import { fetchOfficialEpisodeMetadata } from "../services/animeApiService";
import {
  saveUploadSession,
  getUploadSessionByFingerprint,
  getAllUnfinishedSessions,
  deleteUploadSession,
  getFileFingerprint,
  ActiveUploadController,
  SavedUploadSession
} from "../services/resumableUploadService";
import { getSeasonEpisodeCount, getSeasonStats, setSeasonEpisodeCount } from "../services/seasonService";
import {
  uploadToCloudflareR2,
  generateR2ObjectKey,
  verifyCloudflareVideo,
  finalizeCloudflareVideo,
  deleteFromCloudflareR2,
  buildCloudflareVideoUrl,
  CLOUDFLARE_WORKER_BASE
} from "../services/cloudflareService";

const ALLOWED_EXTENSIONS = ["mp4", "mkv", "mov", "webm", "avi"];

const ALLOWED_MIME_TYPES = [
  "video/mp4",
  "video/x-matroska",
  "video/quicktime",
  "video/webm",
  "video/x-msvideo",
  "video/avi",
  "video/mkv"
];

const LANGUAGE_OPTIONS = [
  "Sub",
  "Dub",
  "Bangla Sub",
  "Bangla Dub",
  "Dual Audio",
  "Multi Audio",
  "Raw",
  "Japanese",
  "English",
  "Hindi",
  "Bangla",
  "Spanish"
];

const QUALITY_OPTIONS = [
  "1080p Full HD",
  "720p HD",
  "480p SD",
  "360p Low",
  "4K Ultra HD",
  "Auto Quality"
];

export type UploadQueueStage = 
  | "Creating Upload"
  | "Connecting"
  | "Uploading"
  | "Upload Completed"
  | "Publishing"
  | "Published"
  | "Paused"
  | "Waiting for Internet..."
  | "Resuming..."
  | "Upload Restored"
  | "Failed";

export interface UploadQueueItem {
  id: string;
  provider?: "cloudflare";
  storageKey?: string;
  tmpStorageKey?: string;
  animeId: string;
  animeTitle: string;
  animePoster?: string;
  animeBanner?: string;
  seasonNum: number;
  episodeNum: number;
  episodeTitle: string;
  episodeDesc: string;
  episodeThumbnail: string;
  language: string;
  quality: string;
  notes: string;
  subtitlesUrl: string;
  releaseDate: string;
  isVisible: boolean;
  skipIntroStart?: number;
  skipIntroEnd?: number;
  skipOutroStart?: number;
  skipOutroEnd?: number;
  file: File | null;
  fileName: string;
  fileSize: number;
  progress: number;
  uploadSpeed: string;
  remainingTime: string;
  uploadedBytes: number;
  totalBytes: number;
  stage: UploadQueueStage;
  errorMessage: string | null;
  errorDetails: string | null;
  errorStage?: string;
  httpStatus?: number | string;
  httpMethod?: string;
  endpoint?: string;
  serverResponseBody?: string;
  errorCode?: string | null;
  errorClassification?: string;
  currentPart?: number;
  totalParts?: number;
  attempt?: number;
  uploadId: string | null;
  assetId: string | null;
  playbackId: string | null;
  duration?: string | null;
  audioTracks?: any[];
  detectedAudioTracks?: DetectedMediaAudioTrack[];
  hasMultipleAudio?: boolean;
  availableAudioTracks?: string[];
  defaultAudio?: string;
  xhr?: XMLHttpRequest | null;
  uploadController?: ActiveUploadController | null;
  fingerprint?: string;
  createdAt: number;
}

interface Props {
  allEpisodesList?: Episode[];
  refreshAllEpisodes?: () => Promise<void>;
  onSuccessEpisodeCreated?: (episode: Episode) => void;
  onOpenMissingAlertModal?: (data: { animeName: string; season: number; episodeNumber: number; language: string }) => void;
}

export const EpUploadByVideo: React.FC<Props> = ({
  allEpisodesList,
  refreshAllEpisodes,
  onSuccessEpisodeCreated,
  onOpenMissingAlertModal
}) => {
  const { animeList } = useApp();
  const [catalogEpisodesList, setCatalogEpisodesList] = useState<Episode[]>(allEpisodesList || []);

  const loadAllEpisodes = useCallback(async () => {
    if (allEpisodesList && allEpisodesList.length > 0) {
      setCatalogEpisodesList(allEpisodesList);
    } else {
      try {
        const list = await fetchAllEpisodes();
        setCatalogEpisodesList(list);
      } catch (err) {
        console.error("Failed to fetch catalog episodes in EpUploadByVideo:", err);
      }
    }
  }, [allEpisodesList]);

  useEffect(() => {
    loadAllEpisodes();
  }, [loadAllEpisodes, allEpisodesList]);

  // Core Form Fields State (Matching Single Episode Upload)
  const [selectedAnimeId, setSelectedAnimeId] = useState("");
  const [selectedAnimeTitle, setSelectedAnimeTitle] = useState("");
  const [singleAudioLanguage, setSingleAudioLanguage] = useState("Sub");
  const [seasonNum, setSeasonNum] = useState<number>(1);
  const [episodeNum, setEpisodeNum] = useState<number>(1);
  const [isOverrideMode, setIsOverrideMode] = useState(false);
  const [videoSourceMethod] = useState<"cloudflare">("cloudflare");

  // Episode Metadata Fields
  const [episodeTitle, setEpisodeTitle] = useState("");
  const [episodeDesc, setEpisodeDesc] = useState("");
  const [episodeThumbnail, setEpisodeThumbnail] = useState("");
  const [videoQualityLabel, setVideoQualityLabel] = useState("1080p Full HD");
  const [videoNotes, setVideoNotes] = useState("");
  const [episodeSubtitlesUrl, setEpisodeSubtitlesUrl] = useState("");
  const [episodeReleaseDate, setEpisodeReleaseDate] = useState(
    new Date().toISOString().split("T")[0]
  );
  const [episodeIsVisible, setEpisodeIsVisible] = useState(true);

  // Skip Intro / Outro Timings
  const [skipIntroStart, setSkipIntroStart] = useState<number | "">("");
  const [skipIntroEnd, setSkipIntroEnd] = useState<number | "">("");
  const [skipOutroStart, setSkipOutroStart] = useState<number | "">("");
  const [skipOutroEnd, setSkipOutroEnd] = useState<number | "">("");

  // UI Accordion Toggle for Advanced Settings
  const [showAdvancedSettings, setShowAdvancedSettings] = useState(false);

  // File Upload State
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [isDragOver, setIsDragOver] = useState(false);
  const [videoPreviewUrl, setVideoPreviewUrl] = useState<string | null>(null);

  // Notification / Success Feedback state
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  // BACKGROUND UPLOAD QUEUE & BOTTOM MANAGER PANEL STATE
  const [uploadQueue, setUploadQueue] = useState<UploadQueueItem[]>([]);
  const [isQueueManagerOpen, setIsQueueManagerOpen] = useState(true);
  const [isQueueManagerMinimized, setIsQueueManagerMinimized] = useState(false);

  // Auto-fill selectedAnimeId if empty and animeList exists
  useEffect(() => {
    if (animeList.length > 0 && !selectedAnimeId) {
      setSelectedAnimeId(animeList[0].id);
      setSelectedAnimeTitle(animeList[0].title);
    }
  }, [animeList, selectedAnimeId]);

  // Clean up Object URLs for video preview to prevent memory leaks
  useEffect(() => {
    return () => {
      if (videoPreviewUrl) {
        URL.revokeObjectURL(videoPreviewUrl);
      }
    };
  }, [videoPreviewUrl]);

  // AUTOMATIC AUTO-FILL METADATA WHEN ANIME IS SELECTED (Identical to Single Episode Upload)
  useEffect(() => {
    if (!selectedAnimeId) return;

    const targetAnime = animeList.find((a) => a.id === selectedAnimeId);
    if (!targetAnime) return;

    const title = targetAnime.title || "Anime";
    setSelectedAnimeTitle(title);

    // Filter catalog episodes for this anime
    const catalogEps = catalogEpisodesList.filter(
      (ep) => ep.animeId === targetAnime.id || ep.animeId === title || (ep as any).anime_id === targetAnime.id
    );

    let calcSeason = 1;
    let calcEp = 1;

    if (catalogEps.length === 0) {
      calcSeason = 1;
      calcEp = 1;
    } else {
      const maxSeason = catalogEps.reduce((max, ep) => ((ep.seasonNumber || 1) > max ? ep.seasonNumber || 1 : max), 1);
      const epsInMaxSeason = catalogEps.filter((ep) => (ep.seasonNumber || 1) === maxSeason);
      const maxEpInSeason = epsInMaxSeason.reduce((max, ep) => {
        const num = ep.episodeNumber || (ep as any).number || 0;
        return num > max ? num : max;
      }, 0);

      const totalLimit = targetAnime.totalEpisodes || targetAnime.episodesCount || 0;
      if (totalLimit > 0 && maxEpInSeason >= totalLimit) {
        calcSeason = maxSeason + 1;
        calcEp = 1;
      } else {
        calcSeason = maxSeason;
        calcEp = maxEpInSeason + 1;
      }
    }

    if (!isOverrideMode) {
      setSeasonNum(calcSeason);
      setEpisodeNum(calcEp);
    }

    setVideoQualityLabel("1080p Full HD");
    setEpisodeIsVisible(true);
  }, [selectedAnimeId, animeList, catalogEpisodesList, isOverrideMode]);

  // AUTOMATIC OFFICIAL EPISODE METADATA & THUMBNAIL FETCHING ON SELECTION
  useEffect(() => {
    if (!selectedAnimeId) return;

    const targetAnime = animeList.find((a) => a.id === selectedAnimeId);
    if (!targetAnime) return;

    const title = targetAnime.title || "Anime";
    let active = true;

    fetchOfficialEpisodeMetadata(
      title,
      seasonNum,
      episodeNum,
      targetAnime.anilistMediaId
    )
      .then((meta) => {
        if (!active) return;
        if (meta.title && meta.title !== `Episode ${episodeNum}`) {
          setEpisodeTitle(meta.title);
        } else {
          setEpisodeTitle(`Episode ${episodeNum}`);
        }

        if (meta.thumbnail) {
          setEpisodeThumbnail(meta.thumbnail);
        } else {
          setEpisodeThumbnail(targetAnime.banner || targetAnime.poster || "");
        }

        if (meta.description) {
          setEpisodeDesc(meta.description);
        }
        if (meta.releaseDate) {
          setEpisodeReleaseDate(meta.releaseDate);
        }
      })
      .catch((err) => {
        console.warn("Auto episode metadata fetch error:", err);
      });

    return () => {
      active = false;
    };
  }, [selectedAnimeId, seasonNum, episodeNum, animeList]);

  // Handle Drag & Drop Events
  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(true);
  };

  const handleDragLeave = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(false);
  };

  // Hidden file input ref for re-attaching files to restored sessions
  const resumeFileInputRef = React.useRef<HTMLInputElement | null>(null);
  const resumingQueueItemIdRef = React.useRef<string | null>(null);

  // SESSION-ONLY UPLOAD LIFECYCLE: CLEAN UP UNFINISHED R2 UPLOADS ON UNLOAD
  useEffect(() => {
    const handleBeforeUnload = () => {
      uploadQueueRef.current.forEach((item) => {
        if (item.provider === "cloudflare" && item.stage === "Uploading" && item.tmpStorageKey) {
          if (item.uploadController?.abort) {
            try { item.uploadController.abort(); } catch (_) {}
          }
          try {
            const deleteUrl = `/api/cloudflare/delete?key=${encodeURIComponent(item.tmpStorageKey)}`;
            if (navigator.sendBeacon) {
              navigator.sendBeacon(deleteUrl);
            } else {
              fetch(deleteUrl, { method: "DELETE", keepalive: true }).catch(() => {});
            }
          } catch (_) {}
        }
      });
    };

    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => {
      window.removeEventListener("beforeunload", handleBeforeUnload);
    };
  }, []);

  // Audio Track Auto-Detection state
  const [detectedAudioTracks, setDetectedAudioTracks] = useState<DetectedMediaAudioTrack[]>([]);
  const [isAnalyzingAudio, setIsAnalyzingAudio] = useState(false);
  const [audioAnalysisWarning, setAudioAnalysisWarning] = useState<string | null>(null);

  const handleUpdateTrackLanguage = (trackIndex: number, newLang: string) => {
    setDetectedAudioTracks((prev) =>
      prev.map((t) =>
        t.trackIndex === trackIndex
          ? { ...t, language: newLang, label: `${newLang} (${t.channelLayout})`, isUserModified: true }
          : t
      )
    );
  };

  const handleSetDefaultAudioTrack = (trackIndex: number) => {
    setDetectedAudioTracks((prev) =>
      prev.map((t) => ({ ...t, isDefault: t.trackIndex === trackIndex }))
    );
  };

  const handleReanalyzeAudio = () => {
    if (!selectedFile) return;
    setIsAnalyzingAudio(true);
    setAudioAnalysisWarning(null);
    analyzeVideoAudioTracks(selectedFile).then((result) => {
      setDetectedAudioTracks(result.tracks);
      setIsAnalyzingAudio(false);
      if (result.warnings && result.warnings.length > 0) {
        setAudioAnalysisWarning(result.warnings.join(" "));
      }
    });
  };

  const validateAndSetFile = (file: File) => {
    setFileError(null);

    if (!file) {
      setFileError("No file selected.");
      return;
    }

    if (file.size <= 0) {
      setFileError("Selected file is empty (0 bytes). Please choose a valid video file.");
      return;
    }

    const ext = file.name.split(".").pop()?.toLowerCase() || "";
    const isExtensionValid = ALLOWED_EXTENSIONS.includes(ext);
    const isMimeValid = ALLOWED_MIME_TYPES.some(m => file.type.toLowerCase().includes(m.replace("video/", ""))) || file.type.startsWith("video/");

    if (!isExtensionValid && !isMimeValid) {
      setFileError(`Unsupported video format (.${ext}). Supported formats: ${ALLOWED_EXTENSIONS.join(", ").toUpperCase()}`);
      return;
    }

    setSelectedFile(file);

    // Auto-analyze audio tracks in video file
    setIsAnalyzingAudio(true);
    setAudioAnalysisWarning(null);
    analyzeVideoAudioTracks(file).then((result) => {
      setDetectedAudioTracks(result.tracks);
      setIsAnalyzingAudio(false);
      if (result.warnings && result.warnings.length > 0) {
        setAudioAnalysisWarning(result.warnings.join(" "));
      }
    }).catch((err) => {
      setIsAnalyzingAudio(false);
      setAudioAnalysisWarning("Failed to analyze audio tracks in video file.");
    });

    // Create object URL for local video preview
    if (videoPreviewUrl) {
      URL.revokeObjectURL(videoPreviewUrl);
    }
    const previewUrl = URL.createObjectURL(file);
    setVideoPreviewUrl(previewUrl);

    // Keep existing episode title if filled, or generate from filename
    if (!episodeTitle || episodeTitle === `Episode ${episodeNum}`) {
      const cleanName = file.name.replace(/\.[^/.]+$/, "").replace(/[._-]/g, " ");
      if (cleanName.length > 2) {
        setEpisodeTitle(cleanName);
      }
    }

    // Check if there is an unfinished queue item with matching fingerprint or filename+fileSize
    const fingerprint = getFileFingerprint(file);
    const matchingRestoredItem = uploadQueueRef.current.find(
      (q) =>
        (q.stage === "Upload Restored" || q.stage === "Paused" || q.stage === "Failed") &&
        (q.fingerprint === fingerprint || (q.fileName === file.name && q.fileSize === file.size))
    );

    if (matchingRestoredItem) {
      const updatedItem: UploadQueueItem = {
        ...matchingRestoredItem,
        file,
        stage: "Creating Upload",
        errorMessage: null,
        errorDetails: null
      };

      setUploadQueue((prev) =>
        prev.map((q) => (q.id === matchingRestoredItem.id ? updatedItem : q))
      );

      setIsQueueManagerOpen(true);
      setIsQueueManagerMinimized(false);

      setSuccessMsg(
        `Restored unfinished upload session found for "${file.name}"! Resuming automatically from ${matchingRestoredItem.progress}%...`
      );
      setTimeout(() => setSuccessMsg(null), 5000);

      processQueueItemUpload(matchingRestoredItem.id, updatedItem);
    }
  };

  const handleResumeFileSelected = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    const itemId = resumingQueueItemIdRef.current;
    if (!file || !itemId) return;

    const item = uploadQueueRef.current.find((q) => q.id === itemId);
    if (!item) return;

    const updatedItem: UploadQueueItem = {
      ...item,
      file,
      stage: "Creating Upload",
      errorMessage: null,
      errorDetails: null
    };

    setUploadQueue((prev) =>
      prev.map((q) => (q.id === itemId ? updatedItem : q))
    );

    setSuccessMsg(
      `File "${file.name}" attached! Resuming upload from ${item.progress}%...`
    );
    setTimeout(() => setSuccessMsg(null), 5000);

    processQueueItemUpload(itemId, updatedItem);
    resumingQueueItemIdRef.current = null;
    if (resumeFileInputRef.current) resumeFileInputRef.current.value = "";
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(false);

    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      validateAndSetFile(e.dataTransfer.files[0]);
    }
  };

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      validateAndSetFile(e.target.files[0]);
    }
  };

  const handleRemoveFile = () => {
    setSelectedFile(null);
    setVideoPreviewUrl(null);
    setFileError(null);
  };

  // Format Helper: Bytes to Human Readable
  const formatBytes = (bytes: number): string => {
    if (bytes === 0) return "0 Bytes";
    const k = 1024;
    const sizes = ["Bytes", "KB", "MB", "GB", "TB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
  };

  // Format Helper: Time Seconds to Human Readable
  const formatTime = (seconds: number): string => {
    if (!isFinite(seconds) || isNaN(seconds) || seconds <= 0) return "0s";
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    if (mins > 0) {
      return `${mins}m ${secs}s`;
    }
    return `${secs}s`;
  };

  const uploadQueueRef = React.useRef<UploadQueueItem[]>(uploadQueue);
  useEffect(() => {
    uploadQueueRef.current = uploadQueue;
  }, [uploadQueue]);

  const pollingTimersRef = React.useRef<Map<string, NodeJS.Timeout>>(new Map());

  useEffect(() => {
    return () => {
      pollingTimersRef.current.forEach((timer) => clearInterval(timer));
      pollingTimersRef.current.clear();
    };
  }, []);

  const formatDuration = (seconds?: number): string => {
    if (!seconds || isNaN(seconds) || seconds <= 0) return "24 min";
    const mins = Math.round(seconds / 60);
    return `${mins} min`;
  };

  // START INDIVIDUAL QUEUE ITEM UPLOAD WORKER (RESUMABLE CHUNKED PIPELINE)
  const processQueueItemUpload = useCallback(async (itemId: string, initialItem?: UploadQueueItem) => {
    const queueItem = initialItem || uploadQueueRef.current.find((q) => q.id === itemId);

    if (!queueItem) {
      console.error("[EpUploadByVideo] Queue item missing for ID:", itemId);
      return;
    }

    if (!queueItem.file) {
      resumingQueueItemIdRef.current = itemId;
      resumeFileInputRef.current?.click();
      setUploadQueue((prev) =>
        prev.map((q) =>
          q.id === itemId
            ? {
                ...q,
                stage: "Upload Restored",
                errorMessage: "Please select file to resume",
                errorDetails: `Please select video file "${queueItem.fileName}" to resume upload.`
              }
            : q
        )
      );
      return;
    }

    const itemFile = queueItem.file;
    const fingerprint = getFileFingerprint(itemFile);

    // CLOUDFLARE R2 UPLOAD PATHWAY (Session-Only / Non-Resumable)
    const finalObjectKey = queueItem.storageKey || generateR2ObjectKey(
      queueItem.animeTitle,
      queueItem.seasonNum,
      queueItem.episodeNum,
      itemFile.name
    );

      const tmpObjectKey = queueItem.tmpStorageKey || `_tmp/uploads/${itemId}/${finalObjectKey}`;

      setUploadQueue((prev) =>
        prev.map((q) =>
          q.id === itemId
            ? {
                ...q,
                stage: "Uploading",
                uploadSpeed: "0 MB/s",
                remainingTime: "Connecting to Cloudflare R2...",
                errorMessage: null,
                errorDetails: null,
                storageKey: finalObjectKey,
                tmpStorageKey: tmpObjectKey,
                fingerprint
              }
            : q
        )
      );

      const ctrl = uploadToCloudflareR2(itemFile, finalObjectKey, {
        onProgress: ({ uploadedBytes, totalBytes, percentage, speed, remainingTime, currentPart, totalParts, uploadId, objectKey }) => {
          setUploadQueue((prev) =>
            prev.map((q) =>
              q.id === itemId
                ? {
                    ...q,
                    progress: Math.min(percentage, 99),
                    uploadedBytes,
                    totalBytes,
                    uploadSpeed: speed,
                    remainingTime,
                    stage: "Uploading",
                    currentPart,
                    totalParts,
                    uploadId: uploadId || q.uploadId,
                    storageKey: objectKey || q.storageKey
                  }
                : q
            )
          );
        },
        onError: (errorMsg, errorDetails, errorInfo) => {
          console.error(`[Cloudflare Upload Failed] Item ${itemId}:`, errorMsg, errorDetails, errorInfo);
          setUploadQueue((prev) =>
            prev.map((q) =>
              q.id === itemId
                ? {
                    ...q,
                    stage: "Failed",
                    errorMessage: errorMsg,
                    errorDetails: errorDetails || null,
                    errorStage: errorInfo?.stage || "uploading",
                    httpStatus: errorInfo?.httpStatus !== undefined ? errorInfo.httpStatus : "N/A",
                    httpMethod: errorInfo?.httpMethod || "PUT",
                    endpoint: errorInfo?.endpoint || `${CLOUDFLARE_WORKER_BASE}/api/r2/multipart/init`,
                    serverResponseBody: errorInfo?.responseBody || errorDetails,
                    errorCode: errorInfo?.errorCode || errorInfo?.errorClassification || `HTTP_${errorInfo?.httpStatus || "ERR"}`,
                    errorClassification: errorInfo?.errorClassification,
                    uploadId: errorInfo?.uploadId || q.uploadId || null,
                    storageKey: errorInfo?.objectKey || q.storageKey,
                    currentPart: errorInfo?.currentPart,
                    totalParts: errorInfo?.totalParts,
                    attempt: errorInfo?.attempt,
                    uploadController: null
                  }
                : q
            )
          );
        },
        onSuccess: async (directVideoUrl, key, uploadId) => {
          const rawVideoUrl = directVideoUrl || buildCloudflareVideoUrl(finalObjectKey);

          setUploadQueue((prev) =>
            prev.map((q) =>
              q.id === itemId
                ? {
                    ...q,
                    progress: 99,
                    remainingTime: "Verifying object existence in Cloudflare R2...",
                    stage: "Verifying" as any,
                    uploadId: uploadId || q.uploadId,
                    storageKey: key || q.storageKey
                  }
                : q
            )
          );

          // R2 Verification Check
          let isR2Verified = false;
          let verifiedUrl = rawVideoUrl;
          try {
            const verifyRes = await verifyCloudflareVideo(finalObjectKey);
            if (verifyRes.exists) {
              isR2Verified = true;
              if (verifyRes.videoUrl) verifiedUrl = verifyRes.videoUrl;
            }
          } catch (vErr) {
            console.warn("[EpUploadByVideo R2 Verification Error]", vErr);
          }

          if (!isR2Verified) {
            setUploadQueue((prev) =>
              prev.map((q) =>
                q.id === itemId
                  ? {
                      ...q,
                      stage: "Failed",
                      errorMessage: "R2 Verification Failed ❌ - Object does not exist in R2 bucket.",
                      errorDetails: "Cloudflare HEAD check returned 404 or missing object.",
                      uploadController: null
                    }
                  : q
              )
            );
            return;
          }

          const finalVideoUrl = verifiedUrl;

          setUploadQueue((prev) =>
            prev.map((q) =>
              q.id === itemId
                ? {
                    ...q,
                    progress: 100,
                    uploadedBytes: q.totalBytes || itemFile.size,
                    uploadSpeed: "Completed",
                    remainingTime: "Saving episode...",
                    stage: "Publishing" as any
                  }
                : q
            )
          );

          const createdEpId = `${queueItem!.animeId}_s${queueItem!.seasonNum}_ep${queueItem!.episodeNum}`;

          let officialMeta: any = null;
          try {
            officialMeta = await fetchOfficialEpisodeMetadata(
              queueItem!.animeTitle,
              queueItem!.seasonNum,
              queueItem!.episodeNum
            );
          } catch (e) {
            console.warn("Official metadata fetch error:", e);
          }

          const epTitle = (officialMeta?.title && !/^Episode\s*\d+$/i.test(officialMeta.title))
            ? officialMeta.title
            : (queueItem!.episodeTitle && !/^Episode\s*\d+$/i.test(queueItem!.episodeTitle)
                ? queueItem!.episodeTitle
                : (officialMeta?.title || `Episode ${queueItem!.episodeNum}`));

          const epThumb = officialMeta?.thumbnail
            || (queueItem!.episodeThumbnail && !queueItem!.episodeThumbnail.includes("unsplash.com") ? queueItem!.episodeThumbnail : "")
            || queueItem!.animeBanner
            || queueItem!.animePoster
            || "";

          const initialEpData: Partial<Episode> = {
            id: createdEpId,
            animeId: queueItem!.animeId,
            seasonNumber: queueItem!.seasonNum,
            episodeNumber: queueItem!.episodeNum,
            title: epTitle,
            description: officialMeta?.description || queueItem!.episodeDesc,
            thumbnail: epThumb,
            thumbnailUrl: epThumb,
            releaseDate: officialMeta?.releaseDate || queueItem!.releaseDate,
            duration: "24 min",
            sourceType: "cloudflare",
            storageKey: finalObjectKey,
            videoUrl: finalVideoUrl,
            hlsUrl: finalVideoUrl,
            status: queueItem!.isVisible ? "Published" : "Draft",
            subtitlesUrl: queueItem!.subtitlesUrl || undefined,
            isVisible: queueItem!.isVisible,
            defaultLanguage: queueItem!.language as SupportedLanguage,
            quality: queueItem!.quality,
            notes: queueItem!.notes || undefined,
            skipIntroStart: queueItem!.skipIntroStart,
            skipIntroEnd: queueItem!.skipIntroEnd,
            skipOutroStart: queueItem!.skipOutroStart,
            skipOutroEnd: queueItem!.skipOutroEnd,
            hasMultipleAudio: queueItem!.hasMultipleAudio || (queueItem!.detectedAudioTracks && queueItem!.detectedAudioTracks.length > 1) || false,
            audioTracks: queueItem!.detectedAudioTracks || [],
            availableAudioTracks: queueItem!.availableAudioTracks || queueItem!.detectedAudioTracks?.map(t => t.language) || [],
            defaultAudio: queueItem!.defaultAudio || queueItem!.detectedAudioTracks?.find(t => t.isDefault)?.language || "Japanese"
          };

          const initialLangData = {
            language: queueItem!.language,
            sourceType: "cloudflare",
            storageKey: finalObjectKey,
            videoUrl: finalVideoUrl,
            hlsUrl: finalVideoUrl,
            quality: queueItem!.quality,
            notes: queueItem!.notes || undefined,
            subtitlesUrl: queueItem!.subtitlesUrl || undefined,
            status: queueItem!.isVisible ? "Published" : "Draft"
          };

          try {
            await saveOrMergeEpisodeLanguage(
              queueItem!.animeId,
              queueItem!.episodeNum,
              queueItem!.language,
              initialLangData,
              initialEpData as Episode
            );

            resolveMissingAlertOnUpload(queueItem!.animeTitle, queueItem!.seasonNum, queueItem!.episodeNum);

            await addNotification(
              `Episode Published!`,
              `${queueItem!.animeTitle} Episode ${queueItem!.episodeNum} (${queueItem!.language}) uploaded to Cloudflare R2 and published!`,
              "episode"
            );

            if (refreshAllEpisodes) {
              await refreshAllEpisodes();
            }

            try {
              const freshList = await fetchAllEpisodes();
              setCatalogEpisodesList(freshList);

              const catalogEps = freshList.filter(
                (ep) => ep.animeId === queueItem!.animeId || ep.animeId === queueItem!.animeTitle || (ep as any).anime_id === queueItem!.animeId
              );
              const allEpNums = catalogEps
                .filter((ep) => (ep.seasonNumber || 1) === queueItem!.seasonNum)
                .map((ep) => ep.episodeNumber);

              const missingEpisodeNums = detectMissingEpisodeNumbers(allEpNums);
              if (missingEpisodeNums.length > 0 && onOpenMissingAlertModal) {
                onOpenMissingAlertModal({
                  animeName: queueItem!.animeTitle,
                  season: queueItem!.seasonNum,
                  episodeNumber: missingEpisodeNums[0],
                  language: queueItem!.language,
                });
              }
            } catch (refErr) {
              console.warn("Failed to update catalog after Cloudflare publish:", refErr);
            }

            if (onSuccessEpisodeCreated) {
              onSuccessEpisodeCreated({
                ...initialEpData,
                id: createdEpId
              } as Episode);
            }

            setUploadQueue((prev) =>
              prev.map((q) =>
                q.id === itemId
                  ? {
                      ...q,
                      stage: "Published",
                      storageKey: finalObjectKey,
                      uploadController: null
                    }
                  : q
              )
            );
          } catch (dbErr: any) {
            console.error("Failed to save Cloudflare episode to database:", dbErr);
            setUploadQueue((prev) =>
              prev.map((q) =>
                q.id === itemId
                  ? {
                      ...q,
                      stage: "Failed",
                      errorMessage: "Episode Publishing Failed",
                      errorDetails: `Video uploaded to Cloudflare, but episode publishing failed: ${dbErr?.message || "Could not save published episode to database."}`,
                      uploadController: null
                    }
                  : q
              )
            );
          }
        }
      });

      setUploadQueue((prev) =>
        prev.map((q) => (q.id === itemId ? { ...q, uploadController: ctrl as any } : q))
      );
  }, [onSuccessEpisodeCreated, refreshAllEpisodes, onOpenMissingAlertModal]);





















  // PAUSE QUEUE ITEM UPLOAD
  const handlePauseQueueItem = (itemId: string) => {
    const item = uploadQueueRef.current.find((q) => q.id === itemId);
    if (item?.uploadController) {
      item.uploadController.pause();
    }
    setUploadQueue((prev) =>
      prev.map((q) => (q.id === itemId ? { ...q, stage: "Paused" } : q))
    );
  };

  // RESUME QUEUE ITEM UPLOAD
  const handleResumeQueueItem = (itemId: string) => {
    const item = uploadQueueRef.current.find((q) => q.id === itemId);
    if (!item) return;

    if (item.file) {
      processQueueItemUpload(itemId, item);
    } else {
      resumingQueueItemIdRef.current = itemId;
      resumeFileInputRef.current?.click();
    }
  };

  // CANCEL QUEUE ITEM UPLOAD
  const handleCancelQueueItem = (itemId: string) => {
    if (pollingTimersRef.current.has(itemId)) {
      clearInterval(pollingTimersRef.current.get(itemId));
      pollingTimersRef.current.delete(itemId);
    }

    const item = uploadQueueRef.current.find((q) => q.id === itemId);
    if (item?.uploadController) {
      try { item.uploadController.abort(); } catch (_) {}
    }
    if (item?.provider === "cloudflare" && item.tmpStorageKey) {
      deleteFromCloudflareR2(item.tmpStorageKey).catch(() => {});
    }

    setUploadQueue((prev) =>
      prev.map((q) => {
        if (q.id === itemId) {
          return {
            ...q,
            stage: "Failed",
            errorMessage: "Upload cancelled by user.",
            errorDetails: "Session cancelled.",
            uploadController: null
          };
        }
        return q;
      })
    );
  };

  // RETRY QUEUE ITEM UPLOAD (Without re-entering form data!)
  const handleRetryQueueItem = (itemId: string) => {
    if (pollingTimersRef.current.has(itemId)) {
      clearInterval(pollingTimersRef.current.get(itemId));
      pollingTimersRef.current.delete(itemId);
    }
    const item = uploadQueueRef.current.find((q) => q.id === itemId);
    if (item) {
      handleResumeQueueItem(itemId);
    }
  };

  // REMOVE ITEM FROM QUEUE LIST
  const handleRemoveQueueItem = (itemId: string) => {
    const item = uploadQueueRef.current.find((q) => q.id === itemId);
    if (item?.uploadController) {
      try { item.uploadController.abort(); } catch (_) {}
    }
    if (item?.provider === "cloudflare" && item.tmpStorageKey) {
      deleteFromCloudflareR2(item.tmpStorageKey).catch(() => {});
    }
    if (item?.uploadId) {
      deleteUploadSession(item.uploadId);
    }
    setUploadQueue((prev) => prev.filter((q) => q.id !== itemId));
  };

  // CLEAR COMPLETED ITEMS FROM QUEUE LIST
  const handleClearCompletedQueue = () => {
    setUploadQueue((prev) => prev.filter((q) => q.stage !== "Published"));
  };

  // MAIN FORM SUBMIT: CLICK PUBLISH -> VALIDATE -> CREATE BACKGROUND TASK -> START UPLOAD -> SHOW BOTTOM MANAGER
  const handlePublishEpisodeAndStartUpload = async (e: React.FormEvent) => {
    e.preventDefault();

    // 1. Validate required fields
    if (!selectedAnimeTitle.trim() && !selectedAnimeId) {
      alert("Please select an Anime Title first.");
      return;
    }

    if (!selectedFile) {
      setFileError("Please select or drag & drop a video file to upload.");
      return;
    }

    const matchedAnime = animeList.find(
      (a) => a.id === selectedAnimeId || a.title.trim().toLowerCase() === selectedAnimeTitle.trim().toLowerCase()
    );

    const animeId = matchedAnime?.id || `anime_${selectedAnimeTitle.toLowerCase().replace(/[^a-z0-9]/g, "_")}`;
    const animeTitle = matchedAnime?.title || selectedAnimeTitle.trim();

    const targetId = animeId.toLowerCase();
    const targetTitle = animeTitle.toLowerCase();

    const catalogEpsForAnime = catalogEpisodesList.filter(
      (ep) =>
        (ep.animeId && (ep.animeId.toLowerCase() === targetId || ep.animeId.toLowerCase() === targetTitle)) ||
        ((ep as any).anime_id && ((ep as any).anime_id.toLowerCase() === targetId || (ep as any).anime_id.toLowerCase() === targetTitle))
    );

    let targetSeasonNum = seasonNum || 1;
    let targetEpisodeNum = episodeNum || 1;
    let isAutoAssigned = false;

    // Calculate next episode number if not in override mode
    if (!isOverrideMode) {
      if (catalogEpsForAnime.length > 0) {
        const maxSeason = catalogEpsForAnime.reduce((max, ep) => ((ep.seasonNumber || 1) > max ? ep.seasonNumber || 1 : max), 1);
        const epsInMaxSeason = catalogEpsForAnime.filter((ep) => (ep.seasonNumber || 1) === maxSeason);
        const maxEpInSeason = epsInMaxSeason.reduce((max, ep) => {
          const num = ep.episodeNumber || (ep as any).number || 0;
          return num > max ? num : max;
        }, 0);

        const totalLimit = matchedAnime?.totalEpisodes || matchedAnime?.episodesCount || 0;
        if (totalLimit > 0 && maxEpInSeason >= totalLimit) {
          targetSeasonNum = maxSeason + 1;
          targetEpisodeNum = 1;
        } else {
          targetSeasonNum = maxSeason;
          targetEpisodeNum = maxEpInSeason + 1;
        }
      } else {
        targetSeasonNum = 1;
        targetEpisodeNum = 1;
      }
    } else {
      // Duplicate Episode Protection in Override Mode
      const existingInSeason = catalogEpsForAnime.find(
        (ep) => (ep.seasonNumber || 1) === targetSeasonNum && ep.episodeNumber === targetEpisodeNum
      );
      if (existingInSeason) {
        const maxEpNum = catalogEpsForAnime
          .filter((ep) => (ep.seasonNumber || 1) === targetSeasonNum)
          .reduce((max, ep) => (ep.episodeNumber > max ? ep.episodeNumber : max), 0);
        targetEpisodeNum = maxEpNum + 1;
        isAutoAssigned = true;
      }
    }

    // Missing Episode Detection
    if (targetEpisodeNum > 1 && !isOverrideMode && !isAutoAssigned) {
      const prevEpExists = catalogEpsForAnime.some(
        (ep) => (ep.seasonNumber || 1) === targetSeasonNum && ep.episodeNumber === targetEpisodeNum - 1
      );
      if (!prevEpExists) {
        const confirmSkip = confirm(
          `⚠️ Missing Episode Warning:\n\nEpisode ${targetEpisodeNum - 1} for Season ${targetSeasonNum} is missing in your catalog!\n\nAre you sure you want to skip Episode ${targetEpisodeNum - 1} and publish Episode ${targetEpisodeNum}?`
        );
        if (!confirmSkip) return;
      }
    }

    let officialMeta: any = null;
    try {
      officialMeta = await fetchOfficialEpisodeMetadata(
        animeTitle,
        targetSeasonNum,
        targetEpisodeNum,
        matchedAnime?.anilistMediaId
      );
    } catch (e) {
      console.warn("Queue item metadata fetch error:", e);
    }

    const finalTitle = (officialMeta?.title && !/^Episode\s*\d+$/i.test(officialMeta.title))
      ? officialMeta.title
      : (episodeTitle.trim() && !/^Episode\s*\d+$/i.test(episodeTitle)
          ? episodeTitle.trim()
          : (officialMeta?.title || `Episode ${targetEpisodeNum}`));

    const finalDesc = officialMeta?.description || episodeDesc.trim() || `Watch ${animeTitle} Season ${targetSeasonNum} Episode ${targetEpisodeNum} in HD with audio support and subtitles.`;

    const finalThumbnail = officialMeta?.thumbnail
      || (episodeThumbnail.trim() && !episodeThumbnail.includes("unsplash.com") ? episodeThumbnail.trim() : "")
      || matchedAnime?.banner
      || matchedAnime?.poster
      || "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=800&auto=format&fit=crop";

    // 2. Create Background Upload Queue Task Item
    const newQueueItemId = `upload_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;
    const queueItem: UploadQueueItem = {
      id: newQueueItemId,
      provider: videoSourceMethod,
      animeId,
      animeTitle,
      animePoster: matchedAnime?.poster,
      animeBanner: matchedAnime?.banner,
      seasonNum: targetSeasonNum,
      episodeNum: targetEpisodeNum,
      episodeTitle: finalTitle,
      episodeDesc: finalDesc,
      episodeThumbnail: finalThumbnail,
      language: singleAudioLanguage,
      quality: videoQualityLabel,
      notes: videoNotes.trim(),
      subtitlesUrl: episodeSubtitlesUrl.trim(),
      releaseDate: episodeReleaseDate,
      isVisible: episodeIsVisible,
      assetId: undefined,
      skipIntroStart: typeof skipIntroStart === "number" ? skipIntroStart : undefined,
      skipIntroEnd: typeof skipIntroEnd === "number" ? skipIntroEnd : undefined,
      skipOutroStart: typeof skipOutroStart === "number" ? skipOutroStart : undefined,
      skipOutroEnd: typeof skipOutroEnd === "number" ? skipOutroEnd : undefined,
      file: selectedFile,
      fileName: selectedFile.name,
      fileSize: selectedFile.size,
      detectedAudioTracks: detectedAudioTracks,
      hasMultipleAudio: detectedAudioTracks.length > 1,
      availableAudioTracks: detectedAudioTracks.map(t => t.language),
      defaultAudio: detectedAudioTracks.find(t => t.isDefault)?.language || singleAudioLanguage,
      progress: 0,
      uploadSpeed: "0 MB/s",
      remainingTime: "Calculated soon...",
      uploadedBytes: 0,
      totalBytes: selectedFile.size,
      stage: "Creating Upload",
      errorMessage: null,
      errorDetails: null,
      uploadId: null,
      playbackId: null,
      createdAt: Date.now()
    };

    // 3. Add to Upload Queue (Do NOT create episode in database yet!)
    uploadQueueRef.current = [queueItem, ...uploadQueueRef.current];
    setUploadQueue(uploadQueueRef.current);

    // 4. Automatically open & show Bottom Upload Manager
    setIsQueueManagerOpen(true);
    setIsQueueManagerMinimized(false);

    // 5. Start background upload process immediately with the item
    processQueueItemUpload(newQueueItemId, queueItem);

    // 6. Show feedback notice
    setSuccessMsg(
      isAutoAssigned
        ? `Background upload started! Auto-assigned Episode ${targetEpisodeNum} (Season ${targetSeasonNum}) for ${animeTitle}. Track progress in the Bottom Upload Manager below.`
        : `Background upload started for ${animeTitle} Episode ${targetEpisodeNum} (${singleAudioLanguage}). Track progress in the Bottom Upload Manager below.`
    );

    // 7. Reset Form File & Fields to prepare for next episode
    setSelectedFile(null);
    setVideoPreviewUrl(null);
    setFileError(null);
    setEpisodeTitle(`Episode ${targetEpisodeNum + 1}`);
    setEpisodeNum(targetEpisodeNum + 1);

    setTimeout(() => setSuccessMsg(null), 4000);
  };

  const selectedAnimeObj = animeList.find((a) => a.id === selectedAnimeId);

  return (
    <div className="flex flex-col gap-6 text-white animate-fade-in max-w-5xl mx-auto pb-32">
      {/* Upper Header Banner */}
      <div className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4 p-5 md:p-6 rounded-3xl bg-gradient-to-r from-[#171b2e] via-[#1d162b] to-[#2b1723] border border-amber-500/30 shadow-2xl">
        <div className="flex items-center gap-3.5 md:gap-4">
          <div className="p-3.5 rounded-2xl bg-gradient-to-tr from-amber-500 to-rose-500 text-white shadow-lg shadow-amber-500/20 shrink-0">
            <Video className="w-6 h-6 md:w-7 md:h-7" />
          </div>
          <div>
            <div className="flex items-center gap-2 flex-wrap">
              <h2 className="text-lg md:text-xl font-black tracking-tight text-white">Ep Upload by Video</h2>
              <span className="text-[10px] font-mono font-black uppercase px-2.5 py-0.5 rounded-full bg-amber-500/20 text-amber-300 border border-amber-500/30">
                Direct Video Upload + Auto Metadata
              </span>
            </div>
            <p className="text-xs text-gray-400 mt-1 leading-relaxed">
              Auto fills all anime information. Creates background upload tasks and publishes episodes upon video upload completion.
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2 text-xs text-gray-400 bg-white/5 border border-white/10 px-3.5 py-2 rounded-xl w-full md:w-auto justify-between md:justify-start">
          <ShieldAlert className="w-4 h-4 text-emerald-400 shrink-0" />
          <span className="truncate">Backend: <strong className="text-white font-mono">anime-backend-01or.onrender.com</strong></span>
        </div>
      </div>

      {/* Success Notice Banner */}
      {successMsg && (
        <div className="p-4 rounded-2xl bg-emerald-500/10 border border-emerald-500/30 text-emerald-300 text-xs flex items-center justify-between gap-2 shadow-xl animate-fade-in">
          <div className="flex items-center gap-2.5 font-bold">
            <CheckCircle className="w-5 h-5 text-emerald-400 shrink-0" />
            <span>{successMsg}</span>
          </div>
          <button onClick={() => setSuccessMsg(null)} className="text-gray-400 hover:text-white p-1">
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      {/* MAIN FORM CONTAINER (IDENTICAL LAYOUT TO SINGLE EPISODE UPLOAD) */}
      <form onSubmit={handlePublishEpisodeAndStartUpload} className="p-5 md:p-8 rounded-3xl bg-[#131520] border border-white/10 flex flex-col gap-6 shadow-xl">
        <h3 className="font-display font-extrabold text-lg text-white flex items-center gap-2">
          <Upload className="w-5 h-5 text-amber-500" />
          <span>Publish New Episode via Direct Video Upload</span>
        </h3>

        {/* 1. Select Anime Title & Audio Language */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div className="flex flex-col gap-2">
            <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider flex items-center gap-1.5">
              <Film className="w-3.5 h-3.5 text-amber-400" />
              <span>Select Anime Title *</span>
            </label>
            <select
              value={selectedAnimeId}
              onChange={(e) => {
                const targetId = e.target.value;
                setSelectedAnimeId(targetId);
                const found = animeList.find((a) => a.id === targetId);
                if (found) setSelectedAnimeTitle(found.title);
              }}
              className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-gray-200 focus:outline-none focus:border-amber-500 transition-all cursor-pointer min-h-[44px]"
              required
            >
              <option value="">-- Choose Published Anime --</option>
              {animeList.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.title}
                </option>
              ))}
            </select>
          </div>

          <div className="flex flex-col gap-2">
            <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider flex items-center gap-1.5">
              <Languages className="w-3.5 h-3.5 text-amber-400" />
              <span>Audio Language</span>
            </label>
            <select
              value={singleAudioLanguage}
              onChange={(e) => setSingleAudioLanguage(e.target.value)}
              className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500 transition-all cursor-pointer min-h-[44px]"
            >
              {LANGUAGE_OPTIONS.map((l) => (
                <option key={l} value={l}>
                  {l}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* AUTO POPULATED ANIME METADATA CARD SUMMARY */}
        {selectedAnimeObj && (
          <div className="p-4 rounded-2xl bg-[#0d0e12] border border-amber-500/20 flex items-center gap-4 animate-fade-in">
            {selectedAnimeObj.poster && (
              <img
                src={selectedAnimeObj.poster}
                alt={selectedAnimeObj.title}
                className="w-12 h-16 object-cover rounded-xl border border-white/10 shrink-0"
                referrerPolicy="no-referrer"
              />
            )}
            <div className="flex-1 min-w-0 flex flex-col gap-1">
              <div className="flex items-center gap-2">
                <span className="text-[10px] font-mono text-emerald-400 font-bold bg-emerald-500/10 px-2 py-0.5 rounded border border-emerald-500/20 uppercase">
                  Auto-Fetched Metadata
                </span>
                <span className="text-xs font-bold text-gray-400 font-mono truncate">
                  Studio: {selectedAnimeObj.studio || "Anime Studio"}
                </span>
              </div>
              <h4 className="font-extrabold text-sm text-white truncate">{selectedAnimeObj.title}</h4>
              <p className="text-xs text-gray-400 line-clamp-1">{selectedAnimeObj.description}</p>
            </div>
          </div>
        )}

        {/* 2. Admin Manual Season & Episode Control Banner */}
        {(() => {
          const targetAnime = animeList.find((a) => a.id === selectedAnimeId);
          const currentSeasonCount = targetAnime ? getSeasonEpisodeCount(targetAnime.id, seasonNum, catalogEpisodesList) : 12;
          const duplicateEp = catalogEpisodesList.find(
            (ep) =>
              (ep.animeId === selectedAnimeId || ep.animeId === selectedAnimeTitle) &&
              (ep.seasonNumber || 1) === seasonNum &&
              ep.episodeNumber === episodeNum
          );

          return (
            <div className="flex flex-col gap-3">
              <div className="flex items-center justify-between p-3.5 rounded-2xl bg-[#0d0e12] border border-white/10 flex-wrap gap-2">
                <div className="flex items-center gap-2.5">
                  <ListOrdered className="w-4 h-4 text-amber-400 shrink-0" />
                  <div className="flex flex-col">
                    <span className="text-xs font-bold text-white">
                      Admin Manual Season & Episode Assignment
                    </span>
                    <span className="text-[10px] text-gray-400 font-mono">
                      You are in full manual control of Season, Total Episode Count, and Episode Number.
                    </span>
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-xs font-mono font-bold text-amber-400 bg-amber-500/10 px-2.5 py-1 rounded-xl border border-amber-500/20">
                    Season {seasonNum} • {currentSeasonCount} Total Episodes
                  </span>
                </div>
              </div>

              {/* Duplicate Episode Alert Banner */}
              {duplicateEp && (
                <div className="p-4 rounded-2xl bg-amber-500/10 border border-amber-500/30 text-amber-300 text-xs flex items-center justify-between gap-3 animate-fade-in">
                  <div className="flex items-center gap-2.5 font-bold">
                    <ShieldAlert className="w-5 h-5 text-amber-400 shrink-0" />
                    <div>
                      <span>⚠️ Duplicate Episode Alert: Episode {episodeNum} already exists in Season {seasonNum} ({duplicateEp.title})!</span>
                      <p className="text-[10px] text-amber-400/80 font-normal font-mono mt-0.5">
                        Uploading will update the video source / language data for this existing episode slot.
                      </p>
                    </div>
                  </div>
                </div>
              )}
            </div>
          );
        })()}

        {/* 3. Season Number, EP Number, and Episode Title */}
        <div className="grid grid-cols-1 sm:grid-cols-4 gap-4">
          <div className="flex flex-col gap-2">
            <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider flex items-center gap-1">
              <span>Season *</span>
            </label>
            <input
              type="number"
              min={1}
              value={seasonNum}
              onChange={(e) => setSeasonNum(parseInt(e.target.value) || 1)}
              className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500 font-mono min-h-[44px]"
              required
            />
          </div>

          <div className="flex flex-col gap-2">
            <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider flex items-center gap-1">
              <span>EP Number *</span>
            </label>
            <input
              type="number"
              min={1}
              value={episodeNum}
              onChange={(e) => setEpisodeNum(parseInt(e.target.value) || 1)}
              className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500 font-mono min-h-[44px]"
              required
            />
          </div>

          <div className="sm:col-span-2 flex flex-col gap-2">
            <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider">Episode Title</label>
            <input
              type="text"
              placeholder="e.g. Departure (optional)"
              value={episodeTitle}
              onChange={(e) => setEpisodeTitle(e.target.value)}
              className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500 min-h-[44px]"
            />
          </div>
        </div>

        {/* 4. Episode Thumbnail Image */}
        <div className="flex flex-col gap-2">
          <div className="flex items-center justify-between">
            <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider">
              Episode Thumbnail Image
            </label>
            <button
              type="button"
              onClick={() => {
                const target = animeList.find((a) => a.id === selectedAnimeId || a.title === selectedAnimeTitle);
                if (target) {
                  setEpisodeThumbnail(target.banner || target.poster || "");
                }
              }}
              className="text-[11px] font-mono text-amber-400 hover:underline cursor-pointer"
            >
              Use Anime Poster/Banner
            </button>
          </div>
          <div className="flex gap-3 items-center">
            <input
              type="url"
              placeholder="https://images.unsplash.com/... or image URL"
              value={episodeThumbnail}
              onChange={(e) => setEpisodeThumbnail(e.target.value)}
              className="flex-1 px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500 min-h-[44px]"
            />
            {episodeThumbnail ? (
              <img
                src={episodeThumbnail}
                alt="Preview"
                className="w-16 h-10 object-cover rounded-lg border border-white/10 shrink-0"
                loading="lazy"
                referrerPolicy="no-referrer"
              />
            ) : null}
          </div>
        </div>

        {/* VIDEO HOSTING PROVIDER BANNER */}
        <div className="flex flex-col gap-2">
          <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider flex items-center gap-1.5">
            <SlidersHorizontal className="w-3.5 h-3.5 text-amber-400" />
            <span>Video Storage Provider</span>
          </label>
          <div className="p-4 rounded-2xl border border-amber-500 bg-amber-500/10 text-amber-300 flex items-center justify-between shadow-lg shadow-amber-500/5">
            <div className="flex items-center gap-3">
              <div className="p-2.5 rounded-xl bg-amber-500 text-black font-black text-sm">
                ⚡
              </div>
              <div>
                <div className="text-xs font-extrabold text-white flex items-center gap-2">
                  <span>Cloudflare R2 Direct Upload System</span>
                  <span className="px-2 py-0.5 rounded-full text-[9px] font-mono bg-amber-500 text-black font-bold uppercase">Active</span>
                </div>
                <div className="text-[10px] text-gray-400">High-speed R2 worker streaming, zero buffering &amp; direct CDN storage</div>
              </div>
            </div>
            <CheckCircle2 className="w-5 h-5 text-amber-400 shrink-0" />
          </div>
        </div>

        {/* 5. VIDEO FILE PICKER ZONE */}
        <div className="flex flex-col gap-2 bg-[#0d0e12] p-5 rounded-2xl border border-amber-500/30">
          <label className="text-xs font-bold text-amber-400 font-mono uppercase tracking-wider flex items-center justify-between flex-wrap gap-1">
            <span className="flex items-center gap-1.5">
              <FileVideo className="w-4 h-4 text-amber-400" />
              <span>Video File Selection *</span>
            </span>
            <span className="text-[10px] text-gray-400 font-mono font-normal">
              Supported: MP4, MKV, MOV, WEBM, AVI
            </span>
          </label>

          {!selectedFile ? (
            <div
              onDragOver={handleDragOver}
              onDragLeave={handleDragLeave}
              onDrop={handleDrop}
              className={`relative border-2 border-dashed rounded-2xl p-6 md:p-8 flex flex-col items-center justify-center text-center transition-all duration-200 cursor-pointer ${
                isDragOver
                  ? "border-amber-400 bg-amber-500/10 scale-[1.01]"
                  : "border-white/15 bg-[#131520]/60 hover:border-amber-500/50 hover:bg-[#131520]"
              }`}
            >
              <input
                type="file"
                accept=".mp4,.mkv,.mov,.webm,.avi,video/*"
                onChange={handleFileSelect}
                className="absolute inset-0 w-full h-full opacity-0 cursor-pointer"
              />

              <div className="w-12 h-12 md:w-14 md:h-14 rounded-2xl bg-amber-500/10 border border-amber-500/20 flex items-center justify-center text-amber-400 mb-3 shadow-lg">
                <Upload className="w-6 h-6 md:w-7 md:h-7" />
              </div>

              <h4 className="text-sm font-extrabold text-white">
                Drag &amp; Drop video file or click to browse
              </h4>
              <p className="text-xs text-gray-400 mt-1 max-w-sm">
                Direct video stream upload to Mux CDN engine.
              </p>
            </div>
          ) : (
            <div className="p-4 rounded-xl bg-[#131520] border border-amber-500/30 flex flex-col md:flex-row items-center justify-between gap-4">
              <div className="flex items-center gap-3.5 w-full md:w-auto min-w-0">
                <div className="w-10 h-10 rounded-xl bg-amber-500/20 border border-amber-500/30 flex items-center justify-center text-amber-400 shrink-0">
                  <FileVideo className="w-5 h-5" />
                </div>
                <div className="flex flex-col min-w-0">
                  <span className="text-xs md:text-sm font-extrabold text-white truncate max-w-xs sm:max-w-md">
                    {selectedFile.name}
                  </span>
                  <div className="flex items-center gap-2 text-[11px] text-gray-400 font-mono mt-0.5">
                    <span>{formatBytes(selectedFile.size)}</span>
                    <span>&bull;</span>
                    <span className="uppercase text-amber-400 font-bold">
                      {selectedFile.name.split(".").pop()}
                    </span>
                  </div>
                </div>
              </div>

              {/* Local File Controls */}
              <div className="flex items-center gap-2 self-end md:self-auto shrink-0">
                {videoPreviewUrl && (
                  <a
                    href={videoPreviewUrl}
                    target="_blank"
                    rel="noreferrer"
                    className="px-3 py-1.5 rounded-xl bg-white/5 hover:bg-white/10 text-xs font-bold text-gray-300 flex items-center gap-1.5 transition-all border border-white/10"
                  >
                    <Play className="w-3.5 h-3.5 text-amber-400" />
                    <span>Local Preview</span>
                  </a>
                )}

                <button
                  type="button"
                  onClick={handleRemoveFile}
                  className="p-2 rounded-xl text-gray-400 hover:text-rose-400 hover:bg-rose-500/10 transition-all cursor-pointer"
                  title="Change Video File"
                >
                  <Trash2 className="w-4.5 h-4.5" />
                </button>
              </div>
            </div>
          )}

          {selectedFile && (
            <div className="mt-2">
              <AudioTrackAnalyzerUI
                tracks={detectedAudioTracks}
                isAnalyzing={isAnalyzingAudio}
                warnings={audioAnalysisWarning ? [audioAnalysisWarning] : []}
                onUpdateTrackLanguage={handleUpdateTrackLanguage}
                onSetDefaultTrack={handleSetDefaultAudioTrack}
                onReanalyze={handleReanalyzeAudio}
              />
            </div>
          )}

          {fileError && (
            <div className="p-3 rounded-xl bg-rose-500/10 border border-rose-500/20 text-rose-400 text-xs flex items-center gap-2">
              <AlertCircle className="w-4 h-4 shrink-0" />
              <span>{fileError}</span>
            </div>
          )}
        </div>

        {/* 6. Quality Label & Notes */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div className="flex flex-col gap-2">
            <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider">Target Video Quality</label>
            <select
              value={videoQualityLabel}
              onChange={(e) => setVideoQualityLabel(e.target.value)}
              className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500 min-h-[44px]"
            >
              {QUALITY_OPTIONS.map((q) => (
                <option key={q} value={q}>
                  {q}
                </option>
              ))}
            </select>
          </div>

          <div className="flex flex-col gap-2">
            <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider">Notes (Optional)</label>
            <input
              type="text"
              placeholder="e.g. Uncut, Director's Cut"
              value={videoNotes}
              onChange={(e) => setVideoNotes(e.target.value)}
              className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500 min-h-[44px]"
            />
          </div>
        </div>

        {/* 7. Episode Summary / Description */}
        <div className="flex flex-col gap-2">
          <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider">Episode Summary</label>
          <textarea
            placeholder="Summary details for this episode (optional)..."
            rows={2}
            value={episodeDesc}
            onChange={(e) => setEpisodeDesc(e.target.value)}
            className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500 resize-none"
          />
        </div>

        {/* 8. Accordion: Advanced Episode Options (Subtitles & Skip Intro/Outro) */}
        <div className="border border-white/10 rounded-2xl bg-[#0d0e12] overflow-hidden">
          <button
            type="button"
            onClick={() => setShowAdvancedSettings(!showAdvancedSettings)}
            className="w-full p-4 flex items-center justify-between text-left text-xs font-bold text-gray-300 hover:text-white transition-all cursor-pointer bg-white/5"
          >
            <div className="flex items-center gap-2">
              <SlidersHorizontal className="w-4 h-4 text-amber-400" />
              <span>Advanced Subtitle &amp; Skip Intro/Outro Settings</span>
            </div>
            {showAdvancedSettings ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
          </button>

          {showAdvancedSettings && (
            <div className="p-4 md:p-5 flex flex-col gap-4 border-t border-white/10 animate-fade-in">
              <div className="flex flex-col gap-1.5">
                <label className="text-xs font-bold text-gray-300">Subtitle Track URL (.vtt / .srt)</label>
                <input
                  type="url"
                  value={episodeSubtitlesUrl}
                  onChange={(e) => setEpisodeSubtitlesUrl(e.target.value)}
                  placeholder="https://.../subtitles.vtt"
                  className="w-full px-4 py-2.5 bg-[#131520] border border-white/10 rounded-xl text-xs text-white focus:outline-none focus:border-amber-500 min-h-[44px]"
                />
              </div>

              {/* Skip Intro / Outro Timings */}
              <div className="flex flex-col gap-2 pt-2 border-t border-white/10">
                <label className="text-xs font-bold text-amber-300 flex items-center gap-1.5">
                  <Clock className="w-3.5 h-3.5" />
                  <span>Skip Intro / Outro Markers (Seconds)</span>
                </label>

                <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                  <div className="flex flex-col gap-1">
                    <span className="text-[10px] text-gray-400">Intro Start (s)</span>
                    <input
                      type="number"
                      min={0}
                      placeholder="e.g. 90"
                      value={skipIntroStart}
                      onChange={(e) => setSkipIntroStart(e.target.value === "" ? "" : Number(e.target.value))}
                      className="w-full px-3 py-2 bg-[#131520] border border-white/10 rounded-lg text-xs text-white font-mono focus:outline-none focus:border-amber-500"
                    />
                  </div>
                  <div className="flex flex-col gap-1">
                    <span className="text-[10px] text-gray-400">Intro End (s)</span>
                    <input
                      type="number"
                      min={0}
                      placeholder="e.g. 180"
                      value={skipIntroEnd}
                      onChange={(e) => setSkipIntroEnd(e.target.value === "" ? "" : Number(e.target.value))}
                      className="w-full px-3 py-2 bg-[#131520] border border-white/10 rounded-lg text-xs text-white font-mono focus:outline-none focus:border-amber-500"
                    />
                  </div>
                  <div className="flex flex-col gap-1">
                    <span className="text-[10px] text-gray-400">Outro Start (s)</span>
                    <input
                      type="number"
                      min={0}
                      placeholder="e.g. 1320"
                      value={skipOutroStart}
                      onChange={(e) => setSkipOutroStart(e.target.value === "" ? "" : Number(e.target.value))}
                      className="w-full px-3 py-2 bg-[#131520] border border-white/10 rounded-lg text-xs text-white font-mono focus:outline-none focus:border-amber-500"
                    />
                  </div>
                  <div className="flex flex-col gap-1">
                    <span className="text-[10px] text-gray-400">Outro End (s)</span>
                    <input
                      type="number"
                      min={0}
                      placeholder="e.g. 1410"
                      value={skipOutroEnd}
                      onChange={(e) => setSkipOutroEnd(e.target.value === "" ? "" : Number(e.target.value))}
                      className="w-full px-3 py-2 bg-[#131520] border border-white/10 rounded-lg text-xs text-white font-mono focus:outline-none focus:border-amber-500"
                    />
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* 9. Release Date & Visibility check */}
        <div className="grid grid-cols-2 gap-4">
          <div className="flex flex-col gap-2">
            <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider">Release Date</label>
            <input
              type="date"
              value={episodeReleaseDate}
              onChange={(e) => setEpisodeReleaseDate(e.target.value)}
              className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none min-h-[44px]"
            />
          </div>

          <div className="flex items-center gap-3 mt-6">
            <input
              type="checkbox"
              id="episodeIsVisibleCheckVideo"
              checked={episodeIsVisible}
              onChange={(e) => setEpisodeIsVisible(e.target.checked)}
              className="w-4 h-4 accent-amber-500 rounded cursor-pointer"
            />
            <label
              htmlFor="episodeIsVisibleCheckVideo"
              className="text-xs font-bold text-gray-300 font-mono uppercase tracking-wider cursor-pointer select-none"
            >
              Is Visible to Users
            </label>
          </div>
        </div>

        {/* PRIMARY PUBLISH CTA BUTTON */}
        <div className="flex gap-3 mt-2">
          <button
            type="submit"
            disabled={!selectedFile}
            className="flex-1 py-3.5 rounded-xl bg-gradient-to-r from-amber-500 to-rose-500 hover:from-amber-400 hover:to-rose-400 text-white font-extrabold text-sm uppercase tracking-widest transition-all shadow-xl disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer flex items-center justify-center gap-2 min-h-[48px]"
          >
            <Upload className="w-5 h-5" />
            <span>Publish Episode &amp; Start Video Upload</span>
          </button>
        </div>
      </form>

      {/* BOTTOM UPLOAD MANAGER PANEL (REQUIREMENT 3: STAYS AT BOTTOM & SUPPORTS MULTIPLE UPLOADS) */}
      {uploadQueue.length > 0 && isQueueManagerOpen && (
        <div className="fixed bottom-0 left-0 right-0 z-50 p-4 md:p-5 bg-[#0c0d14]/95 backdrop-blur-2xl border-t border-amber-500/30 shadow-[0_-10px_40px_rgba(0,0,0,0.8)] transition-all animate-fade-in max-h-[60vh] flex flex-col">
          <div className="max-w-5xl mx-auto w-full flex flex-col gap-3">
            
            {/* Header control bar */}
            <div className="flex items-center justify-between border-b border-white/10 pb-2.5 flex-wrap gap-2 shrink-0">
              <div className="flex items-center gap-3">
                <div className="p-2 rounded-xl bg-amber-500/20 text-amber-400 border border-amber-500/30 shrink-0">
                  <ListOrdered className="w-5 h-5" />
                </div>
                <div>
                  <h3 className="font-display font-black text-sm md:text-base text-white flex items-center gap-2">
                    <span>Upload Manager</span>
                    <span className="text-[10px] font-mono font-black uppercase px-2.5 py-0.5 rounded-full bg-amber-500/20 text-amber-300 border border-amber-500/30">
                      {uploadQueue.filter((q) => q.stage !== "Published" && q.stage !== "Failed").length} Active
                    </span>
                  </h3>
                </div>
              </div>

              <div className="flex items-center gap-2">
                {uploadQueue.some((q) => q.stage === "Published") && (
                  <button
                    onClick={handleClearCompletedQueue}
                    className="text-[11px] font-extrabold font-mono text-gray-400 hover:text-white bg-white/5 hover:bg-white/10 px-3 py-1.5 rounded-xl border border-white/10 transition-all cursor-pointer"
                  >
                    Clear Completed
                  </button>
                )}
                <button
                  onClick={() => setIsQueueManagerMinimized(!isQueueManagerMinimized)}
                  className="p-1.5 rounded-xl bg-white/5 hover:bg-white/10 text-gray-300 hover:text-white border border-white/10 transition-all cursor-pointer"
                  title={isQueueManagerMinimized ? "Expand Manager" : "Minimize Manager"}
                >
                  {isQueueManagerMinimized ? <Maximize2 className="w-4 h-4" /> : <Minimize2 className="w-4 h-4" />}
                </button>
                <button
                  onClick={() => setIsQueueManagerOpen(false)}
                  className="p-1.5 rounded-xl bg-white/5 hover:bg-white/10 text-gray-400 hover:text-white transition-all cursor-pointer"
                  title="Close Manager Panel"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            </div>

            {/* Queue Cards Scrollable Body */}
            {!isQueueManagerMinimized && (
              <div className="flex flex-col gap-3 overflow-y-auto pr-1 max-h-[42vh] custom-scrollbar">
                {uploadQueue.map((item) => {
                  const isPreparing = item.stage === "Creating Upload" || item.stage === "Connecting";
                  const isUploading = item.stage === "Uploading";
                  const isPublishing = item.stage === "Publishing";
                  const isPublished = item.stage === "Published";
                  const isFailed = item.stage === "Failed";
                  const isActive = isPreparing || isUploading || isPublishing;

                  return (
                    <div
                      key={item.id}
                      className={`p-4 rounded-2xl border flex flex-col gap-3 transition-all shadow-lg ${
                        isPublished
                          ? "bg-emerald-500/10 border-emerald-500/40"
                          : isFailed
                          ? "bg-rose-500/10 border-rose-500/40"
                          : "bg-[#141624] border-amber-500/40"
                      }`}
                    >
                      {/* CARD STATUS HEADER */}
                      <div className="flex items-center justify-between border-b border-white/10 pb-2">
                        <div className="flex items-center gap-2">
                          {isPreparing && (
                            <span className="text-xs font-black text-amber-400 flex items-center gap-1.5 animate-pulse">
                              <RefreshCw className="w-4 h-4 animate-spin text-amber-400" />
                              <span>Uploading...</span>
                            </span>
                          )}
                          {isUploading && (
                            <span className="text-xs font-black text-amber-400 flex items-center gap-1.5 animate-pulse">
                              <Upload className="w-4 h-4 animate-bounce text-amber-400" />
                              <span>Uploading ({item.progress}%)</span>
                            </span>
                          )}
                          {isPublishing && (
                            <span className="text-xs font-black text-amber-400 flex items-center gap-1.5 animate-pulse">
                              <RefreshCw className="w-4 h-4 animate-spin text-amber-400" />
                              <span>Publishing...</span>
                            </span>
                          )}
                          {isPublished && (
                            <span className="text-xs font-black text-emerald-400 flex items-center gap-1.5">
                              <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                              <span>Published ✅</span>
                            </span>
                          )}
                          {isFailed && (
                            <span className="text-xs font-black text-rose-400 flex items-center gap-1.5">
                              <AlertCircle className="w-4 h-4" />
                              <span>Upload Failed ❌</span>
                            </span>
                          )}
                        </div>

                        {/* Action buttons */}
                        <div className="flex items-center gap-2">
                          {isActive && (
                            <button
                              onClick={() => handleCancelQueueItem(item.id)}
                              className="px-2.5 py-1 rounded-lg bg-rose-500/20 hover:bg-rose-500/30 text-rose-300 font-extrabold text-[11px] border border-rose-500/30 transition-all cursor-pointer flex items-center gap-1"
                              title="Cancel Upload"
                            >
                              <StopCircle className="w-3.5 h-3.5" />
                              <span>Cancel</span>
                            </button>
                          )}

                          {isFailed && (
                            <button
                              onClick={() => handleRetryQueueItem(item.id)}
                              className="px-3 py-1 rounded-xl bg-rose-500 hover:bg-rose-400 text-white font-black text-xs flex items-center gap-1 transition-all cursor-pointer shadow-lg"
                            >
                              <RefreshCw className="w-3.5 h-3.5" />
                              <span>Retry Upload</span>
                            </button>
                          )}

                          {(isPublished || isFailed) && (
                            <button
                              onClick={() => handleRemoveQueueItem(item.id)}
                              className="p-1 rounded-lg text-gray-400 hover:text-white transition-all cursor-pointer"
                              title="Remove"
                            >
                              <X className="w-4 h-4" />
                            </button>
                          )}
                        </div>
                      </div>

                      {/* CARD DETAILS GRID */}
                      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-xs font-mono">
                        <div>
                          <span className="text-gray-400">Anime : </span>
                          <strong className="text-white font-sans">{item.animeTitle}</strong>
                        </div>
                        <div>
                          <span className="text-gray-400">Episode : </span>
                          <strong className="text-amber-400">{item.episodeNum}</strong>
                        </div>
                        <div>
                          <span className="text-gray-400">Language : </span>
                          <strong className="text-white">{item.language}</strong>
                        </div>
                        <div>
                          <span className="text-gray-400">Status : </span>
                          <strong className={isPublished ? "text-emerald-400" : isFailed ? "text-rose-400" : "text-amber-400"}>
                            {isPublished ? "Published" : isFailed ? "Upload Failed" : isPublishing ? "Publishing..." : `Uploading (${item.progress}%)`}
                          </strong>
                        </div>

                        {isUploading && (
                          <>
                            <div>
                              <span className="text-gray-400">Progress : </span>
                              <strong className="text-amber-400">{item.progress}%</strong>
                            </div>
                            <div>
                              <span className="text-gray-400">Speed : </span>
                              <strong className="text-emerald-400">{item.uploadSpeed}</strong>
                            </div>
                            <div>
                              <span className="text-gray-400">Size : </span>
                              <strong className="text-gray-200">
                                {formatBytes(item.uploadedBytes)} / {formatBytes(item.totalBytes)}
                              </strong>
                            </div>
                            <div>
                              <span className="text-gray-400">Remaining : </span>
                              <strong className="text-blue-400">{item.remainingTime}</strong>
                            </div>
                          </>
                        )}

                        {isPublished && item.playbackId && (
                          <div className="col-span-2 sm:col-span-4 flex items-center gap-1.5 text-[11px] bg-emerald-500/10 p-2 rounded-xl border border-emerald-500/20">
                            <span className="text-emerald-300 font-bold">Mux Playback ID:</span>
                            <span className="text-white font-mono bg-black/50 px-2.5 py-1 rounded-lg text-[11px] border border-white/10 select-all">
                              {item.playbackId}
                            </span>
                          </div>
                        )}
                      </div>

                      {/* PROGRESS BAR */}
                      {(isUploading || isPreparing || isPublishing) && (
                        <div className="w-full h-2 bg-white/10 rounded-full overflow-hidden border border-white/10 p-0.5 mt-1">
                          <div
                            className={`h-full rounded-full transition-all duration-300 ${
                              isPublished
                                ? "bg-emerald-400"
                                : "bg-gradient-to-r from-amber-500 via-rose-500 to-emerald-400"
                            }`}
                            style={{ width: `${item.progress}%` }}
                          />
                        </div>
                      )}

                      {/* ERROR DETAILS IF FAILED */}
                      {isFailed && (
                        <div className="p-3.5 rounded-xl bg-rose-950/40 border border-rose-500/30 text-xs font-mono space-y-2.5 text-rose-200 mt-2">
                          <div className="flex items-center justify-between border-b border-rose-500/20 pb-2">
                            <p className="font-bold text-rose-300 flex items-center gap-1.5 text-xs">
                              <AlertCircle className="w-4 h-4 text-rose-400 shrink-0" />
                              <span>Upload Failed ❌ [{item.errorClassification || "ERROR"}]</span>
                            </p>
                            <span className="px-2 py-0.5 rounded bg-rose-500/30 text-rose-200 font-extrabold text-[10px]">
                              {item.errorCode || `HTTP_${item.httpStatus || "ERR"}`}
                            </span>
                          </div>

                          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-[11px] text-gray-300">
                            <div><span className="text-gray-400 font-semibold">Worker Endpoint:</span> <span className="font-mono text-cyan-300 truncate inline-block max-w-[180px] align-bottom" title={CLOUDFLARE_WORKER_BASE}>{CLOUDFLARE_WORKER_BASE}</span></div>
                            <div><span className="text-gray-400 font-semibold">Current Stage:</span> <span className="font-bold text-amber-300">{item.errorStage || item.stage}</span></div>
                            <div><span className="text-gray-400 font-semibold">HTTP Status:</span> <span className="font-bold text-rose-300">{item.httpStatus || "N/A"}</span></div>
                            <div><span className="text-gray-400 font-semibold">Method:</span> <span className="font-mono text-gray-200">{item.httpMethod || "PUT"}</span></div>
                            <div><span className="text-gray-400 font-semibold">Current Part:</span> <span className="font-mono text-amber-200">{item.currentPart ? `${item.currentPart} of ${item.totalParts || "?"}` : "N/A"}</span></div>
                            <div><span className="text-gray-400 font-semibold">Chunk Size:</span> <span className="font-mono text-gray-200">64 MiB</span></div>
                            <div><span className="text-gray-400 font-semibold">Attempt:</span> <span className="font-mono text-gray-200">{item.attempt ? `${item.attempt}/3` : "1/3"}</span></div>
                            <div><span className="text-gray-400 font-semibold">Classification:</span> <span className="font-bold text-rose-300">{item.errorClassification || "UNKNOWN"}</span></div>
                            <div><span className="text-gray-400 font-semibold">uploadId:</span> <span className="font-mono text-gray-200 truncate inline-block max-w-[180px] align-bottom" title={item.uploadId || "N/A"}>{item.uploadId || "N/A"}</span></div>
                            <div><span className="text-gray-400 font-semibold">objectKey:</span> <span className="font-mono text-gray-200 truncate inline-block max-w-[180px] align-bottom" title={item.storageKey || "N/A"}>{item.storageKey || "N/A"}</span></div>
                          </div>

                          <div className="pt-1.5 border-t border-rose-500/20">
                            <p className="text-gray-400 font-semibold mb-1">Message:</p>
                            <p className="text-rose-200 font-sans font-medium text-xs bg-black/40 p-2.5 rounded-lg border border-rose-500/20 leading-relaxed">
                              {item.errorMessage || "Unknown upload failure."}
                            </p>
                          </div>

                          {item.serverResponseBody && item.serverResponseBody !== item.errorMessage && (
                            <div className="pt-1">
                              <p className="text-gray-400 font-semibold mb-1">Server Response:</p>
                              <pre className="p-2.5 rounded-lg bg-black/60 text-[10px] text-gray-300 overflow-x-auto whitespace-pre-wrap font-mono border border-white/10 max-h-32">
                                {item.serverResponseBody}
                              </pre>
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
