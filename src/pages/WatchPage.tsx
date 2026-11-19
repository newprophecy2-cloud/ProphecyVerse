import React, { useState, useEffect, useMemo, useCallback } from "react";
import { useApp } from "../context/AppContext";
import { usePlayer } from "../context/PlayerContext";
import { VideoPlayer } from "../components/VideoPlayer";
import { updateWatchProgress } from "../services/dbService";
import {
  BackendApiError,
  applyPlaybackToEpisode,
  fetchMovieBoxDetails,
  fetchMovieBoxPlayback,
} from "../services/movieboxApiService";
import { Episode, Anime } from "../types";
import {
  ChevronLeft,
  Film,
  Bookmark,
  Layers,
  Check,
  Play,
  SkipBack,
  SkipForward,
  Maximize2,
  Sparkles,
  RefreshCw,
  AlertTriangle,
  Globe,
} from "lucide-react";
import { HorizontalScrollSection } from "../components/HorizontalScrollSection";
import { AnimeCard } from "../components/AnimeCard";

interface WatchPageProps {
  animeId: string;
  setView: (view: string, detailsId?: string) => void;
  seasonNumber?: number;
  episodeNumber?: number;
  initialPosition?: number;
}

export const WatchPage: React.FC<WatchPageProps> = ({
  animeId,
  setView,
  seasonNumber = 1,
  episodeNumber = 1,
  initialPosition = 0,
}) => {
  const { animeList, user, refreshUserData, upsertCatalogItems } = useApp();
  const {
    playerMode,
    setActiveAnime,
    setActiveEpisode,
    setPlayerMode,
    minimizePlayer,
    expandPlayer,
    setCurrentTime,
  } = usePlayer();

  const [detailsAnime, setDetailsAnime] = useState<Anime | null>(
    () => animeList.find((a) => a.id === animeId) || null
  );
  const [episodes, setEpisodes] = useState<Episode[]>([]);
  const [currentEpisode, setCurrentEpisode] = useState<Episode | null>(null);
  const [selectedSeasonNumber, setSelectedSeasonNumber] = useState<number>(seasonNumber);
  const [activeSubjectId, setActiveSubjectId] = useState<string>(animeId);
  const [resumePosition, setResumePosition] = useState<number>(initialPosition);
  const [resumeMessage, setResumeMessage] = useState<string | null>(null);
  const [isLoadingDetails, setIsLoadingDetails] = useState(true);
  const [isLoadingPlayback, setIsLoadingPlayback] = useState(false);
  const [playbackError, setPlaybackError] = useState<string | null>(null);
  const [playbackRefreshNonce, setPlaybackRefreshNonce] = useState(0);

  const anime = detailsAnime || animeList.find((a) => a.id === animeId) || null;

  useEffect(() => {
    setActiveSubjectId(animeId);
  }, [animeId]);

  const seasonsMap = useMemo(() => {
    const map = new Map<number, Episode[]>();
    for (const ep of episodes) {
      const sNum = ep.seasonNumber ?? 0;
      const list = map.get(sNum) || [];
      list.push(ep);
      map.set(sNum, list);
    }
    for (const [, epList] of map.entries()) {
      epList.sort(
        (a, b) => (Number(a.episodeNumber) || 0) - (Number(b.episodeNumber) || 0)
      );
    }
    return map;
  }, [episodes]);

  const sortedSeasonNumbers = useMemo(() => {
    return Array.from(seasonsMap.keys()).sort((a, b) => a - b);
  }, [seasonsMap]);

  useEffect(() => {
    if (anime && currentEpisode) {
      setActiveAnime(anime);
      setActiveEpisode(currentEpisode);
      if (playerMode === "hidden") {
        setPlayerMode("full");
      }
    }
  }, [anime, currentEpisode, playerMode, setActiveAnime, setActiveEpisode, setPlayerMode]);

  const popularCatalog = useMemo(() => {
    return animeList
      .filter((a) => a.isVisible !== false && a.id !== animeId)
      .slice(0, 12);
  }, [animeList, animeId]);

  // 1. Load MovieBox details + episode list
  useEffect(() => {
    if (!animeId) return;
    const controller = new AbortController();
    setIsLoadingDetails(true);

    fetchMovieBoxDetails(animeId, controller.signal)
      .then(({ anime: loadedAnime, episodes: eps }) => {
        setDetailsAnime(loadedAnime);
        setEpisodes(eps);
        upsertCatalogItems([loadedAnime]);

        let targetSeason = seasonNumber;
        let targetEpNum = episodeNumber;
        let startPos = initialPosition;

        try {
          const savedDataStr = localStorage.getItem(
            `anime_continue_watching_${animeId}`
          );
          if (savedDataStr && initialPosition === 0 && episodeNumber === 1) {
            const savedData = JSON.parse(savedDataStr);
            if (
              (savedData?.seasonNumber ?? 1) === seasonNumber &&
              savedData?.episodeNumber !== undefined &&
              savedData?.position > 5
            ) {
              targetSeason = savedData.seasonNumber ?? targetSeason;
              targetEpNum = savedData.episodeNumber;
              startPos = savedData.position || 0;
              const minutes = Math.floor(startPos / 60);
              const seconds = Math.floor(startPos % 60);
              setResumeMessage(
                `Resumed S${targetSeason} EP ${targetEpNum} at ${minutes}:${
                  seconds < 10 ? "0" : ""
                }${seconds}`
              );
            }
          }
        } catch {
          // ignore localStorage errors
        }

        const targetEp =
          eps.find(
            (e) =>
              (e.seasonNumber ?? 0) === targetSeason &&
              e.episodeNumber === targetEpNum
          ) ||
          eps.find((e) => e.episodeNumber === targetEpNum) ||
          eps[0];

        if (targetEp) {
          setCurrentEpisode(targetEp);
          setSelectedSeasonNumber(targetEp.seasonNumber ?? 0);
          setResumePosition(startPos);
        }
      })
      .catch((err) => {
        if (controller.signal.aborted) return;
        setPlaybackError(
          err instanceof Error ? err.message : "Failed to load title details."
        );
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setIsLoadingDetails(false);
        }
      });

    return () => {
      controller.abort();
    };
  }, [animeId, seasonNumber, episodeNumber, initialPosition, upsertCatalogItems]);

  // 2. Fetch fresh playback streams & subtitles whenever episode, dub subjectId, or manual refresh changes
  useEffect(() => {
    if (!currentEpisode || !activeSubjectId) return;
    const controller = new AbortController();
    setIsLoadingPlayback(true);
    setPlaybackError(null);

    const se = currentEpisode.seasonNumber ?? 0;
    const ep = currentEpisode.episodeNumber ?? 0;

    fetchMovieBoxPlayback(
      activeSubjectId,
      se,
      ep,
      playbackRefreshNonce > 0,
      controller.signal
    )
      .then((playback) => {
        setCurrentEpisode((prev) => {
          const baseEpisode = prev || currentEpisode;
          const enriched = applyPlaybackToEpisode(baseEpisode, playback);
          if (
            prev &&
            prev.id === baseEpisode.id &&
            prev.videoUrl === enriched.videoUrl &&
            prev.hlsUrl === enriched.hlsUrl &&
            prev.dashUrl === enriched.dashUrl &&
            (prev.streamVariants?.length || 0) === (enriched.streamVariants?.length || 0) &&
            (prev.subtitles?.length || 0) === (enriched.subtitles?.length || 0)
          ) {
            return prev;
          }
          return enriched;
        });
      })
      .catch((err) => {
        if (controller.signal.aborted) return;
        if (err instanceof BackendApiError) {
          setPlaybackError(err.message);
        } else {
          setPlaybackError(
            err instanceof Error ? err.message : "Failed to load playback."
          );
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setIsLoadingPlayback(false);
        }
      });

    return () => {
      controller.abort();
    };
  }, [
    activeSubjectId,
    currentEpisode?.id,
    currentEpisode?.seasonNumber,
    currentEpisode?.episodeNumber,
    playbackRefreshNonce,
  ]);

  // Update browser URL cleanly without violating router behavior
  useEffect(() => {
    if (!anime || !currentEpisode) return;
    const sNum = currentEpisode.seasonNumber ?? selectedSeasonNumber;
    const epNum = currentEpisode.episodeNumber;
    const slugTitle = (anime.title || "title")
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "");
    try {
      window.history.replaceState(
        null,
        "",
        `/watch/${slugTitle}/season-${sNum}/episode-${epNum}`
      );
    } catch {
      // ignore sandbox history restriction
    }
  }, [anime, currentEpisode, selectedSeasonNumber]);

  const saveContinueWatching = useCallback(
    (sNum: number, epNum: number, posSeconds: number, totalDuration: number) => {
      if (!animeId || posSeconds <= 0) return;
      try {
        localStorage.setItem(
          `anime_continue_watching_${animeId}`,
          JSON.stringify({
            animeId,
            seasonNumber: sNum,
            episodeNumber: epNum,
            position: Math.round(posSeconds),
            updatedAt: Date.now(),
          })
        );
      } catch {
        // ignore quota errors
      }
      const uid = user ? user.uid : "guest-user-id";
      updateWatchProgress(uid, animeId, epNum, posSeconds, totalDuration, {
        seasonNumber: sNum,
        title: anime?.title,
        poster: anime?.poster,
        banner: anime?.banner,
      }).catch((err) => console.error("Progress sync error:", err));
    },
    [animeId, user, anime?.title, anime?.poster, anime?.banner]
  );

  const selectEpisode = (ep: Episode, seasonNum?: number) => {
    setCurrentEpisode(ep);
    setSelectedSeasonNumber(seasonNum ?? ep.seasonNumber ?? 0);
    setResumePosition(0);
    setResumeMessage(null);
  };

  const handleProgressUpdate = (seconds: number, totalDuration: number) => {
    if (!currentEpisode) return;
    setCurrentTime(seconds);
    const sNum = currentEpisode.seasonNumber ?? selectedSeasonNumber;
    saveContinueWatching(sNum, currentEpisode.episodeNumber, seconds, totalDuration);
  };

  const getNextEpisodeInfo = (): { nextEp: Episode | null; nextSeason: number } => {
    if (!currentEpisode) return { nextEp: null, nextSeason: selectedSeasonNumber };
    const currentSeasonEps = seasonsMap.get(selectedSeasonNumber) || [];
    const currentIndex = currentSeasonEps.findIndex((e) => e.id === currentEpisode.id);
    if (currentIndex >= 0 && currentIndex < currentSeasonEps.length - 1) {
      return {
        nextEp: currentSeasonEps[currentIndex + 1],
        nextSeason: selectedSeasonNumber,
      };
    }
    const currentSeasonIdx = sortedSeasonNumbers.indexOf(selectedSeasonNumber);
    if (
      currentSeasonIdx >= 0 &&
      currentSeasonIdx < sortedSeasonNumbers.length - 1
    ) {
      const nextSNum = sortedSeasonNumbers[currentSeasonIdx + 1];
      const nextSeasonEps = seasonsMap.get(nextSNum) || [];
      if (nextSeasonEps.length > 0) {
        return { nextEp: nextSeasonEps[0], nextSeason: nextSNum };
      }
    }
    return { nextEp: null, nextSeason: selectedSeasonNumber };
  };

  const getPrevEpisodeInfo = (): { prevEp: Episode | null; prevSeason: number } => {
    if (!currentEpisode) return { prevEp: null, prevSeason: selectedSeasonNumber };
    const currentSeasonEps = seasonsMap.get(selectedSeasonNumber) || [];
    const currentIndex = currentSeasonEps.findIndex((e) => e.id === currentEpisode.id);
    if (currentIndex > 0) {
      return {
        prevEp: currentSeasonEps[currentIndex - 1],
        prevSeason: selectedSeasonNumber,
      };
    }
    const currentSeasonIdx = sortedSeasonNumbers.indexOf(selectedSeasonNumber);
    if (currentSeasonIdx > 0) {
      const prevSNum = sortedSeasonNumbers[currentSeasonIdx - 1];
      const prevSeasonEps = seasonsMap.get(prevSNum) || [];
      if (prevSeasonEps.length > 0) {
        return {
          prevEp: prevSeasonEps[prevSeasonEps.length - 1],
          prevSeason: prevSNum,
        };
      }
    }
    return { prevEp: null, prevSeason: selectedSeasonNumber };
  };

  const { nextEp, nextSeason } = getNextEpisodeInfo();
  const { prevEp, prevSeason } = getPrevEpisodeInfo();

  const triggerNext = () => {
    if (nextEp) selectEpisode(nextEp, nextSeason);
  };

  const triggerPrev = () => {
    if (prevEp) selectEpisode(prevEp, prevSeason);
  };

  const handleEpisodeComplete = () => {
    if (!currentEpisode) return;
    const uid = user ? user.uid : "guest-user-id";
    const durSecs = currentEpisode.duration
      ? parseFloat(currentEpisode.duration) * 60 || 1440
      : 1440;
    updateWatchProgress(
      uid,
      animeId,
      currentEpisode.episodeNumber,
      durSecs,
      durSecs,
      {
        seasonNumber: currentEpisode.seasonNumber ?? selectedSeasonNumber,
        title: anime?.title,
        poster: anime?.poster,
        banner: anime?.banner,
      }
    ).then(() => refreshUserData());
    triggerNext();
  };

  const activeSeasonEpisodes = seasonsMap.get(selectedSeasonNumber) || [];
  const dubsList = anime?.dubs || currentEpisode?.dubs || [];

  if (isLoadingDetails && !anime) {
    return (
      <div className="w-full min-h-[70vh] flex flex-col items-center justify-center gap-3 text-gray-300">
        <RefreshCw className="w-8 h-8 text-brand-primary animate-spin" />
        <p className="text-sm font-mono">Loading stream workspace...</p>
      </div>
    );
  }

  if (!anime) {
    return (
      <div className="text-center py-20 text-gray-400">
        <p className="mb-4">Title details could not be loaded.</p>
        <button
          onClick={() => setView("home")}
          className="px-4 py-2 rounded-xl bg-brand-primary text-white text-xs font-bold cursor-pointer"
        >
          Back to Home
        </button>
      </div>
    );
  }

  return (
    <div className="w-full min-h-screen pb-20 px-1">
      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <button
          onClick={() => setView("anime-details", animeId)}
          className="flex items-center gap-1.5 text-xs font-semibold text-gray-400 hover:text-white transition-colors group cursor-pointer"
        >
          <ChevronLeft className="w-4 h-4 group-hover:-translate-x-0.5 transition-transform" />
          Back to Details
        </button>

        <div className="flex items-center gap-2">
          {dubsList.length > 1 && (
            <div className="flex items-center gap-1.5 bg-[#131520] border border-white/10 rounded-xl px-2.5 py-1">
              <Globe className="w-3.5 h-3.5 text-brand-primary" />
              <select
                value={activeSubjectId}
                onChange={(e) => setActiveSubjectId(e.target.value)}
                className="bg-transparent text-xs font-semibold text-gray-200 focus:outline-none cursor-pointer"
                title="Switch Audio Track"
              >
                {dubsList.map((dub) => (
                  <option
                    key={dub.subjectId}
                    value={dub.subjectId}
                    className="bg-[#131520] text-white"
                  >
                    {dub.language}
                  </option>
                ))}
              </select>
            </div>
          )}

          <button
            onClick={() => setPlaybackRefreshNonce((n) => n + 1)}
            disabled={isLoadingPlayback}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-white/5 hover:bg-white/10 border border-white/10 text-xs font-semibold text-gray-200 transition-all cursor-pointer disabled:opacity-50"
            title="Refresh stream"
          >
            <RefreshCw
              className={`w-3.5 h-3.5 ${isLoadingPlayback ? "animate-spin text-brand-primary" : ""}`}
            />
            <span>Refresh Stream</span>
          </button>
        </div>
      </div>

      {playbackError && (
        <div className="mb-4 p-4 rounded-2xl bg-amber-500/10 border border-amber-500/20 text-amber-200 text-xs flex items-start gap-3">
          <AlertTriangle className="w-5 h-5 text-amber-400 shrink-0 mt-0.5" />
          <div className="flex-1">
            <p className="font-bold text-white text-sm">
              Playback Notice
            </p>
            <p className="mt-0.5 text-amber-200/90">{playbackError}</p>
          </div>
        </div>
      )}

      {currentEpisode?.playbackNotes && !playbackError && (
        <div className="mb-4 p-3.5 rounded-xl bg-amber-500/10 border border-amber-500/20 text-amber-200 text-xs flex items-center gap-2.5">
          <AlertTriangle className="w-4 h-4 text-amber-400 shrink-0" />
          <span>{currentEpisode.playbackNotes}</span>
        </div>
      )}

      {currentEpisode ? (
        <div className="flex flex-col gap-6">
          <div className="flex flex-col gap-2">
            {playerMode === "mini" ? (
              <div className="relative aspect-video w-full rounded-2xl bg-[#131520]/80 border border-amber-500/30 flex flex-col items-center justify-center gap-3 p-6 text-center shadow-xl">
                <div className="w-12 h-12 rounded-full bg-amber-500/10 border border-amber-500/20 flex items-center justify-center text-amber-400">
                  <Film className="w-6 h-6 animate-pulse" />
                </div>
                <div>
                  <h3 className="font-extrabold text-white text-base">
                    Playing in Floating Mini-Player
                  </h3>
                  <p className="text-xs text-gray-400 mt-1">
                    {currentEpisode.seasonNumber
                      ? `Season ${currentEpisode.seasonNumber} EP ${currentEpisode.episodeNumber}: `
                      : ""}
                    {currentEpisode.title}
                  </p>
                </div>
                <button
                  onClick={expandPlayer}
                  className="mt-2 flex items-center gap-2 px-5 py-2.5 rounded-xl bg-amber-500 text-black font-extrabold text-xs uppercase tracking-wider hover:opacity-95 active:scale-95 transition-all shadow-md cursor-pointer"
                >
                  <Maximize2 className="w-4 h-4" />
                  Expand to Main Screen
                </button>
              </div>
            ) : (
              <VideoPlayer
                episode={currentEpisode}
                nextEpisode={nextEp}
                onNextEpisode={triggerNext}
                onPreviousEpisode={triggerPrev}
                hasPreviousEpisode={Boolean(prevEp)}
                onEpisodeComplete={handleEpisodeComplete}
                onProgressUpdate={handleProgressUpdate}
                initialPosition={resumePosition}
                onMinimize={minimizePlayer}
                animeTitle={anime.title}
                onBack={() => setView("anime-details", animeId)}
                isStreamInitializing={isLoadingPlayback}
                streamErrorMessage={playbackError}
                onRetryPlayback={() => setPlaybackRefreshNonce((n) => n + 1)}
              />
            )}

            {resumeMessage && (
              <div className="flex items-center justify-between px-4 py-2.5 rounded-xl bg-amber-500/10 border border-amber-500/20 text-amber-400 text-xs font-mono font-medium animate-fade-in">
                <span className="flex items-center gap-2 truncate">
                  <Bookmark className="w-4 h-4 shrink-0 fill-current" />
                  {resumeMessage}
                </span>
                <button
                  onClick={() => setResumeMessage(null)}
                  className="text-gray-400 hover:text-white text-xs font-bold underline cursor-pointer shrink-0 ml-2"
                >
                  Dismiss
                </button>
              </div>
            )}
          </div>

          <div className="flex flex-col gap-6 bg-[#131520]/60 border border-white/5 p-6 rounded-3xl backdrop-blur-md shadow-2xl">
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 pb-5 border-b border-white/5">
              <div>
                <h1 className="font-display font-black text-2xl md:text-3xl text-white tracking-tight">
                  {anime.title}
                </h1>
                {anime.japaneseTitle && anime.japaneseTitle !== anime.title && (
                  <p className="text-xs text-gray-400 font-mono mt-1">
                    {anime.japaneseTitle}
                  </p>
                )}
              </div>

              <div className="flex items-center gap-3 px-4 py-2.5 rounded-2xl bg-brand-primary/10 border border-brand-primary/30 text-white shadow-lg self-start md:self-auto">
                <div className="w-2.5 h-2.5 rounded-full bg-emerald-400 animate-ping" />
                <div className="flex flex-col">
                  <span className="text-[10px] uppercase font-mono font-bold text-gray-400 tracking-wider">
                    Currently Watching
                  </span>
                  <span className="text-xs font-extrabold text-amber-400 font-mono">
                    {currentEpisode.seasonNumber
                      ? `Season ${currentEpisode.seasonNumber} Episode ${currentEpisode.episodeNumber}`
                      : currentEpisode.title}
                  </span>
                </div>
              </div>
            </div>

            {anime.type !== "Movie" && sortedSeasonNumbers.length > 0 && (
              <div className="flex flex-col gap-3">
                <label className="text-xs font-bold text-gray-400 uppercase tracking-wider font-mono flex items-center gap-2">
                  <Layers className="w-4 h-4 text-brand-primary" />
                  Select Season:
                </label>
                <div className="flex items-center gap-2.5 overflow-x-auto no-scrollbar pb-1">
                  {sortedSeasonNumbers.map((sNum) => {
                    const isSelected = selectedSeasonNumber === sNum;
                    const seasonCount = seasonsMap.get(sNum)?.length || 0;
                    return (
                      <button
                        key={sNum}
                        onClick={() => setSelectedSeasonNumber(sNum)}
                        className={`px-5 py-2.5 rounded-xl font-bold text-xs uppercase tracking-wider transition-all cursor-pointer shrink-0 flex items-center gap-2 border ${
                          isSelected
                            ? "bg-amber-500 text-black border-amber-400 shadow-lg shadow-amber-500/20 font-extrabold scale-105"
                            : "bg-white/5 text-gray-300 border-white/5 hover:bg-white/10 hover:text-white"
                        }`}
                      >
                        <span>Season {sNum}</span>
                        {seasonCount > 0 && (
                          <span
                            className={`px-1.5 py-0.5 rounded-full text-[10px] font-mono ${
                              isSelected
                                ? "bg-black/30 text-amber-200"
                                : "bg-white/10 text-gray-400"
                            }`}
                          >
                            {seasonCount}
                          </span>
                        )}
                      </button>
                    );
                  })}
                </div>
              </div>
            )}

            <div className="flex flex-col gap-4 mt-2">
              <div className="flex items-center justify-between">
                <h3 className="font-display font-extrabold text-lg text-white flex items-center gap-2">
                  <span>
                    {anime.type === "Movie"
                      ? "Feature Playback"
                      : `Season ${selectedSeasonNumber} Episodes`}
                  </span>
                  <span className="text-xs font-mono font-normal text-gray-400">
                    ({activeSeasonEpisodes.length} available)
                  </span>
                </h3>

                {anime.type !== "Movie" && (
                  <div className="flex items-center gap-2">
                    <button
                      onClick={triggerPrev}
                      disabled={!prevEp}
                      className="p-2 rounded-lg bg-white/5 hover:bg-white/10 text-gray-300 disabled:opacity-30 disabled:pointer-events-none transition-all cursor-pointer"
                      title="Previous Episode"
                    >
                      <SkipBack className="w-4 h-4" />
                    </button>
                    <button
                      onClick={triggerNext}
                      disabled={!nextEp}
                      className="p-2 rounded-lg bg-amber-500 text-black hover:bg-amber-400 disabled:opacity-30 disabled:pointer-events-none transition-all cursor-pointer font-bold"
                      title="Next Episode"
                    >
                      <SkipForward className="w-4 h-4" />
                    </button>
                  </div>
                )}
              </div>

              {activeSeasonEpisodes.length > 0 && (
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-3.5">
                  {activeSeasonEpisodes.map((ep) => {
                    const isActive = currentEpisode.id === ep.id;
                    const epThumb =
                      ep.thumbnail || ep.thumbnailUrl || anime.banner || anime.poster;

                    return (
                      <div
                        key={ep.id}
                        onClick={() => selectEpisode(ep, selectedSeasonNumber)}
                        className={`group relative flex flex-col justify-between p-3.5 rounded-2xl border transition-all cursor-pointer ${
                          isActive
                            ? "bg-amber-500/15 border-amber-500/60 shadow-xl shadow-amber-500/10 ring-2 ring-amber-500/30"
                            : "bg-[#0d0e15]/80 border-white/5 hover:border-white/20 hover:bg-[#131520]"
                        }`}
                      >
                        <div className="relative aspect-video w-full rounded-xl overflow-hidden bg-neutral-900 border border-white/5 mb-3">
                          {epThumb ? (
                            <img
                              src={epThumb}
                              alt={ep.title}
                              loading="lazy"
                              className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                              referrerPolicy="no-referrer"
                            />
                          ) : (
                            <div className="w-full h-full flex items-center justify-center font-mono font-extrabold text-sm text-gray-500">
                              EP {ep.episodeNumber}
                            </div>
                          )}

                          <div
                            className={`absolute inset-0 flex items-center justify-center transition-all ${
                              isActive
                                ? "bg-amber-500/30 backdrop-blur-[2px]"
                                : "bg-black/40 opacity-0 group-hover:opacity-100"
                            }`}
                          >
                            <div
                              className={`w-10 h-10 rounded-full flex items-center justify-center shadow-lg transition-transform ${
                                isActive
                                  ? "bg-amber-500 text-black scale-110"
                                  : "bg-white/90 text-black group-hover:scale-110"
                              }`}
                            >
                              <Play className="w-5 h-5 fill-current ml-0.5" />
                            </div>
                          </div>

                          {isActive && (
                            <div className="absolute top-2 right-2 bg-amber-500 text-black px-2 py-0.5 rounded-full text-[10px] font-extrabold font-mono flex items-center gap-1 shadow-md">
                              <Check className="w-3 h-3 stroke-[3]" />
                              ACTIVE
                            </div>
                          )}

                          <div className="absolute bottom-2 left-2 bg-black/80 backdrop-blur-md px-2 py-0.5 rounded-md text-[10px] font-mono font-extrabold text-amber-400 border border-white/10">
                            {ep.episodeNumber > 0 ? `EP ${ep.episodeNumber}` : "MOVIE"}
                          </div>
                        </div>

                        <div className="flex flex-col gap-1">
                          <div className="flex items-center justify-between gap-2">
                            <span className="text-[10px] font-mono font-bold text-amber-400 uppercase tracking-wider">
                              {ep.episodeNumber > 0
                                ? `Episode ${ep.episodeNumber}`
                                : "Feature"}
                            </span>
                            {isActive && (
                              <span className="text-[10px] font-bold text-emerald-400 flex items-center gap-1">
                                ✓ Playing
                              </span>
                            )}
                          </div>

                          <h4
                            className={`font-bold text-xs line-clamp-1 ${
                              isActive
                                ? "text-amber-400"
                                : "text-white group-hover:text-amber-300"
                            }`}
                          >
                            {ep.title}
                          </h4>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </div>
        </div>
      ) : (
        <div className="text-center py-20 bg-[#131520]/30 border border-white/5 rounded-2xl">
          <Film className="w-10 h-10 text-neutral-600 mx-auto mb-3" />
          <h4 className="font-bold text-sm text-white">No Episodes Found</h4>
          <p className="text-xs text-gray-400 mt-1">
            No playable episodes were returned for this title.
          </p>
        </div>
      )}

      {popularCatalog.length > 0 && (
        <div className="mt-14 pt-8 border-t border-white/5">
          <div className="flex items-center gap-2 mb-2 px-1">
            <span className="px-2.5 py-1 rounded-full bg-brand-primary/10 border border-brand-primary/20 text-[11px] font-mono font-bold text-brand-primary uppercase tracking-wider flex items-center gap-1.5">
              <Sparkles className="w-3.5 h-3.5" /> Catalog Recommendations
            </span>
          </div>
          <HorizontalScrollSection
            title="More Titles You Might Like"
            items={popularCatalog}
            renderItem={(recommendedAnime) => (
              <AnimeCard
                key={recommendedAnime.id}
                anime={recommendedAnime}
                onClick={() => setView("anime-details", recommendedAnime.id)}
              />
            )}
          />
        </div>
      )}
    </div>
  );
};
