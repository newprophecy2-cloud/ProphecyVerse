import React, { useState, useEffect, useMemo } from "react";
import { useApp } from "../context/AppContext";
import { usePlayer } from "../context/PlayerContext";
import { fetchEpisodesForAnime, updateWatchProgress } from "../services/dbService";
import { Episode, Anime } from "../types";
import { VideoPlayer } from "../components/VideoPlayer";
import { AnimeCard } from "../components/AnimeCard";
import { HorizontalScrollSection } from "../components/HorizontalScrollSection";
import { 
  ChevronLeft, List, SkipBack, SkipForward, Film, Sparkles, Maximize2, 
  Play, Check, Layers, Clock, Tv, RefreshCw, Bookmark, ArrowRight 
} from "lucide-react";

interface WatchPageProps {
  animeId: string;
  setView: (view: string, detailsId?: string) => void;
  episodeNumber?: number;
  initialPosition?: number;
}

export const WatchPage: React.FC<WatchPageProps> = ({
  animeId,
  setView,
  episodeNumber = 1,
  initialPosition = 0,
}) => {
  const { animeList, user, refreshUserData } = useApp();
  const { 
    playerMode, 
    minimizePlayer, 
    expandPlayer, 
    setActiveAnime, 
    setActiveEpisode, 
    setPlayerMode, 
    setCurrentTime 
  } = usePlayer();

  const [episodes, setEpisodes] = useState<Episode[]>([]);
  const [currentEpisode, setCurrentEpisode] = useState<Episode | null>(null);
  const [selectedSeasonNumber, setSelectedSeasonNumber] = useState<number>(1);
  const [resumePosition, setResumePosition] = useState<number>(initialPosition);
  const [resumeMessage, setResumeMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const anime = animeList.find((a) => a.id === animeId);

  // Group episodes by seasonNumber
  const seasonsMap = useMemo(() => {
    const map = new Map<number, Episode[]>();
    for (const ep of episodes) {
      const sNum = ep.seasonNumber || 1;
      const list = map.get(sNum) || [];
      list.push(ep);
      map.set(sNum, list);
    }
    // Sort episodes in each season by episodeNumber
    for (const [, epList] of map.entries()) {
      epList.sort((a, b) => (Number(a.episodeNumber) || 0) - (Number(b.episodeNumber) || 0));
    }
    return map;
  }, [episodes]);

  const sortedSeasonNumbers = useMemo(() => {
    return Array.from(seasonsMap.keys()).sort((a: number, b: number) => a - b);
  }, [seasonsMap]);

  // Sync active anime & episode with global PlayerContext
  useEffect(() => {
    if (anime && currentEpisode) {
      setActiveAnime(anime);
      setActiveEpisode(currentEpisode);
      if (playerMode === "hidden") {
        setPlayerMode("full");
      }
    }
  }, [anime, currentEpisode]);

  // Compute Popular Anime recommendations strictly from website database
  const popularDatabaseAnime = useMemo(() => {
    if (!animeList || animeList.length === 0) return [];
    return animeList
      .filter((a) => a.isVisible !== false && a.id !== animeId)
      .sort((a, b) => {
        const scoreA = (a.popularity || 0) * 10 + (a.score ? a.score * 100 : 0) + (a.favorites || 0);
        const scoreB = (b.popularity || 0) * 10 + (b.score ? b.score * 100 : 0) + (b.favorites || 0);
        return scoreB - scoreA;
      });
  }, [animeList, animeId]);

  // 1. Fetch episodes list for this anime
  useEffect(() => {
    if (!animeId) return;
    setIsLoading(true);

    fetchEpisodesForAnime(animeId).then((eps) => {
      setEpisodes(eps);

      // Check LocalStorage Continue Watching state if initialPosition is 0
      let targetSeason = 1;
      let targetEpNum = episodeNumber;
      let startPos = initialPosition;

      try {
        const savedDataStr = localStorage.getItem(`anime_continue_watching_${animeId}`);
        if (savedDataStr && initialPosition === 0) {
          const savedData = JSON.parse(savedDataStr);
          if (savedData?.episodeNumber && savedData?.position > 5) {
            targetSeason = savedData.seasonNumber || 1;
            targetEpNum = savedData.episodeNumber;
            startPos = savedData.position || 0;
            
            const minutes = Math.floor(startPos / 60);
            const seconds = Math.floor(startPos % 60);
            setResumeMessage(
              `Resumed Season ${targetSeason} Episode ${targetEpNum} at ${minutes}:${seconds < 10 ? '0' : ''}${seconds}`
            );
          }
        }
      } catch (e) {
        console.warn("Continue watching restore parse error:", e);
      }

      // Find targeted episode or fallback to first episode
      const targetEp = eps.find((e) => (e.seasonNumber || 1) === targetSeason && e.episodeNumber === targetEpNum)
        || eps.find((e) => e.episodeNumber === targetEpNum)
        || eps[0];

      if (targetEp) {
        setCurrentEpisode(targetEp);
        setSelectedSeasonNumber(targetEp.seasonNumber || targetSeason || 1);
        setResumePosition(startPos);
      }
      setIsLoading(false);
    });
  }, [animeId, episodeNumber, initialPosition]);

  // Update browser URL route when episode/season changes
  useEffect(() => {
    if (!anime || !currentEpisode) return;
    const sNum = currentEpisode.seasonNumber || selectedSeasonNumber || 1;
    const epNum = currentEpisode.episodeNumber;
    const slugTitle = (anime.title || "anime")
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "");
    
    const newRoute = `/watch/${slugTitle}/season-${sNum}/episode-${epNum}`;
    try {
      window.history.replaceState(null, "", newRoute);
    } catch (e) {
      // Ignore if iframe location restriction
    }
  }, [anime, currentEpisode, selectedSeasonNumber]);

  // Save continue watching progress
  const saveContinueWatching = (sNum: number, epNum: number, posSeconds: number, totalDuration: number) => {
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
    } catch (e) {
      // Ignore localStorage quota
    }

    const uid = user ? user.uid : "guest-user-id";
    updateWatchProgress(uid, animeId, epNum, posSeconds, totalDuration).catch((err) =>
      console.error("Progress sync error:", err)
    );
  };

  // Select episode from UI
  const selectEpisode = (ep: Episode, seasonNum?: number) => {
    setCurrentEpisode(ep);
    const targetSeason = seasonNum || ep.seasonNumber || 1;
    setSelectedSeasonNumber(targetSeason);
    setResumePosition(0);
    setResumeMessage(null);
  };

  const handleProgressUpdate = (seconds: number, totalDuration: number) => {
    if (!currentEpisode) return;
    setCurrentTime(seconds);
    const sNum = currentEpisode.seasonNumber || selectedSeasonNumber || 1;
    saveContinueWatching(sNum, currentEpisode.episodeNumber, seconds, totalDuration);
  };

  const handleEpisodeComplete = () => {
    if (!currentEpisode) return;
    const uid = user ? user.uid : "guest-user-id";
    const durSecs = currentEpisode.duration ? parseFloat(currentEpisode.duration) * 60 : 1440;
    
    updateWatchProgress(
      uid,
      animeId,
      currentEpisode.episodeNumber,
      durSecs,
      durSecs
    ).then(() => refreshUserData());

    // Trigger Next Episode automatically
    triggerNext();
  };

  // Logic for Next Episode (cross season support)
  const getNextEpisodeInfo = (): { nextEp: Episode | null; nextSeason: number } => {
    if (!currentEpisode) return { nextEp: null, nextSeason: selectedSeasonNumber };
    const currentSeasonEps = seasonsMap.get(selectedSeasonNumber) || [];
    const currentIndex = currentSeasonEps.findIndex((e) => e.id === currentEpisode.id);

    // 1. Next episode in same season
    if (currentIndex >= 0 && currentIndex < currentSeasonEps.length - 1) {
      return { nextEp: currentSeasonEps[currentIndex + 1], nextSeason: selectedSeasonNumber };
    }

    // 2. Last episode of season -> Find first episode of next season
    const currentSeasonIdx = sortedSeasonNumbers.indexOf(selectedSeasonNumber);
    if (currentSeasonIdx >= 0 && currentSeasonIdx < sortedSeasonNumbers.length - 1) {
      const nextSNum = sortedSeasonNumbers[currentSeasonIdx + 1];
      const nextSeasonEps = seasonsMap.get(nextSNum) || [];
      if (nextSeasonEps.length > 0) {
        return { nextEp: nextSeasonEps[0], nextSeason: nextSNum };
      }
    }

    return { nextEp: null, nextSeason: selectedSeasonNumber };
  };

  // Logic for Prev Episode (cross season support)
  const getPrevEpisodeInfo = (): { prevEp: Episode | null; prevSeason: number } => {
    if (!currentEpisode) return { prevEp: null, prevSeason: selectedSeasonNumber };
    const currentSeasonEps = seasonsMap.get(selectedSeasonNumber) || [];
    const currentIndex = currentSeasonEps.findIndex((e) => e.id === currentEpisode.id);

    // 1. Previous episode in same season
    if (currentIndex > 0) {
      return { prevEp: currentSeasonEps[currentIndex - 1], prevSeason: selectedSeasonNumber };
    }

    // 2. First episode of season -> Find last episode of previous season
    const currentSeasonIdx = sortedSeasonNumbers.indexOf(selectedSeasonNumber);
    if (currentSeasonIdx > 0) {
      const prevSNum = sortedSeasonNumbers[currentSeasonIdx - 1];
      const prevSeasonEps = seasonsMap.get(prevSNum) || [];
      if (prevSeasonEps.length > 0) {
        return { prevEp: prevSeasonEps[prevSeasonEps.length - 1], prevSeason: prevSNum };
      }
    }

    return { prevEp: null, prevSeason: selectedSeasonNumber };
  };

  const { nextEp, nextSeason } = getNextEpisodeInfo();
  const { prevEp, prevSeason } = getPrevEpisodeInfo();

  const triggerNext = () => {
    if (nextEp) {
      selectEpisode(nextEp, nextSeason);
    }
  };

  const triggerPrev = () => {
    if (prevEp) {
      selectEpisode(prevEp, prevSeason);
    }
  };

  if (!anime) return null;

  const activeSeasonEpisodes = seasonsMap.get(selectedSeasonNumber) || [];

  return (
    <div className="w-full min-h-screen pb-20 px-2 md:px-6 max-w-7xl mx-auto">
      {/* Top Header Navigation */}
      <div className="flex items-center justify-between py-4 mb-4 border-b border-white/5">
        <button
          onClick={() => setView("anime-details", animeId)}
          className="flex items-center gap-2 px-3.5 py-2 rounded-xl text-gray-400 hover:text-white hover:bg-white/5 transition-all cursor-pointer text-xs font-bold"
        >
          <ChevronLeft className="w-4 h-4" />
          <span>Back to Details</span>
        </button>

        <div className="text-center hidden sm:block">
          <h2 className="text-sm font-bold text-white font-display truncate max-w-md">
            {anime.title}
          </h2>
          <p className="text-[11px] text-amber-400 font-mono mt-0.5">
            Season {currentEpisode?.seasonNumber || selectedSeasonNumber} • Episode {currentEpisode?.episodeNumber || 1}
          </p>
        </div>

        <button
          onClick={() => setView("anime-details", animeId)}
          className="px-3.5 py-2 rounded-xl bg-white/5 hover:bg-white/10 text-xs font-bold text-gray-300 transition-all cursor-pointer flex items-center gap-1.5"
        >
          <Tv className="w-3.5 h-3.5 text-brand-primary" />
          <span className="hidden sm:inline">Overview</span>
        </button>
      </div>

      {isLoading ? (
        <div className="flex flex-col items-center justify-center py-24 gap-4">
          <RefreshCw className="w-8 h-8 text-brand-primary animate-spin" />
          <span className="text-xs text-gray-400 font-mono tracking-wider">
            Loading Video Stream & Seasons...
          </span>
        </div>
      ) : currentEpisode ? (
        <div className="flex flex-col gap-8">
          
          {/* ========================================================== */}
          {/* PLAYER SECTION (16:9 Responsive Modern Video Player)     */}
          {/* ========================================================== */}
          <div className="w-full flex flex-col gap-3">
            {playerMode === "mini" ? (
              <div className="relative aspect-video w-full rounded-2xl bg-[#131520]/80 border border-amber-500/30 flex flex-col items-center justify-center gap-3 p-6 text-center shadow-xl">
                <div className="w-12 h-12 rounded-full bg-amber-500/10 border border-amber-500/20 flex items-center justify-center text-amber-400">
                  <Film className="w-6 h-6 animate-pulse" />
                </div>
                <div>
                  <h3 className="font-extrabold text-white text-base">Video Playing in Floating Mini-Player</h3>
                  <p className="text-xs text-gray-400 mt-1">
                    Season {currentEpisode.seasonNumber || selectedSeasonNumber} EP {currentEpisode.episodeNumber}: {currentEpisode.title}
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
                animeTitle={anime?.title}
                onBack={() => setView("anime-details", animeId)}
              />
            )}

            {/* Notification Toast for Resume position if loaded */}
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

          {/* ========================================================== */}
          {/* EPISODE NAVIGATION UI (Below Player)                       */}
          {/* ========================================================== */}
          <div className="flex flex-col gap-6 bg-[#131520]/60 border border-white/5 p-6 rounded-3xl backdrop-blur-md shadow-2xl">
            
            {/* Anime Title & Currently Watching Indicator */}
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 pb-5 border-b border-white/5">
              <div>
                <h1 className="font-display font-black text-2xl md:text-3xl text-white tracking-tight">
                  {anime.title}
                </h1>
                {anime.japaneseTitle && (
                  <p className="text-xs text-gray-400 font-mono mt-1">
                    {anime.japaneseTitle}
                  </p>
                )}
              </div>

              {/* Currently Watching Highlight Badge */}
              <div className="flex items-center gap-3 px-4 py-2.5 rounded-2xl bg-brand-primary/10 border border-brand-primary/30 text-white shadow-lg self-start md:self-auto">
                <div className="w-2.5 h-2.5 rounded-full bg-emerald-400 animate-ping" />
                <div className="flex flex-col">
                  <span className="text-[10px] uppercase font-mono font-bold text-gray-400 tracking-wider">
                    Currently Watching
                  </span>
                  <span className="text-xs font-extrabold text-amber-400 font-mono">
                    Season {currentEpisode.seasonNumber || selectedSeasonNumber} Episode {currentEpisode.episodeNumber}
                  </span>
                </div>
              </div>
            </div>

            {/* Season Selector Tabs */}
            <div className="flex flex-col gap-3">
              <label className="text-xs font-bold text-gray-400 uppercase tracking-wider font-mono flex items-center gap-2">
                <Layers className="w-4 h-4 text-brand-primary" />
                Select Season:
              </label>

              <div className="flex items-center gap-2.5 overflow-x-auto no-scrollbar pb-1">
                {(sortedSeasonNumbers.length > 0 ? sortedSeasonNumbers : [1]).map((sNum) => {
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
                            isSelected ? "bg-black/30 text-amber-200" : "bg-white/10 text-gray-400"
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

            {/* Episodes Grid/Cards for Selected Season */}
            <div className="flex flex-col gap-4 mt-2">
              <div className="flex items-center justify-between">
                <h3 className="font-display font-extrabold text-lg text-white flex items-center gap-2">
                  <span>Season {selectedSeasonNumber} Episodes</span>
                  <span className="text-xs font-mono font-normal text-gray-400">
                    ({activeSeasonEpisodes.length} available)
                  </span>
                </h3>

                {/* Prev / Next Season Jump buttons if multiple seasons */}
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
              </div>

              {activeSeasonEpisodes.length > 0 ? (
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-3.5">
                  {activeSeasonEpisodes.map((ep) => {
                    const isActive = currentEpisode.id === ep.id;
                    const epThumb = ep.thumbnail || ep.thumbnailUrl || anime.banner || anime.poster;

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
                        {/* Thumbnail or Number Box */}
                        <div className="relative aspect-video w-full rounded-xl overflow-hidden bg-neutral-900 border border-white/5 mb-3">
                          {epThumb ? (
                            <img
                              src={epThumb}
                              alt={ep.title || `Episode ${ep.episodeNumber}`}
                              loading="lazy"
                              className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
                              referrerPolicy="no-referrer"
                            />
                          ) : (
                            <div className="w-full h-full flex items-center justify-center font-mono font-extrabold text-sm text-gray-500">
                              EP {ep.episodeNumber}
                            </div>
                          )}

                          {/* Play overlay / Active Badge */}
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

                          {/* Top Right Active Checkmark Badge */}
                          {isActive && (
                            <div className="absolute top-2 right-2 bg-amber-500 text-black px-2 py-0.5 rounded-full text-[10px] font-extrabold font-mono flex items-center gap-1 shadow-md">
                              <Check className="w-3 h-3 stroke-[3]" />
                              ACTIVE
                            </div>
                          )}

                          {/* Bottom Left Episode Number Pill */}
                          <div className="absolute bottom-2 left-2 bg-black/80 backdrop-blur-md px-2 py-0.5 rounded-md text-[10px] font-mono font-extrabold text-amber-400 border border-white/10">
                            EP {ep.episodeNumber}
                          </div>
                        </div>

                        {/* Title & Info */}
                        <div className="flex flex-col gap-1">
                          <div className="flex items-center justify-between gap-2">
                            <span className="text-[10px] font-mono font-bold text-amber-400 uppercase tracking-wider">
                              Episode {ep.episodeNumber}
                            </span>
                            {isActive && (
                              <span className="text-[10px] font-bold text-emerald-400 flex items-center gap-1">
                                ✓ Playing
                              </span>
                            )}
                          </div>

                          <h4
                            className={`font-bold text-xs line-clamp-1 ${
                              isActive ? "text-amber-400" : "text-white group-hover:text-amber-300"
                            }`}
                          >
                            {ep.title || `Episode ${ep.episodeNumber}`}
                          </h4>

                          {ep.description && (
                            <p className="text-[11px] text-gray-400 line-clamp-2 leading-relaxed mt-0.5">
                              {ep.description}
                            </p>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              ) : (
                <div className="p-8 rounded-2xl border border-white/5 bg-black/30 text-center">
                  <Film className="w-8 h-8 text-neutral-600 mx-auto mb-2" />
                  <h4 className="font-bold text-xs text-white">No Episodes Found in Season {selectedSeasonNumber}</h4>
                  <p className="text-[11px] text-gray-400 mt-1">Select another season tab above.</p>
                </div>
              )}
            </div>

          </div>

        </div>
      ) : (
        <div className="text-center py-20 bg-[#131520]/30 border border-white/5 rounded-2xl">
          <Film className="w-10 h-10 text-neutral-600 mx-auto mb-3" />
          <h4 className="font-bold text-sm text-white">No Published Episodes Found</h4>
          <p className="text-xs text-gray-400 mt-1">
            An administrator can publish episodes in the catalog.
          </p>
        </div>
      )}

      {/* Suggested Popular Anime Section */}
      {popularDatabaseAnime.length > 0 && (
        <div className="mt-14 pt-8 border-t border-white/5">
          <div className="flex items-center gap-2 mb-2 px-1">
            <span className="px-2.5 py-1 rounded-full bg-brand-primary/10 border border-brand-primary/20 text-[11px] font-mono font-bold text-brand-primary uppercase tracking-wider flex items-center gap-1.5">
              <Sparkles className="w-3.5 h-3.5" /> Popular Recommendations
            </span>
          </div>
          <HorizontalScrollSection
            title="Popular Anime You Might Like"
            items={popularDatabaseAnime}
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
