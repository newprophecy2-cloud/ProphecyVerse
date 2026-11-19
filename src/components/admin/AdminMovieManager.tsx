import React, { useState } from "react";
import { 
  Film, Sparkles, Search, RefreshCw, Upload, Video, Play, Edit3, Trash2, Globe, CheckCircle2, AlertTriangle, X, Eye, Copy, Check
} from "lucide-react";
import { Anime, Episode, SupportedLanguage } from "../../types";
import { useApp } from "../../context/AppContext";
import { searchAnimeSuggestions, compileFullAnimeMetadata } from "../../services/animeApiService";
import { uploadToCloudflareR2, generateR2ObjectKey, buildCloudflareVideoUrl, checkR2ObjectExists } from "../../services/cloudflareService";
import { VideoPlayer } from "../VideoPlayer";
import { LanguageManagementModal } from "../LanguageManagementModal";

export const AdminMovieManager: React.FC = () => {
  const { animeList, allEpisodesList, saveAnime, deleteAnime, saveOrMergeEpisodeLanguage, refreshAnime, refreshAllEpisodes, addNotification } = useApp();

  // Search & Metadata auto fetch
  const [movieSearchQuery, setMovieSearchQuery] = useState("");
  const [isFetchingMovieMeta, setIsFetchingMovieMeta] = useState(false);

  // Form Fields
  const [movieEnglishTitle, setMovieEnglishTitle] = useState("");
  const [movieTitle, setMovieTitle] = useState("");
  const [movieSynopsis, setMovieSynopsis] = useState("");
  const [moviePoster, setMoviePoster] = useState("");
  const [movieBanner, setMovieBanner] = useState("");
  const [movieStudio, setMovieStudio] = useState("");
  const [movieReleaseYear, setMovieReleaseYear] = useState<number>(new Date().getFullYear());
  const [movieDuration, setMovieDuration] = useState("110 min");
  const [movieRating, setMovieRating] = useState("PG-13");
  const [movieGenres, setMovieGenres] = useState<string>("Action, Drama, Fantasy");
  const [movieAudioLang, setMovieAudioLang] = useState<SupportedLanguage>("Japanese");
  const [movieVideoUrl, setMovieVideoUrl] = useState("");
  const [movieSubtitlesUrl, setMovieSubtitlesUrl] = useState("");

  // Upload Mode: 'file' | 'manual'
  const [uploadMode, setUploadMode] = useState<"file" | "manual">("file");
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [isUploading, setIsUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [uploadSpeed, setUploadSpeed] = useState("");
  const [remainingTime, setRemainingTime] = useState("");
  const [uploadStage, setUploadStage] = useState<"idle" | "Uploading" | "Verifying" | "Published" | "Failed">("idle");
  const [uploadErrorMsg, setUploadErrorMsg] = useState<string | null>(null);

  // Feedback State
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  // Playback Player Modal State
  const [previewMovie, setPreviewMovie] = useState<{ anime: Anime; episode?: Episode } | null>(null);

  // Edit Modal State
  const [editingMovie, setEditingMovie] = useState<Anime | null>(null);

  // Language Modal State
  const [langModalMovie, setLangModalMovie] = useState<{ anime: Anime; ep: Episode } | null>(null);

  // Get movies list
  const moviesList = animeList.filter((a) => a.type === "Movie");

  // Copy helper
  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopiedId(text);
    setTimeout(() => setCopiedId(null), 2000);
  };

  // 1. Auto Fetch Metadata from API
  const handleAutoFetchMovieMeta = async () => {
    if (!movieSearchQuery.trim()) return;
    setIsFetchingMovieMeta(true);
    try {
      const results = await searchAnimeSuggestions(movieSearchQuery.trim());
      if (results && results.length > 0) {
        const bestResult = results.find((r) => r.type === "Movie") || results[0];
        const fullMeta = await compileFullAnimeMetadata(bestResult);

        setMovieEnglishTitle(fullMeta.englishTitle || fullMeta.title);
        setMovieTitle(fullMeta.japaneseTitle || fullMeta.title);
        setMovieSynopsis(fullMeta.description || "");
        setMoviePoster(fullMeta.poster || "");
        setMovieBanner(fullMeta.banner || fullMeta.poster || "");
        setMovieStudio(fullMeta.studio || "Anime Studio");
        setMovieReleaseYear(fullMeta.releaseYear || new Date().getFullYear());
        setMovieDuration(fullMeta.duration || "110 min");
        setMovieRating(fullMeta.rating || "PG-13");
        setMovieGenres(fullMeta.genres?.join(", ") || "Action, Fantasy");
        setSuccessMsg(`Auto-fetched metadata for "${fullMeta.title}"!`);
        setTimeout(() => setSuccessMsg(null), 3500);
      } else {
        alert("No metadata found for movie query.");
      }
    } catch (err: any) {
      alert(`Auto-fetch error: ${err.message}`);
    } finally {
      setIsFetchingMovieMeta(false);
    }
  };

  // 2. Direct File Upload to Cloudflare R2
  const handleDirectCloudflareUpload = async () => {
    if (!selectedFile) {
      alert("Please choose a video file first.");
      return;
    }
    const primaryTitle = movieEnglishTitle.trim() || movieTitle.trim();
    if (!primaryTitle) {
      alert("Please provide a Movie Title before starting upload.");
      return;
    }

    setIsUploading(true);
    setUploadStage("Uploading");
    setUploadProgress(0);
    setUploadErrorMsg(null);

    try {
      const objectKey = generateR2ObjectKey(primaryTitle, 1, 1, selectedFile.name);

      await new Promise<string>((resolve, reject) => {
        uploadToCloudflareR2(selectedFile, objectKey, {
          onProgress: (payload) => {
            setUploadProgress(payload.percentage);
            setUploadSpeed(payload.speed || "");
            setRemainingTime(payload.remainingTime || "");
          },
          onSuccess: (videoUrl) => {
            resolve(videoUrl);
          },
          onError: (errMsg) => {
            reject(new Error(errMsg));
          }
        });
      });

      setUploadStage("Verifying");
      const r2Check = await checkR2ObjectExists(objectKey);
      if (!r2Check.exists) {
        throw new Error("R2 Verification failed: Object not found after upload.");
      }

      const generatedUrl = buildCloudflareVideoUrl(objectKey);
      setMovieVideoUrl(generatedUrl);
      setUploadStage("Published");

      // Auto publish movie with Cloudflare video URL
      await publishMovie(generatedUrl, objectKey);
    } catch (err: any) {
      console.error("Direct Cloudflare upload failed:", err);
      setUploadStage("Failed");
      setUploadErrorMsg(err.message || "Movie upload failed.");
      alert(`Upload Error: ${err.message || "Failed to upload movie to Cloudflare R2."}`);
    } finally {
      setIsUploading(false);
    }
  };

  // 3. Publish Movie Record
  const publishMovie = async (videoUrlToUse: string, objectKey?: string) => {
    const primaryTitle = movieEnglishTitle.trim() || movieTitle.trim();
    if (!primaryTitle) {
      alert("Please enter a Movie Title.");
      return;
    }
    const finalVideoUrl = videoUrlToUse.trim();
    if (!finalVideoUrl) {
      alert("Please enter or generate a valid Video Stream URL.");
      return;
    }

    setIsSubmitting(true);
    try {
      const movieId = editingMovie ? editingMovie.id : `movie_${Date.now()}`;
      const genresList = movieGenres.split(",").map((g) => g.trim()).filter(Boolean);

      const movieAnimeObj: Anime = {
        id: movieId,
        title: primaryTitle,
        englishTitle: primaryTitle,
        japaneseTitle: movieTitle.trim() || primaryTitle,
        description: movieSynopsis || `Watch ${primaryTitle} streaming in HD on Prophecy Verse.`,
        poster: moviePoster || "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=800",
        banner: movieBanner || moviePoster || "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=1600",
        type: "Movie",
        status: "Completed",
        season: "Spring",
        episodesCount: 1,
        studio: movieStudio || "Prophecy Animation",
        releaseYear: movieReleaseYear,
        duration: movieDuration,
        rating: movieRating,
        genres: genresList.length > 0 ? genresList : ["Action", "Drama"],
        score: 8.8,
        trailerUrl: "",
        isTrending: true,
        isVisible: true,
      };

      await saveAnime(movieAnimeObj);

      // Subtitles URL
      let autoSubUrl = movieSubtitlesUrl;
      if (!autoSubUrl) {
        try {
          const subRes = await fetch("/api/subtitles/generate", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ videoUrl: finalVideoUrl, language: movieAudioLang }),
          });
          if (subRes.ok) {
            const subData = await subRes.json();
            autoSubUrl = subData.subtitlesUrl || "";
          }
        } catch (e) {
          // ignore subtitle fallback
        }
      }

      const movieEpisodeObj: Episode = {
        id: `${movieId}_ep1`,
        animeId: movieId,
        episodeNumber: 1,
        title: `${primaryTitle} (Full Movie)`,
        description: movieSynopsis || `Watch ${primaryTitle} Full Movie streaming in HD.`,
        releaseDate: new Date().toISOString().split("T")[0],
        duration: movieDuration,
        videoUrl: finalVideoUrl,
        hlsUrl: finalVideoUrl,
        sourceType: "cloudflare",
        storageKey: objectKey || "",
        status: "Published",
        subtitlesUrl: autoSubUrl,
        defaultLanguage: movieAudioLang,
        isVisible: true,
      };

      await saveOrMergeEpisodeLanguage(
        movieId,
        1,
        movieAudioLang,
        {
          language: movieAudioLang,
          sourceType: "cloudflare",
          storageKey: objectKey || "",
          videoUrl: finalVideoUrl,
          hlsUrl: finalVideoUrl,
          subtitlesUrl: autoSubUrl,
          status: "Published",
        },
        movieEpisodeObj
      );

      await addNotification(
        "New Anime Movie Released!",
        `${primaryTitle} is now available on Prophecy Verse!`,
        "anime"
      );

      setSuccessMsg(`Anime Movie "${primaryTitle}" successfully published and live!`);
      await refreshAnime();
      await refreshAllEpisodes();

      // Reset Form
      setEditingMovie(null);
      setMovieSearchQuery("");
      setMovieTitle("");
      setMovieEnglishTitle("");
      setMovieSynopsis("");
      setMoviePoster("");
      setMovieBanner("");
      setMovieStudio("");
      setMovieVideoUrl("");
      setMovieSubtitlesUrl("");
      setSelectedFile(null);
      setUploadStage("idle");
      setUploadProgress(0);

      setTimeout(() => setSuccessMsg(null), 5000);
    } catch (err: any) {
      alert(`Publishing error: ${err.message}`);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleFormSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (uploadMode === "file" && selectedFile && uploadStage !== "Published") {
      handleDirectCloudflareUpload();
    } else {
      publishMovie(movieVideoUrl);
    }
  };

  // Delete Movie
  const handleDeleteMovie = async (movie: Anime) => {
    if (!window.confirm(`Are you sure you want to delete movie "${movie.title}"?`)) return;
    try {
      await deleteAnime(movie.id);
      setSuccessMsg(`Movie "${movie.title}" deleted.`);
      await refreshAnime();
      await refreshAllEpisodes();
      setTimeout(() => setSuccessMsg(null), 3000);
    } catch (err: any) {
      alert(`Failed to delete movie: ${err.message}`);
    }
  };

  // Edit Movie Populate
  const handleStartEditMovie = (movie: Anime) => {
    setEditingMovie(movie);
    setMovieEnglishTitle(movie.englishTitle || movie.title);
    setMovieTitle(movie.japaneseTitle || movie.title);
    setMovieSynopsis(movie.description || "");
    setMoviePoster(movie.poster || "");
    setMovieBanner(movie.banner || "");
    setMovieStudio(movie.studio || "");
    setMovieReleaseYear(movie.releaseYear || new Date().getFullYear());
    setMovieDuration(movie.duration || "110 min");
    setMovieRating(movie.rating || "PG-13");
    setMovieGenres(movie.genres?.join(", ") || "");

    const epMatch = allEpisodesList.find((e) => e.animeId === movie.id);
    if (epMatch) {
      setMovieVideoUrl(epMatch.videoUrl || "");
      setMovieSubtitlesUrl(epMatch.subtitlesUrl || "");
      setMovieAudioLang((epMatch.defaultLanguage as SupportedLanguage) || "Japanese");
    }
    setUploadMode("manual");
    window.scrollTo({ top: 100, behavior: "smooth" });
  };

  // Language management helper
  const handleOpenLanguageModal = (movie: Anime) => {
    const epMatch = allEpisodesList.find((e) => e.animeId === movie.id) || {
      id: `${movie.id}_ep1`,
      animeId: movie.id,
      episodeNumber: 1,
      title: `${movie.title} (Full Movie)`,
      releaseDate: new Date().toISOString().split("T")[0],
      duration: movie.duration,
      status: "Published",
      defaultLanguage: "Japanese",
    };
    setLangModalMovie({ anime: movie, ep: epMatch as Episode });
  };

  return (
    <div className="flex flex-col gap-8 max-w-6xl animate-fade-in pb-12">
      
      {/* Top Banner Header */}
      <div className="p-6 rounded-2xl bg-gradient-to-r from-[#181a29] via-[#131520] to-[#1c1829] border border-amber-500/30 shadow-2xl flex flex-col md:flex-row md:items-center justify-between gap-6">
        <div className="flex items-center gap-4">
          <div className="p-3.5 rounded-2xl bg-amber-500/10 text-amber-400 border border-amber-500/30 shrink-0">
            <Film className="w-8 h-8" />
          </div>
          <div>
            <div className="flex items-center gap-2 flex-wrap">
              <h2 className="text-xl md:text-2xl font-extrabold text-white font-display tracking-tight">
                Section 3: Movie Upload & Playback (Cloudflare R2 Integration)
              </h2>
              <span className="px-2.5 py-0.5 rounded-full text-[10px] font-mono font-black uppercase bg-amber-500/20 text-amber-300 border border-amber-500/30">
                Cloudflare R2 Storage
              </span>
            </div>
            <p className="text-xs text-gray-400 mt-1.5 leading-relaxed">
              Upload feature-length anime movies directly to Cloudflare R2, manage metadata & audio tracks, and stream directly via Cloudflare Worker URLs.
            </p>
          </div>
        </div>

        <button
          onClick={() => {
            refreshAnime();
            refreshAllEpisodes();
          }}
          className="px-4 py-2.5 bg-white/5 hover:bg-white/10 text-gray-300 hover:text-white rounded-xl text-xs font-mono font-bold border border-white/10 flex items-center gap-2 cursor-pointer transition-all shrink-0"
        >
          <RefreshCw className="w-4 h-4 text-amber-400" />
          <span>Refresh Movie List</span>
        </button>
      </div>

      {/* Success Notification Alert */}
      {successMsg && (
        <div className="p-4 rounded-xl bg-emerald-500/10 border border-emerald-500/30 text-emerald-400 text-sm font-bold flex items-center justify-between animate-fade-in shadow-lg">
          <div className="flex items-center gap-2">
            <CheckCircle2 className="w-5 h-5 text-emerald-400 shrink-0" />
            <span>{successMsg}</span>
          </div>
          <button onClick={() => setSuccessMsg(null)} className="text-gray-400 hover:text-white">
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      {/* MAIN UPLOAD & MANAGEMENT FORM CARD */}
      <div className="p-6 md:p-8 rounded-2xl bg-[#131520] border border-white/10 flex flex-col gap-8 shadow-2xl">
        
        <div className="flex items-center justify-between border-b border-white/10 pb-4">
          <h3 className="font-display font-black text-lg text-white flex items-center gap-2.5">
            <Sparkles className="w-5 h-5 text-amber-400" />
            <span>{editingMovie ? `Edit Movie: ${editingMovie.title}` : "Upload & Publish Feature Movie"}</span>
          </h3>
          {editingMovie && (
            <button
              onClick={() => {
                setEditingMovie(null);
                setMovieTitle("");
                setMovieEnglishTitle("");
                setMovieVideoUrl("");
              }}
              className="px-3 py-1 bg-rose-500/20 text-rose-300 text-xs font-bold rounded-lg border border-rose-500/30 hover:bg-rose-500/30 transition-all cursor-pointer"
            >
              Cancel Editing
            </button>
          )}
        </div>

        {/* STEP 1: AUTO-FETCH METADATA */}
        <div className="p-4 bg-[#0d0e12] rounded-2xl border border-amber-500/20 flex flex-col gap-3">
          <label className="text-xs font-mono font-bold uppercase tracking-wider text-amber-400 flex items-center gap-2">
            <Search className="w-4 h-4" />
            <span>Step 1: Auto Fetch Movie Metadata (Kitsu / AniList API)</span>
          </label>
          <div className="flex items-center gap-3">
            <input
              type="text"
              placeholder="Search Movie Title (e.g., Your Name, Demon Slayer Mugen Train, Suzume, Jujutsu Kaisen 0)..."
              value={movieSearchQuery}
              onChange={(e) => setMovieSearchQuery(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && (e.preventDefault(), handleAutoFetchMovieMeta())}
              className="flex-1 px-4 py-3 bg-[#131520] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500 font-medium"
            />
            <button
              type="button"
              onClick={handleAutoFetchMovieMeta}
              disabled={isFetchingMovieMeta || !movieSearchQuery.trim()}
              className="px-5 py-3 bg-amber-500 hover:bg-amber-400 text-black font-extrabold text-xs rounded-xl shadow-lg transition-all flex items-center gap-2 disabled:opacity-40 cursor-pointer shrink-0"
            >
              {isFetchingMovieMeta ? <RefreshCw className="w-4 h-4 animate-spin" /> : <Search className="w-4 h-4" />}
              <span>Auto Fetch</span>
            </button>
          </div>
        </div>

        {/* STEP 2: VIDEO UPLOAD ENGINE (Cloudflare R2 Direct Upload vs Manual Video URL) */}
        <div className="p-5 bg-[#0d0e12] rounded-2xl border border-white/10 flex flex-col gap-4">
          <div className="flex items-center justify-between border-b border-white/5 pb-3">
            <span className="text-xs font-mono font-bold uppercase text-amber-400 tracking-wider flex items-center gap-2">
              <Video className="w-4 h-4" />
              <span>Step 2: Cloudflare R2 Stream Integration</span>
            </span>

            {/* Upload Mode Selector */}
            <div className="flex bg-[#131520] p-1 rounded-xl border border-white/10">
              <button
                type="button"
                onClick={() => setUploadMode("file")}
                className={`px-3 py-1.5 rounded-lg text-xs font-mono font-bold transition-all cursor-pointer ${
                  uploadMode === "file" ? "bg-amber-500 text-black shadow-md" : "text-gray-400 hover:text-white"
                }`}
              >
                🚀 Cloudflare R2 Upload
              </button>
              <button
                type="button"
                onClick={() => setUploadMode("manual")}
                className={`px-3 py-1.5 rounded-lg text-xs font-mono font-bold transition-all cursor-pointer ${
                  uploadMode === "manual" ? "bg-amber-500 text-black shadow-md" : "text-gray-400 hover:text-white"
                }`}
              >
                🔗 Paste Video URL
              </button>
            </div>
          </div>

          {/* MODE A: DIRECT FILE UPLOAD */}
          {uploadMode === "file" ? (
            <div className="flex flex-col gap-4">
              <div className="border-2 border-dashed border-amber-500/30 hover:border-amber-500 bg-[#131520] p-6 rounded-2xl flex flex-col items-center justify-center gap-3 transition-all">
                <Upload className="w-10 h-10 text-amber-400 animate-bounce" />
                <div className="text-center">
                  <p className="text-sm font-bold text-white">Select Movie Video File (.mp4, .mkv, .mov)</p>
                  <p className="text-xs text-gray-400 mt-0.5">High-speed multipart upload to Cloudflare R2 Storage</p>
                </div>
                <input
                  type="file"
                  accept="video/*"
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    if (file) {
                      setSelectedFile(file);
                      setUploadStage("idle");
                      setUploadProgress(0);
                    }
                  }}
                  className="hidden"
                  id="movie-file-input"
                />
                <label
                  htmlFor="movie-file-input"
                  className="px-5 py-2.5 bg-amber-500/20 hover:bg-amber-500/30 text-amber-300 font-extrabold text-xs rounded-xl border border-amber-500/40 cursor-pointer transition-all"
                >
                  {selectedFile ? `Selected: ${selectedFile.name} (${(selectedFile.size / (1024 * 1024)).toFixed(1)} MB)` : "Browse Video File"}
                </label>
              </div>

              {/* Upload Progress Bar */}
              {(isUploading || uploadStage !== "idle") && (
                <div className="p-4 bg-[#131520] rounded-xl border border-white/10 flex flex-col gap-2">
                  <div className="flex items-center justify-between text-xs font-mono">
                    <span className="font-bold text-amber-400 flex items-center gap-2">
                      {uploadStage === "Uploading" && <RefreshCw className="w-3.5 h-3.5 animate-spin" />}
                      {uploadStage === "Verifying" && <RefreshCw className="w-3.5 h-3.5 animate-spin text-amber-400" />}
                      {uploadStage === "Published" && <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />}
                      {uploadStage === "Failed" && <AlertTriangle className="w-3.5 h-3.5 text-rose-400" />}
                      <span>
                        {uploadStage === "Uploading" && `Uploading to Cloudflare R2 (${uploadProgress}%)... ${uploadSpeed ? `[${uploadSpeed}]` : ""} ${remainingTime ? `[${remainingTime} remaining]` : ""}`}
                        {uploadStage === "Verifying" && "Verifying R2 Object Availability..."}
                        {uploadStage === "Published" && "Published ✅"}
                        {uploadStage === "Failed" && "Upload Failed"}
                      </span>
                    </span>
                    <span className="text-white font-bold">{uploadProgress}%</span>
                  </div>
                  <div className="w-full h-2 bg-white/10 rounded-full overflow-hidden p-0.5">
                    <div
                      className={`h-full rounded-full transition-all duration-300 ${
                        uploadStage === "Published" ? "bg-emerald-400" : uploadStage === "Failed" ? "bg-rose-500" : "bg-amber-400"
                      }`}
                      style={{ width: `${uploadProgress}%` }}
                    />
                  </div>
                  {uploadErrorMsg && <p className="text-xs text-rose-400 mt-1 font-mono">{uploadErrorMsg}</p>}
                </div>
              )}
            </div>
          ) : (
            /* MODE B: PASTE VIDEO URL */
            <div className="flex flex-col gap-2">
              <label className="text-xs font-bold text-amber-400 font-mono uppercase tracking-wider flex items-center justify-between">
                <span>Cloudflare R2 Video Stream URL *</span>
                <span className="text-[10px] text-gray-400 font-normal">Direct Worker Streaming URL</span>
              </label>
              <input
                type="text"
                placeholder="https://prophecy-verse-video-api.newprophecy4.workers.dev/video/Title/Season 1/Episode_01.mp4"
                value={movieVideoUrl}
                onChange={(e) => setMovieVideoUrl(e.target.value)}
                className="px-4 py-3 bg-[#131520] border border-white/10 rounded-xl text-sm text-white font-mono focus:outline-none focus:border-amber-500"
              />
            </div>
          )}
        </div>

        {/* STEP 3: METADATA FORM */}
        <form onSubmit={handleFormSubmit} className="flex flex-col gap-6">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="flex flex-col gap-2">
              <label className="text-xs font-bold text-gray-400 uppercase font-mono">Movie Display Title (English) *</label>
              <input
                type="text"
                placeholder="e.g. Your Name."
                value={movieEnglishTitle}
                onChange={(e) => setMovieEnglishTitle(e.target.value)}
                className="px-4 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500 font-medium"
                required
              />
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-xs font-bold text-gray-400 uppercase font-mono">Original Japanese Title</label>
              <input
                type="text"
                placeholder="e.g. 君の名は。"
                value={movieTitle}
                onChange={(e) => setMovieTitle(e.target.value)}
                className="px-4 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500 font-medium"
              />
            </div>
          </div>

          <div className="flex flex-col gap-2">
            <label className="text-xs font-bold text-gray-400 uppercase font-mono">Synopsis / Description</label>
            <textarea
              rows={3}
              placeholder="Movie synopsis..."
              value={movieSynopsis}
              onChange={(e) => setMovieSynopsis(e.target.value)}
              className="px-4 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500 font-medium resize-none"
            />
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div className="flex flex-col gap-2">
              <label className="text-xs font-bold text-gray-400 uppercase font-mono">Poster Image URL</label>
              <input
                type="text"
                placeholder="https://..."
                value={moviePoster}
                onChange={(e) => setMoviePoster(e.target.value)}
                className="px-4 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none focus:border-amber-500 font-mono"
              />
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-xs font-bold text-gray-400 uppercase font-mono">Banner Image URL</label>
              <input
                type="text"
                placeholder="https://..."
                value={movieBanner}
                onChange={(e) => setMovieBanner(e.target.value)}
                className="px-4 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none focus:border-amber-500 font-mono"
              />
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-xs font-bold text-gray-400 uppercase font-mono">Subtitles VTT URL</label>
              <input
                type="text"
                placeholder="https://.../subtitles.vtt"
                value={movieSubtitlesUrl}
                onChange={(e) => setMovieSubtitlesUrl(e.target.value)}
                className="px-4 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none focus:border-amber-500 font-mono"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <div className="flex flex-col gap-2">
              <label className="text-xs font-bold text-gray-400 uppercase font-mono">Studio</label>
              <input
                type="text"
                placeholder="e.g. CoMix Wave Films"
                value={movieStudio}
                onChange={(e) => setMovieStudio(e.target.value)}
                className="px-4 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none focus:border-amber-500"
              />
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-xs font-bold text-gray-400 uppercase font-mono">Release Year</label>
              <input
                type="number"
                value={movieReleaseYear}
                onChange={(e) => setMovieReleaseYear(parseInt(e.target.value) || new Date().getFullYear())}
                className="px-4 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none focus:border-amber-500 font-mono"
              />
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-xs font-bold text-gray-400 uppercase font-mono">Duration</label>
              <input
                type="text"
                value={movieDuration}
                onChange={(e) => setMovieDuration(e.target.value)}
                className="px-4 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none focus:border-amber-500 font-mono"
              />
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-xs font-bold text-gray-400 uppercase font-mono">Audio Language</label>
              <select
                value={movieAudioLang}
                onChange={(e) => setMovieAudioLang(e.target.value as SupportedLanguage)}
                className="px-4 py-3 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white focus:outline-none focus:border-amber-500 font-mono"
              >
                <option value="Japanese">Japanese</option>
                <option value="English">English</option>
                <option value="Spanish">Spanish</option>
                <option value="Hindi">Hindi</option>
                <option value="Korean">Korean</option>
              </select>
            </div>
          </div>

          <div className="flex items-center justify-end gap-4 pt-4 border-t border-white/10">
            <button
              type="submit"
              disabled={isSubmitting || isUploading}
              className="px-8 py-3.5 bg-amber-500 hover:bg-amber-400 text-black font-extrabold text-sm rounded-xl shadow-xl transition-all flex items-center gap-2 cursor-pointer disabled:opacity-40"
            >
              {isSubmitting ? <RefreshCw className="w-4 h-4 animate-spin" /> : <CheckCircle2 className="w-4 h-4" />}
              <span>{editingMovie ? "Save Movie Changes" : "Publish Feature Movie"}</span>
            </button>
          </div>
        </form>
      </div>

      {/* CATALOG OF PUBLISHED MOVIES */}
      <div className="p-6 md:p-8 rounded-2xl bg-[#131520] border border-white/10 flex flex-col gap-6 shadow-2xl">
        <div className="flex items-center justify-between border-b border-white/10 pb-4">
          <h3 className="font-display font-black text-lg text-white flex items-center gap-2.5">
            <Film className="w-5 h-5 text-amber-400" />
            <span>Published Feature Movies Catalog ({moviesList.length})</span>
          </h3>
        </div>

        {moviesList.length === 0 ? (
          <div className="p-12 text-center text-gray-400 font-mono text-sm bg-[#0d0e12] rounded-2xl border border-white/5 flex flex-col items-center justify-center gap-3">
            <Film className="w-10 h-10 text-gray-600" />
            <p>No feature movies published yet. Use the upload form above to add your first movie.</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            {moviesList.map((movie) => {
              const epMatch = allEpisodesList.find((e) => e.animeId === movie.id);
              const videoUrl = epMatch?.videoUrl || movieVideoUrl;
              const availableLangs = epMatch?.languages
                ? Object.keys(epMatch.languages)
                : [epMatch?.defaultLanguage || "Japanese"];

              return (
                <div
                  key={movie.id}
                  className="p-5 rounded-2xl bg-[#0d0e12] border border-white/10 hover:border-amber-500/40 transition-all flex flex-col gap-4 justify-between shadow-xl group"
                >
                  <div className="flex items-start gap-4">
                    <div className="relative w-20 h-28 rounded-xl overflow-hidden border border-white/10 shrink-0 bg-black">
                      <img
                        src={movie.poster}
                        alt={movie.title}
                        className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                        loading="lazy"
                        referrerPolicy="no-referrer"
                      />
                      {videoUrl && (
                        <button
                          onClick={() => setPreviewMovie({ anime: movie, episode: epMatch })}
                          className="absolute inset-0 bg-black/60 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center text-white cursor-pointer"
                          title="Stream Movie"
                        >
                          <Play className="w-8 h-8 text-amber-400 fill-amber-400" />
                        </button>
                      )}
                    </div>

                    <div className="flex-1 min-w-0 flex flex-col gap-1.5">
                      <div className="flex items-start justify-between gap-2">
                        <h4 className="font-bold text-base text-white truncate group-hover:text-amber-400 transition-colors">
                          {movie.title}
                        </h4>
                        <span className="text-[10px] font-mono font-bold uppercase bg-emerald-500/10 text-emerald-400 px-2 py-0.5 rounded border border-emerald-500/20 shrink-0">
                          Published ✅
                        </span>
                      </div>

                      <p className="text-xs text-gray-400 font-mono">
                        {movie.studio} • {movie.releaseYear} • {movie.duration || "110 min"}
                      </p>

                      <p className="text-xs text-gray-400 line-clamp-2 leading-relaxed">
                        {movie.description}
                      </p>

                      {/* Video URL Tag */}
                      {videoUrl ? (
                        <div className="flex items-center gap-2 mt-1">
                          <span className="text-[10px] font-mono text-gray-400">Stream:</span>
                          <code className="text-[10px] font-mono bg-white/5 px-2 py-0.5 rounded border border-white/10 text-amber-300 truncate max-w-[140px]">
                            {videoUrl}
                          </code>
                          <button
                            onClick={() => handleCopy(videoUrl)}
                            className="text-gray-400 hover:text-white"
                            title="Copy Stream URL"
                          >
                            {copiedId === videoUrl ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                          </button>
                        </div>
                      ) : (
                        <span className="text-[10px] font-mono text-rose-400 font-bold">No Video Stream Linked</span>
                      )}

                      {/* Languages */}
                      <div className="flex items-center gap-1.5 mt-1 flex-wrap">
                        <span className="text-[10px] font-mono text-gray-400">Audio:</span>
                        {availableLangs.map((lang) => (
                          <span key={lang} className="text-[9px] font-mono bg-amber-500/10 text-amber-300 px-1.5 py-0.5 rounded border border-amber-500/20 font-bold">
                            {lang}
                          </span>
                        ))}
                      </div>
                    </div>
                  </div>

                  {/* Movie Action Controls */}
                  <div className="flex items-center justify-between border-t border-white/5 pt-3 mt-1 flex-wrap gap-2">
                    <button
                      onClick={() => setPreviewMovie({ anime: movie, episode: epMatch })}
                      className="px-3.5 py-2 bg-amber-500 text-black font-extrabold text-xs rounded-xl shadow-lg hover:bg-amber-400 transition-all flex items-center gap-1.5 cursor-pointer"
                    >
                      <Play className="w-3.5 h-3.5 fill-black" />
                      <span>Play Movie</span>
                    </button>

                    <div className="flex items-center gap-2">
                      <button
                        onClick={() => handleOpenLanguageModal(movie)}
                        className="px-3 py-1.5 bg-white/5 hover:bg-white/10 text-gray-300 font-bold text-xs rounded-xl border border-white/10 transition-all flex items-center gap-1 cursor-pointer"
                        title="Manage Audio & Subtitles"
                      >
                        <Globe className="w-3.5 h-3.5 text-amber-400" />
                        <span>Languages</span>
                      </button>

                      <button
                        onClick={() => handleStartEditMovie(movie)}
                        className="px-3 py-1.5 bg-white/5 hover:bg-white/10 text-gray-300 font-bold text-xs rounded-xl border border-white/10 transition-all flex items-center gap-1 cursor-pointer"
                        title="Edit Movie Metadata"
                      >
                        <Edit3 className="w-3.5 h-3.5 text-sky-400" />
                        <span>Edit</span>
                      </button>

                      <button
                        onClick={() => handleDeleteMovie(movie)}
                        className="p-1.5 bg-rose-500/10 hover:bg-rose-500/20 text-rose-400 rounded-xl border border-rose-500/20 transition-all cursor-pointer"
                        title="Delete Movie"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* 4. STREAM PLAYER MODAL FOR PREVIEWING MOVIE */}
      {previewMovie && (
        <div className="fixed inset-0 z-[999] bg-black/90 backdrop-blur-md flex items-center justify-center p-4 md:p-8 animate-fade-in">
          <div className="relative w-full max-w-5xl bg-[#0b0c10] rounded-2xl border border-white/10 overflow-hidden shadow-2xl flex flex-col">
            
            {/* Modal Header */}
            <div className="p-4 md:p-6 bg-[#131520] border-b border-white/10 flex items-center justify-between">
              <div>
                <h3 className="font-display font-extrabold text-base md:text-lg text-white flex items-center gap-2">
                  <Film className="w-5 h-5 text-amber-400" />
                  <span>{previewMovie.anime.title} (Movie Stream)</span>
                </h3>
                <p className="text-xs text-gray-400 font-mono mt-0.5 truncate max-w-md">
                  Source: {previewMovie.episode?.videoUrl || "Cloudflare R2 Stream"}
                </p>
              </div>
              <button
                onClick={() => setPreviewMovie(null)}
                className="p-2 bg-white/10 hover:bg-white/20 text-white rounded-xl transition-all cursor-pointer"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            {/* Video Player Box */}
            <div className="w-full aspect-video bg-black flex items-center justify-center relative">
              <VideoPlayer
                episode={
                  previewMovie.episode || {
                    id: `${previewMovie.anime.id}_ep1`,
                    animeId: previewMovie.anime.id,
                    episodeNumber: 1,
                    title: previewMovie.anime.title,
                    videoUrl: previewMovie.anime.poster,
                  }
                }
                animeTitle={previewMovie.anime.title}
                onEnded={() => {}}
              />
            </div>
          </div>
        </div>
      )}

      {/* 5. MULTI-LANGUAGE MODAL */}
      {langModalMovie && (
        <LanguageManagementModal
          animeId={langModalMovie.anime.id}
          animeTitle={langModalMovie.anime.title}
          episode={langModalMovie.ep}
          onClose={() => setLangModalMovie(null)}
          onSuccess={() => {
            refreshAnime();
            refreshAllEpisodes();
          }}
        />
      )}

    </div>
  );
};
