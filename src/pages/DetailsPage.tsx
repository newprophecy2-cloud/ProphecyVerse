import React, { useState, useEffect, useMemo } from "react";
import { useApp } from "../context/AppContext";
import {
  subscribeToComments,
  addComment,
  rateAnime,
  fetchAnimeRatings,
  fetchUserRating,
} from "../services/dbService";
import {
  BackendApiError,
  fetchMovieBoxDetails,
} from "../services/movieboxApiService";
import { Episode, Comment, Anime } from "../types";
import {
  Play,
  Plus,
  Check,
  Star,
  Share2,
  Send,
  Clock,
  Film,
  Globe,
  AlertTriangle,
  RefreshCw,
} from "lucide-react";
import { HorizontalScrollSection } from "../components/HorizontalScrollSection";
import { AnimeCard } from "../components/AnimeCard";

interface DetailsPageProps {
  animeId: string;
  setView: (view: string, detailsId?: string) => void;
  setWatchParams?: (
    animeId: string,
    episodeNumber: number,
    initialPosition: number,
    seasonNumber?: number
  ) => void;
}

export const DetailsPage: React.FC<DetailsPageProps> = ({
  animeId,
  setView,
  setWatchParams,
}) => {
  const {
    animeList,
    watchlist,
    favorites,
    toggleWatchlist,
    toggleFavorite,
    user,
    profile,
    setGuestUser,
    upsertCatalogItems,
  } = useApp();

  const [detailsAnime, setDetailsAnime] = useState<Anime | null>(() => {
    return animeList.find((a) => a.id === animeId) || null;
  });
  const [episodes, setEpisodes] = useState<Episode[]>([]);
  const [selectedSeasonNumber, setSelectedSeasonNumber] = useState<number>(1);
  const [comments, setComments] = useState<Comment[]>([]);
  const [newComment, setNewComment] = useState("");
  const [averageRating, setAverageRating] = useState<number>(0);
  const [userRating, setUserRating] = useState<number | null>(null);
  const [isCopied, setIsCopied] = useState(false);
  const [isLoadingDetails, setIsLoadingDetails] = useState(true);
  const [detailsError, setDetailsError] = useState<string | null>(null);
  const [reloadTick, setReloadTick] = useState(0);

  const anime = detailsAnime || animeList.find((a) => a.id === animeId) || null;
  const inWatchlist = watchlist.includes(animeId);
  const inFavorites = favorites.includes(animeId);

  useEffect(() => {
    if (!animeId) return;
    const controller = new AbortController();
    setIsLoadingDetails(true);
    setDetailsError(null);

    fetchMovieBoxDetails(animeId, controller.signal)
      .then(({ anime: loadedAnime, episodes: loadedEpisodes }) => {
        setDetailsAnime(loadedAnime);
        setEpisodes(loadedEpisodes);
        upsertCatalogItems([loadedAnime]);

        const firstSeason =
          loadedEpisodes[0]?.seasonNumber !== undefined
            ? loadedEpisodes[0].seasonNumber
            : loadedAnime.type === "Movie"
            ? 0
            : 1;
        setSelectedSeasonNumber(firstSeason);
      })
      .catch((err) => {
        if (controller.signal.aborted) return;
        if (err instanceof BackendApiError) {
          setDetailsError(err.message);
        } else {
          setDetailsError(
            err instanceof Error ? err.message : "Failed to load MovieBox details."
          );
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setIsLoadingDetails(false);
        }
      });

    fetchAnimeRatings(animeId).then((score) => {
      if (score > 0) setAverageRating(score);
    });

    if (user && user.uid !== "guest-user-id") {
      fetchUserRating(user.uid, animeId).then((score) => {
        setUserRating(score);
      });
    }

    const unsubscribe = subscribeToComments(animeId, (coms) => {
      setComments(coms);
    });

    return () => {
      controller.abort();
      unsubscribe();
    };
  }, [animeId, user, reloadTick, upsertCatalogItems]);

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
    const nums = Array.from(seasonsMap.keys()).sort((a, b) => a - b);
    return nums.length > 0 ? nums : [anime?.type === "Movie" ? 0 : 1];
  }, [seasonsMap, anime?.type]);

  const activeSeasonEpisodes = useMemo(() => {
    return (
      seasonsMap.get(selectedSeasonNumber) ||
      episodes.filter((e) => (e.seasonNumber ?? 0) === selectedSeasonNumber)
    );
  }, [seasonsMap, selectedSeasonNumber, episodes]);

  const handleRatingSubmit = async (score: number) => {
    if (!user || user.uid === "guest-user-id") {
      setView("auth");
      return;
    }
    setUserRating(score);
    await rateAnime(user.uid, animeId, score);
    const avg = await fetchAnimeRatings(animeId);
    if (avg > 0) setAverageRating(avg);
  };

  const handleAddComment = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newComment.trim()) return;
    if (!user || !profile || user.uid === "guest-user-id") {
      setGuestUser();
      return;
    }
    await addComment(
      animeId,
      undefined,
      user.uid,
      profile.displayName,
      newComment,
      profile.photoURL
    );
    setNewComment("");
  };

  const handleShareClick = () => {
    navigator.clipboard.writeText(window.location.href);
    setIsCopied(true);
    setTimeout(() => setIsCopied(false), 2000);
  };

  const handleWatchlistToggle = () => {
    if (!user || user.uid === "guest-user-id") {
      setView("auth");
    } else {
      toggleWatchlist(animeId);
    }
  };

  const handleFavoriteToggle = () => {
    if (!user || user.uid === "guest-user-id") {
      setView("auth");
    } else {
      toggleFavorite(animeId);
    }
  };

  const recommendations = useMemo(() => {
    return animeList.filter((a) => a.id !== animeId).slice(0, 12);
  }, [animeList, animeId]);

  if (!anime && isLoadingDetails) {
    return (
      <div className="w-full min-h-[60vh] flex flex-col items-center justify-center gap-3 text-gray-300">
        <Clock className="w-8 h-8 text-brand-primary animate-spin" />
        <p className="text-sm font-mono">Loading MovieBox details...</p>
      </div>
    );
  }

  if (!anime) {
    return (
      <div className="w-full min-h-[60vh] flex flex-col items-center justify-center gap-4 text-center px-6">
        <AlertTriangle className="w-10 h-10 text-amber-400" />
        <h2 className="font-display font-bold text-xl text-white">
          Title Details Unavailable
        </h2>
        <p className="text-sm text-gray-400 max-w-md">
          {detailsError || "Unable to load metadata for this MovieBox title."}
        </p>
        <button
          onClick={() => setReloadTick((t) => t + 1)}
          className="flex items-center gap-2 px-5 py-2.5 rounded-xl bg-brand-primary text-white text-xs font-bold uppercase cursor-pointer"
        >
          <RefreshCw className="w-4 h-4" />
          Retry
        </button>
      </div>
    );
  }

  const firstEp = episodes[0];

  return (
    <div className="w-full min-h-screen pb-20">
      <div className="relative w-full h-56 sm:h-72 md:h-96 rounded-3xl overflow-hidden mb-8 border border-white/5 shadow-2xl">
        <img
          src={anime.banner || anime.poster}
          alt={anime.title}
          className="w-full h-full object-cover"
          referrerPolicy="no-referrer"
        />
        <div className="absolute inset-0 bg-gradient-to-t from-[#0b0c10] via-[#0b0c10]/60 to-transparent" />
      </div>

      <div className="flex flex-col lg:flex-row gap-10 px-1">
        <div className="w-full lg:w-72 flex flex-col gap-5 flex-shrink-0 -mt-20 md:-mt-28 z-10">
          <div className="w-48 sm:w-56 lg:w-full aspect-[2/3] mx-auto rounded-2xl overflow-hidden shadow-2xl border-2 border-white/10 bg-neutral-900">
            <img
              src={anime.poster}
              alt={anime.title}
              className="w-full h-full object-cover"
              referrerPolicy="no-referrer"
            />
          </div>

          <div className="flex flex-col gap-3">
            <button
              onClick={() => {
                const targetSeason =
                  firstEp?.seasonNumber ?? (anime.type === "Movie" ? 0 : 1);
                const targetEpNum =
                  firstEp?.episodeNumber ?? (anime.type === "Movie" ? 0 : 1);
                if (setWatchParams) {
                  setWatchParams(anime.id, targetEpNum, 0, targetSeason);
                }
                setView("watch", anime.id);
              }}
              className="w-full py-4 rounded-xl font-bold text-sm text-white brand-gradient shadow-lg shadow-brand-glow flex items-center justify-center gap-2 hover:opacity-95 transition-all transform active:scale-95 cursor-pointer"
            >
              <Play className="w-4.5 h-4.5 fill-current" />
              {anime.type === "Movie" ? "Watch Movie" : "Watch Episode 1"}
            </button>

            <div className="grid grid-cols-2 gap-3">
              <button
                onClick={handleWatchlistToggle}
                className={`py-3 rounded-xl font-semibold text-xs border transition-all flex items-center justify-center gap-1.5 cursor-pointer ${
                  inWatchlist
                    ? "bg-brand-primary/15 border-brand-primary text-brand-primary"
                    : "bg-white/5 border-white/5 text-gray-300 hover:text-white hover:bg-white/10"
                }`}
              >
                {inWatchlist ? (
                  <Check className="w-4 h-4" />
                ) : (
                  <Plus className="w-4 h-4" />
                )}
                {inWatchlist ? "In Watchlist" : "Watchlist"}
              </button>

              <button
                onClick={handleFavoriteToggle}
                className={`py-3 rounded-xl font-semibold text-xs border transition-all flex items-center justify-center gap-1.5 cursor-pointer ${
                  inFavorites
                    ? "bg-amber-500/15 border-amber-500 text-amber-400"
                    : "bg-white/5 border-white/5 text-gray-300 hover:text-white hover:bg-white/10"
                }`}
              >
                <Star
                  className={`w-4 h-4 ${inFavorites ? "fill-current" : ""}`}
                />
                {inFavorites ? "Favorited" : "Favorite"}
              </button>
            </div>

            <button
              onClick={handleShareClick}
              className="w-full py-2.5 rounded-xl bg-white/5 border border-white/5 text-gray-400 hover:text-white text-xs font-medium flex items-center justify-center gap-1.5 transition-all cursor-pointer"
            >
              <Share2 className="w-3.5 h-3.5" />
              {isCopied ? "Link Copied!" : "Share Title"}
            </button>
          </div>

          <div className="p-5 rounded-2xl bg-[#131520]/80 border border-white/5 flex flex-col gap-3 text-xs">
            <div className="flex justify-between">
              <span className="text-gray-500 font-mono">Format</span>
              <span className="font-semibold text-white">{anime.type}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-gray-500 font-mono">Year</span>
              <span className="font-semibold text-white">{anime.releaseYear}</span>
            </div>
            {anime.country && (
              <div className="flex justify-between gap-2">
                <span className="text-gray-500 font-mono">Country</span>
                <span className="font-semibold text-white text-right">
                  {anime.country}
                </span>
              </div>
            )}
            {anime.director && (
              <div className="flex justify-between gap-2">
                <span className="text-gray-500 font-mono">Director</span>
                <span className="font-semibold text-white text-right">
                  {anime.director}
                </span>
              </div>
            )}
            {anime.duration && (
              <div className="flex justify-between">
                <span className="text-gray-500 font-mono">Duration</span>
                <span className="font-semibold text-white">{anime.duration}</span>
              </div>
            )}
            <div className="flex justify-between">
              <span className="text-gray-500 font-mono">Community Rating</span>
              <span className="font-semibold text-amber-400">
                ★ {averageRating > 0 ? averageRating : anime.rating} / 10
              </span>
            </div>
          </div>
        </div>

        <div className="flex-1 flex flex-col gap-8">
          <div className="flex flex-col gap-3">
            <h1 className="font-display font-extrabold text-3xl md:text-5xl text-white tracking-tight">
              {anime.title}
            </h1>
            {anime.japaneseTitle && anime.japaneseTitle !== anime.title && (
              <p className="text-sm font-mono text-gray-400">
                {anime.japaneseTitle}
              </p>
            )}

            <div className="flex flex-wrap gap-2 mt-1">
              {(anime.genres || []).map((g) => (
                <span
                  key={g}
                  className="px-3 py-1 rounded-full text-xs font-medium bg-white/5 border border-white/5 text-gray-300"
                >
                  {g}
                </span>
              ))}
            </div>

            {anime.dubs && anime.dubs.length > 0 && (
              <div className="flex flex-wrap items-center gap-2 mt-2">
                <span className="text-xs font-mono text-gray-400 flex items-center gap-1.5">
                  <Globe className="w-3.5 h-3.5 text-brand-primary" />
                  Audio Dubs:
                </span>
                {anime.dubs.map((d) => (
                  <button
                    key={d.subjectId}
                    onClick={() => setView("anime-details", d.subjectId)}
                    className={`px-2.5 py-1 rounded-lg text-xs font-semibold border transition-all cursor-pointer ${
                      d.subjectId === animeId
                        ? "bg-brand-primary/20 border-brand-primary text-brand-primary"
                        : "bg-white/5 border-white/5 text-gray-300 hover:text-white"
                    }`}
                  >
                    {d.language}
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="flex flex-col gap-2">
            <h3 className="text-xs font-mono uppercase tracking-widest text-gray-500 font-bold">
              Synopsis
            </h3>
            <p className="text-gray-300 text-sm md:text-base leading-relaxed">
              {anime.description}
            </p>
            {anime.stars && (
              <p className="text-xs text-gray-400 mt-1">
                <span className="font-mono text-gray-500">Cast:</span> {anime.stars}
              </p>
            )}
          </div>

          <div className="p-5 rounded-2xl bg-[#131520]/40 border border-white/5 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div>
              <h4 className="font-bold text-sm text-white">Rate this Title</h4>
              <p className="text-xs text-gray-400 mt-0.5">
                {userRating
                  ? `You rated this ${userRating}/10`
                  : "Share your personal rating with the community"}
              </p>
            </div>
            <div className="flex items-center gap-1">
              {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((num) => (
                <button
                  key={num}
                  onClick={() => handleRatingSubmit(num)}
                  className={`w-7 h-7 rounded-lg text-xs font-bold transition-all cursor-pointer ${
                    userRating && num <= userRating
                      ? "bg-amber-500 text-black shadow"
                      : "bg-white/5 text-gray-400 hover:bg-white/15 hover:text-white"
                  }`}
                >
                  {num}
                </button>
              ))}
            </div>
          </div>

          {detailsError && (
            <div className="p-4 rounded-xl bg-amber-500/10 border border-amber-500/20 text-amber-300 text-xs flex items-center gap-2.5">
              <AlertTriangle className="w-4 h-4 shrink-0" />
              <span>{detailsError}</span>
            </div>
          )}

          <div className="flex flex-col gap-4">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
              <h3 className="font-display font-extrabold text-xl text-white tracking-tight flex items-center gap-2">
                {anime.type === "Movie" ? "Feature Stream" : "Episodes"}
                <span className="w-1 h-5 brand-gradient rounded-full" />
              </h3>

              {sortedSeasonNumbers.length > 0 && anime.type !== "Movie" && (
                <div className="flex items-center gap-2 overflow-x-auto no-scrollbar pb-1">
                  {sortedSeasonNumbers.map((sNum) => {
                    const seasonEpsCount = seasonsMap.get(sNum)?.length || 0;
                    const isSelected = selectedSeasonNumber === sNum;
                    return (
                      <button
                        key={sNum}
                        onClick={() => setSelectedSeasonNumber(sNum)}
                        className={`px-4 py-2 rounded-xl text-xs font-bold whitespace-nowrap transition-all flex items-center gap-1.5 cursor-pointer ${
                          isSelected
                            ? "brand-gradient text-white shadow-lg shadow-brand-glow"
                            : "bg-[#131520] text-gray-400 hover:text-white border border-white/5 hover:border-white/10"
                        }`}
                      >
                        <Film className="w-3.5 h-3.5" />
                        <span>Season {sNum}</span>
                        <span
                          className={`px-1.5 py-0.2 rounded-full text-[10px] ${
                            isSelected
                              ? "bg-black/30 text-white"
                              : "bg-white/10 text-gray-400"
                          }`}
                        >
                          {seasonEpsCount}
                        </span>
                      </button>
                    );
                  })}
                </div>
              )}
            </div>

            {isLoadingDetails ? (
              <div className="flex items-center gap-3.5 py-6">
                <Clock className="w-5 h-5 text-brand-primary animate-spin" />
                <span className="text-sm text-gray-400">
                  Loading MovieBox episodes...
                </span>
              </div>
            ) : activeSeasonEpisodes.length > 0 ? (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {activeSeasonEpisodes.map((ep) => (
                  <div
                    key={ep.id}
                    onClick={() => {
                      if (setWatchParams) {
                        setWatchParams(
                          animeId,
                          ep.episodeNumber,
                          0,
                          ep.seasonNumber ?? selectedSeasonNumber
                        );
                      }
                      setView("watch", animeId);
                    }}
                    className="flex gap-4 p-3 rounded-2xl bg-[#131520]/50 border border-white/5 hover:border-brand-primary/25 cursor-pointer hover:bg-[#131520]/80 transition-all group"
                  >
                    <div className="relative w-28 aspect-video rounded-xl overflow-hidden bg-neutral-900 flex-shrink-0">
                      <img
                        src={
                          ep.thumbnail ||
                          ep.thumbnailUrl ||
                          anime.banner ||
                          anime.poster
                        }
                        alt={ep.title}
                        className="w-full h-full object-cover group-hover:scale-105 transition-transform"
                        referrerPolicy="no-referrer"
                      />
                      <div className="absolute inset-0 bg-black/40 flex items-center justify-center">
                        <Play className="w-4 h-4 fill-current text-white scale-90 group-hover:scale-110 transition-transform" />
                      </div>
                    </div>

                    <div className="flex flex-col justify-between truncate">
                      <div>
                        <h4 className="font-semibold text-xs text-brand-primary font-mono uppercase tracking-wider">
                          {ep.seasonNumber && ep.seasonNumber > 0
                            ? `Season ${ep.seasonNumber} • EP ${ep.episodeNumber}`
                            : "Full Feature"}
                        </h4>
                        <h5 className="font-bold text-sm text-white group-hover:text-brand-primary transition-colors truncate mt-0.5">
                          {ep.title}
                        </h5>
                      </div>
                      <p className="text-[11px] text-gray-400 line-clamp-1 mt-1">
                        {ep.description || "Stream via MovieBox Provider"}
                      </p>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="p-8 rounded-2xl border border-white/5 bg-[#131520]/30 text-center">
                <Clock className="w-8 h-8 text-neutral-600 mx-auto mb-2.5" />
                <h4 className="font-bold text-sm text-white">
                  No Episodes Available for Season {selectedSeasonNumber}
                </h4>
                <p className="text-xs text-gray-400 mt-1">
                  Try another season tab or check back later.
                </p>
              </div>
            )}
          </div>

          <div className="flex flex-col gap-4 mt-8">
            <h3 className="font-display font-extrabold text-xl text-white tracking-tight flex items-center gap-2">
              Discussions
              <span className="w-1 h-5 brand-gradient rounded-full" />
            </h3>

            <form onSubmit={handleAddComment} className="flex gap-3">
              <input
                type="text"
                placeholder={
                  user && user.uid !== "guest-user-id"
                    ? "Share your thoughts on this title..."
                    : "Sign in to post comments..."
                }
                disabled={!user || user.uid === "guest-user-id"}
                value={newComment}
                onChange={(e) => setNewComment(e.target.value)}
                className="flex-1 px-4 py-3 bg-[#131520] border border-white/5 focus:border-brand-primary/35 rounded-xl text-sm text-white focus:outline-none focus:bg-[#131520]/80 transition-all"
              />
              <button
                type="submit"
                disabled={
                  !user || user.uid === "guest-user-id" || !newComment.trim()
                }
                className="p-3 rounded-xl bg-brand-primary hover:bg-brand-primary/95 text-white hover:shadow-lg hover:shadow-brand-glow transition-all transform active:scale-95 disabled:opacity-40 disabled:pointer-events-none cursor-pointer"
              >
                <Send className="w-4 h-4" />
              </button>
            </form>

            <div className="flex flex-col gap-3 max-h-[350px] overflow-y-auto no-scrollbar pr-1 mt-2">
              {comments.length > 0 ? (
                comments.map((comment) => (
                  <div
                    key={comment.id}
                    className="flex gap-3.5 p-4 rounded-xl bg-[#131520]/30 border border-white/5"
                  >
                    <img
                      src={
                        comment.userPhoto ||
                        `https://api.dicebear.com/7.x/adventurer/svg?seed=${encodeURIComponent(
                          comment.userName
                        )}`
                      }
                      alt={comment.userName}
                      className="w-9 h-9 rounded-full border border-white/5 bg-neutral-800 object-cover"
                    />
                    <div className="flex-1 flex flex-col gap-0.5">
                      <div className="flex items-center justify-between">
                        <span className="font-bold text-xs text-white">
                          {comment.userName}
                        </span>
                        <span className="text-[9px] font-mono text-gray-500">
                          {comment.createdAt
                            ? new Date(
                                comment.createdAt.toDate
                                  ? comment.createdAt.toDate()
                                  : comment.createdAt
                              ).toLocaleDateString()
                            : "Just now"}
                        </span>
                      </div>
                      <p className="text-xs text-gray-300 leading-relaxed mt-1">
                        {comment.text}
                      </p>
                    </div>
                  </div>
                ))
              ) : (
                <div className="text-center py-8 text-xs text-gray-500 font-mono">
                  No discussions yet. Be the first to start the conversation!
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {recommendations.length > 0 && (
        <div className="mt-14">
          <HorizontalScrollSection
            title="More on MovieBox"
            items={recommendations}
            renderItem={(rec) => (
              <AnimeCard
                key={rec.id}
                anime={rec}
                onClick={() => setView("anime-details", rec.id)}
              />
            )}
          />
        </div>
      )}
    </div>
  );
};
