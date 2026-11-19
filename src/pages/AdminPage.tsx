import React, { useState, useEffect, useRef } from "react";
import { useApp } from "../context/AppContext";
import { 
  fetchAllUsers, 
  updateUserRole, 
  deleteUserProfile, 
  saveAnime, 
  deleteAnime, 
  saveEpisode, 
  saveOrMergeEpisodeLanguage,
  fetchEpisodesForAnime, 
  fetchAllEpisodes,
  deleteEpisode,
  addNotification,
  syncSingleAnimeMetadataAndSeasons,
  syncAllAnimeCatalog,
  validateAndRepairDatabaseInvariants
} from "../services/dbService";
import { Anime, Episode, UserProfile, UserRole, SupportedLanguage } from "../types";
import { 
  searchAnimeSuggestions, 
  compileFullAnimeMetadata, 
  parseEpisodeFilename, 
  checkAniListNewSeasonAlert,
  AnimeSearchResult 
} from "../services/animeApiService";
import { getSeasonEpisodeCount } from "../services/seasonService";
import { 
  Users, Film, Upload, Shield, ShieldAlert, Trash2, Plus, Search, Sparkles, 
  Check, RefreshCw, FileVideo, Video, PlaySquare, AlertCircle, Info, CheckCircle, CheckCircle2,
  Edit, X, Layers, Languages, ArrowUpRight, Play, Clock, CheckSquare, ListPlus, ListFilter, Globe,
  Lock, Unlock, Bell, AlertTriangle, ListOrdered
} from "lucide-react";
import { LanguageManagementModal } from "../components/LanguageManagementModal";
import { AdminMovieManager } from "../components/admin/AdminMovieManager";
import { NoticeManager } from "../components/NoticeManager";
import { EpisodeMissingAlertManager } from "../components/EpisodeMissingAlertManager";
import { EpUploadByVideo } from "../components/EpUploadByVideo";
import { 
  getMissingAlerts, 
  addMissingAlert, 
  resolveMissingAlertOnUpload, 
  detectMissingEpisodeNumbers 
} from "../services/missingEpisodeService";
import { AdminDashboardStats } from "../components/admin/AdminDashboardStats";
import { AdminBulkEpisodeManager } from "../components/admin/AdminBulkEpisodeManager";
import { AdminSeasonManager } from "../components/admin/AdminSeasonManager";
import { AdminMissingEpisodesWidget } from "../components/admin/AdminMissingEpisodesWidget";
import { AdminMultipleEpisodeUpload } from "../components/admin/AdminMultipleEpisodeUpload";
import { History, Activity } from "lucide-react";

const POPULAR_ANIME_SUGGESTIONS = [
  "Attack on Titan",
  "Demon Slayer",
  "Naruto Shippuden",
  "Jujutsu Kaisen",
  "Frieren: Beyond Journey's End",
  "Cyberpunk: Edgerunners",
  "Chainsaw Man",
  "One Piece",
  "My Hero Academia",
  "Solo Leveling",
  "Death Note",
  "Bleach: Thousand-Year Blood War",
  "Hunter x Hunter",
  "Fullmetal Alchemist: Brotherhood"
];

