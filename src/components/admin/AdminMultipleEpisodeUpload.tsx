import React, { useState, useEffect, useRef, useCallback } from "react";
import { useApp } from "../../context/AppContext";
import { Anime, Episode, SupportedLanguage, EpisodeLanguageData } from "../../types";
import { saveEpisode, saveOrMergeEpisodeLanguage, fetchEpisodesForAnime, addNotification } from "../../services/dbService";
import { fetchOfficialEpisodeMetadata, parseEpisodeFilename } from "../../services/animeApiService";
import { analyzeVideoAudioTracks, DetectedMediaAudioTrack } from "../../utils/mediaInspector";
import { AudioTrackAnalyzerUI } from "./AudioTrackAnalyzerUI";
import {
  uploadToCloudflareR2,
  generateR2ObjectKey,
  verifyCloudflareVideo,
  finalizeCloudflareVideo,
  deleteFromCloudflareR2,
  buildCloudflareVideoUrl,
  CLOUDFLARE_WORKER_BASE
} from "../../services/cloudflareService";
import {
  Upload,
  Video,
  FileVideo,
  CheckCircle2,
  AlertCircle,
  X,
  XCircle,
  RefreshCw,
  Film,
  Clock,
  ShieldAlert,
  Play,
  Languages,
  Trash2,
  SlidersHorizontal,
  ChevronDown,
  ChevronUp,
  ListOrdered,
  CheckCircle,
  Pause,
  RotateCcw,
  Plus,
  Image as ImageIcon,
  AlertTriangle,
  Sparkles,
  Layers,
  ArrowRight,
  Wifi,
  WifiOff,
  Check,
  FileText,
  Copy,
  Info
} from "lucide-react";

export interface MultipleEpisodeItem {
  id: string; // unique batch item id
  file: File;
  filename: string;
  fileSize: number;
  formattedSize: string;
  
  // Scoped episode numbering
  seasonNum: number;
  episodeNumber: number;
  hasDetectedNumber: boolean;
  isDuplicate: boolean;
  duplicateWarning?: string;
  
  // Metadata
  title: string;
  officialTitleFetched: boolean;
  isFetchingTitle: boolean;
  description: string;
  releaseDate: string;
  duration: string;
  
  // Thumbnails / Images
  images: {
    id: string;
    url: string; // Object URL or remote URL
    file?: File;
    isPrimary: boolean;
  }[];
  primaryThumbnailUrl?: string;
  
  // Audio metadata
  audioLanguages: SupportedLanguage[];
  defaultLanguage: SupportedLanguage;
  quality: string;
  notes: string;
  
  // Video Source Provider
  provider: "cloudflare";
  
  // Upload & Verification State
  stage: string;
  progressPercent: number;
  uploadedBytes: number;
  totalBytes: number;
  uploadSpeed: string;
  remainingTime: string;
  errorMessage: string | null;
  errorDetails: string | null;
  
  // Structured Error Reporting Fields
  errorStage?: string;
  httpStatus?: number | string;
  httpMethod?: string;
  endpoint?: string;
  serverResponseBody?: string;
  errorCode?: string | null;
  errorClassification?: string;
  uploadId?: string | null;
  objectKey?: string | null;
  currentPart?: number;
  totalParts?: number;
  attempt?: number;
  
  // Upload references
  uploadController?: any | null;
  cloudflareStorageKey?: string;
  tmpStorageKey?: string;
  finalVideoUrl?: string;
  
  // Visibility
  isVisible: boolean;

  // Audio track detection
  detectedAudioTracks?: DetectedMediaAudioTrack[];
  isAnalyzingAudio?: boolean;
  audioAnalysisWarning?: string;

  // DB save retry flag
  canRetryDbSave?: boolean;
}

const ALLOWED_VIDEO_EXTENSIONS = ["mp4", "mkv", "mov", "webm", "avi"];
const ALLOWED_IMAGE_EXTENSIONS = ["jpg", "jpeg", "png", "webp", "gif"];

const LANGUAGE_OPTIONS: SupportedLanguage[] = [
  "Japanese",
  "English",
  "Hindi",
  "Bangla",
  "Spanish",
  "Subbed",
  "Dubbed"
];

const QUALITY_OPTIONS = [
  "1080p HD",
  "720p HD",
  "480p SD",
  "4K Ultra HD",
  "Auto Quality"
];