export const AdminPage: React.FC = () => {
  const { animeList, refreshAnime, user: currentUserProfile, profile, setProfileRole, setGuestUser, deleteAnimeFromApp } = useApp();

  const [usersList, setUsersList] = useState<UserProfile[]>([]);
  const [activeTab, setActiveTab] = useState<"stats" | "multiple-ep-upload" | "mux-monitor" | "notices" | "users" | "anime" | "episodes" | "ep-upload-video" | "movie" | "import" | "episodes-manage" | "missing-alerts" | "seasons">("stats");

  // Missing Episode Alert State
  const [missingAlertsCount, setMissingAlertsCount] = useState<number>(0);
  const [missingDetectModal, setMissingDetectModal] = useState<{
    animeName: string;
    season: number | string;
    episodeNumber: number;
    language: string;
  } | null>(null);

  useEffect(() => {
    const updateMissingCount = () => {
      const alerts = getMissingAlerts();
      const active = alerts.filter(
        (a) => a.status === "Missing" || a.status === "Waiting" || a.status === "Uploading"
      );
      setMissingAlertsCount(active.length);
    };

    updateMissingCount();
    window.addEventListener("animestream_missing_alerts_updated", updateMissingCount);
    return () => {
      window.removeEventListener("animestream_missing_alerts_updated", updateMissingCount);
    };
  }, []);

  // Anime Movie Manual Entry States
  const [movieSearchQuery, setMovieSearchQuery] = useState("");
  const [isFetchingMovieMeta, setIsFetchingMovieMeta] = useState(false);
  const [movieTitle, setMovieTitle] = useState("");
  const [movieEnglishTitle, setMovieEnglishTitle] = useState("");
  const [movieSynopsis, setMovieSynopsis] = useState("");
  const [moviePoster, setMoviePoster] = useState("");
  const [movieBanner, setMovieBanner] = useState("");
  const [movieStudio, setMovieStudio] = useState("");
  const [movieReleaseYear, setMovieReleaseYear] = useState<number>(new Date().getFullYear());
  const [movieDuration, setMovieDuration] = useState("110 min");
  const [movieRating, setMovieRating] = useState("PG-13");
  const [movieGenres, setMovieGenres] = useState<string>("Action, Drama, Fantasy");
  const [movieAudioLang, setMovieAudioLang] = useState<SupportedLanguage>("Japanese");
  const [moviePlaybackId, setMoviePlaybackId] = useState("");
  const [movieSubtitlesUrl, setMovieSubtitlesUrl] = useState("");

  // Language Management Modal States
  const [isLanguageModalOpen, setIsLanguageModalOpen] = useState(false);
  const [languageModalEp, setLanguageModalEp] = useState<Episode | null>(null);
  const [languageModalAnimeTitle, setLanguageModalAnimeTitle] = useState<string>("");

  const handleOpenLanguageManagement = (ep: Episode, parentTitle?: string) => {
    setLanguageModalEp(ep);
    setLanguageModalAnimeTitle(parentTitle || "");
    setIsLanguageModalOpen(true);
  };

  const handleOpenMovieLanguageManagement = (movieAnime: Anime) => {
    const epId = `${movieAnime.id}_ep1`;
    const existingEp = allEpisodesList.find(e => e.id === epId || e.animeId === movieAnime.id);
    
    const movieEp: Episode = existingEp || {
      id: epId,
      animeId: movieAnime.id,
      episodeNumber: 1,
      title: movieAnime.title,
      description: movieAnime.description,
      releaseDate: `${movieAnime.releaseYear}-01-01`,
      duration: movieAnime.duration || "110 min",
      videoUrl: movieAnime.trailerUrl || "",
      status: "Published",
      defaultLanguage: "Japanese",
      languages: {}
    };

    setLanguageModalEp(movieEp);
    setLanguageModalAnimeTitle(movieAnime.title);
    setIsLanguageModalOpen(true);
  };

  // State for Import Anime via Auto API
  const [importQuery, setImportQuery] = useState("");
  const [importedAnime, setImportedAnime] = useState<Partial<Anime> | null>(null);
  const [isImporting, setIsImporting] = useState(false);
  const [importError, setImportError] = useState<string | null>(null);
  const [showSuggestions, setShowSuggestions] = useState(false);

  // Advanced search & suggestion states
  const [searchResults, setSearchResults] = useState<any[]>([]);
  const [isSearchingSuggestions, setIsSearchingSuggestions] = useState(false);

  // Duplicate resolution states
  const [showDuplicateDialog, setShowDuplicateDialog] = useState(false);
  const [duplicateAnimeMatch, setDuplicateAnimeMatch] = useState<Anime | null>(null);
  const [tempImportedAnime, setTempImportedAnime] = useState<Partial<Anime> | null>(null);

  const updateImportedField = (field: string, value: any) => {
    if (!importedAnime) return;
    setImportedAnime(prev => {
      if (!prev) return null;
      return {
        ...prev,
        [field]: value
      };
    });
  };

  // State for Episode Management / Mux Playback ID Entry
  const [selectedAnimeId, setSelectedAnimeId] = useState("");
  const [seasonNum, setSeasonNum] = useState<number>(1);
  const [episodeNum, setEpisodeNum] = useState<number>(1);
  const [episodeTitle, setEpisodeTitle] = useState("");
  const [episodeDesc, setEpisodeDesc] = useState("");
  const [episodeThumbnail, setEpisodeThumbnail] = useState("");
  const [muxPlaybackId, setMuxPlaybackId] = useState("");
  const [videoQualityLabel, setVideoQualityLabel] = useState("1080p HD");
  const [videoNotes, setVideoNotes] = useState("");
  const [episodeSubtitlesUrl, setEpisodeSubtitlesUrl] = useState("");
  const [episodeReleaseDate, setEpisodeReleaseDate] = useState(new Date().toISOString().split("T")[0]);
  const [episodeIsVisible, setEpisodeIsVisible] = useState(true);
  const [singleAudioLanguage, setSingleAudioLanguage] = useState<SupportedLanguage>("Japanese");
  const [isOverrideMode, setIsOverrideMode] = useState<boolean>(false);
  const [syncingAnimeId, setSyncingAnimeId] = useState<string | null>(null);
  const [anilistSyncAlerts, setAnilistSyncAlerts] = useState<Record<string, { hasNewSeason: boolean; message?: string; nextSeasonInfo?: any }>>({});
  
  // States for Edit/Delete
  const [editingAnime, setEditingAnime] = useState<Anime | null>(null);
  const [editingEpisodeId, setEditingEpisodeId] = useState<string | null>(null);
  const [episodesList, setEpisodesList] = useState<Episode[]>([]);

  const [isLoading, setIsLoading] = useState(false);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  // Episode Management Center States
  const [allEpisodesList, setAllEpisodesList] = useState<Episode[]>([]);
  const [epSearchQuery, setEpSearchQuery] = useState("");
  const [epFilterAnimeId, setEpFilterAnimeId] = useState("");
  const [epFilterLanguage, setEpFilterLanguage] = useState<string>("All");
  const [epFilterStatus, setEpFilterStatus] = useState<string>("All");
  const [deletingEpId, setDeletingEpId] = useState<string | null>(null);
  const [isTranslatingSubtitles, setIsTranslatingSubtitles] = useState(false);

  // Sync / Load episodes of selected anime
  const refreshEpisodes = async () => {
    if (selectedAnimeId) {
      try {
        const list = await fetchEpisodesForAnime(selectedAnimeId);
        setEpisodesList(list);
      } catch (err) {
        console.error("Failed to fetch episodes:", err);
      }
    } else {
      setEpisodesList([]);
    }
  };

  const refreshAllEpisodes = async () => {
    try {
      const list = await fetchAllEpisodes();
      setAllEpisodesList(list);
    } catch (err) {
      console.error("Failed to fetch all catalog episodes:", err);
    }
  };

  // Auto-fill selectedAnimeId if empty and animeList exists
  useEffect(() => {
    if (animeList.length > 0 && !selectedAnimeId) {
      setSelectedAnimeId(animeList[0].id);
    }
  }, [animeList, selectedAnimeId]);

  // Auto calculate Season Number & Episode Number and auto-generate title & description
  useEffect(() => {
    if (!selectedAnimeId) return;
    if (editingEpisodeId) return; // Keep existing values if editing an episode

    const targetAnime = animeList.find(a => a.id === selectedAnimeId);
    const animeTitle = targetAnime?.title || "Anime";

    const catalogEps = allEpisodesList.filter(
      ep => ep.animeId === selectedAnimeId || (ep as any).anime_id === selectedAnimeId
    );

    if (catalogEps.length === 0) {
      setSeasonNum(1);
      setEpisodeNum(1);
      setEpisodeTitle("Episode 1");
      setEpisodeDesc(`Watch ${animeTitle} Season 1 Episode 1 in HD with multiple audio support and English subtitles.`);
      if (targetAnime) {
        setEpisodeThumbnail(targetAnime.banner || targetAnime.poster || "");
      }
      return;
    }

    // Determine highest season in catalog
    const maxSeasonInCatalog = catalogEps.reduce((max, ep) => {
      const s = ep.seasonNumber || 1;
      return s > max ? s : max;
    }, 1);

    const epsInMaxSeason = catalogEps.filter(ep => (ep.seasonNumber || 1) === maxSeasonInCatalog);
    const maxEpInMaxSeason = epsInMaxSeason.reduce((max, ep) => {
      const n = ep.episodeNumber || (ep as any).number || 0;
      return n > max ? n : max;
    }, 0);

    const totalEpLimit = targetAnime?.totalEpisodes || targetAnime?.episodesCount || 0;

    let calcSeason = maxSeasonInCatalog;
    let calcEp = maxEpInMaxSeason + 1;

    // Auto Season Bump if max episode reached season episode limit (e.g. 12, 13, 24)
    if (totalEpLimit > 0 && maxEpInMaxSeason >= totalEpLimit) {
      calcSeason = maxSeasonInCatalog + 1;
      calcEp = 1;
    }

    setSeasonNum(calcSeason);
    setEpisodeNum(calcEp);
    setEpisodeTitle(`Episode ${calcEp}`);
    setEpisodeDesc(`Watch ${animeTitle} Season ${calcSeason} Episode ${calcEp} in HD with multiple audio support and English subtitles.`);
    if (targetAnime) {
      setEpisodeThumbnail(targetAnime.banner || targetAnime.poster || "");
    }
  }, [selectedAnimeId, allEpisodesList, animeList, editingEpisodeId]);

  useEffect(() => {
    refreshEpisodes();
  }, [selectedAnimeId]);

  useEffect(() => {
    refreshAllEpisodes();
  }, [activeTab]);

  const handleDeleteEpisode = async (epId: string) => {
    if (!window.confirm("Are you sure you want to permanently delete this episode from the database? This action cannot be undone.")) {
      return;
    }
    setDeletingEpId(epId);
    try {
      await deleteEpisode(epId);
      setSuccessMsg("Episode deleted successfully!");
      setAllEpisodesList(prev => prev.filter(ep => ep.id !== epId));
      setEpisodesList(prev => prev.filter(ep => ep.id !== epId));
      await refreshAllEpisodes();
      await refreshEpisodes();
      await refreshAnime();
      setTimeout(() => setSuccessMsg(null), 3000);
    } catch (err: any) {
      alert(`Failed to delete episode: ${err.message}`);
    } finally {
      setDeletingEpId(null);
    }
  };

  const handleDeleteAnime = async (animeId: string) => {
    if (!window.confirm("Are you sure you want to permanently delete this anime and ALL associated episodes? This action cannot be undone.")) {
      return;
    }
    setIsLoading(true);
    try {
      await deleteAnimeFromApp(animeId);
      setSuccessMsg("Anime and all associated records deleted successfully!");
      await refreshAnime();
      await refreshAllEpisodes();
      if (selectedAnimeId === animeId) {
        setSelectedAnimeId("");
        setEpisodesList([]);
      }
      setTimeout(() => setSuccessMsg(null), 3000);
    } catch (err: any) {
      alert(`Failed to delete anime: ${err.message}`);
    } finally {
      setIsLoading(false);
    }
  };

  // Movie Auto-Fetch Metadata
  const handleAutoFetchMovieMeta = async () => {
    if (!movieSearchQuery.trim()) return;
    setIsFetchingMovieMeta(true);
    try {
      const suggestions = await searchAnimeSuggestions(movieSearchQuery);
      if (suggestions && suggestions.length > 0) {
        const movieItem = suggestions.find(s => s.type?.toLowerCase().includes("movie")) || suggestions[0];
        const meta = await compileFullAnimeMetadata(movieItem);
        if (meta) {
          setMovieTitle(meta.title || "");
          setMovieEnglishTitle(meta.englishTitle || meta.title || "");
          setMovieSynopsis(meta.description || "");
          setMoviePoster(meta.poster || "");
          setMovieBanner(meta.banner || "");
          setMovieStudio(meta.studio || "");
          setMovieReleaseYear(meta.releaseYear || new Date().getFullYear());
          setMovieDuration(meta.duration || "110 min");
          setMovieRating(meta.rating || "PG-13");
          setMovieGenres((meta.genres || ["Action", "Drama"]).join(", "));
          setSuccessMsg(`Auto fetched metadata for movie: ${meta.englishTitle || meta.title}`);
          setTimeout(() => setSuccessMsg(null), 3000);
        }
      } else {
        alert("No movie metadata found for search query.");
      }
    } catch (err: any) {
      alert(`Error fetching movie metadata: ${err.message}`);
    } finally {
      setIsFetchingMovieMeta(false);
    }
  };

  // Publish Standalone Anime Movie
  const handlePublishMovie = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!movieTitle) {
      alert("Please enter or fetch a Movie Title.");
      return;
    }
    const trimmedMoviePlaybackId = moviePlaybackId.trim();
    if (!trimmedMoviePlaybackId) {
      alert("Validation Error: Please enter a valid Mux Playback ID for this movie.");
      return;
    }

    setIsLoading(true);
    try {
      const movieId = `movie_${Date.now()}`;
      const genresList = movieGenres.split(",").map(g => g.trim()).filter(Boolean);
      const primaryTitle = movieEnglishTitle.trim() || movieTitle.trim();

      const movieAnimeObj: Anime = {
        id: movieId,
        title: primaryTitle,
        englishTitle: primaryTitle,
        japaneseTitle: movieTitle,
        description: movieSynopsis || `Watch ${primaryTitle} movie streaming in HD.`,
        poster: moviePoster || "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=800",
        banner: movieBanner || moviePoster || "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=1600",
        type: "Movie",
        status: "Completed",
        season: "Spring",
        episodesCount: 1,
        studio: movieStudio || "Animation Studio",
        releaseYear: movieReleaseYear,
        duration: movieDuration,
        rating: movieRating,
        genres: genresList.length > 0 ? genresList : ["Action", "Drama"],
        score: 8.5,
        trailerUrl: "",
        isTrending: true,
        isVisible: true
      };

      await saveAnime(movieAnimeObj);

      const videoStreamUrl = `https://stream.mux.com/${trimmedMoviePlaybackId}.m3u8`;

      // Auto generate English subtitles via AssemblyAI
      let autoSubUrl = movieSubtitlesUrl;
      try {
        const subRes = await fetch("/api/subtitles/generate", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            videoUrl: videoStreamUrl,
            language: movieAudioLang
          })
        });
        if (subRes.ok) {
          const subData = await subRes.json();
          autoSubUrl = subData.subtitlesUrl || autoSubUrl || "https://raw.githubusercontent.com/andreas2203/vtt-test/master/sample.vtt";
        }
      } catch (err) {
        console.warn("AssemblyAI movie auto-subtitle generation:", err);
      }

      const movieEpisodeObj: Episode = {
        id: `${movieId}_ep1`,
        animeId: movieId,
        episodeNumber: 1,
        title: `${primaryTitle} (Full Movie)`,
        description: movieSynopsis || `Watch ${primaryTitle} Full Movie streaming in HD.`,
        releaseDate: new Date().toISOString().split("T")[0],
        duration: movieDuration,
        videoUrl: videoStreamUrl,
        hlsUrl: videoStreamUrl,
        status: "Published",
        subtitlesUrl: autoSubUrl,
        defaultLanguage: movieAudioLang,
        muxPlaybackId: trimmedMoviePlaybackId,
        muxPlaybackUrl: videoStreamUrl,
        isVisible: true
      };

      await saveOrMergeEpisodeLanguage(
        movieId,
        1,
        movieAudioLang,
        {
          language: movieAudioLang,
          videoUrl: videoStreamUrl,
          hlsUrl: videoStreamUrl,
          muxPlaybackId: trimmedMoviePlaybackId,
          muxPlaybackUrl: videoStreamUrl,
          subtitlesUrl: autoSubUrl,
          status: "Published"
        },
        movieEpisodeObj
      );

      await addNotification(
        "New Anime Movie Released!",
        `${primaryTitle} is now available to watch in HD!`,
        "anime"
      );

      setSuccessMsg(`Anime Movie '${primaryTitle}' published successfully!`);
      await refreshAnime();
      await refreshAllEpisodes();

      // Reset form
      setMovieSearchQuery("");
      setMovieTitle("");
      setMovieEnglishTitle("");
      setMovieSynopsis("");
      setMoviePoster("");
      setMovieBanner("");
      setMovieStudio("");
      setMoviePlaybackId("");
      setMovieSubtitlesUrl("");

      setTimeout(() => setSuccessMsg(null), 4000);
    } catch (err: any) {
      alert(`Failed to publish movie: ${err.message}`);
    } finally {
      setIsLoading(false);
    }
  };

  const handleToggleEpisodeVisibility = async (ep: Episode) => {
    const nextVisible = ep.isVisible === false ? true : false;
    const nextStatus = nextVisible ? "Published" : "Draft";
    try {
      await saveEpisode({
        ...ep,
        isVisible: nextVisible,
        status: nextStatus
      });
      setSuccessMsg(`Episode ${ep.episodeNumber} is now ${nextVisible ? "Visible" : "Hidden"}.`);
      await refreshAllEpisodes();
      await refreshEpisodes();
      setTimeout(() => setSuccessMsg(null), 3000);
    } catch (err: any) {
      alert(`Failed to update episode visibility: ${err.message}`);
    }
  };

  const handleEditEpisode = (ep: Episode) => {
    setEditingEpisodeId(ep.id);
    setSelectedAnimeId(ep.animeId);
    setSeasonNum(ep.seasonNumber || 1);
    setEpisodeNum(ep.episodeNumber);
    setEpisodeTitle(ep.title);
    setEpisodeDesc(ep.description || "");
    setEpisodeThumbnail(ep.thumbnail || ep.thumbnailUrl || "");
    setMuxPlaybackId(ep.muxPlaybackId || "");
    setVideoQualityLabel(ep.quality || "1080p HD");
    setVideoNotes(ep.notes || "");
    setEpisodeSubtitlesUrl(ep.subtitlesUrl || "");
    setEpisodeReleaseDate(ep.releaseDate || new Date().toISOString().split("T")[0]);
    setEpisodeIsVisible(ep.isVisible !== false);
    setSingleAudioLanguage(ep.defaultLanguage || "Japanese");

    setActiveTab("episodes");
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  const handleTranslateSubtitlesToEnglish = async (videoUrl?: string, lang?: string) => {
    const targetUrl = videoUrl || (muxPlaybackId ? `https://stream.mux.com/${muxPlaybackId}.m3u8` : "");
    const targetLang = lang || singleAudioLanguage || "Japanese";

    setIsTranslatingSubtitles(true);
    try {
      const res = await fetch("/api/subtitles/generate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          videoUrl: targetUrl,
          language: targetLang
        })
      });
      const data = await res.json();
      if (data.vttContent) {
        const blob = new Blob([data.vttContent], { type: "text/vtt" });
        const blobUrl = URL.createObjectURL(blob);
        setEpisodeSubtitlesUrl(blobUrl);
        setSuccessMsg("Subtitles generated via AssemblyAI Speech API!");
      } else if (data.subtitlesUrl) {
        setEpisodeSubtitlesUrl(data.subtitlesUrl);
        setSuccessMsg("AssemblyAI subtitle track linked successfully!");
      } else {
        setSuccessMsg("AssemblyAI subtitle process initiated!");
      }
      setTimeout(() => setSuccessMsg(null), 4000);
    } catch (err: any) {
      alert(`Subtitle Translation error: ${err.message}`);
    } finally {
      setIsTranslatingSubtitles(false);
    }
  };

  // Load Users
  useEffect(() => {
    if (activeTab === "users" || activeTab === "stats") {
      fetchAllUsers().then(list => setUsersList(list));
    }
  }, [activeTab]);

  // Handle changing user role
  const handleChangeRole = async (uid: string, nextRole: UserRole) => {
    if (uid === currentUserProfile?.uid) {
      alert("You cannot modify your own administrative role.");
      return;
    }
    await updateUserRole(uid, nextRole);
    // Refresh users
    const updated = await fetchAllUsers();
    setUsersList(updated);
  };

  // Handle deleting a user profile
  const handleDeleteUser = async (uid: string) => {
    if (uid === currentUserProfile?.uid) {
      alert("You cannot delete your own profile.");
      return;
    }
    if (window.confirm("Permanently delete this user profile?")) {
      await deleteUserProfile(uid);
      const updated = await fetchAllUsers();
      setUsersList(updated);
    }
  };

  // 1. Live Suggestion Debouncing Listener (Multi-Source AniList / Jikan / Kitsu)
  useEffect(() => {
    if (!importQuery.trim()) {
      setSearchResults([]);
      return;
    }

    const timer = setTimeout(async () => {
      setIsSearchingSuggestions(true);
      try {
        const results = await searchAnimeSuggestions(importQuery);
        setSearchResults(results);
      } catch (err) {
        console.error("Multi-API suggestions retrieval failed:", err);
      } finally {
        setIsSearchingSuggestions(false);
      }
    }, 300);

    return () => clearTimeout(timer);
  }, [importQuery]);

  // Handle selecting a live search suggestion
  const handleSelectSuggestion = async (suggestion: AnimeSearchResult) => {
    setImportQuery(suggestion.title);
    setSearchResults([]);
    setIsImporting(true);
    setImportError(null);
    setImportedAnime(null);

    try {
      // Compile complete metadata using multi-source fallback
      const data = await compileFullAnimeMetadata(suggestion);
      setImportedAnime(data);

      // Smart Duplicate Detection (Check for matching normalized title or English title)
      const duplicate = animeList.find(a => 
        (a.title && data.title && a.title.toLowerCase() === data.title.toLowerCase()) || 
        (a.englishTitle && data.englishTitle && a.englishTitle.toLowerCase() === data.englishTitle.toLowerCase()) ||
        (a.romajiTitle && data.romajiTitle && a.romajiTitle.toLowerCase() === data.romajiTitle.toLowerCase())
      );

      if (duplicate) {
        setDuplicateAnimeMatch(duplicate);
        setTempImportedAnime(data);
        setShowDuplicateDialog(true);
      }
    } catch (err: any) {
      console.error(err);
      setImportError(err.message || "Failed to retrieve full anime details.");
    } finally {
      setIsImporting(false);
    }
  };

  // 2. Duplicate Resolution Handlers
  const handleResolveUpdate = async () => {
    if (!duplicateAnimeMatch || !tempImportedAnime) return;
    setIsLoading(true);
    try {
      const updatedAnime: Anime = {
        ...duplicateAnimeMatch,
        ...tempImportedAnime,
        id: duplicateAnimeMatch.id // Keep original record ID
      };
      await saveAnime(updatedAnime);
      await refreshAnime();

      await addNotification(
        `Anime Updated!`,
        `${updatedAnime.title} information has been updated with the latest high-res metadata.`,
        "anime"
      );

      setSuccessMsg("Existing anime record updated successfully!");
      setImportedAnime(null);
      setTempImportedAnime(null);
      setDuplicateAnimeMatch(null);
      setShowDuplicateDialog(false);
      setImportQuery("");
      setTimeout(() => setSuccessMsg(null), 3000);
    } catch (err: any) {
      alert(err.message);
    } finally {
      setIsLoading(false);
    }
  };

  const handleResolveNewVersion = async () => {
    if (!tempImportedAnime) return;
    setIsLoading(true);
    try {
      const suffix = tempImportedAnime.releaseYear ? ` (${tempImportedAnime.releaseYear})` : " (New)";
      const newTitle = tempImportedAnime.title + suffix;
      const targetId = newTitle.toLowerCase().replace(/[^a-z0-9]+/g, "-");

      const newAnime: Anime = {
        ...tempImportedAnime,
        id: targetId,
        title: newTitle
      } as Anime;

      await saveAnime(newAnime);
      await refreshAnime();

      await addNotification(
        `New Anime Version Added!`,
        `A new version of ${newTitle} is now streaming!`,
        "anime"
      );

      setSuccessMsg("New version created successfully!");
      setImportedAnime(null);
      setTempImportedAnime(null);
      setDuplicateAnimeMatch(null);
      setShowDuplicateDialog(false);
      setImportQuery("");
      setTimeout(() => setSuccessMsg(null), 3000);
    } catch (err: any) {
      alert(err.message);
    } finally {
      setIsLoading(false);
    }
  };

  const handleResolveCancel = () => {
    setImportedAnime(null);
    setTempImportedAnime(null);
    setDuplicateAnimeMatch(null);
    setShowDuplicateDialog(false);
    setImportQuery("");
  };

  // Traditional Search Search Button Trigger (fallback)
  const handleImportSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!importQuery.trim()) return;
    setIsImporting(true);
    setImportError(null);
    setImportedAnime(null);

    try {
      const res = await fetch(`/api/anime/search-suggestions?query=${encodeURIComponent(importQuery)}`);
      if (!res.ok) throw new Error("Search suggestions call failed");
      const suggestions = await res.json();
      if (suggestions && suggestions.length > 0) {
        await handleSelectSuggestion(suggestions[0]);
      } else {
        throw new Error("No matching anime found. Try a different search title.");
      }
    } catch (err: any) {
      console.error(err);
      setImportError(err.message || "Failed to import metadata.");
    } finally {
      setIsImporting(false);
    }
  };

  // 3. Publish/Save Newly Imported Anime Form
  const handleSaveImportedAnime = async () => {
    if (!importedAnime) return;
    setIsLoading(true);
    try {
      const targetId = (importedAnime.title || "").toLowerCase().replace(/[^a-z0-9]+/g, "-");
      const fullAnime: Anime = {
        id: targetId,
        title: importedAnime.title || "",
        englishTitle: importedAnime.englishTitle || "",
        japaneseTitle: importedAnime.japaneseTitle || "",
        nativeTitle: importedAnime.nativeTitle || "",
        romajiTitle: importedAnime.romajiTitle || "",
        description: importedAnime.description || "",
        genres: importedAnime.genres || [],
        themes: importedAnime.themes || [],
        studio: importedAnime.studio || "",
        producers: importedAnime.producers || [],
        releaseYear: importedAnime.releaseYear || 2024,
        season: importedAnime.season || "Spring",
        status: importedAnime.status || "Ongoing",
        duration: importedAnime.duration || "24 min",
        rating: importedAnime.rating || "PG-13",
        score: importedAnime.score || 0,
        popularity: importedAnime.popularity || 0,
        totalEpisodes: importedAnime.totalEpisodes || 12,
        episodesCount: importedAnime.episodesCount || 12,
        trailerUrl: importedAnime.trailerUrl || "",
        banner: importedAnime.banner || "",
        poster: importedAnime.poster || "",
        type: importedAnime.type || "TV",
        source: importedAnime.source || "Original",
        ageRating: importedAnime.ageRating || "PG-13",
        officialWebsite: importedAnime.officialWebsite || "",
        characters: importedAnime.characters || [],
        coverColor: importedAnime.coverColor || "#10121e"
      };

      await saveAnime(fullAnime);
      await refreshAnime();
      
      await addNotification(
        `New Anime Added!`,
        `${fullAnime.title} is now streaming. Type: ${fullAnime.type}, Studio: ${fullAnime.studio}!`,
        "anime"
      );

      setSuccessMsg("Anime imported and published successfully!");
      setImportedAnime(null);
      setImportQuery("");
      setTimeout(() => setSuccessMsg(null), 3000);
    } catch (err: any) {
      alert(err.message);
    } finally {
      setIsLoading(false);
    }
  };

  // 5. Save/Publish Episode with Mux Playback ID to Firestore
  const handlePublishEpisode = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedAnimeId) {
      alert("Please select an Anime first.");
      return;
    }

    const trimmedPlaybackId = muxPlaybackId.trim();
    if (!trimmedPlaybackId) {
      alert("Validation Error: Please enter a Mux Playback ID.");
      return;
    }

    const animeItem = animeList.find(a => a.id === selectedAnimeId);
    const animeTitle = animeItem?.title || "Anime";

    const catalogEpsForAnime = allEpisodesList.filter(
      ep => ep.animeId === selectedAnimeId || (ep as any).anime_id === selectedAnimeId
    );

    let targetSeasonNum = seasonNum || 1;
    let targetEpisodeNum = episodeNum;
    let targetEpisodeId = editingEpisodeId || `${selectedAnimeId}_s${targetSeasonNum}_ep${targetEpisodeNum}`;
    let isAutoAssigned = false;

    // Feature 4: Duplicate Episode Protection
    if (!editingEpisodeId) {
      const existingInSeason = catalogEpsForAnime.find(
        ep => (ep.seasonNumber || 1) === targetSeasonNum && ep.episodeNumber === targetEpisodeNum
      );
      if (existingInSeason && !isOverrideMode) {
        // Auto assign next episode number
        const maxEpNum = catalogEpsForAnime
          .filter(ep => (ep.seasonNumber || 1) === targetSeasonNum)
          .reduce((max, ep) => (ep.episodeNumber > max ? ep.episodeNumber : max), 0);
        targetEpisodeNum = maxEpNum + 1;
        targetEpisodeId = `${selectedAnimeId}_s${targetSeasonNum}_ep${targetEpisodeNum}`;
        isAutoAssigned = true;
      }
    }

    // Feature 5: Missing Episode Detection
    if (targetEpisodeNum > 1 && !editingEpisodeId && !isOverrideMode && !isAutoAssigned) {
      const prevEpExists = catalogEpsForAnime.some(
        ep => (ep.seasonNumber || 1) === targetSeasonNum && ep.episodeNumber === targetEpisodeNum - 1
      );
      if (!prevEpExists) {
        const confirmSkip = confirm(
          `⚠️ Missing Episode Warning:\n\nEpisode ${targetEpisodeNum - 1} for Season ${targetSeasonNum} is missing in your catalog!\n\nAre you sure you want to skip Episode ${targetEpisodeNum - 1} and publish Episode ${targetEpisodeNum}?`
        );
        if (!confirmSkip) return;
      }
    }

    const finalTitle = episodeTitle.trim() || `Episode ${targetEpisodeNum}`;
    const finalDesc = episodeDesc.trim() || `Watch ${animeTitle} Season ${targetSeasonNum} Episode ${targetEpisodeNum} in HD with multiple audio support and English subtitles.`;
    const finalThumbnail = episodeThumbnail.trim() || animeItem?.banner || animeItem?.poster || "";

    setIsLoading(true);

    try {
      const primaryUrl = trimmedPlaybackId.startsWith("http")
        ? trimmedPlaybackId
        : `https://prophecy-verse-video-api.newprophecy4.workers.dev/video/${encodeURIComponent(trimmedPlaybackId)}`;

      const newEpisode: Episode = {
        id: targetEpisodeId,
        animeId: selectedAnimeId,
        seasonNumber: targetSeasonNum,
        episodeNumber: targetEpisodeNum,
        title: finalTitle,
        description: finalDesc,
        thumbnail: finalThumbnail,
        thumbnailUrl: finalThumbnail,
        releaseDate: episodeReleaseDate || new Date().toISOString().split("T")[0],
        duration: "24 min",
        videoUrl: primaryUrl,
        hlsUrl: primaryUrl,
        muxPlaybackId: trimmedPlaybackId,
        muxPlaybackUrl: primaryUrl,
        muxStatus: "Ready",
        status: episodeIsVisible ? "Published" : "Draft",
        subtitlesUrl: episodeSubtitlesUrl,
        isVisible: episodeIsVisible,
        defaultLanguage: singleAudioLanguage
      };

      // Save/Merge audio language data in Firestore
      const langData = {
        language: singleAudioLanguage,
        videoUrl: primaryUrl,
        hlsUrl: primaryUrl,
        muxPlaybackId: trimmedPlaybackId,
        muxPlaybackUrl: primaryUrl,
        muxStatus: "Ready",
        quality: videoQualityLabel || "1080p HD",
        notes: videoNotes || undefined,
        subtitlesUrl: episodeSubtitlesUrl,
        status: episodeIsVisible ? "Published" : "Draft"
      };

      await saveOrMergeEpisodeLanguage(
        selectedAnimeId,
        targetEpisodeNum,
        singleAudioLanguage,
        langData,
        newEpisode
      );

      // Trigger notification if it's a new episode
      if (!editingEpisodeId && animeItem) {
        await addNotification(
          `New Episode Released!`,
          `${animeItem.title} Episode ${targetEpisodeNum} (${singleAudioLanguage}) is now streaming!`,
          "episode"
        );
      }

      // Resolve any existing missing alert for this episode
      resolveMissingAlertOnUpload(animeTitle, targetSeasonNum, targetEpisodeNum);

      // Auto detect missing episodes in sequence
      const updatedCatalogEps = [...catalogEpsForAnime, newEpisode];
      const allEpNums = updatedCatalogEps
        .filter((ep) => (ep.seasonNumber || 1) === targetSeasonNum)
        .map((ep) => ep.episodeNumber);

      const missingEpisodeNums = detectMissingEpisodeNumbers(allEpNums);
      if (missingEpisodeNums.length > 0) {
        setMissingDetectModal({
          animeName: animeTitle,
          season: targetSeasonNum,
          episodeNumber: missingEpisodeNums[0],
          language: singleAudioLanguage,
        });
      }

      setSuccessMsg(
        editingEpisodeId
          ? `Episode ${targetEpisodeNum} (${singleAudioLanguage}) updated successfully!` 
          : isAutoAssigned
          ? `Episode ${targetEpisodeNum} published! (Auto-assigned Episode ${targetEpisodeNum} to prevent overwriting existing Episode ${episodeNum}).`
          : `Episode ${targetEpisodeNum} (${singleAudioLanguage}) published successfully!`
      );
      
      // Reset episode form
      setEpisodeTitle("");
      setEpisodeDesc("");
      setEpisodeThumbnail("");
      setMuxPlaybackId("");
      setVideoQualityLabel("1080p HD");
      setVideoNotes("");
      setEpisodeSubtitlesUrl("");
      setEpisodeReleaseDate(new Date().toISOString().split("T")[0]);
      setEpisodeIsVisible(true);
      setEditingEpisodeId(null);

      // Refresh episode registry
      await refreshEpisodes();
      await refreshAllEpisodes();

      setTimeout(() => setSuccessMsg(null), 3500);
    } catch (err: any) {
      alert(err.message || "Failed to publish episode.");
    } finally {
      setIsLoading(false);
    }
  };

  const handleStartEditEpisode = (ep: Episode) => {
    setEditingEpisodeId(ep.id);
    setSeasonNum(ep.seasonNumber || 1);
    setEpisodeNum(ep.episodeNumber);
    setEpisodeTitle(ep.title);
    setEpisodeDesc(ep.description);
    setEpisodeThumbnail(ep.thumbnail || ep.thumbnailUrl || "");
    setMuxPlaybackId(ep.muxPlaybackId || "");
    setVideoQualityLabel(ep.quality || "1080p HD");
    setVideoNotes(ep.notes || "");
    setEpisodeSubtitlesUrl(ep.subtitlesUrl || "");
    setEpisodeReleaseDate(ep.releaseDate || new Date().toISOString().split("T")[0]);
    setEpisodeIsVisible(ep.isVisible !== false);
  };

  const [isBatchSyncing, setIsBatchSyncing] = useState(false);
  const [isRepairingDb, setIsRepairingDb] = useState(false);

  const handleSyncAniListForAnime = async (anime: Anime) => {
    setSyncingAnimeId(anime.id);
    try {
      const syncRes = await syncSingleAnimeMetadataAndSeasons(anime.id);
      const res = await checkAniListNewSeasonAlert(anime);
      setAnilistSyncAlerts(prev => ({
        ...prev,
        [anime.id]: res
      }));
      await refreshAnime();
      await refreshAllEpisodes();
      setSuccessMsg(syncRes.message);
      setTimeout(() => setSuccessMsg(null), 4000);
    } catch (err: any) {
      alert("Failed to sync AniList: " + (err.message || "Unknown error"));
    } finally {
      setSyncingAnimeId(null);
    }
  };

  const handleSyncAllAnime = async () => {
    if (!window.confirm("Are you sure you want to re-sync metadata & season info for ALL anime in the catalog? Existing uploaded videos and Mux Playback IDs will be preserved.")) {
      return;
    }
    setIsBatchSyncing(true);
    try {
      const res = await syncAllAnimeCatalog();
      await refreshAnime();
      await refreshAllEpisodes();
      setSuccessMsg(`🚀 Batch Sync Complete! Re-synced ${res.totalSynced} anime entries across the catalog.`);
      setTimeout(() => setSuccessMsg(null), 5000);
    } catch (err: any) {
      alert("Batch Sync Failed: " + (err.message || "Unknown error"));
    } finally {
      setIsBatchSyncing(false);
    }
  };

  const handleRepairDatabase = async () => {
    setIsRepairingDb(true);
    try {
      const res = await validateAndRepairDatabaseInvariants();
      await refreshAnime();
      await refreshAllEpisodes();
      setSuccessMsg(`🩺 Database Integrity Repair Complete! Inspected ${res.totalCheckedEpisodes} episodes: Fixed ${res.fixedSeasonMappingsCount} missing seasons, merged ${res.duplicateMergedCount} duplicates, repaired ${res.repairedEpisodesCount} records.`);
      setTimeout(() => setSuccessMsg(null), 5000);
    } catch (err: any) {
      alert("Database Repair Failed: " + (err.message || "Unknown error"));
    } finally {
      setIsRepairingDb(false);
    }
  };

  const handleStartEditAnime = (anime: Anime) => {
    setEditingAnime(anime);
  };

  const handleSaveAnimeEdits = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingAnime) return;
    setIsLoading(true);
    try {
      await saveAnime(editingAnime);
      await refreshAnime();
      setSuccessMsg("Anime updated successfully!");
      setEditingAnime(null);
      setTimeout(() => setSuccessMsg(null), 3000);
    } catch (err: any) {
      alert(err.message);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="w-full min-h-screen pb-20 px-1">
      {/* Upper Brand Header */}
      <div className="mb-8 flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="font-display font-extrabold text-3xl text-white tracking-tight flex items-center gap-2.5">
            <Shield className="w-8 h-8 text-amber-500" />
            Admin Control Center
          </h1>
          <p className="text-gray-400 text-sm mt-1">
            Monitor streams, manage roles, and import new anime instantly.
          </p>
        </div>

        {/* Quick Privilege Elevator Badge */}
        <div className="flex items-center gap-3 bg-neutral-900/80 border border-amber-500/20 px-4 py-2.5 rounded-2xl shadow-lg">
          <div className="flex items-center gap-2 text-xs font-semibold text-amber-400">
            <ShieldAlert className="w-4 h-4 text-amber-400" />
            <span>Role: <strong className="text-white">{profile?.role || "Super Admin"}</strong></span>
          </div>
          {profile?.role !== "Super Admin" && profile?.role !== "Admin" && (
            <button
              onClick={() => {
                if (!profile) {
                  setGuestUser();
                }
                setProfileRole("Super Admin");
              }}
              className="px-3 py-1 rounded-lg bg-amber-500/20 hover:bg-amber-500/30 text-amber-300 font-bold text-[11px] border border-amber-500/30 transition-all"
            >
              Unlock Full Admin
            </button>
          )}
        </div>
      </div>

      {/* Success/Action notice */}
      {successMsg && (
        <div className="mb-6 p-4 rounded-xl bg-emerald-500/10 border border-emerald-500/10 text-emerald-400 text-xs flex items-center gap-2 animate-fade-in">
          <CheckCircle className="w-4.5 h-4.5" />
          <span>{successMsg}</span>
        </div>
      )}

      {/* Dashboard Notification for Missing Episodes */}
      {missingAlertsCount > 0 && (
        <div
          onClick={() => setActiveTab("missing-alerts")}
          className="mb-6 p-4 rounded-2xl bg-gradient-to-r from-rose-500/20 via-[#2a1723] to-[#181a26] border border-rose-500/40 text-white flex items-center justify-between cursor-pointer hover:border-rose-500/70 transition-all shadow-xl group animate-fade-in"
        >
          <div className="flex items-center gap-3.5">
            <div className="p-2.5 rounded-xl bg-rose-500 text-white shadow-lg shadow-rose-500/30 group-hover:scale-105 transition-transform">
              <AlertTriangle className="w-5 h-5" />
            </div>
            <div>
              <h4 className="font-extrabold text-sm text-rose-300 flex items-center gap-2">
                <span>You currently have {missingAlertsCount} missing episode{missingAlertsCount > 1 ? "s" : ""} that require upload.</span>
              </h4>
              <p className="text-xs text-gray-400 mt-0.5">Click here to open the Episode Missing Alert section and manage missing releases.</p>
            </div>
          </div>
          <span className="px-4 py-2 rounded-xl bg-rose-500 hover:bg-rose-400 text-white font-extrabold text-xs shrink-0 shadow-md">
            View Missing Alerts &rarr;
          </span>
        </div>
      )}

      {/* Grid Tabs Panel */}
      <div className="flex border-b border-white/5 mb-8 overflow-x-auto no-scrollbar scroll-smooth">
        {[
          { id: "stats", label: "Dashboard Stats", icon: Shield },
          { id: "multiple-ep-upload", label: "Multiple EP Upload", icon: ListPlus },
          { id: "episodes", label: "Single EP Upload", icon: Upload },
          { id: "movie", label: "Section 3: Movie Upload & Playback", icon: Film },
          { id: "ep-upload-video", label: "Cloudflare Video Upload", icon: Video },
          { id: "episodes-manage", label: "Bulk Episode Manager", icon: ListFilter },
          { id: "seasons", label: "Season Manager", icon: Layers },
          { 
            id: "missing-alerts", 
            label: `🔴 Missing Episodes${missingAlertsCount > 0 ? ` (${missingAlertsCount})` : ''}`, 
            icon: AlertTriangle 
          },
          { id: "import", label: "Auto Anime Import", icon: Sparkles },
          { id: "anime", label: "Anime Registry", icon: Film },
          { id: "notices", label: "🔔 Notice Management", icon: Bell },
          { id: "users", label: "User Roles", icon: Users },
        ].map((tab) => {
          const Icon = tab.icon;
          const isActive = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id as any)}
              className={`flex items-center gap-2.5 px-6 py-4 border-b-2 font-bold text-sm transition-all whitespace-nowrap ${
                isActive
                  ? "border-rose-500 text-rose-400 bg-rose-500/5"
                  : "border-transparent text-gray-400 hover:text-white"
              }`}
            >
              <Icon className="w-4 h-4" />
              {tab.label}
            </button>
          );
        })}
      </div>

      {/* Tab Screen Displays */}
      <div className="mt-2">
        {/* MULTIPLE EPISODE UPLOAD TAB */}
        {activeTab === "multiple-ep-upload" && (
          <div className="animate-fade-in">
            <AdminMultipleEpisodeUpload
              allEpisodesList={allEpisodesList}
              refreshAllEpisodes={refreshAllEpisodes}
            />
          </div>
        )}

        {/* EP UPLOAD BY VIDEO TAB */}
        {activeTab === "ep-upload-video" && (
          <div className="animate-fade-in">
            <EpUploadByVideo
              allEpisodesList={allEpisodesList}
              refreshAllEpisodes={refreshAllEpisodes}
              onSuccessEpisodeCreated={() => {
                refreshAnime();
                refreshAllEpisodes();
              }}
              onOpenMissingAlertModal={(data) => {
                setMissingDetectModal(data);
              }}
            />
          </div>
        )}

        {/* MISSING EPISODES TAB */}
        {activeTab === "missing-alerts" && (
          <div className="animate-fade-in">
            <EpisodeMissingAlertManager
              onNavigateToUploadEpisode={(aTitle, aSeason, aEp) => {
                setActiveTab("episodes");
                if (aSeason) setSeasonNum(Number(aSeason));
                if (aEp) setEpisodeNum(Number(aEp));
              }}
            />
          </div>
        )}

        {/* NOTICE MANAGEMENT TAB */}
        {activeTab === "notices" && (
          <div className="animate-fade-in">
            <NoticeManager />
          </div>
        )}

        {/* STATS SCREEN */}
        {activeTab === "stats" && (
          <AdminDashboardStats
            animeList={animeList}
            allEpisodesList={allEpisodesList}
            missingAlertsCount={missingAlertsCount}
            onNavigateTab={(tab) => setActiveTab(tab)}
            onRefreshAll={() => {
              refreshAnime();
              refreshAllEpisodes();
            }}
          />
        )}



        {/* ANIME MOVIE UPLOAD TAB (SECTION 3) */}
        {activeTab === "movie" && <AdminMovieManager />}



        {/* IMPORT ANIME VIA MULTI-SOURCE API */}
        {activeTab === "import" && (
          <div className="flex flex-col gap-6 max-w-2xl">
            <div className="p-6 rounded-2xl bg-[#131520] border border-white/5 flex flex-col gap-4 relative">
              <h3 className="font-display font-extrabold text-lg text-white flex items-center gap-2">
                <Sparkles className="w-5 h-5 text-amber-400 animate-pulse" />
                Multi-Source Anime Metadata System
              </h3>
              <p className="text-gray-400 text-xs leading-relaxed">
                Type any anime title below. System queries AniList, Jikan, Kitsu, and TMDB with automatic fallbacks to compile complete titles, descriptions, genres, studio, character lists, and high-res posters/banners.
              </p>

              <div className="relative mt-2">
                <form onSubmit={handleImportSearch} className="flex gap-3">
                  <div className="relative flex-1">
                    <input
                      type="text"
                      placeholder="Type anime title (e.g. Solo Leveling, Demon Slayer, Jujutsu Kaisen...)"
                      value={importQuery}
                      onChange={(e) => {
                        setImportQuery(e.target.value);
                        setShowSuggestions(true);
                      }}
                      onFocus={() => setShowSuggestions(true)}
                      className="w-full px-4 py-3 bg-[#0d0e12] border border-white/10 focus:border-amber-500 rounded-xl text-sm text-white focus:outline-none"
                      required
                    />
                    {isSearchingSuggestions && (
                      <div className="absolute right-3 top-3.5">
                        <RefreshCw className="w-4 h-4 text-amber-500 animate-spin" />
                      </div>
                    )}
                  </div>
                  <button
                    type="submit"
                    disabled={isImporting}
                    className="px-6 py-3 bg-amber-500 text-black font-bold text-sm rounded-xl hover:opacity-95 transform active:scale-95 transition-all flex items-center gap-2 disabled:opacity-40"
                  >
                    {isImporting ? <RefreshCw className="w-4 h-4 animate-spin" /> : "Search AI"}
                  </button>
                </form>

                {/* Live Grounded Search Suggestions Dropdown */}
                {showSuggestions && (searchResults.length > 0 || (importQuery.trim() && POPULAR_ANIME_SUGGESTIONS.some(item => item.toLowerCase().includes(importQuery.toLowerCase())))) && (
                  <div className="absolute top-full left-0 right-0 mt-2 bg-[#0d0e12] border border-white/10 rounded-2xl overflow-hidden shadow-2xl z-50 max-h-72 overflow-y-auto">
                    <div className="p-2 border-b border-white/5 bg-white/5 flex items-center justify-between">
                      <span className="text-[10px] font-mono text-gray-400 uppercase tracking-wider px-2">Suggestions</span>
                      <button onClick={() => setShowSuggestions(false)} className="text-[10px] text-amber-500 font-bold hover:underline px-2">Close</button>
                    </div>
                    {searchResults.length > 0 ? (
                      searchResults.map((item, i) => (
                        <button
                          key={i}
                          type="button"
                          onClick={() => {
                            handleSelectSuggestion(item);
                            setShowSuggestions(false);
                          }}
                          className="w-full flex items-center gap-3 text-left px-4 py-3 hover:bg-white/5 border-b border-white/5 last:border-0 transition-all"
                        >
                          {item.poster ? (
                            <img src={item.poster} alt="" className="w-9 h-12 object-cover rounded-md bg-gray-800 shrink-0" referrerPolicy="no-referrer" />
                          ) : (
                            <div className="w-9 h-12 bg-gray-800 rounded-md flex items-center justify-center text-xs text-gray-500 font-mono shrink-0">Art</div>
                          )}
                          <div className="flex-1 min-w-0">
                            <p className="text-xs font-bold text-white truncate">{item.title}</p>
                            <p className="text-[10px] font-mono text-gray-400 mt-0.5">
                              {item.releaseYear || "N/A"} • {item.type || "TV Series"}
                            </p>
                          </div>
                        </button>
                      ))
                    ) : (
                      POPULAR_ANIME_SUGGESTIONS.filter(item => item.toLowerCase().includes(importQuery.toLowerCase())).map((suggestion, i) => (
                        <button
                          key={i}
                          type="button"
                          onClick={async () => {
                            const searchRes = await searchAnimeSuggestions(suggestion);
                            if (searchRes.length > 0) {
                              handleSelectSuggestion(searchRes[0]);
                            } else {
                              handleSelectSuggestion({
                                id: `popular-${i}`,
                                title: suggestion,
                                englishTitle: suggestion,
                                romajiTitle: suggestion,
                                japaneseTitle: "",
                                synonyms: [],
                                releaseYear: 2024,
                                type: "TV",
                                poster: "https://images.unsplash.com/photo-1578632767115-351597cf2477?q=80&w=600",
                                banner: "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?q=80&w=1600",
                                sourceApi: "AniList",
                                rawObject: null
                              });
                            }
                            setShowSuggestions(false);
                          }}
                          className="w-full text-left px-4 py-3 text-xs text-gray-300 hover:text-white hover:bg-amber-500/10 border-b border-white/5 last:border-0 transition-all"
                        >
                          {suggestion}
                        </button>
                      ))
                    )}
                  </div>
                )}
              </div>

              {importError && (
                <div className="p-3 rounded-xl bg-red-500/10 border border-red-500/10 text-red-400 text-xs flex items-center gap-2">
                  <AlertCircle className="w-4.5 h-4.5" />
                  <span>{importError}</span>
                </div>
              )}
            </div>

            {/* Smart Duplicate Entry Resolution Dialog */}
            {showDuplicateDialog && duplicateAnimeMatch && (
              <div className="p-6 rounded-2xl bg-amber-500/10 border border-amber-500/30 flex flex-col gap-4 animate-fade-in shadow-xl">
                <div className="flex items-start gap-3">
                  <AlertCircle className="w-6 h-6 text-amber-500 shrink-0 mt-0.5" />
                  <div className="flex-1">
                    <h4 className="text-sm font-bold text-white">Duplicate Anime Entry Detected</h4>
                    <p className="text-xs text-gray-400 mt-1 leading-relaxed">
                      An entry named <strong className="text-white">"{duplicateAnimeMatch.title}"</strong> already exists in your streaming catalog database. How would you like to handle this import?
                    </p>
                  </div>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-3 gap-3 mt-1.5">
                  <button
                    onClick={handleResolveUpdate}
                    disabled={isLoading}
                    className="px-4 py-2.5 bg-amber-500 text-black font-bold text-xs rounded-xl hover:bg-amber-400 transition-all flex items-center justify-center gap-1.5 shadow cursor-pointer"
                  >
                    <RefreshCw className="w-3.5 h-3.5" />
                    Update Existing Anime
                  </button>
                  <button
                    onClick={handleResolveNewVersion}
                    disabled={isLoading}
                    className="px-4 py-2.5 bg-white/10 text-white font-bold text-xs rounded-xl hover:bg-white/15 transition-all flex items-center justify-center gap-1.5 border border-white/10 cursor-pointer"
                  >
                    <Plus className="w-3.5 h-3.5" />
                    Create New Version
                  </button>
                  <button
                    onClick={handleResolveCancel}
                    disabled={isLoading}
                    className="px-4 py-2.5 bg-red-500/10 text-red-400 font-bold text-xs rounded-xl hover:bg-red-500/20 transition-all flex items-center justify-center gap-1.5 cursor-pointer"
                  >
                    <X className="w-3.5 h-3.5" />
                    Cancel Import
                  </button>
                </div>
              </div>
            )}

            {/* Imported Anime Preview & Edit Sheet */}
            {importedAnime && !showDuplicateDialog && (
              <div className="p-6 rounded-2xl bg-[#131520] border border-white/10 flex flex-col gap-5 animate-fade-in shadow-2xl">
                <div className="flex items-center justify-between border-b border-white/5 pb-2.5">
                  <h4 className="font-display font-extrabold text-base text-white flex items-center gap-2">
                    <Info className="w-5 h-5 text-amber-500" />
                    Review & Edit Imported Metadata
                  </h4>
                  <button
                    onClick={() => setImportedAnime(null)}
                    className="p-1 rounded-lg text-gray-400 hover:text-white hover:bg-white/5"
                  >
                    <X className="w-4 h-4" />
                  </button>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  {/* Title */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Anime Title</label>
                    <input
                      type="text"
                      value={importedAnime.title || ""}
                      onChange={(e) => updateImportedField("title", e.target.value)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>

                  {/* English Title */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">English Title</label>
                    <input
                      type="text"
                      value={importedAnime.englishTitle || ""}
                      onChange={(e) => updateImportedField("englishTitle", e.target.value)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>

                  {/* Japanese Kanji Title */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Japanese Kanji Title</label>
                    <input
                      type="text"
                      value={importedAnime.japaneseTitle || ""}
                      onChange={(e) => updateImportedField("japaneseTitle", e.target.value)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>

                  {/* Native Japanese Title */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Native Title</label>
                    <input
                      type="text"
                      value={importedAnime.nativeTitle || ""}
                      onChange={(e) => updateImportedField("nativeTitle", e.target.value)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>

                  {/* Romaji Pronunciation Title */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Romaji Title</label>
                    <input
                      type="text"
                      value={importedAnime.romajiTitle || ""}
                      onChange={(e) => updateImportedField("romajiTitle", e.target.value)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>

                  {/* Studio */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Studio</label>
                    <input
                      type="text"
                      value={importedAnime.studio || ""}
                      onChange={(e) => updateImportedField("studio", e.target.value)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>
                </div>

                {/* Description */}
                <div className="flex flex-col gap-1.5">
                  <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Description</label>
                  <textarea
                    rows={3}
                    value={importedAnime.description || ""}
                    onChange={(e) => updateImportedField("description", e.target.value)}
                    className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none resize-none"
                  />
                </div>

                <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                  {/* Release Year */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Release Year</label>
                    <input
                      type="number"
                      value={importedAnime.releaseYear || 2024}
                      onChange={(e) => updateImportedField("releaseYear", parseInt(e.target.value) || 2024)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>

                  {/* Rating */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Rating</label>
                    <input
                      type="text"
                      value={importedAnime.rating || "PG-13"}
                      onChange={(e) => updateImportedField("rating", e.target.value)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>

                  {/* Format/Type */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Format</label>
                    <select
                      value={importedAnime.type || "TV"}
                      onChange={(e) => updateImportedField("type", e.target.value)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    >
                      <option value="TV">TV</option>
                      <option value="Movie">Movie</option>
                      <option value="OVA">OVA</option>
                      <option value="ONA">ONA</option>
                    </select>
                  </div>

                  {/* Status */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Status</label>
                    <select
                      value={importedAnime.status || "Ongoing"}
                      onChange={(e) => updateImportedField("status", e.target.value)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    >
                      <option value="Ongoing">Ongoing</option>
                      <option value="Completed">Completed</option>
                    </select>
                  </div>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  {/* Poster URL */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Poster Image URL</label>
                    <input
                      type="text"
                      value={importedAnime.poster || ""}
                      onChange={(e) => updateImportedField("poster", e.target.value)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>

                  {/* Banner URL */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Banner Image URL</label>
                    <input
                      type="text"
                      value={importedAnime.banner || ""}
                      onChange={(e) => updateImportedField("banner", e.target.value)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  {/* Genres (as comma-separated input) */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Genres (comma separated)</label>
                    <input
                      type="text"
                      value={Array.isArray(importedAnime.genres) ? importedAnime.genres.join(", ") : ""}
                      onChange={(e) => {
                        const arr = e.target.value.split(",").map(g => g.trim()).filter(Boolean);
                        updateImportedField("genres", arr);
                      }}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>

                  {/* Themes */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Themes (comma separated)</label>
                    <input
                      type="text"
                      value={Array.isArray(importedAnime.themes) ? importedAnime.themes.join(", ") : ""}
                      onChange={(e) => {
                        const arr = e.target.value.split(",").map(t => t.trim()).filter(Boolean);
                        updateImportedField("themes", arr);
                      }}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  {/* Producers */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Producers (comma separated)</label>
                    <input
                      type="text"
                      value={Array.isArray(importedAnime.producers) ? importedAnime.producers.join(", ") : ""}
                      onChange={(e) => {
                        const arr = e.target.value.split(",").map(p => p.trim()).filter(Boolean);
                        updateImportedField("producers", arr);
                      }}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>

                  {/* Trailer URL */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Trailer Embed URL</label>
                    <input
                      type="text"
                      value={importedAnime.trailerUrl || ""}
                      onChange={(e) => updateImportedField("trailerUrl", e.target.value)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>
                </div>

                <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                  {/* Score */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Score (out of 10)</label>
                    <input
                      type="number"
                      step="0.01"
                      min="0"
                      max="10"
                      value={importedAnime.score || 0}
                      onChange={(e) => updateImportedField("score", parseFloat(e.target.value) || 0)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>

                  {/* Total Episodes */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Total Episodes</label>
                    <input
                      type="number"
                      value={importedAnime.totalEpisodes || 12}
                      onChange={(e) => updateImportedField("totalEpisodes", parseInt(e.target.value) || 12)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>

                  {/* Cover Color Theme Hex */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Cover Hex Color</label>
                    <input
                      type="text"
                      placeholder="e.g. #ff0055"
                      value={importedAnime.coverColor || "#10121e"}
                      onChange={(e) => updateImportedField("coverColor", e.target.value)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>

                  {/* Source */}
                  <div className="flex flex-col gap-1.5">
                    <label className="text-[10px] font-bold text-gray-400 font-mono uppercase tracking-wider">Source Material</label>
                    <input
                      type="text"
                      placeholder="e.g. Manga, Novel, Game"
                      value={importedAnime.source || "Manga"}
                      onChange={(e) => updateImportedField("source", e.target.value)}
                      className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none"
                    />
                  </div>
                </div>

                {/* Character list review and editor */}
                <div className="flex flex-col gap-2.5 border-t border-white/5 pt-4">
                  <div className="flex items-center justify-between">
                    <label className="text-[10px] font-bold text-amber-500 font-mono uppercase tracking-wider">Character Cast List</label>
                    <button
                      type="button"
                      onClick={() => {
                        const currentChars = importedAnime.characters || [];
                        updateImportedField("characters", [
                          ...currentChars,
                          { name: "New Character", role: "Supporting", voiceActor: "N/A" }
                        ]);
                      }}
                      className="px-2 py-1 bg-amber-500/10 text-amber-500 hover:bg-amber-500/20 text-[10px] font-bold rounded-lg flex items-center gap-1 transition-all"
                    >
                      <Plus className="w-3 h-3" /> Add Character
                    </button>
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 max-h-60 overflow-y-auto pr-1">
                    {Array.isArray(importedAnime.characters) && importedAnime.characters.map((char, index) => (
                      <div key={index} className="p-3 rounded-xl bg-[#0d0e12] border border-white/5 flex flex-col gap-2 relative">
                        <button
                          type="button"
                          onClick={() => {
                            const filtered = (importedAnime.characters || []).filter((_, idx) => idx !== index);
                            updateImportedField("characters", filtered);
                          }}
                          className="absolute right-2 top-2 text-gray-500 hover:text-red-400 p-1"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                        
                        <div className="flex flex-col gap-1">
                          <span className="text-[9px] font-mono text-gray-500 uppercase">Char Name</span>
                          <input
                            type="text"
                            value={char.name || ""}
                            onChange={(e) => {
                              const updatedList = [...(importedAnime.characters || [])];
                              updatedList[index] = { ...updatedList[index], name: e.target.value };
                              updateImportedField("characters", updatedList);
                            }}
                            className="bg-transparent border-b border-white/10 text-xs text-white focus:outline-none focus:border-amber-500 py-0.5"
                          />
                        </div>

                        <div className="grid grid-cols-2 gap-2 mt-1">
                          <div className="flex flex-col gap-0.5">
                            <span className="text-[8px] font-mono text-gray-500 uppercase">Role</span>
                            <select
                              value={char.role || "Supporting"}
                              onChange={(e) => {
                                const updatedList = [...(importedAnime.characters || [])];
                                updatedList[index] = { ...updatedList[index], role: e.target.value };
                                updateImportedField("characters", updatedList);
                              }}
                              className="bg-[#131520] border border-white/5 rounded px-1 py-0.5 text-[10px] text-gray-300 focus:outline-none"
                            >
                              <option value="Main">Main</option>
                              <option value="Supporting">Supporting</option>
                            </select>
                          </div>

                          <div className="flex flex-col gap-0.5">
                            <span className="text-[8px] font-mono text-gray-500 uppercase">VA / Actor</span>
                            <input
                              type="text"
                              value={char.voiceActor || ""}
                              onChange={(e) => {
                                const updatedList = [...(importedAnime.characters || [])];
                                updatedList[index] = { ...updatedList[index], voiceActor: e.target.value };
                                updateImportedField("characters", updatedList);
                              }}
                              className="bg-transparent border-b border-white/10 text-[10px] text-gray-300 focus:outline-none py-0.5"
                            />
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>

                <button
                  onClick={handleSaveImportedAnime}
                  disabled={isLoading}
                  className="w-full py-3.5 mt-2 rounded-xl bg-amber-500 text-black font-extrabold text-sm uppercase tracking-widest hover:opacity-95 transform active:scale-95 transition-all shadow-lg cursor-pointer"
                >
                  {isLoading ? "Saving..." : "Publish Anime to Website"}
                </button>
              </div>
            )}
          </div>
        )}

        {/* PUBLISH EPISODE & VIDEO PLAYBACK ID */}
        {activeTab === "episodes" && (
          <div className="flex flex-col gap-8">
            <div className="flex flex-col lg:flex-row gap-8 items-start">
              
              {/* Form column */}
              <form onSubmit={handlePublishEpisode} className="flex-1 max-w-2xl p-6 rounded-2xl bg-[#131520] border border-white/5 flex flex-col gap-4">
                <h3 className="font-display font-extrabold text-lg text-white flex items-center gap-2">
                  <Upload className="w-5 h-5 text-amber-500" />
                  {editingEpisodeId ? "Edit Episode Info" : "Publish New Episode with Video Stream URL"}
                </h3>

                {/* Select Anime & Audio Language */}
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div className="flex flex-col gap-2">
                    <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider">Select Anime Title *</label>
                    <select
                      value={selectedAnimeId}
                      onChange={(e) => setSelectedAnimeId(e.target.value)}
                      className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-gray-300 focus:outline-none focus:border-amber-500"
                      required
                    >
                      <option value="">-- Choose Published Anime --</option>
                      {animeList.map(a => (
                        <option key={a.id} value={a.id}>{a.title}</option>
                      ))}
                    </select>
                  </div>

                  <div className="flex flex-col gap-2">
                    <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider">Audio Language</label>
                    <select
                      value={singleAudioLanguage}
                      onChange={(e) => setSingleAudioLanguage(e.target.value as SupportedLanguage)}
                      className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                    >
                      {["Japanese", "English", "Hindi", "Bangla", "Spanish"].map(l => (
                        <option key={l} value={l}>{l}</option>
                      ))}
                    </select>
                  </div>
                </div>

                {/* Admin Manual Season & Episode Control Banner */}
                {(() => {
                  const targetAnime = animeList.find((a) => a.id === selectedAnimeId);
                  const currentSeasonCount = targetAnime ? getSeasonEpisodeCount(targetAnime.id, seasonNum, allEpisodesList) : 12;
                  const duplicateEp = allEpisodesList.find(
                    (ep) =>
                      (ep.animeId === selectedAnimeId || (targetAnime && ep.animeId === targetAnime.title)) &&
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
                              Admin Manual Season & Episode Control
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
                        <div className="p-3.5 rounded-2xl bg-amber-500/10 border border-amber-500/30 text-amber-300 text-xs flex items-center justify-between gap-3 animate-fade-in">
                          <div className="flex items-center gap-2.5 font-bold">
                            <ShieldAlert className="w-5 h-5 text-amber-400 shrink-0" />
                            <div>
                              <span>⚠️ Duplicate Episode Alert: Episode {episodeNum} already exists in Season {seasonNum} ({duplicateEp.title})!</span>
                              <p className="text-[10px] text-amber-400/80 font-normal font-mono mt-0.5">
                                Saving will update or merge language audio tracks for this existing episode slot.
                              </p>
                            </div>
                          </div>
                        </div>
                      )}
                    </div>
                  );
                })()}

                {/* Season Number, Episode Number and title */}
                <div className="grid grid-cols-1 sm:grid-cols-4 gap-4">
                  <div className="flex flex-col gap-2">
                    <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider flex items-center gap-1">
                      Season *
                    </label>
                    <input
                      type="number"
                      min={1}
                      value={seasonNum}
                      onChange={(e) => setSeasonNum(parseInt(e.target.value) || 1)}
                      className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                      required
                    />
                  </div>
                  <div className="flex flex-col gap-2">
                    <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider flex items-center gap-1">
                      EP Number *
                    </label>
                    <input
                      type="number"
                      min={1}
                      value={episodeNum}
                      onChange={(e) => setEpisodeNum(parseInt(e.target.value) || 1)}
                      className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
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
                      className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                    />
                  </div>
                </div>

                {/* Episode Thumbnail */}
                <div className="flex flex-col gap-2">
                  <div className="flex items-center justify-between">
                    <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider">
                      Episode Thumbnail Image
                    </label>
                    <button
                      type="button"
                      onClick={() => {
                        const target = animeList.find(a => a.id === selectedAnimeId);
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
                      type="text"
                      placeholder="https://images.unsplash.com/... or image URL"
                      value={episodeThumbnail}
                      onChange={(e) => setEpisodeThumbnail(e.target.value)}
                      className="flex-1 px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
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

                {/* Video Stream URL / Storage Key Input */}
                <div className="flex flex-col gap-2 bg-[#0d0e12] p-4 rounded-xl border border-amber-500/20">
                  <label className="text-xs font-bold text-amber-400 font-mono uppercase tracking-wider flex items-center justify-between">
                    <span>Video Stream URL or R2 Key *</span>
                    <span className="text-[10px] text-gray-400 font-normal">Cloudflare R2 Direct Stream</span>
                  </label>
                  <input
                    type="text"
                    placeholder="e.g. https://prophecy-verse-video-api.newprophecy4.workers.dev/video/anime/season/ep1.mp4"
                    value={muxPlaybackId}
                    onChange={(e) => setMuxPlaybackId(e.target.value)}
                    className="px-3.5 py-3 bg-[#131520] border border-white/10 rounded-xl text-sm text-white font-mono focus:outline-none focus:border-amber-500"
                    required
                  />
                  <p className="text-[11px] text-gray-400">
                    Paste the Cloudflare R2 video stream URL or R2 object key here.
                  </p>
                </div>

                {/* Quality Label & Notes */}
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div className="flex flex-col gap-2">
                    <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider">Video Quality Label (Optional)</label>
                    <input
                      type="text"
                      placeholder="e.g. 1080p HD, 4K, 720p"
                      value={videoQualityLabel}
                      onChange={(e) => setVideoQualityLabel(e.target.value)}
                      className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                    />
                  </div>
                  <div className="flex flex-col gap-2">
                    <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider">Notes (Optional)</label>
                    <input
                      type="text"
                      placeholder="e.g. Uncut, Director's Cut"
                      value={videoNotes}
                      onChange={(e) => setVideoNotes(e.target.value)}
                      className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                    />
                  </div>
                </div>

                {/* Description */}
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

                {/* Release Date & Visibility check */}
                <div className="grid grid-cols-2 gap-4">
                  <div className="flex flex-col gap-2">
                    <label className="text-xs font-bold text-gray-400 font-mono uppercase tracking-wider">Release Date</label>
                    <input
                      type="date"
                      value={episodeReleaseDate}
                      onChange={(e) => setEpisodeReleaseDate(e.target.value)}
                      className="px-3.5 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none"
                    />
                  </div>
                  <div className="flex items-center gap-3 mt-6">
                    <input
                      type="checkbox"
                      id="episodeIsVisibleCheck"
                      checked={episodeIsVisible}
                      onChange={(e) => setEpisodeIsVisible(e.target.checked)}
                      className="w-4 h-4 accent-amber-500 rounded cursor-pointer"
                    />
                    <label htmlFor="episodeIsVisibleCheck" className="text-xs font-bold text-gray-300 font-mono uppercase tracking-wider cursor-pointer select-none">
                      Is Visible to Users
                    </label>
                  </div>
                </div>

                <div className="flex gap-3 mt-2">
                  <button
                    type="submit"
                    disabled={isLoading}
                    className="flex-1 py-3.5 rounded-xl bg-amber-500 text-black font-extrabold text-sm uppercase tracking-widest hover:opacity-95 transform active:scale-95 transition-all shadow-md disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer"
                  >
                    {isLoading ? "Saving..." : (editingEpisodeId ? "Update Episode" : "Publish Episode")}
                  </button>
                  {editingEpisodeId && (
                    <button
                      type="button"
                      onClick={() => {
                        const targetEp = allEpisodesList.find(e => e.id === editingEpisodeId) || episodesList.find(e => e.id === editingEpisodeId);
                        if (targetEp) {
                          handleOpenLanguageManagement(targetEp, animeList.find(a => a.id === targetEp.animeId)?.title);
                        }
                      }}
                      className="px-4 py-3.5 rounded-xl bg-blue-500/10 hover:bg-blue-500/20 border border-blue-500/20 text-xs font-bold text-blue-400 flex items-center gap-1.5 transition-all uppercase tracking-wider cursor-pointer"
                    >
                      <Globe className="w-4 h-4" />
                      Language Management
                    </button>
                  )}
                  {editingEpisodeId && (
                    <button
                      type="button"
                      onClick={() => {
                        setEditingEpisodeId(null);
                        setEpisodeTitle("");
                        setEpisodeDesc("");
                        setMuxPlaybackId("");
                        setVideoQualityLabel("1080p HD");
                        setVideoNotes("");
                        setEpisodeSubtitlesUrl("");
                        setEpisodeReleaseDate(new Date().toISOString().split("T")[0]);
                        setEpisodeIsVisible(true);
                      }}
                      className="px-4 py-3.5 rounded-xl bg-white/5 border border-white/10 text-xs font-bold text-gray-400 hover:text-white hover:bg-white/10 transition-all uppercase tracking-wider"
                    >
                      Cancel
                    </button>
                  )}
                </div>
              </form>

            </div>

            {/* Episode Registry for Selected Anime */}
            {selectedAnimeId && (
              <div className="p-6 rounded-2xl bg-[#131520] border border-white/5 flex flex-col gap-4">
                <div className="flex items-center justify-between border-b border-white/5 pb-2.5">
                  <h3 className="font-display font-extrabold text-base text-white flex items-center gap-2">
                    <PlaySquare className="w-5 h-5 text-amber-500" />
                    Episodes Registry for selected Anime
                  </h3>
                  <span className="text-xs text-gray-400 font-mono">
                    {episodesList.length} Episode(s) published
                  </span>
                </div>

                {episodesList.length === 0 ? (
                  <p className="text-gray-400 text-xs py-2">No episodes published yet for this anime title.</p>
                ) : (
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4 max-h-[350px] overflow-y-auto no-scrollbar pr-1">
                    {episodesList.map((ep) => {
                      const isEncoding = ep.muxStatus === "Encoding" || ep.status === "Waiting For Encoding";
                      const isErrored = ep.muxStatus === "Errored" || ep.status === "Failed";
                      const isReady = ep.muxStatus === "Ready" || ep.status === "Published";

                      return (
                        <div
                          key={ep.id}
                          className="flex items-center justify-between p-4 rounded-xl bg-[#0d0e12]/60 border border-white/5 hover:border-white/10 transition-all flex-wrap gap-2"
                        >
                          <div className="flex items-center gap-3 flex-1 min-w-0">
                            <div className="w-9 h-9 rounded-lg bg-amber-500/10 text-amber-500 font-extrabold font-mono text-sm flex items-center justify-center shrink-0">
                              {ep.episodeNumber}
                            </div>
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-2 flex-wrap">
                                <h4 className="font-bold text-sm text-white truncate">{ep.title}</h4>
                                <span className={`text-[10px] font-mono px-2 py-0.5 rounded-full uppercase tracking-wider font-bold ${
                                  isReady ? "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20" :
                                  isEncoding ? "bg-amber-500/10 text-amber-400 border border-amber-500/20 animate-pulse" :
                                  isErrored ? "bg-red-500/10 text-red-400 border border-red-500/20" :
                                  "bg-gray-800 text-gray-400"
                                }`}>
                                  {isReady ? "Published" : isEncoding ? "Publishing" : isErrored ? "Upload Failed" : ep.status}
                                </span>
                              </div>
                              <p className="text-xs text-gray-400 truncate mt-0.5 max-w-[200px] md:max-w-[280px]">
                                {ep.description}
                              </p>
                            </div>
                          </div>

                          <div className="flex items-center gap-2 ml-2 shrink-0">
                            <button
                              type="button"
                              onClick={() => handleStartEditEpisode(ep)}
                              className="p-2 rounded-lg text-gray-400 hover:text-amber-500 hover:bg-amber-500/10 transition-colors"
                              title="Edit Episode"
                            >
                              <Edit className="w-4 h-4" />
                            </button>
                            <button
                              type="button"
                              onClick={() => handleDeleteEpisode(ep.id)}
                              className="p-2 rounded-lg text-gray-400 hover:text-red-500 hover:bg-red-500/10 transition-colors"
                              title="Delete Episode"
                            >
                              <Trash2 className="w-4 h-4" />
                            </button>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            )}

          </div>
        )}

        {/* BULK EPISODE MANAGEMENT TAB */}
        {activeTab === "episodes-manage" && (
          <div className="animate-fade-in">
            <AdminBulkEpisodeManager
              animeList={animeList}
              allEpisodesList={allEpisodesList}
              onRefresh={() => {
                refreshAnime();
                refreshAllEpisodes();
              }}
              onOpenLanguageManagement={(ep, animeTitle) => {
                handleOpenLanguageManagement(ep, animeTitle);
              }}
              onEditSingleEpisode={(ep) => {
                handleEditEpisode(ep);
                setActiveTab("episodes");
              }}
            />
          </div>
        )}

        {/* SEASON MANAGER TAB */}
        {activeTab === "seasons" && (
          <div className="animate-fade-in">
            <AdminSeasonManager
              animeList={animeList}
              allEpisodesList={allEpisodesList}
              onRefresh={() => {
                refreshAnime();
                refreshAllEpisodes();
              }}
              onNavigateToEpisodes={(animeId) => {
                setSelectedAnimeId(animeId);
                setActiveTab("episodes-manage");
              }}
            />
          </div>
        )}

        {/* MISSING EPISODES DETECTOR & AUDITOR TAB */}
        {activeTab === "missing-alerts" && (
          <div className="animate-fade-in flex flex-col gap-6">
            <AdminMissingEpisodesWidget
              animeList={animeList}
              allEpisodesList={allEpisodesList}
              onUploadMissingEpisode={(animeId, season, epNum) => {
                setSelectedAnimeId(animeId);
                setSeasonNum(season);
                setEpisodeNum(epNum);
                setActiveTab("episodes");
              }}
            />
            <div className="pt-4 border-t border-white/5">
              <h4 className="font-bold text-sm text-gray-300 mb-3">Old Missing Alert Records</h4>
              <EpisodeMissingAlertManager
                onNavigateToUploadEpisode={(aTitle, aSeason, aEp) => {
                  setActiveTab("episodes");
                  if (aSeason) setSeasonNum(Number(aSeason));
                  if (aEp) setEpisodeNum(Number(aEp));
                }}
              />
            </div>
          </div>
        )}


        {/* REGISTRY TAB */}
        {activeTab === "anime" && (
          <div className="flex flex-col gap-4">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-2 border-b border-white/5">
              <h3 className="text-xs font-mono uppercase text-gray-400">
                Published Anime Catalog ({animeList.length})
              </h3>
              
              <div className="flex items-center gap-2 flex-wrap">
                <button
                  type="button"
                  onClick={handleSyncAllAnime}
                  disabled={isBatchSyncing}
                  className="px-3 py-1.5 rounded-xl bg-amber-500/10 hover:bg-amber-500/20 text-amber-400 border border-amber-500/20 text-xs font-bold font-mono flex items-center gap-1.5 transition-all cursor-pointer disabled:opacity-50"
                  title="Re-sync metadata and official season episode counts for all anime without touching uploaded videos"
                >
                  <RefreshCw className={`w-3.5 h-3.5 ${isBatchSyncing ? "animate-spin text-amber-400" : ""}`} />
                  <span>{isBatchSyncing ? "Syncing Catalog..." : "Sync All Anime"}</span>
                </button>

                <button
                  type="button"
                  onClick={handleRepairDatabase}
                  disabled={isRepairingDb}
                  className="px-3 py-1.5 rounded-xl bg-blue-500/10 hover:bg-blue-500/20 text-blue-400 border border-blue-500/20 text-xs font-bold font-mono flex items-center gap-1.5 transition-all cursor-pointer disabled:opacity-50"
                  title="Inspect database, fix missing season mappings, and merge duplicate episode records"
                >
                  <Sparkles className={`w-3.5 h-3.5 ${isRepairingDb ? "animate-spin text-blue-400" : ""}`} />
                  <span>{isRepairingDb ? "Repairing..." : "Database Integrity Doctor"}</span>
                </button>
              </div>
            </div>

            <div className="flex flex-col gap-2 max-h-[450px] overflow-y-auto no-scrollbar">
              {animeList.map(anime => {
                const syncAlert = anilistSyncAlerts[anime.id] || (anime.hasNewSeasonAlert ? { hasNewSeason: true, message: `New Season Alert: "${anime.nextSeasonInfo?.title || 'Sequel'}"` } : null);
                const isSyncing = syncingAnimeId === anime.id;
                const statusLabel = anime.seriesStatus || (anime.status === "RELEASING" ? "Airing" : anime.status === "FINISHED" ? "Completed" : anime.status || "Finished");

                return (
                  <div
                    key={anime.id}
                    className="flex flex-col gap-3 p-4 rounded-xl bg-[#131520]/50 border border-white/5 hover:border-white/10 transition-all"
                  >
                    <div className="flex items-center justify-between flex-wrap gap-3">
                      <div className="flex items-center gap-4">
                        <img
                          src={anime.poster}
                          alt={anime.title}
                          className="w-10 h-14 rounded object-cover border border-white/5"
                        />
                        <div className="flex flex-col gap-1">
                          <div className="flex items-center gap-2 flex-wrap">
                            <h4 className="font-bold text-sm text-white">{anime.title}</h4>
                            <span className={`text-[10px] font-mono font-extrabold px-2 py-0.5 rounded border ${
                              statusLabel === "Airing"
                                ? "bg-emerald-500/10 text-emerald-400 border-emerald-500/20"
                                : statusLabel === "Officially Announced"
                                ? "bg-amber-500/10 text-amber-400 border-amber-500/20"
                                : "bg-blue-500/10 text-blue-400 border-blue-500/20"
                            }`}>
                              {statusLabel}
                            </span>
                          </div>
                          <p className="text-xs text-gray-400">
                            {anime.studio} • {anime.releaseYear} • {anime.type} • Season {anime.currentSeason || 1}
                          </p>
                        </div>
                      </div>

                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          onClick={() => handleSyncAniListForAnime(anime)}
                          disabled={isSyncing}
                          className="px-3 py-1.5 rounded-lg bg-white/5 hover:bg-white/10 text-amber-400 text-xs font-bold font-mono flex items-center gap-1.5 transition-all border border-amber-500/20 cursor-pointer disabled:opacity-50"
                          title="Check AniList GraphQL for new seasons, episodes, or status updates"
                        >
                          <RefreshCw className={`w-3.5 h-3.5 ${isSyncing ? "animate-spin text-amber-400" : ""}`} />
                          {isSyncing ? "Syncing..." : "Sync AniList Updates"}
                        </button>

                        <button
                          onClick={() => handleStartEditAnime(anime)}
                          className="p-2.5 rounded-lg text-gray-400 hover:text-amber-500 hover:bg-amber-500/10 transition-colors cursor-pointer"
                          title="Edit Anime"
                        >
                          <Edit className="w-4 h-4" />
                        </button>
                        <button
                          onClick={() => handleDeleteAnime(anime.id)}
                          className="p-2.5 rounded-lg text-gray-400 hover:text-red-500 hover:bg-red-500/10 transition-colors cursor-pointer"
                          title="Delete Anime"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    </div>

                    {/* New Season Alert Box */}
                    {syncAlert?.hasNewSeason && (
                      <div className="flex items-center justify-between p-2.5 rounded-lg bg-amber-500/10 border border-amber-500/20 text-xs text-amber-300">
                        <div className="flex items-center gap-2">
                          <Sparkles className="w-4 h-4 text-amber-400 shrink-0" />
                          <span className="font-semibold">{syncAlert.message}</span>
                        </div>
                        <button
                          type="button"
                          onClick={() => {
                            const query = syncAlert.nextSeasonInfo?.title || anime.title;
                            setImportQuery(query);
                            setActiveTab("import");
                          }}
                          className="px-2.5 py-1 bg-amber-500 text-black font-extrabold rounded text-[11px] hover:opacity-90 transition-all cursor-pointer"
                        >
                          Import New Season
                        </button>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {/* USERS ROLES TAB */}
        {activeTab === "users" && (
          <div className="flex flex-col gap-4">
            <h3 className="text-xs font-mono uppercase text-gray-400 border-b border-white/5 pb-2">
              Account Roles Directory
            </h3>

            <div className="flex flex-col gap-2.5 max-h-[450px] overflow-y-auto no-scrollbar">
              {usersList.map(item => (
                <div
                  key={item.uid}
                  className="flex items-center justify-between p-4 rounded-xl bg-[#131520]/50 border border-white/5 flex-wrap gap-4"
                >
                  <div className="flex items-center gap-3">
                    <img
                      src={item.photoURL || `https://api.dicebear.com/7.x/adventurer/svg?seed=${item.displayName}`}
                      alt={item.displayName}
                      className="w-10 h-10 rounded-full border border-white/10 bg-neutral-800 object-cover"
                    />
                    <div>
                      <h4 className="font-bold text-sm text-white">{item.displayName}</h4>
                      <p className="text-xs text-gray-400">{item.email}</p>
                    </div>
                  </div>

                  {/* Role actions togglers */}
                  <div className="flex items-center gap-3">
                    <select
                      value={item.role}
                      onChange={(e) => handleChangeRole(item.uid, e.target.value as UserRole)}
                      className="px-2.5 py-1.5 bg-neutral-900 border border-white/10 rounded-lg text-xs text-gray-300"
                    >
                      <option value="Guest">Guest</option>
                      <option value="User">User</option>
                      <option value="Admin">Admin</option>
                      <option value="Super Admin">Super Admin</option>
                    </select>

                    <button
                      onClick={() => handleDeleteUser(item.uid)}
                      className="p-2 rounded-lg text-gray-500 hover:text-red-500 hover:bg-red-500/10 transition-colors"
                      title="Delete User"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

      </div>

      {/* 5. EDIT ANIME MODAL OVERLAY */}
      {editingAnime && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/85 backdrop-blur-sm overflow-y-auto">
          <div className="relative w-full max-w-2xl bg-[#0f111a] border border-white/10 rounded-2xl shadow-2xl p-6 md:p-8 my-8 max-h-[90vh] overflow-y-auto no-scrollbar">
            
            <button
              onClick={() => setEditingAnime(null)}
              className="absolute top-4 right-4 p-2 text-gray-400 hover:text-white hover:bg-white/5 rounded-full transition-all"
              title="Close modal"
            >
              <X className="w-5 h-5" />
            </button>

            <h3 className="font-display font-extrabold text-xl text-white mb-6 flex items-center gap-2">
              <Edit className="w-5 h-5 text-amber-500" />
              Edit Anime: {editingAnime.title}
            </h3>

            <form onSubmit={handleSaveAnimeEdits} className="flex flex-col gap-5">
              {/* Row 1: Titles */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="flex flex-col gap-1.5">
                  <label className="text-xs font-mono text-gray-400 uppercase">Title</label>
                  <input
                    type="text"
                    value={editingAnime.title || ""}
                    onChange={(e) => setEditingAnime({ ...editingAnime, title: e.target.value })}
                    className="px-3.5 py-2.5 bg-neutral-900 border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                    required
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <label className="text-xs font-mono text-gray-400 uppercase">English Title</label>
                  <input
                    type="text"
                    value={editingAnime.englishTitle || ""}
                    onChange={(e) => setEditingAnime({ ...editingAnime, englishTitle: e.target.value })}
                    className="px-3.5 py-2.5 bg-neutral-900 border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                  />
                </div>
              </div>

              {/* Row 2: Japanese title & Studio */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="flex flex-col gap-1.5">
                  <label className="text-xs font-mono text-gray-400 uppercase">Japanese Title</label>
                  <input
                    type="text"
                    value={editingAnime.japaneseTitle || ""}
                    onChange={(e) => setEditingAnime({ ...editingAnime, japaneseTitle: e.target.value })}
                    className="px-3.5 py-2.5 bg-neutral-900 border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <label className="text-xs font-mono text-gray-400 uppercase">Studio</label>
                  <input
                    type="text"
                    value={editingAnime.studio || ""}
                    onChange={(e) => setEditingAnime({ ...editingAnime, studio: e.target.value })}
                    className="px-3.5 py-2.5 bg-neutral-900 border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                    required
                  />
                </div>
              </div>

              {/* Row 3: Description */}
              <div className="flex flex-col gap-1.5">
                <label className="text-xs font-mono text-gray-400 uppercase">Description</label>
                <textarea
                  value={editingAnime.description || ""}
                  onChange={(e) => setEditingAnime({ ...editingAnime, description: e.target.value })}
                  rows={4}
                  className="px-3.5 py-2.5 bg-neutral-900 border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500 resize-none"
                  required
                />
              </div>

              {/* Row 4: Genres as comma separated */}
              <div className="flex flex-col gap-1.5">
                <label className="text-xs font-mono text-gray-400 uppercase">Genres (Comma separated)</label>
                <input
                  type="text"
                  value={editingAnime.genres?.join(", ") || ""}
                  onChange={(e) => setEditingAnime({ 
                    ...editingAnime, 
                    genres: e.target.value.split(",").map(g => g.trim()).filter(Boolean) 
                  })}
                  className="px-3.5 py-2.5 bg-neutral-900 border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                  required
                />
              </div>

              {/* Row 5: Year, Season, Status, Type */}
              <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                <div className="flex flex-col gap-1.5">
                  <label className="text-xs font-mono text-gray-400 uppercase">Release Year</label>
                  <input
                    type="number"
                    value={editingAnime.releaseYear || 2024}
                    onChange={(e) => setEditingAnime({ ...editingAnime, releaseYear: parseInt(e.target.value) || 2024 })}
                    className="px-3 py-2 bg-neutral-900 border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                    required
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <label className="text-xs font-mono text-gray-400 uppercase">Season</label>
                  <input
                    type="text"
                    value={editingAnime.season || ""}
                    onChange={(e) => setEditingAnime({ ...editingAnime, season: e.target.value })}
                    className="px-3 py-2 bg-neutral-900 border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                    required
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <label className="text-xs font-mono text-gray-400 uppercase">Status</label>
                  <select
                    value={editingAnime.status || "Ongoing"}
                    onChange={(e) => setEditingAnime({ ...editingAnime, status: e.target.value as any })}
                    className="px-3 py-2 bg-neutral-900 border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                  >
                    <option value="Ongoing">Ongoing</option>
                    <option value="Completed">Completed</option>
                    <option value="Upcoming">Upcoming</option>
                  </select>
                </div>
                <div className="flex flex-col gap-1.5">
                  <label className="text-xs font-mono text-gray-400 uppercase">Type</label>
                  <select
                    value={editingAnime.type || "TV"}
                    onChange={(e) => setEditingAnime({ ...editingAnime, type: e.target.value as any })}
                    className="px-3 py-2 bg-neutral-900 border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                  >
                    <option value="TV">TV</option>
                    <option value="Movie">Movie</option>
                    <option value="OVA">OVA</option>
                    <option value="ONA">ONA</option>
                  </select>
                </div>
              </div>

              {/* Row 6: Poster, Banner, Trailer */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="flex flex-col gap-1.5">
                  <label className="text-xs font-mono text-gray-400 uppercase">Poster Image URL</label>
                  <input
                    type="text"
                    value={editingAnime.poster || ""}
                    onChange={(e) => setEditingAnime({ ...editingAnime, poster: e.target.value })}
                    className="px-3.5 py-2.5 bg-neutral-900 border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                    required
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <label className="text-xs font-mono text-gray-400 uppercase">Banner Image URL</label>
                  <input
                    type="text"
                    value={editingAnime.banner || ""}
                    onChange={(e) => setEditingAnime({ ...editingAnime, banner: e.target.value })}
                    className="px-3.5 py-2.5 bg-neutral-900 border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                    required
                  />
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="flex flex-col gap-1.5">
                  <label className="text-xs font-mono text-gray-400 uppercase">Trailer YouTube URL</label>
                  <input
                    type="text"
                    value={editingAnime.trailerUrl || ""}
                    onChange={(e) => setEditingAnime({ ...editingAnime, trailerUrl: e.target.value })}
                    className="px-3.5 py-2.5 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <label className="text-xs font-mono text-gray-400 uppercase">Rating (e.g. PG-13, R-17)</label>
                  <input
                    type="text"
                    value={editingAnime.rating || "PG-13"}
                    onChange={(e) => setEditingAnime({ ...editingAnime, rating: e.target.value })}
                    className="px-3.5 py-2.5 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                  />
                </div>
              </div>

              <div className="flex justify-end gap-3.5 mt-4 border-t border-white/5 pt-5">
                <button
                  type="button"
                  onClick={() => setEditingAnime(null)}
                  className="px-5 py-3 bg-white/5 hover:bg-white/10 border border-white/5 text-xs text-gray-300 hover:text-white font-bold uppercase tracking-widest rounded-xl transition-all"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={isLoading}
                  className="px-6 py-3 bg-amber-500 text-black font-extrabold text-xs uppercase tracking-widest rounded-xl hover:opacity-95 transform active:scale-95 transition-all shadow-md"
                >
                  {isLoading ? "Saving..." : "Save Changes"}
                </button>
              </div>
            </form>

          </div>
        </div>
      )}

      {/* Language Management Modal */}
      <LanguageManagementModal
        isOpen={isLanguageModalOpen}
        onClose={() => {
          setIsLanguageModalOpen(false);
          setLanguageModalEp(null);
        }}
        episode={languageModalEp}
        animeTitle={languageModalAnimeTitle}
        onSaved={() => {
          refreshAllEpisodes();
          refreshAnime();
        }}
      />

      {/* Auto Missing Episode Detection Prompt Modal */}
      {missingDetectModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md">
          <div className="w-full max-w-md bg-[#131520] border border-rose-500/40 rounded-3xl p-6 flex flex-col gap-4 shadow-2xl animate-scale-up text-white">
            <div className="flex items-center gap-3 text-rose-400">
              <div className="p-2.5 rounded-2xl bg-rose-500/20 border border-rose-500/30">
                <AlertTriangle className="w-6 h-6" />
              </div>
              <div>
                <h3 className="text-lg font-black text-white">Missing Episode Detected!</h3>
                <span className="text-[10px] font-mono text-rose-300">Auto Sequence Check</span>
              </div>
            </div>

            <p className="text-xs text-gray-300 leading-relaxed">
              Episode <strong className="text-rose-400 font-extrabold text-sm">#{missingDetectModal.episodeNumber}</strong> for{" "}
              <strong className="text-white">{missingDetectModal.animeName}</strong> (Season {missingDetectModal.season}) was skipped during your upload.
            </p>

            <div className="p-3.5 rounded-2xl bg-white/5 border border-white/10 text-xs text-gray-300 flex flex-col gap-1.5 font-mono">
              <div className="flex justify-between">
                <span className="text-gray-400">Anime:</span>
                <span className="font-bold text-white">{missingDetectModal.animeName}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-400">Season:</span>
                <span className="font-bold text-white">Season {missingDetectModal.season}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-400">Missing Episode:</span>
                <span className="font-extrabold text-rose-400">EP #{missingDetectModal.episodeNumber}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-400">Audio Language:</span>
                <span className="font-bold text-white">{missingDetectModal.language}</span>
              </div>
            </div>

            <p className="text-xs font-extrabold text-amber-300">
              Would you like to add Episode #{missingDetectModal.episodeNumber} to your Missing Alerts list?
            </p>

            <div className="flex items-center justify-end gap-3 pt-3 border-t border-white/10">
              <button
                type="button"
                onClick={() => setMissingDetectModal(null)}
                className="px-4 py-2 rounded-xl bg-white/10 hover:bg-white/15 text-gray-300 font-bold text-xs cursor-pointer transition-all"
              >
                No
              </button>

              <button
                type="button"
                onClick={() => {
                  addMissingAlert({
                    animeName: missingDetectModal.animeName,
                    season: missingDetectModal.season,
                    episodeNumber: missingDetectModal.episodeNumber,
                    language: missingDetectModal.language || "Sub",
                    priority: "High",
                    reason: "Temporary Skip",
                    status: "Missing",
                    notes: `Auto-detected skipped episode when uploading EP #${missingDetectModal.episodeNumber + 1}`,
                  });
                  setMissingDetectModal(null);
                  setSuccessMsg(`Episode ${missingDetectModal.episodeNumber} added to Episode Missing Alert!`);
                  setTimeout(() => setSuccessMsg(null), 3500);
                }}
                className="px-5 py-2 rounded-xl bg-rose-500 hover:bg-rose-400 text-white font-extrabold text-xs cursor-pointer shadow-lg shadow-rose-500/20 transition-all"
              >
                Yes, Add to Missing Alert
              </button>
            </div>
          </div>
        </div>
      )}


    </div>
  );
};