function formatBytes(bytes: number, decimals = 2): string {
  if (bytes === 0) return "0 Bytes";
  const k = 1024;
  const dm = decimals < 0 ? 0 : decimals;
  const sizes = ["Bytes", "KB", "MB", "GB", "TB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + " " + sizes[i];
}

interface AdminMultipleEpisodeUploadProps {
  allEpisodesList: Episode[];
  refreshAllEpisodes: () => Promise<void>;
}

export const AdminMultipleEpisodeUpload: React.FC<AdminMultipleEpisodeUploadProps> = ({
  allEpisodesList,
  refreshAllEpisodes
}) => {
  const { animeList, refreshAnime } = useApp();

  // Selection state
  const [selectedAnimeId, setSelectedAnimeId] = useState<string>("");
  const [seasonNum, setSeasonNum] = useState<number>(1);
  const [sourceProvider] = useState<"cloudflare">("cloudflare");

  // Batch queue items state
  const [batchItems, setBatchItems] = useState<MultipleEpisodeItem[]>([]);
  const batchItemsRef = useRef<MultipleEpisodeItem[]>(batchItems);
  batchItemsRef.current = batchItems;

  // Track item IDs currently processing to prevent double-triggering
  const activeUploadsSetRef = useRef<Set<string>>(new Set());

  // Active Upload Concurrency state
  const [maxConcurrency, setMaxConcurrency] = useState<number>(3); // 1 to 5 adaptive
  const [isQueuePaused, setIsQueuePaused] = useState<boolean>(false);
  const [networkSpeedMbps, setNetworkSpeedMbps] = useState<number>(10); // estimated Mbps

  // UI View Tab inside Multiple Upload component
  const [activeStageTab, setActiveStageTab] = useState<"setup" | "review" | "queue" | "summary">("setup");
  
  // Status message
  const [statusMessage, setStatusMessage] = useState<string | null>(null);

  // Drag and drop hover state
  const [isDraggingOver, setIsDraggingOver] = useState<boolean>(false);

  // Selected anime details
  const selectedAnime = animeList.find(a => a.id === selectedAnimeId);

  // Auto-fill selectedAnimeId if empty
  useEffect(() => {
    if (animeList.length > 0 && !selectedAnimeId) {
      setSelectedAnimeId(animeList[0].id);
    }
  }, [animeList, selectedAnimeId]);

  // Calculate next available episode number for selectedAnimeId + seasonNum
  const getNextAvailableEpisodeNum = useCallback((animeId: string, season: number, currentBatch: MultipleEpisodeItem[] = []): number => {
    if (!animeId) return 1;
    
    // Existing catalog episodes for this anime and season
    const catalogEpsInSeason = allEpisodesList.filter(
      ep => (ep.animeId === animeId || (ep as any).anime_id === animeId) && (ep.seasonNumber || 1) === season
    );

    const highestCatalogEp = catalogEpsInSeason.reduce((max, ep) => {
      const num = ep.episodeNumber || (ep as any).number || 0;
      return num > max ? num : max;
    }, 0);

    // Highest in current batch items for same season
    const highestBatchEp = currentBatch.reduce((max, item) => {
      if (item.seasonNum === season) {
        return item.episodeNumber > max ? item.episodeNumber : max;
      }
      return max;
    }, 0);

    return Math.max(highestCatalogEp, highestBatchEp) + 1;
  }, [allEpisodesList]);

  // Check if an episode number is a duplicate
  const checkIsDuplicate = useCallback((animeId: string, season: number, epNum: number, itemId: string, currentBatch: MultipleEpisodeItem[]): { isDuplicate: boolean; warning?: string } => {
    if (!animeId) return { isDuplicate: false };

    // 1. Check database catalog
    const dbMatch = allEpisodesList.find(
      ep => (ep.animeId === animeId || (ep as any).anime_id === animeId) &&
            (ep.seasonNumber || 1) === season &&
            ep.episodeNumber === epNum
    );

    if (dbMatch) {
      return {
        isDuplicate: true,
        warning: `Episode ${epNum} already exists in database for Season ${season} ("${dbMatch.title}")`
      };
    }

    // 2. Check current batch items
    const batchMatch = currentBatch.find(
      item => item.id !== itemId && item.seasonNum === season && item.episodeNumber === epNum
    );

    if (batchMatch) {
      return {
        isDuplicate: true,
        warning: `Episode ${epNum} is duplicated in another file in this batch ("${batchMatch.filename}")`
      };
    }

    return { isDuplicate: false };
  }, [allEpisodesList]);

  // Handle adding raw video files to the batch
  const handleAddVideoFiles = async (files: FileList | File[]) => {
    if (!selectedAnimeId) {
      alert("Please select an Anime first.");
      return;
    }

    const newItems: MultipleEpisodeItem[] = [];
    const fileArray = Array.from(files);

    let runningBatch = [...batchItemsRef.current];

    for (let i = 0; i < fileArray.length; i++) {
      const file = fileArray[i];
      const ext = file.name.split(".").pop()?.toLowerCase() || "";

      if (!ALLOWED_VIDEO_EXTENSIONS.includes(ext) && !file.type.startsWith("video/")) {
        alert(`File "${file.name}" is not a supported video format. Supported formats: MP4, MKV, MOV, WEBM, AVI.`);
        continue;
      }

      // Parse filename for episode number
      const parsed = parseEpisodeFilename(file.name);
      
      // Determine episode number logic
      let epNum = parsed.episodeNumber;
      let hasDetected = true;

      // Check if filename contained a clear episode number match
      const nameWithoutExt = file.name.replace(/\.[^/.]+$/, "");
      const hasClearEpNumber = /(?:E|Ep|Episode|#|S\d+E)\s*\d+/i.test(nameWithoutExt) || /\b\d{1,4}\b/.test(nameWithoutExt);

      if (!hasClearEpNumber) {
        hasDetected = false;
        // Auto assign sequential next available number
        epNum = getNextAvailableEpisodeNum(selectedAnimeId, seasonNum, runningBatch);
      }

      const itemId = `batch_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;

      // Check duplicate status
      const dupCheck = checkIsDuplicate(selectedAnimeId, seasonNum, epNum, itemId, runningBatch);

      const newItem: MultipleEpisodeItem = {
        id: itemId,
        file,
        filename: file.name,
        fileSize: file.size,
        formattedSize: formatBytes(file.size),
        seasonNum,
        episodeNumber: epNum,
        hasDetectedNumber: hasDetected,
        isDuplicate: dupCheck.isDuplicate,
        duplicateWarning: dupCheck.warning,
        title: `Episode ${epNum}`,
        officialTitleFetched: false,
        isFetchingTitle: false,
        description: `Watch ${selectedAnime?.title || "Anime"} Season ${seasonNum} Episode ${epNum} in HD.`,
        releaseDate: new Date().toISOString().split("T")[0],
        duration: "24 min",
        images: [],
        primaryThumbnailUrl: selectedAnime?.banner || selectedAnime?.poster || "",
        audioLanguages: [parsed.language === "Japanese" ? "Japanese" : parsed.language as SupportedLanguage],
        defaultLanguage: parsed.language === "Japanese" ? "Japanese" : parsed.language as SupportedLanguage,
        quality: parsed.quality || "1080p HD",
        notes: "",
        provider: sourceProvider,
        stage: "SELECTED",
        progressPercent: 0,
        uploadedBytes: 0,
        totalBytes: file.size,
        uploadSpeed: "0 KB/s",
        remainingTime: "Pending",
        errorMessage: null,
        errorDetails: null,
        isVisible: true
      };

      newItems.push(newItem);
      runningBatch.push(newItem);
    }

    if (newItems.length === 0) return;

    // Append to state
    setBatchItems(prev => [...prev, ...newItems]);

    // Fetch official titles and analyze audio tracks asynchronously for each item
    for (const item of newItems) {
      fetchTitleForItem(item.id, selectedAnimeId, seasonNum, item.episodeNumber);
      analyzeAudioForItem(item.id, item.file);
    }
  };

  // Helper to analyze video audio tracks for a single batch item
  const analyzeAudioForItem = async (itemId: string, file: File) => {
    setBatchItems(prev =>
      prev.map(item => item.id === itemId ? { ...item, isAnalyzingAudio: true } : item)
    );

    try {
      const res = await analyzeVideoAudioTracks(file);
      setBatchItems(prev =>
        prev.map(item =>
          item.id === itemId
            ? {
                ...item,
                detectedAudioTracks: res.tracks,
                isAnalyzingAudio: false,
                audioAnalysisWarning: res.warnings && res.warnings.length > 0 ? res.warnings.join(" ") : undefined
              }
            : item
        )
      );
    } catch (err) {
      setBatchItems(prev =>
        prev.map(item =>
          item.id === itemId
            ? { ...item, isAnalyzingAudio: false, audioAnalysisWarning: "Failed to analyze audio tracks in video file." }
            : item
        )
      );
    }
  };

  const handleUpdateItemTrackLanguage = (itemId: string, trackIndex: number, newLang: string) => {
    setBatchItems(prev =>
      prev.map(item => {
        if (item.id !== itemId) return item;
        const updatedTracks = (item.detectedAudioTracks || []).map(t =>
          t.trackIndex === trackIndex
            ? { ...t, language: newLang, label: `${newLang} (${t.channelLayout})`, isUserModified: true }
            : t
        );
        return { ...item, detectedAudioTracks: updatedTracks };
      })
    );
  };

  const handleSetItemDefaultTrack = (itemId: string, trackIndex: number) => {
    setBatchItems(prev =>
      prev.map(item => {
        if (item.id !== itemId) return item;
        const updatedTracks = (item.detectedAudioTracks || []).map(t => ({
          ...t,
          isDefault: t.trackIndex === trackIndex
        }));
        return { ...item, detectedAudioTracks: updatedTracks };
      })
    );
  };

  const handleReanalyzeItemAudio = (itemId: string) => {
    const item = batchItems.find(i => i.id === itemId);
    if (!item || !item.file) return;
    analyzeAudioForItem(itemId, item.file);
  };

  // Helper to fetch official episode metadata for a single item
  const fetchTitleForItem = async (itemId: string, animeId: string, season: number, epNum: number) => {
    const targetAnime = animeList.find(a => a.id === animeId);
    if (!targetAnime) return;

    setBatchItems(prev =>
      prev.map(item => item.id === itemId ? { ...item, isFetchingTitle: true } : item)
    );

    try {
      const meta = await fetchOfficialEpisodeMetadata(
        targetAnime.title || targetAnime.englishTitle || "Anime",
        epNum,
        season,
        targetAnime.anilistMediaId
      );

      if (meta && meta.title && !/^Episode\s*\d+$/i.test(meta.title)) {
        setBatchItems(prev =>
          prev.map(item => {
            if (item.id === itemId) {
              return {
                ...item,
                title: meta.title,
                officialTitleFetched: true,
                isFetchingTitle: false,
                description: meta.description || item.description,
                primaryThumbnailUrl: meta.thumbnail || item.primaryThumbnailUrl
              };
            }
            return item;
          })
        );
      } else {
        setBatchItems(prev =>
          prev.map(item => item.id === itemId ? { ...item, isFetchingTitle: false } : item)
        );
      }
    } catch (err) {
      setBatchItems(prev =>
        prev.map(item => item.id === itemId ? { ...item, isFetchingTitle: false } : item)
      );
    }
  };

  // Re-validate duplicates whenever season or episode numbers change
  const revalidateBatch = useCallback(() => {
    setBatchItems(prev => {
      return prev.map(item => {
        const dupCheck = checkIsDuplicate(selectedAnimeId, item.seasonNum, item.episodeNumber, item.id, prev);
        return {
          ...item,
          isDuplicate: dupCheck.isDuplicate,
          duplicateWarning: dupCheck.warning
        };
      });
    });
  }, [selectedAnimeId, checkIsDuplicate]);

  // Trigger revalidation when seasonNum changes
  useEffect(() => {
    revalidateBatch();
  }, [seasonNum, revalidateBatch]);

  // Handle manual editing of Episode Number for a batch item
  const handleUpdateEpisodeNumber = (itemId: string, newEpNum: number) => {
    setBatchItems(prev => {
      const updated = prev.map(item => {
        if (item.id === itemId) {
          return {
            ...item,
            episodeNumber: newEpNum,
            hasDetectedNumber: true,
            title: item.officialTitleFetched ? item.title : `Episode ${newEpNum}`
          };
        }
        return item;
      });

      // Recheck duplicates across all items
      return updated.map(item => {
        const dupCheck = checkIsDuplicate(selectedAnimeId, item.seasonNum, item.episodeNumber, item.id, updated);
        return {
          ...item,
          isDuplicate: dupCheck.isDuplicate,
          duplicateWarning: dupCheck.warning
        };
      });
    });
  };

  // Handle uploading image(s) for a specific episode card
  const handleUploadImagesForCard = (itemId: string, files: FileList | File[]) => {
    const fileArray = Array.from(files);
    const validImages: { id: string; url: string; file: File; isPrimary: boolean }[] = [];

    for (const f of fileArray) {
      const ext = f.name.split(".").pop()?.toLowerCase() || "";
      if (!ALLOWED_IMAGE_EXTENSIONS.includes(ext) && !f.type.startsWith("image/")) {
        alert(`File "${f.name}" is not a valid image format. Supported: JPG, PNG, WEBP, GIF.`);
        continue;
      }
      if (f.size > 10 * 1024 * 1024) {
        alert(`Image "${f.name}" exceeds maximum allowed size of 10 MB.`);
        continue;
      }

      const objectUrl = URL.createObjectURL(f);
      validImages.push({
        id: `img_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
        url: objectUrl,
        file: f,
        isPrimary: false
      });
    }

    if (validImages.length === 0) return;

    setBatchItems(prev =>
      prev.map(item => {
        if (item.id === itemId) {
          const combinedImages = [...item.images, ...validImages];
          // If no primary exists, make first image primary
          const hasPrimary = combinedImages.some(img => img.isPrimary);
          if (!hasPrimary && combinedImages.length > 0) {
            combinedImages[0].isPrimary = true;
          }
          const primaryImg = combinedImages.find(img => img.isPrimary);
          return {
            ...item,
            images: combinedImages,
            primaryThumbnailUrl: primaryImg ? primaryImg.url : item.primaryThumbnailUrl
          };
        }
        return item;
      })
    );
  };

  // Set an image as primary thumbnail
  const handleSetPrimaryImage = (itemId: string, imageId: string) => {
    setBatchItems(prev =>
      prev.map(item => {
        if (item.id === itemId) {
          const updatedImages = item.images.map(img => ({
            ...img,
            isPrimary: img.id === imageId
          }));
          const primaryImg = updatedImages.find(img => img.isPrimary);
          return {
            ...item,
            images: updatedImages,
            primaryThumbnailUrl: primaryImg ? primaryImg.url : item.primaryThumbnailUrl
          };
        }
        return item;
      })
    );
  };

  // Remove an image from an episode card
  const handleRemoveImageFromCard = (itemId: string, imageId: string) => {
    setBatchItems(prev =>
      prev.map(item => {
        if (item.id === itemId) {
          const targetImg = item.images.find(img => img.id === imageId);
          if (targetImg && targetImg.url.startsWith("blob:")) {
            URL.revokeObjectURL(targetImg.url);
          }
          const filteredImages = item.images.filter(img => img.id !== imageId);
          // If removed primary, reassign primary
          let primaryImg = filteredImages.find(img => img.isPrimary);
          if (!primaryImg && filteredImages.length > 0) {
            filteredImages[0].isPrimary = true;
            primaryImg = filteredImages[0];
          }
          return {
            ...item,
            images: filteredImages,
            primaryThumbnailUrl: primaryImg ? primaryImg.url : (selectedAnime?.banner || selectedAnime?.poster || "")
          };
        }
        return item;
      })
    );
  };

  // Remove individual file from batch
  const handleRemoveBatchItem = (itemId: string) => {
    const item = batchItems.find(i => i.id === itemId);
    if (item?.uploadController) {
      try { item.uploadController.abort(); } catch (_) {}
    }
    if (item?.provider === "cloudflare" && item.tmpStorageKey) {
      deleteFromCloudflareR2(item.tmpStorageKey).catch(() => {});
    }
    // Clean up preview object URLs
    item?.images.forEach(img => {
      if (img.url.startsWith("blob:")) URL.revokeObjectURL(img.url);
    });

    setBatchItems(prev => prev.filter(i => i.id !== itemId));
    setTimeout(() => revalidateBatch(), 50);
  };

  // Clear all items in batch
  const handleClearAllBatchItems = () => {
    batchItems.forEach(item => {
      if (item.uploadController) {
        try { item.uploadController.abort(); } catch (_) {}
      }
      if (item.provider === "cloudflare" && item.tmpStorageKey) {
        deleteFromCloudflareR2(item.tmpStorageKey).catch(() => {});
      }
      item.images.forEach(img => {
        if (img.url.startsWith("blob:")) URL.revokeObjectURL(img.url);
      });
    });
    setBatchItems([]);
  };

  // Single item retry handler
  const handleRetryItem = (itemId: string) => {
    activeUploadsSetRef.current.delete(itemId);
    setBatchItems(prev =>
      prev.map(item =>
        item.id === itemId
          ? {
              ...item,
              stage: "QUEUED",
              progressPercent: 0,
              uploadedBytes: 0,
              uploadSpeed: "0 KB/s",
              remainingTime: "Queued...",
              errorMessage: null,
              errorDetails: null
            }
          : item
      )
    );
  };

  // Single item cancel handler
  const handleCancelItem = (itemId: string) => {
    activeUploadsSetRef.current.delete(itemId);
    const item = batchItems.find(i => i.id === itemId);
    if (item?.uploadController) {
      try { item.uploadController.abort(); } catch (_) {}
    }
    if (item?.provider === "cloudflare" && item.tmpStorageKey) {
      deleteFromCloudflareR2(item.tmpStorageKey).catch(() => {});
    }

    setBatchItems(prev =>
      prev.map(i =>
        i.id === itemId
          ? {
              ...i,
              stage: "CANCELLED",
              errorMessage: "Cancelled by user.",
              errorDetails: "Upload cancelled.",
              uploadController: null
            }
          : i
      )
    );
  };

  // Retry all failed items
  const handleRetryAllFailed = () => {
    // For items where storage upload succeeded but catalog save failed, trigger catalog recovery
    const catalogFailedItems = batchItems.filter(i => i.stage === "catalog_save_failed" || i.canRetryDbSave);
    catalogFailedItems.forEach(i => handleRetryDbSave(i.id));

    // For items with upload/verification failures, requeue for re-upload
    setBatchItems(prev =>
      prev.map(item => {
        if (item.stage === "FAILED" || item.stage === "upload_failed" || item.stage === "finalization_failed" || item.stage === "verification_failed") {
          activeUploadsSetRef.current.delete(item.id);
          return {
            ...item,
            stage: "QUEUED",
            progressPercent: 0,
            uploadedBytes: 0,
            errorMessage: null,
            errorDetails: null,
            canRetryDbSave: false
          };
        }
        return item;
      })
    );
  };

  // Retry saving database record directly if video upload was successful
  const handleRetryDbSave = async (itemId: string) => {
    const item = batchItems.find(i => i.id === itemId);
    if (!item) return;

    setBatchItems(prev =>
      prev.map(i =>
        i.id === itemId
          ? { ...i, stage: "verifying", remainingTime: "Verifying R2 video object before catalog save..." }
          : i
      )
    );

    const storageKey = item.cloudflareStorageKey || item.tmpStorageKey;

    try {
      // 1. Verify object exists in Cloudflare R2 bucket first via recover endpoint
      if (item.provider === "cloudflare" && storageKey) {
        const recRes = await fetch("/api/catalog/recover", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ objectKey: storageKey })
        }).catch(() => null);

        if (recRes && !recRes.ok) {
          const errData = await recRes.json().catch(() => ({}));
          setBatchItems(prev =>
            prev.map(i =>
              i.id === itemId
                ? {
                    ...i,
                    stage: "verification_failed",
                    canRetryDbSave: false,
                    errorMessage: errData.error || "R2 Object Verification Failed: Object does not exist in R2 bucket.",
                    errorDetails: JSON.stringify(errData, null, 2)
                  }
                : i
            )
          );
          return;
        }
      }

      setBatchItems(prev =>
        prev.map(i =>
          i.id === itemId
            ? { ...i, stage: "publishing", remainingTime: "Saving episode metadata to catalog database..." }
            : i
        )
      );

      const videoUrl = item.finalVideoUrl || (storageKey ? buildCloudflareVideoUrl(storageKey) : "");

      await saveBatchItemToDatabase(
        itemId,
        videoUrl,
        "cloudflare",
        storageKey
      );

      setBatchItems(prev =>
        prev.map(i =>
          i.id === itemId
            ? {
                ...i,
                stage: "published",
                progressPercent: 100,
                remainingTime: "Published ✅",
                errorMessage: null,
                errorDetails: null,
                canRetryDbSave: false
              }
            : i
        )
      );
    } catch (err: any) {
      console.error(`Error retrying DB save for item ${itemId}:`, err);
      setBatchItems(prev =>
        prev.map(i =>
          i.id === itemId
            ? {
                ...i,
                stage: "catalog_save_failed",
                errorMessage: `Storage: Uploaded ✅, Catalog Save Failed: ${err?.message || "Unknown error"}`,
                errorDetails: String(err),
                canRetryDbSave: true
              }
            : i
        )
      );
    }
  };

  // Process a single item upload to completion
  const uploadSingleBatchItem = async (item: MultipleEpisodeItem) => {
    const itemId = item.id;

    // Guard against duplicate concurrent execution
    if (activeUploadsSetRef.current.has(itemId)) return;
    activeUploadsSetRef.current.add(itemId);

    const targetAnime = animeList.find(a => a.id === selectedAnimeId);
    if (!targetAnime) {
      activeUploadsSetRef.current.delete(itemId);
      return;
    }

    // Mark as validating -> initializing -> uploading
    setBatchItems(prev =>
      prev.map(i => i.id === itemId ? { ...i, stage: "uploading", progressPercent: 1, remainingTime: "Initializing upload..." } : i)
    );

    try {
      const finalObjectKey = generateR2ObjectKey(
        targetAnime.title,
        item.seasonNum,
        item.episodeNumber,
        item.filename
      );

        setBatchItems(prev =>
          prev.map(i =>
            i.id === itemId
              ? { ...i, cloudflareStorageKey: finalObjectKey, tmpStorageKey: finalObjectKey }
              : i
          )
        );

        // Upload directly to Cloudflare R2
        const ctrl = uploadToCloudflareR2(item.file, finalObjectKey, {
          onProgress: ({ uploadedBytes, totalBytes, percentage, speed, remainingTime, currentPart, totalParts, uploadId, objectKey }) => {
            setBatchItems(prev =>
              prev.map(i =>
                i.id === itemId
                  ? {
                      ...i,
                      stage: percentage >= 100 ? "finalizing" : "uploading",
                      progressPercent: Math.min(percentage, 99),
                      uploadedBytes,
                      totalBytes,
                      uploadSpeed: speed,
                      remainingTime: percentage >= 100 ? "Finalizing multipart upload..." : remainingTime,
                      currentPart,
                      totalParts,
                      uploadId: uploadId || i.uploadId,
                      cloudflareStorageKey: objectKey || finalObjectKey
                    }
                  : i
              )
            );
          },
          onError: (errorMsg, errorDetails, errorInfo) => {
            activeUploadsSetRef.current.delete(itemId);
            setBatchItems(prev =>
              prev.map(i =>
                i.id === itemId
                  ? {
                      ...i,
                      stage: "upload_failed",
                      errorMessage: errorMsg,
                      errorDetails: errorDetails || null,
                      errorStage: errorInfo?.stage || "uploading",
                      httpStatus: errorInfo?.httpStatus !== undefined ? errorInfo.httpStatus : "N/A",
                      httpMethod: errorInfo?.httpMethod || "PUT",
                      endpoint: errorInfo?.endpoint || `${CLOUDFLARE_WORKER_BASE}/api/r2/multipart/init`,
                      serverResponseBody: errorInfo?.responseBody || errorDetails,
                      errorCode: errorInfo?.errorCode || errorInfo?.errorClassification || `HTTP_${errorInfo?.httpStatus || "ERR"}`,
                      errorClassification: errorInfo?.errorClassification,
                      uploadId: errorInfo?.uploadId || i.uploadId || null,
                      objectKey: errorInfo?.objectKey || finalObjectKey,
                      cloudflareStorageKey: errorInfo?.objectKey || finalObjectKey,
                      currentPart: errorInfo?.currentPart,
                      totalParts: errorInfo?.totalParts,
                      attempt: errorInfo?.attempt,
                      canRetryDbSave: false,
                      uploadController: null
                    }
                  : i
              )
            );
          },
          onSuccess: async (videoUrl, key, uploadId) => {
            try {
              const finalVideoUrl = videoUrl || buildCloudflareVideoUrl(finalObjectKey);

              // Transition to VERIFYING stage before saving catalog
              setBatchItems(prev =>
                prev.map(i =>
                  i.id === itemId ? {
                    ...i,
                    stage: "verifying",
                    progressPercent: 99,
                    uploadedBytes: item.fileSize || item.file?.size || i.totalBytes || 0,
                    totalBytes: item.fileSize || item.file?.size || i.totalBytes || 0,
                    uploadSpeed: "Verifying...",
                    finalVideoUrl,
                    remainingTime: "Verifying R2 object existence..."
                  } : i
                )
              );

              // Verify object in Cloudflare R2 bucket
              let isR2Verified = false;
              let verifiedUrl = finalVideoUrl;
              try {
                const verifyRes = await verifyCloudflareVideo(finalObjectKey);
                if (verifyRes.exists) {
                  isR2Verified = true;
                  if (verifyRes.videoUrl) verifiedUrl = verifyRes.videoUrl;
                }
              } catch (vErr) {
                console.warn(`[R2 Verification Error] Item ${itemId}:`, vErr);
              }

              if (!isR2Verified) {
                activeUploadsSetRef.current.delete(itemId);
                setBatchItems(prev =>
                  prev.map(i =>
                    i.id === itemId
                      ? {
                          ...i,
                          stage: "verification_failed",
                          progressPercent: 0,
                          errorMessage: "R2 Verification Failed ❌ - Object does not exist in R2 bucket.",
                          errorDetails: "Cloudflare HEAD check returned 404 or missing file in R2 storage.",
                          errorStage: "verifying",
                          httpStatus: 404,
                          httpMethod: "HEAD",
                          endpoint: `${CLOUDFLARE_WORKER_BASE}/video/${encodeURIComponent(finalObjectKey)}`,
                          serverResponseBody: "HEAD request returned 404 Not Found",
                          errorCode: "HTTP_404",
                          uploadId: null,
                          objectKey: finalObjectKey,
                          canRetryDbSave: false,
                          uploadController: null
                        }
                      : i
                  )
                );
                return;
              }

              // Object exists in R2! Set storage status = uploaded, then transition to publishing
              setBatchItems(prev =>
                prev.map(i =>
                  i.id === itemId ? {
                    ...i,
                    stage: "publishing",
                    progressPercent: 100,
                    uploadedBytes: item.fileSize || item.file?.size || i.totalBytes || 0,
                    totalBytes: item.fileSize || item.file?.size || i.totalBytes || 0,
                    uploadSpeed: "Completed",
                    finalVideoUrl: verifiedUrl,
                    remainingTime: "Saving episode metadata to catalog database..."
                  } : i
                )
              );

              let dbSuccess = false;
              let dbErrText = "";
              try {
                await saveBatchItemToDatabase(itemId, verifiedUrl, "cloudflare", finalObjectKey);
                dbSuccess = true;
              } catch (dbErr: any) {
                console.error(`Error saving batch item database record for item ${itemId}:`, dbErr);
                dbErrText = dbErr?.message || "Failed to save episode record to catalog database.";
              }

              activeUploadsSetRef.current.delete(itemId);

              if (dbSuccess) {
                setBatchItems(prev =>
                  prev.map(i =>
                    i.id === itemId
                      ? {
                          ...i,
                          stage: "published",
                          progressPercent: 100,
                          uploadedBytes: item.fileSize || item.file?.size || i.totalBytes || 0,
                          totalBytes: item.fileSize || item.file?.size || i.totalBytes || 0,
                          finalVideoUrl: verifiedUrl,
                          remainingTime: "Published ✅",
                          errorMessage: null,
                          errorDetails: null,
                          canRetryDbSave: false,
                          uploadController: null
                        }
                      : i
                  )
                );
              } else {
                setBatchItems(prev =>
                  prev.map(i =>
                    i.id === itemId
                      ? {
                          ...i,
                          stage: "catalog_save_failed",
                          progressPercent: 100,
                          uploadedBytes: item.fileSize || item.file?.size || i.totalBytes || 0,
                          totalBytes: item.fileSize || item.file?.size || i.totalBytes || 0,
                          finalVideoUrl: verifiedUrl,
                          errorMessage: `Storage: Uploaded ✅, but catalog database save failed: ${dbErrText}`,
                          errorDetails: dbErrText,
                          errorStage: "publishing",
                          httpStatus: 500,
                          httpMethod: "POST",
                          endpoint: "/api/catalog/save",
                          serverResponseBody: dbErrText,
                          errorCode: "CATALOG_SAVE_FAILED",
                          uploadId: null,
                          objectKey: finalObjectKey,
                          canRetryDbSave: true,
                          uploadController: null
                        }
                      : i
                  )
                );
              }
            } catch (err: any) {
              console.error(`Error in Cloudflare onSuccess for item ${itemId}:`, err);
              activeUploadsSetRef.current.delete(itemId);
              setBatchItems(prev =>
                prev.map(i =>
                  i.id === itemId
                    ? {
                        ...i,
                        stage: "upload_failed",
                        errorMessage: err?.message || "Failed during R2 upload processing.",
                        errorDetails: String(err),
                        errorStage: "finalizing",
                        httpStatus: 500,
                        httpMethod: "PUT",
                        endpoint: `${CLOUDFLARE_WORKER_BASE}/api/r2/multipart/init`,
                        serverResponseBody: String(err),
                        errorCode: "PROCESSING_ERROR",
                        uploadId: null,
                        objectKey: finalObjectKey,
                        uploadController: null
                      }
                    : i
                )
              );
            }
          }
        });

        // Store controller
        setBatchItems(prev =>
          prev.map(i => i.id === itemId ? { ...i, uploadController: ctrl } : i)
        );
    } catch (err: any) {
      console.error(`Upload error for item ${itemId}:`, err);
      activeUploadsSetRef.current.delete(itemId);
      setBatchItems(prev =>
        prev.map(i =>
          i.id === itemId
            ? {
                ...i,
                stage: "FAILED",
                errorMessage: err.message || "Upload process failed.",
                errorDetails: err.stack || null,
                uploadController: null
              }
            : i
        )
      );
    }
  };

  // Save published episode record to database
  const saveBatchItemToDatabase = async (
    itemId: string,
    videoUrl: string,
    sourceType: "cloudflare",
    storageKey?: string
  ) => {
    const targetAnime = animeList.find(a => a.id === selectedAnimeId);
    if (!targetAnime) return;

    // Retrieve fresh item state from batchItemsRef to preserve any user edits
    const item = batchItemsRef.current.find(i => i.id === itemId);
    if (!item) return;

    const episodeId = `${selectedAnimeId}_s${item.seasonNum}_ep${item.episodeNumber}`;

    // Selected thumbnail or fallback
    const primaryThumb = item.primaryThumbnailUrl || item.images.find(i => i.isPrimary)?.url || targetAnime.banner || targetAnime.poster;
    const allImageUrls = item.images.map(i => i.url);

    const episodeObj: Episode = {
      id: episodeId,
      animeId: selectedAnimeId,
      seasonNumber: item.seasonNum,
      episodeNumber: item.episodeNumber,
      title: item.title,
      description: item.description,
      thumbnail: primaryThumb,
      thumbnailUrl: primaryThumb,
      thumbnails: allImageUrls,
      primaryThumbnail: primaryThumb,
      releaseDate: item.releaseDate,
      duration: item.duration,
      sourceType,
      storageKey,
      videoUrl,
      hlsUrl: videoUrl,
      status: item.isVisible ? "Published" : "Draft",
      isVisible: item.isVisible,
      defaultLanguage: item.defaultLanguage,
      quality: item.quality,
      notes: item.notes,
      hasMultipleAudio: item.detectedAudioTracks ? item.detectedAudioTracks.length > 1 : item.audioLanguages.length > 1,
      availableAudioTracks: item.detectedAudioTracks ? item.detectedAudioTracks.map(t => t.language) : item.audioLanguages,
      audioTracks: item.detectedAudioTracks && item.detectedAudioTracks.length > 0
        ? item.detectedAudioTracks
        : item.audioLanguages.map((lang, idx) => ({
            id: idx + 1,
            trackIndex: idx + 1,
            language: lang,
            languageCode: lang.toLowerCase().slice(0, 2),
            name: lang,
            label: lang,
            primary: lang === item.defaultLanguage,
            isDefault: lang === item.defaultLanguage
          })),
      defaultAudio: item.detectedAudioTracks?.find(t => t.isDefault)?.language || item.defaultLanguage
    };

    const initialLangData: EpisodeLanguageData = {
      language: item.detectedAudioTracks?.find(t => t.isDefault)?.language || item.defaultLanguage,
      sourceType,
      storageKey,
      videoUrl,
      hlsUrl: videoUrl,
      quality: item.quality,
      status: item.isVisible ? "Published" : "Draft",
      hasMultipleAudio: item.detectedAudioTracks ? item.detectedAudioTracks.length > 1 : item.audioLanguages.length > 1,
      availableAudioTracks: item.detectedAudioTracks ? item.detectedAudioTracks.map(t => t.language) : item.audioLanguages,
    };

    await saveOrMergeEpisodeLanguage(
      selectedAnimeId,
      item.episodeNumber,
      item.defaultLanguage,
      initialLangData,
      episodeObj
    );

    await addNotification(
      `New Episode Released!`,
      `${targetAnime.title} S${item.seasonNum} Ep ${item.episodeNumber} (${item.title}) is now live!`,
      "episode"
    );

    try {
      await refreshAllEpisodes();
    } catch (e) {
      console.warn("Failed to refresh episodes catalog after saving batch item:", e);
    }
  };

  // Adaptive Queue Processing Loop
  useEffect(() => {
    if (isQueuePaused) return;

    const queuedItems = batchItems.filter(i => (i.stage === "queued" || i.stage === "QUEUED") && !activeUploadsSetRef.current.has(i.id));
    const uploadingItems = batchItems.filter(i =>
      i.stage === "validating" || i.stage === "initializing" || i.stage === "uploading" || i.stage === "finalizing" || i.stage === "verifying" || i.stage === "publishing" || i.stage === "UPLOADING" || i.stage === "VERIFYING" || activeUploadsSetRef.current.has(i.id)
    );

    if (queuedItems.length > 0 && uploadingItems.length < maxConcurrency) {
      const slotsAvailable = maxConcurrency - uploadingItems.length;
      const itemsToStart = queuedItems.slice(0, slotsAvailable);

      for (const item of itemsToStart) {
        uploadSingleBatchItem(item);
      }
    }
  }, [batchItems, maxConcurrency, isQueuePaused]);

  // Auto transition to Summary Tab when all items complete processing
  useEffect(() => {
    if (batchItems.length > 0 && activeStageTab === "queue") {
      const isAnyActive = batchItems.some(i =>
        i.stage === "queued" || i.stage === "QUEUED" ||
        i.stage === "validating" || i.stage === "initializing" || i.stage === "uploading" || i.stage === "finalizing" || i.stage === "verifying" || i.stage === "publishing" || i.stage === "UPLOADING" || i.stage === "VERIFYING"
      );
      if (!isAnyActive) {
        setActiveStageTab("summary");
      }
    }
  }, [batchItems, activeStageTab]);

  // Start processing batch queue
  const handleStartBatchUpload = () => {
    const invalidItems = batchItems.filter(i => i.isDuplicate || !i.episodeNumber);
    if (invalidItems.length > 0) {
      if (!window.confirm(`Warning: ${invalidItems.length} items have validation errors or duplicate episode numbers. Do you want to skip invalid items and upload valid ones?`)) {
        return;
      }
    }

    // Set stage to QUEUED for all SELECTED items that are valid
    setBatchItems(prev =>
      prev.map(i => {
        if ((i.stage === "SELECTED" || i.stage === "VALIDATING" || i.stage === "validating") && !i.isDuplicate && i.episodeNumber > 0) {
          return { ...i, stage: "QUEUED" };
        }
        return i;
      })
    );

    setActiveStageTab("queue");
  };

  // Overall progress stats
  const totalItemsCount = batchItems.length;
  const completedCount = batchItems.filter(i => i.stage === "published" || i.stage === "PUBLISHED").length;
  const failedCount = batchItems.filter(i => i.stage === "upload_failed" || i.stage === "finalization_failed" || i.stage === "verification_failed" || i.stage === "catalog_save_failed" || i.stage === "FAILED").length;
  const uploadingCount = batchItems.filter(i => i.stage === "validating" || i.stage === "initializing" || i.stage === "uploading" || i.stage === "finalizing" || i.stage === "verifying" || i.stage === "publishing" || i.stage === "UPLOADING" || i.stage === "VERIFYING").length;
  const queuedCount = batchItems.filter(i => i.stage === "queued" || i.stage === "QUEUED").length;
  const cancelledCount = batchItems.filter(i => i.stage === "cancelled" || i.stage === "CANCELLED").length;
  const remainingCount = Math.max(0, totalItemsCount - completedCount - failedCount - cancelledCount);

  const totalBatchBytes = batchItems.reduce((acc, i) => acc + (i.totalBytes || i.fileSize || i.file?.size || 0), 0);
  const totalUploadedBytes = batchItems.reduce((acc, i) => acc + (i.stage === "PUBLISHED" ? (i.totalBytes || i.fileSize || i.file?.size || 0) : (i.uploadedBytes || 0)), 0);
  const overallPercent = totalBatchBytes > 0 ? Math.min(100, Math.round((totalUploadedBytes / totalBatchBytes) * 100)) : 0;

  return (
    <div className="w-full bg-neutral-900/90 border border-white/10 rounded-3xl p-4 sm:p-6 md:p-8 shadow-2xl backdrop-blur-xl">
      {/* Title & Top Banner */}
      <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 pb-6 border-b border-white/10">
        <div>
          <div className="flex items-center gap-3">
            <div className="p-3 rounded-2xl bg-gradient-to-br from-rose-500 to-amber-500 text-white shadow-lg shadow-rose-500/20">
              <Layers className="w-6 h-6" />
            </div>
            <div>
              <h2 className="text-xl sm:text-2xl font-extrabold text-white tracking-tight">
                Multiple Episode Upload Manager
              </h2>
              <p className="text-xs sm:text-sm text-gray-400 mt-0.5">
                Batch upload, auto-number, fetch official titles, manage thumbnails & audio tracks in one workflow.
              </p>
            </div>
          </div>
        </div>

        {/* Storage Provider & Concurrency Selector */}
        <div className="flex flex-wrap items-center gap-3">
          {/* Provider Badge */}
          <div className="flex items-center gap-1.5 bg-amber-500/10 border border-amber-500/20 px-3 py-1.5 rounded-xl text-xs font-bold text-amber-400">
            <span className="w-2 h-2 rounded-full bg-amber-500 animate-pulse" />
            Cloudflare R2 Storage
          </div>

          {/* Adaptive Concurrency Control */}
          <div className="flex items-center gap-2 bg-neutral-950 px-3 py-1.5 rounded-xl border border-white/10 text-xs">
            <Wifi className="w-3.5 h-3.5 text-emerald-400" />
            <span className="text-gray-400 font-medium">Concurrency:</span>
            <select
              value={maxConcurrency}
              onChange={(e) => setMaxConcurrency(Number(e.target.value))}
              className="bg-neutral-900 text-white font-bold px-2 py-1 rounded border border-white/10 text-xs outline-none focus:border-rose-500"
            >
              <option value={1}>1 (Slow / Unstable)</option>
              <option value={2}>2 (Medium)</option>
              <option value={3}>3 (Balanced)</option>
              <option value={4}>4 (Fast)</option>
              <option value={5}>5 (Maximum)</option>
            </select>
          </div>
        </div>
      </div>

      {/* Main Sub-Navigation Bar */}
      <div className="flex items-center gap-2 my-6 overflow-x-auto no-scrollbar pb-1 border-b border-white/5">
        {[
          { id: "setup", label: "1. Select Anime & Files", count: batchItems.length },
          { id: "review", label: "2. Metadata & Validation", count: batchItems.length },
          { id: "queue", label: "3. Adaptive Upload Queue", count: uploadingCount + queuedCount },
          { id: "summary", label: "4. Batch Summary", count: completedCount + failedCount }
        ].map((tab) => (
          <button
            key={tab.id}
            type="button"
            onClick={() => setActiveStageTab(tab.id as any)}
            className={`flex items-center gap-2 px-4 py-2.5 rounded-xl font-bold text-xs sm:text-sm whitespace-nowrap transition-all ${
              activeStageTab === tab.id
                ? "bg-rose-500 text-white shadow-lg shadow-rose-500/20"
                : "bg-neutral-950 text-gray-400 hover:text-white border border-white/5"
            }`}
          >
            <span>{tab.label}</span>
            {tab.count > 0 && (
              <span className="px-2 py-0.5 rounded-full bg-white/20 text-white text-[11px] font-extrabold">
                {tab.count}
              </span>
            )}
          </button>
        ))}
      </div>

      {/* STAGE 1: SETUP (Anime, Season, Multi File Picker) */}
      {activeStageTab === "setup" && (
        <div className="space-y-6 animate-fade-in">
          {/* Anime & Season Selector Box */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 p-5 rounded-2xl bg-neutral-950 border border-white/10">
            {/* Anime Selector */}
            <div className="md:col-span-2 space-y-1.5">
              <label className="text-xs font-bold text-gray-300 flex items-center gap-2">
                <Film className="w-4 h-4 text-rose-400" />
                Select Target Anime
              </label>
              <select
                value={selectedAnimeId}
                onChange={(e) => {
                  setSelectedAnimeId(e.target.value);
                  setTimeout(() => revalidateBatch(), 50);
                }}
                className="w-full bg-neutral-900 border border-white/10 rounded-xl px-4 py-3 text-sm text-white font-semibold outline-none focus:border-rose-500 transition-all"
              >
                {animeList.map(a => (
                  <option key={a.id} value={a.id}>
                    {a.title} ({a.releaseYear}) - {a.type}
                  </option>
                ))}
              </select>
            </div>

            {/* Season Selector */}
            <div className="space-y-1.5">
              <label className="text-xs font-bold text-gray-300 flex items-center gap-2">
                <ListOrdered className="w-4 h-4 text-amber-400" />
                Season Number
              </label>
              <div className="flex items-center gap-2">
                <input
                  type="number"
                  min={1}
                  max={50}
                  value={seasonNum}
                  onChange={(e) => setSeasonNum(Math.max(1, parseInt(e.target.value) || 1))}
                  className="w-full bg-neutral-900 border border-white/10 rounded-xl px-4 py-3 text-sm text-white font-bold outline-none focus:border-rose-500 transition-all"
                />
              </div>
            </div>
          </div>

          {/* Drag and Drop Multi Video Upload Area */}
          <div
            onDragOver={(e) => {
              e.preventDefault();
              setIsDraggingOver(true);
            }}
            onDragLeave={() => setIsDraggingOver(false)}
            onDrop={(e) => {
              e.preventDefault();
              setIsDraggingOver(false);
              if (e.dataTransfer.files) {
                handleAddVideoFiles(e.dataTransfer.files);
              }
            }}
            className={`relative flex flex-col items-center justify-center p-8 sm:p-12 rounded-3xl border-2 border-dashed transition-all text-center cursor-pointer ${
              isDraggingOver
                ? "border-rose-500 bg-rose-500/10 scale-[1.01]"
                : "border-white/20 bg-neutral-950/60 hover:border-white/40 hover:bg-neutral-950"
            }`}
          >
            <input
              type="file"
              multiple
              accept="video/*,.mp4,.mkv,.mov,.webm,.avi"
              onChange={(e) => {
                if (e.target.files) handleAddVideoFiles(e.target.files);
              }}
              className="absolute inset-0 opacity-0 cursor-pointer w-full h-full"
            />
            <div className="p-4 rounded-2xl bg-rose-500/20 text-rose-400 mb-4 shadow-xl">
              <Upload className="w-8 h-8 sm:w-10 sm:h-10 animate-bounce" />
            </div>
            <h3 className="text-base sm:text-lg font-extrabold text-white">
              Drag & Drop Multiple Episode Videos Here
            </h3>
            <p className="text-xs sm:text-sm text-gray-400 mt-1 max-w-md">
              Select multiple files at once. Supports <strong className="text-white">MP4, MKV, MOV, WEBM, AVI</strong> formats. Episode numbers and titles will be auto-detected.
            </p>
            <div className="mt-4 px-5 py-2.5 rounded-xl bg-rose-500 hover:bg-rose-600 text-white font-bold text-xs shadow-lg transition-all">
              Browse Files from Device
            </div>
          </div>

          {/* Loaded Files Quick Summary Bar */}
          {batchItems.length > 0 && (
            <div className="flex flex-col sm:flex-row items-center justify-between gap-4 p-4 rounded-2xl bg-neutral-950 border border-white/10">
              <div className="flex items-center gap-3">
                <FileVideo className="w-5 h-5 text-amber-400" />
                <span className="text-sm font-bold text-white">
                  {batchItems.length} Episode Video{batchItems.length > 1 ? "s" : ""} Loaded ({formatBytes(totalBatchBytes)})
                </span>
              </div>

              <div className="flex items-center gap-3 w-full sm:w-auto">
                <button
                  type="button"
                  onClick={handleClearAllBatchItems}
                  className="px-4 py-2 rounded-xl bg-rose-500/20 hover:bg-rose-500/30 text-rose-300 font-bold text-xs transition-all border border-rose-500/30"
                >
                  Clear All
                </button>

                <button
                  type="button"
                  onClick={() => setActiveStageTab("review")}
                  className="px-5 py-2 rounded-xl bg-rose-500 hover:bg-rose-600 text-white font-bold text-xs shadow-lg transition-all flex items-center gap-2"
                >
                  <span>Proceed to Metadata & Validation</span>
                  <ArrowRight className="w-4 h-4" />
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* STAGE 2: METADATA & VALIDATION (Episode Cards) */}
      {(activeStageTab === "review" || activeStageTab === "setup") && batchItems.length > 0 && (
        <div className="space-y-6 mt-6 animate-fade-in">
          <div className="flex items-center justify-between border-b border-white/10 pb-4">
            <div>
              <h3 className="font-extrabold text-lg text-white">
                Episode Cards & Metadata ({batchItems.length} Files)
              </h3>
              <p className="text-xs text-gray-400">
                Review titles, manage multiple thumbnails, assign audio languages, and verify duplicate checks before uploading.
              </p>
            </div>

            <button
              type="button"
              onClick={handleStartBatchUpload}
              className="px-6 py-3 rounded-2xl bg-gradient-to-r from-rose-500 to-amber-500 hover:from-rose-600 hover:to-amber-600 text-white font-extrabold text-sm shadow-xl shadow-rose-500/20 transition-all flex items-center gap-2.5"
            >
              <Play className="w-4 h-4 fill-white" />
              <span>Start Batch Upload Queue</span>
            </button>
          </div>

          {/* Episode Cards Grid */}
          <div className="space-y-4">
            {batchItems.map((item, index) => (
              <div
                key={item.id}
                className={`p-5 rounded-2xl border transition-all ${
                  item.isDuplicate
                    ? "bg-rose-950/30 border-rose-500/50"
                    : item.stage === "PUBLISHED"
                    ? "bg-emerald-950/20 border-emerald-500/30"
                    : "bg-neutral-950 border-white/10 hover:border-white/20"
                }`}
              >
                {/* Header Row */}
                <div className="flex flex-col md:flex-row md:items-center justify-between gap-3 pb-4 border-b border-white/10">
                  <div className="flex items-center gap-3">
                    <span className="flex items-center justify-center w-8 h-8 rounded-xl bg-rose-500/20 text-rose-400 font-black text-xs border border-rose-500/30">
                      #{index + 1}
                    </span>
                    <div>
                      <h4 className="font-extrabold text-sm text-white flex items-center gap-2">
                        <span>{item.filename}</span>
                        <span className="text-xs text-gray-400 font-mono">({item.formattedSize})</span>
                      </h4>
                      <p className="text-xs text-gray-400">
                        Anime: <strong className="text-gray-200">{selectedAnime?.title}</strong> | Season: <strong className="text-gray-200">{item.seasonNum}</strong>
                      </p>
                    </div>
                  </div>

                  <div className="flex items-center gap-2">
                    {/* Duplicate Warning Badge */}
                    {item.isDuplicate && (
                      <span className="px-3 py-1 rounded-lg bg-rose-500/20 text-rose-300 font-bold text-xs border border-rose-500/40 flex items-center gap-1.5 animate-pulse">
                        <AlertTriangle className="w-3.5 h-3.5 text-rose-400" />
                        <span>Duplicate Episode Number</span>
                      </span>
                    )}

                    {/* Stage Status Badge */}
                    <span className={`px-3 py-1 rounded-lg font-bold text-xs border ${
                      item.stage === "PUBLISHED"
                        ? "bg-emerald-500/20 text-emerald-300 border-emerald-500/30"
                        : item.stage === "FAILED"
                        ? "bg-rose-500/20 text-rose-300 border-rose-500/30"
                        : item.stage === "UPLOADING"
                        ? "bg-amber-500/20 text-amber-300 border-amber-500/30 animate-pulse"
                        : "bg-neutral-800 text-gray-300 border-white/10"
                    }`}>
                      {item.stage}
                    </span>

                    {/* Delete Item Button */}
                    <button
                      type="button"
                      onClick={() => handleRemoveBatchItem(item.id)}
                      className="p-2 rounded-lg bg-neutral-900 hover:bg-rose-500/20 text-gray-400 hover:text-rose-400 border border-white/10 transition-all"
                      title="Remove file"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                </div>

                {/* Form Fields Grid */}
                <div className="grid grid-cols-1 md:grid-cols-4 gap-4 mt-4">
                  {/* Episode Number */}
                  <div className="space-y-1">
                    <label className="text-[11px] font-bold text-gray-400 flex items-center gap-1.5">
                      <span>Episode Number</span>
                      {!item.hasDetectedNumber && (
                        <span className="text-[10px] text-amber-400 font-semibold">(Manual required)</span>
                      )}
                    </label>
                    <input
                      type="number"
                      min={1}
                      value={item.episodeNumber}
                      onChange={(e) => handleUpdateEpisodeNumber(item.id, parseInt(e.target.value) || 1)}
                      className={`w-full bg-neutral-900 border rounded-xl px-3 py-2 text-xs text-white font-bold outline-none ${
                        item.isDuplicate
                          ? "border-rose-500 text-rose-300 bg-rose-950/40"
                          : "border-white/10 focus:border-rose-500"
                      }`}
                    />
                  </div>

                  {/* Official Title */}
                  <div className="md:col-span-2 space-y-1">
                    <label className="text-[11px] font-bold text-gray-400 flex items-center justify-between">
                      <span>Official Title</span>
                      {item.isFetchingTitle && (
                        <span className="text-[10px] text-amber-400 animate-pulse">Fetching official title...</span>
                      )}
                    </label>
                    <input
                      type="text"
                      value={item.title}
                      onChange={(e) => {
                        const val = e.target.value;
                        setBatchItems(prev =>
                          prev.map(i => i.id === item.id ? { ...i, title: val } : i)
                        );
                      }}
                      placeholder="Official episode title"
                      className="w-full bg-neutral-900 border border-white/10 rounded-xl px-3 py-2 text-xs text-white font-semibold outline-none focus:border-rose-500"
                    />
                  </div>

                  {/* Audio Track Selector */}
                  <div className="space-y-1">
                    <label className="text-[11px] font-bold text-gray-400">
                      Audio Tracks / Languages
                    </label>
                    <div className="flex flex-wrap gap-1 max-h-20 overflow-y-auto no-scrollbar p-1 bg-neutral-900 rounded-xl border border-white/10">
                      {LANGUAGE_OPTIONS.map((lang) => {
                        const isSelected = item.audioLanguages.includes(lang);
                        return (
                          <button
                            key={lang}
                            type="button"
                            onClick={() => {
                              setBatchItems(prev =>
                                prev.map(i => {
                                  if (i.id === item.id) {
                                    const updatedLangs = isSelected
                                      ? i.audioLanguages.filter(l => l !== lang)
                                      : [...i.audioLanguages, lang];
                                    return {
                                      ...i,
                                      audioLanguages: updatedLangs.length > 0 ? updatedLangs : ["Japanese"]
                                    };
                                  }
                                  return i;
                                })
                              );
                            }}
                            className={`px-2 py-0.5 rounded text-[10px] font-bold transition-all ${
                              isSelected
                                ? "bg-rose-500 text-white"
                                : "bg-neutral-800 text-gray-400 hover:text-white"
                            }`}
                          >
                            {lang}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                </div>

                {/* Audio Tracks Analyzer UI */}
                <div className="mt-4 pt-3 border-t border-white/5">
                  <AudioTrackAnalyzerUI
                    tracks={item.detectedAudioTracks || []}
                    isAnalyzing={item.isAnalyzingAudio || false}
                    warnings={item.audioAnalysisWarning ? [item.audioAnalysisWarning] : []}
                    onUpdateTrackLanguage={(trackIndex, newLang) => handleUpdateItemTrackLanguage(item.id, trackIndex, newLang)}
                    onSetDefaultTrack={(trackIndex) => handleSetItemDefaultTrack(item.id, trackIndex)}
                    onReanalyze={() => handleReanalyzeItemAudio(item.id)}
                    compact
                  />
                </div>

                {/* Multiple Thumbnails / Images Section */}
                <div className="mt-4 pt-4 border-t border-white/5 space-y-2">
                  <div className="flex items-center justify-between">
                    <label className="text-[11px] font-bold text-gray-300 flex items-center gap-1.5">
                      <ImageIcon className="w-3.5 h-3.5 text-amber-400" />
                      <span>Thumbnails / Screenshots ({item.images.length})</span>
                    </label>

                    <label className="cursor-pointer px-3 py-1 rounded-lg bg-neutral-900 hover:bg-neutral-800 text-amber-400 border border-amber-500/20 font-bold text-[11px] transition-all flex items-center gap-1.5">
                      <Plus className="w-3.5 h-3.5" />
                      <span>Add Images</span>
                      <input
                        type="file"
                        multiple
                        accept="image/*,.jpg,.png,.webp"
                        onChange={(e) => {
                          if (e.target.files) handleUploadImagesForCard(item.id, e.target.files);
                        }}
                        className="hidden"
                      />
                    </label>
                  </div>

                  {/* Thumbnail Previews Grid */}
                  <div className="flex items-center gap-3 overflow-x-auto no-scrollbar py-2">
                    {/* Primary Image Preview if available */}
                    {item.images.length === 0 && (
                      <div className="flex items-center gap-2 p-2 rounded-xl bg-neutral-900 border border-white/10 shrink-0">
                        <img
                          src={item.primaryThumbnailUrl}
                          alt="Poster Fallback"
                          className="w-16 h-10 object-cover rounded-lg"
                        />
                        <div className="text-[10px] text-gray-400">
                          <p className="font-bold text-white">Default Poster</p>
                          <p>Upload screenshots above</p>
                        </div>
                      </div>
                    )}

                    {item.images.map((img) => (
                      <div
                        key={img.id}
                        className={`relative group shrink-0 w-24 h-16 rounded-xl overflow-hidden border-2 transition-all ${
                          img.isPrimary
                            ? "border-amber-400 ring-2 ring-amber-400/20"
                            : "border-white/10 hover:border-white/30"
                        }`}
                      >
                        <img
                          src={img.url}
                          alt="Thumbnail preview"
                          className="w-full h-full object-cover"
                        />

                        {img.isPrimary && (
                          <span className="absolute top-1 left-1 px-1.5 py-0.5 rounded bg-amber-500 text-black font-black text-[9px] shadow-md">
                            PRIMARY
                          </span>
                        )}

                        <div className="absolute inset-0 bg-black/60 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center gap-1.5 p-1">
                          {!img.isPrimary && (
                            <button
                              type="button"
                              onClick={() => handleSetPrimaryImage(item.id, img.id)}
                              className="px-1.5 py-0.5 rounded bg-amber-500 text-black text-[9px] font-bold"
                              title="Set as primary"
                            >
                              Make Primary
                            </button>
                          )}
                          <button
                            type="button"
                            onClick={() => handleRemoveImageFromCard(item.id, img.id)}
                            className="p-1 rounded bg-rose-500 text-white"
                            title="Delete image"
                          >
                            <Trash2 className="w-3 h-3" />
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>

                {/* Progress Bar if Uploading or Failed */}
                {(item.stage === "UPLOADING" || item.stage === "VERIFYING" || item.stage === "FAILED" || item.stage === "PUBLISHED") && (
                  <div className="mt-4 pt-3 border-t border-white/10 space-y-1.5">
                    <div className="flex items-center justify-between text-xs font-bold">
                      <span className="text-gray-300">{item.remainingTime}</span>
                      <span className="text-white">{item.progressPercent}% ({item.uploadSpeed})</span>
                    </div>

                    <div className="w-full h-2.5 bg-neutral-900 rounded-full overflow-hidden border border-white/10">
                      <div
                        className={`h-full transition-all duration-300 ${
                          item.stage === "PUBLISHED"
                            ? "bg-emerald-500"
                            : item.stage === "FAILED"
                            ? "bg-rose-500"
                            : "bg-gradient-to-r from-rose-500 to-amber-500"
                        }`}
                        style={{ width: `${item.progressPercent}%` }}
                      />
                    </div>

                    {item.errorMessage && (
                      <p className="text-xs text-rose-400 font-semibold mt-1">
                        Error: {item.errorMessage}
                      </p>
                    )}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* STAGE 3: UPLOAD QUEUE DISPLAY */}
      {activeStageTab === "queue" && (
        <div className="space-y-6 animate-fade-in">
          {/* Global Queue Progress Bar */}
          <div className="p-6 rounded-2xl bg-neutral-950 border border-white/10 space-y-4">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
              <div>
                <h3 className="font-extrabold text-lg text-white">
                  Active Upload Queue Progress
                </h3>
                <p className="text-xs text-gray-400">
                  Adaptive queue running at max {maxConcurrency} concurrent uploads.
                </p>
              </div>

              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => setIsQueuePaused(!isQueuePaused)}
                  className={`px-4 py-2 rounded-xl font-bold text-xs transition-all flex items-center gap-2 ${
                    isQueuePaused
                      ? "bg-emerald-500 text-black"
                      : "bg-amber-500/20 text-amber-300 border border-amber-500/30"
                  }`}
                >
                  <Pause className="w-4 h-4" />
                  <span>{isQueuePaused ? "Resume Queue" : "Pause Queue"}</span>
                </button>

                {failedCount > 0 && (
                  <button
                    type="button"
                    onClick={handleRetryAllFailed}
                    className="px-4 py-2 rounded-xl bg-rose-500 hover:bg-rose-600 text-white font-bold text-xs shadow-lg transition-all flex items-center gap-2"
                  >
                    <RotateCcw className="w-4 h-4" />
                    <span>Retry All Failed ({failedCount})</span>
                  </button>
                )}
              </div>
            </div>

            {/* Progress Meter */}
            <div className="space-y-2">
              <div className="flex items-center justify-between text-xs font-bold text-gray-300">
                <span>Completed {completedCount} of {totalItemsCount} episodes</span>
                <span>{overallPercent}% ({formatBytes(totalUploadedBytes)} / {formatBytes(totalBatchBytes)})</span>
              </div>
              <div className="w-full h-3 bg-neutral-900 rounded-full overflow-hidden border border-white/10">
                <div
                  className="h-full bg-gradient-to-r from-rose-500 via-amber-500 to-emerald-500 transition-all duration-300"
                  style={{ width: `${overallPercent}%` }}
                />
              </div>
            </div>

            {/* Queue Summary Chips */}
            <div className="grid grid-cols-2 sm:grid-cols-5 gap-3 pt-2 text-center text-xs font-bold">
              <div className="p-3 rounded-xl bg-neutral-900 border border-white/5">
                <p className="text-gray-400">Total</p>
                <p className="text-base text-white font-black">{totalItemsCount}</p>
              </div>
              <div className="p-3 rounded-xl bg-neutral-900 border border-emerald-500/20 text-emerald-400">
                <p>Completed</p>
                <p className="text-base font-black">{completedCount}</p>
              </div>
              <div className="p-3 rounded-xl bg-neutral-900 border border-amber-500/20 text-amber-400">
                <p>Uploading</p>
                <p className="text-base font-black">{uploadingCount}</p>
              </div>
              <div className="p-3 rounded-xl bg-neutral-900 border border-sky-500/20 text-sky-400">
                <p>Queued</p>
                <p className="text-base font-black">{queuedCount}</p>
              </div>
              <div className="p-3 rounded-xl bg-neutral-900 border border-rose-500/20 text-rose-400">
                <p>Failed</p>
                <p className="text-base font-black">{failedCount}</p>
              </div>
            </div>
          </div>

          {/* Active Queue List */}
          <div className="space-y-3">
            {batchItems.map((item) => (
              <div
                key={item.id}
                className="p-4 rounded-xl bg-neutral-950 border border-white/10 flex flex-col space-y-3"
              >
                <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
                  <div className="flex items-center gap-3">
                    <div className={`p-2.5 rounded-xl border ${
                      item.stage === "published" || item.stage === "PUBLISHED" ? "bg-emerald-500/20 text-emerald-400 border-emerald-500/30" :
                      item.stage === "catalog_save_failed" ? "bg-amber-500/20 text-amber-400 border-amber-500/30" :
                      item.stage === "upload_failed" || item.stage === "finalization_failed" || item.stage === "verification_failed" || item.stage === "FAILED" ? "bg-rose-500/20 text-rose-400 border-rose-500/30" :
                      "bg-neutral-900 text-gray-400 border-white/10"
                    }`}>
                      <FileVideo className="w-5 h-5" />
                    </div>
                    <div>
                      <h5 className="font-bold text-sm text-white flex items-center gap-2">
                        <span>Ep {item.episodeNumber}: {item.title}</span>
                        <span className="text-xs text-gray-400 font-normal">({item.formattedSize})</span>
                      </h5>
                      <p className="text-xs text-gray-400 flex items-center gap-2 mt-0.5">
                        <span>Status: <strong className="text-gray-200">{item.remainingTime}</strong> ({item.progressPercent}%)</span>
                      </p>
                    </div>
                  </div>

                  {/* Single Item Action Buttons & Badges */}
                  <div className="flex items-center gap-2">
                    {/* Stage Badge */}
                    {item.stage === "published" || item.stage === "PUBLISHED" ? (
                      <span className="px-2.5 py-1 rounded-lg bg-emerald-500/20 text-emerald-300 border border-emerald-500/40 font-extrabold text-xs flex items-center gap-1">
                        <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
                        <span>Published ✅</span>
                      </span>
                    ) : item.stage === "catalog_save_failed" ? (
                      <span className="px-2.5 py-1 rounded-lg bg-amber-500/20 text-amber-300 border border-amber-500/40 font-extrabold text-xs flex items-center gap-1">
                        <AlertTriangle className="w-3.5 h-3.5 text-amber-400" />
                        <span>Storage: Uploaded ✅ | Catalog: Save Failed ⚠️</span>
                      </span>
                    ) : item.stage === "upload_failed" ? (
                      <span className="px-2.5 py-1 rounded-lg bg-rose-500/20 text-rose-300 border border-rose-500/40 font-extrabold text-xs flex items-center gap-1">
                        <XCircle className="w-3.5 h-3.5 text-rose-400" />
                        <span>Upload Failed ❌</span>
                      </span>
                    ) : item.stage === "finalization_failed" ? (
                      <span className="px-2.5 py-1 rounded-lg bg-rose-500/20 text-rose-300 border border-rose-500/40 font-extrabold text-xs flex items-center gap-1">
                        <XCircle className="w-3.5 h-3.5 text-rose-400" />
                        <span>Finalization Failed ❌</span>
                      </span>
                    ) : item.stage === "verification_failed" ? (
                      <span className="px-2.5 py-1 rounded-lg bg-rose-500/20 text-rose-300 border border-rose-500/40 font-extrabold text-xs flex items-center gap-1">
                        <XCircle className="w-3.5 h-3.5 text-rose-400" />
                        <span>Verification Failed ❌</span>
                      </span>
                    ) : (
                      <span className="px-2.5 py-1 rounded-lg bg-neutral-800 text-gray-300 border border-white/10 font-bold text-xs">
                        {item.stage}
                      </span>
                    )}

                    {(item.canRetryDbSave || item.stage === "catalog_save_failed") && (
                      <button
                        type="button"
                        onClick={() => handleRetryDbSave(item.id)}
                        className="px-3 py-1.5 rounded-lg bg-emerald-500 hover:bg-emerald-600 text-black font-black text-xs transition-all flex items-center gap-1.5 shadow-md"
                      >
                        <CheckCircle2 className="w-3.5 h-3.5" />
                        <span>Save to Catalog</span>
                      </button>
                    )}

                    {(item.stage === "upload_failed" || item.stage === "finalization_failed" || item.stage === "verification_failed" || item.stage === "FAILED") && (
                      <button
                        type="button"
                        onClick={() => handleRetryItem(item.id)}
                        className="px-3 py-1.5 rounded-lg bg-rose-500 hover:bg-rose-600 text-white font-bold text-xs transition-all flex items-center gap-1.5"
                      >
                        <RotateCcw className="w-3.5 h-3.5" />
                        <span>Retry Upload</span>
                      </button>
                    )}

                    {(item.stage === "uploading" || item.stage === "UPLOADING" || item.stage === "queued" || item.stage === "QUEUED") && (
                      <button
                        type="button"
                        onClick={() => handleCancelItem(item.id)}
                        className="px-3 py-1.5 rounded-lg bg-neutral-900 hover:bg-rose-500/20 text-gray-400 hover:text-rose-400 border border-white/10 font-bold text-xs transition-all"
                      >
                        Cancel
                      </button>
                    )}
                  </div>
                </div>

                {/* Diagnostic Error Details Box */}
                {item.errorMessage && (
                  <div className="p-3.5 rounded-xl bg-rose-950/40 border border-rose-500/30 text-xs font-mono space-y-2.5 text-rose-200">
                    <div className="flex items-center justify-between border-b border-rose-500/20 pb-2">
                      <p className="font-bold text-rose-300 flex items-center gap-1.5 text-sm">
                        <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0" />
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
                      <div><span className="text-gray-400 font-semibold">objectKey:</span> <span className="font-mono text-gray-200 truncate inline-block max-w-[180px] align-bottom" title={item.objectKey || item.cloudflareStorageKey || "N/A"}>{item.objectKey || item.cloudflareStorageKey || "N/A"}</span></div>
                    </div>

                    <div className="pt-1.5 border-t border-rose-500/20">
                      <p className="text-gray-400 font-semibold mb-1">Message:</p>
                      <p className="text-rose-200 font-sans font-medium text-xs bg-black/40 p-2.5 rounded-lg border border-rose-500/20 leading-relaxed">
                        {item.errorMessage}
                      </p>
                    </div>

                    {item.serverResponseBody && item.serverResponseBody !== item.errorMessage && (
                      <div className="pt-1">
                        <p className="text-gray-400 font-semibold mb-1">Server Response:</p>
                        <pre className="p-2 rounded bg-black/60 text-[10px] text-gray-300 overflow-x-auto whitespace-pre-wrap font-mono border border-white/10 max-h-32">
                          {item.serverResponseBody}
                        </pre>
                      </div>
                    )}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* STAGE 4: BATCH SUMMARY */}
      {activeStageTab === "summary" && (
        <div className="space-y-6 animate-fade-in text-center p-8 rounded-2xl bg-neutral-950 border border-white/10">
          <div className="inline-flex p-4 rounded-3xl bg-emerald-500/20 text-emerald-400 mb-2">
            <CheckCircle className="w-12 h-12" />
          </div>
          <h3 className="text-2xl font-black text-white">Batch Upload Operations Finished</h3>
          <p className="text-sm text-gray-400 max-w-md mx-auto">
            {completedCount} episodes were successfully uploaded and published to the website.
          </p>

          <div className="grid grid-cols-3 gap-4 max-w-lg mx-auto py-4 text-xs font-bold">
            <div className="p-4 rounded-xl bg-neutral-900 border border-emerald-500/20 text-emerald-400">
              <p className="text-xl font-black">{completedCount}</p>
              <p>Published</p>
            </div>
            <div className="p-4 rounded-xl bg-neutral-900 border border-rose-500/20 text-rose-400">
              <p className="text-xl font-black">{failedCount}</p>
              <p>Failed</p>
            </div>
            <div className="p-4 rounded-xl bg-neutral-900 border border-gray-500/20 text-gray-400">
              <p className="text-xl font-black">{cancelledCount}</p>
              <p>Cancelled</p>
            </div>
          </div>

          <div className="flex items-center justify-center gap-4 pt-4">
            <button
              type="button"
              onClick={handleClearAllBatchItems}
              className="px-6 py-3 rounded-2xl bg-neutral-900 hover:bg-neutral-800 text-white font-bold text-sm border border-white/10 transition-all"
            >
              Start New Batch Upload
            </button>
            {failedCount > 0 && (
              <button
                type="button"
                onClick={() => {
                  handleRetryAllFailed();
                  setActiveStageTab("queue");
                }}
                className="px-6 py-3 rounded-2xl bg-rose-500 hover:bg-rose-600 text-white font-bold text-sm shadow-lg transition-all"
              >
                Retry Failed Items
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
