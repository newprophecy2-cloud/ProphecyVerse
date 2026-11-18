import React, { useState, useEffect, useMemo } from "react";
import { useApp } from "../context/AppContext";
import { 
  fetchEpisodesForAnime, 
  subscribeToComments, 
  addComment, 
  rateAnime, 
  fetchAnimeRatings, 
  fetchUserRating 
} from "../services/dbService";
import { Episode, Comment, Anime } from "../types";
import { 
  Play, Plus, Check, Star, Share2, MessageSquare, Calendar, Compass, 
  User, Send, X, Clock, HelpCircle, Film
} from "lucide-react";
import { HorizontalScrollSection } from "../components/HorizontalScrollSection";
import { AnimeCard } from "../components/AnimeCard";
import { getPopularCatalogRecommendations } from "../services/animeApiService";


interface DetailsPageProps {
  animeId: string;
  setView: (view: string, detailsId?: string) => void;
  setWatchParams?: (animeId: string, episodeNumber: number, initialPosition: number) => void;
}

export const DetailsPage: React.FC<DetailsPageProps> = ({ 
  animeId, 
  setView, 
  setWatchParams 
}) => {
  const { 
    animeList, 
    watchlist, 
    favorites, 
    toggleWatchlist, 
    toggleFavorite, 
    user, 
    profile, 
    setGuestUser 
  } = useApp();

  const [episodes, setEpisodes] = useState<Episode[]>([]);
  const [selectedSeasonNumber, setSelectedSeasonNumber] = useState<number>(1);
  const [comments, setComments] = useState<Comment[]>([]);
  const [newComment, setNewComment] = useState("");
  const [averageRating, setAverageRating] = useState<number>(8.5);
  const [userRating, setUserRating] = useState<number | null>(null);
  const [isCopied, setIsCopied] = useState(false);
  const [isLoadingEpisodes, setIsLoadingEpisodes] = useState(true);

  // Group episodes by seasonNumber
  const seasonsMap = useMemo(() => {
    const map = new Map<number, Episode[]>();
    for (const ep of episodes) {
      const sNum = ep.seasonNumber || 1;
      const list = map.get(sNum) || [];
      list.push(ep);
      map.set(sNum, list);
    }
    for (const [, epList] of map.entries()) {
      epList.sort((a, b) => (Number(a.episodeNumber) || 0) - (Number(b.episodeNumber) || 0));
    }
    return map;
  }, [episodes]);

  const sortedSeasonNumbers = useMemo(() => {
    const nums = Array.from(seasonsMap.keys()).sort((a: number, b: number) => a - b);
    return nums.length > 0 ? nums : [1];
  }, [seasonsMap]);

  const activeSeasonEpisodes = useMemo(() => {
    return seasonsMap.get(selectedSeasonNumber) || episodes.filter(e => (e.seasonNumber || 1) === selectedSeasonNumber);
  }, [seasonsMap, selectedSeasonNumber, episodes]);

  // Find targeted anime details
  const anime = animeList.find((a) => a.id === animeId);

  const inWatchlist = watchlist.includes(animeId);
  const inFavorites = favorites.includes(animeId);

  // Load episodes & rating metadata
  useEffect(() => {
    if (!animeId) return;
    setIsLoadingEpisodes(true);
    
    // Fetch episodes
    fetchEpisodesForAnime(animeId).then((eps) => {
      setEpisodes(eps);
      setIsLoadingEpisodes(false);
    });

    // Fetch average ratings
    fetchAnimeRatings(animeId).then((score) => {
      if (score > 0) setAverageRating(score);
    });

    // Fetch user specific rating if logged in
    if (user) {
      fetchUserRating(user.uid, animeId).then((score) => {
        setUserRating(score);
      });
    }

    // Subscribe to comments
    const unsubscribe = subscribeToComments(animeId, (coms) => {
      setComments(coms);
    });

    return () => {
      unsubscribe();
    };
  }, [animeId, user]);

  const handleRatingSubmit = async (score: number) => {
    if (!user) {
      setGuestUser();
      return;
    }
    setUserRating(score);
    await rateAnime(user.uid, animeId, score);
    // Refresh average score
    const avg = await fetchAnimeRatings(animeId);
    if (avg > 0) setAverageRating(avg);
  };

  const handleAddComment = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newComment.trim()) return;
    if (!user || !profile) {
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
    if (!user) {
      setGuestUser();
    } else {
      toggleWatchlist(animeId);
    }
  };

  const handleFavoriteToggle = () => {
    if (!user) {
      setGuestUser();
    } else {
      toggleFavorite(animeId);
    }
  };

  const [popularRecommendations, setPopularRecommendations] = useState<Anime[]>([]);

  // Load popular catalog recommendations
  useEffect(() => {
    if (!animeList || animeList.length === 0) return;
    getPopularCatalogRecommendations(animeList, animeId).then((recs) => {
      setPopularRecommendations(recs);
    });
  }, [animeList, animeId]);

  // Fallback / merged recommendations
  const recommendations = popularRecommendations.length > 0 
    ? popularRecommendations 
    : animeList.filter((a) => {
        if (a.id === animeId) return false;
        return a.genres.some((g) => anime?.genres.includes(g)) || a.studio === anime?.studio;
      });

  if (!anime) {
    return (
      <div className="flex flex-col items-center justify-center py-24 text-center">
        <HelpCircle className="w-12 h-12 text-gray-600 mb-4 animate-pulse" />
        <h3 className="font-display font-extrabold text-lg text-white">Anime Not Found</h3>
        <button
          onClick={() => setView("home")}
          className="mt-6 px-5 py-2.5 rounded-xl bg-brand-primary text-white font-bold"
        >
          Return Home
        </button>
      </div>
    );
  }

  return (
    <div className="w-full min-h-screen pb-20">
      {/* 1. Large Cinematic Hero Banner Backdrop */}
      <div className="relative w-full h-[40vh] md:h-[55vh] rounded-3xl overflow-hidden shadow-2xl mb-8 group select-none">
        <img
          src={anime.banner}
          alt={anime.title}
          className="w-full h-full object-cover object-center scale-100 group-hover:scale-105 transition-transform duration-1000 ease-out"
        />
        <div className="absolute inset-0 bg-gradient-to-t from-[#0b0c10] via-[#0b0c10]/50 to-transparent" />
        <div className="absolute inset-0 bg-gradient-to-r from-[#0b0c10]/80 via-transparent to-transparent" />
      </div>

      {/* 2. Overlapping Details Block */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 px-1 md:px-4 relative -mt-20 md:-mt-32 z-10">
        
        {/* Left column: Poster & interactive buttons */}
        <div className="col-span-1 lg:col-span-4 flex flex-col gap-6">
          <div className="w-48 sm:w-60 lg:w-full mx-auto aspect-[2/3] rounded-3xl overflow-hidden shadow-2xl border border-white/5 bg-neutral-900">
            <img
              src={anime.poster}
              alt={anime.title}
              className="w-full h-full object-cover"
              referrerPolicy="no-referrer"
            />
          </div>

          {/* Inline controls */}
          <div className="grid grid-cols-2 gap-3.5">
            <button
              onClick={handleWatchlistToggle}
              className={`flex items-center justify-center gap-2 py-3 px-4 rounded-xl text-xs uppercase tracking-widest font-extrabold border transition-all ${
                inWatchlist
                  ? "bg-brand-primary/20 text-brand-primary border-brand-primary/20"
                  : "bg-white/5 text-white border-white/5 hover:bg-white/10"
              }`}
            >
              {inWatchlist ? <Check className="w-4 h-4" /> : <Plus className="w-4 h-4" />}
              {inWatchlist ? "Watchlisted" : "Watchlist"}
            </button>

            <button
              onClick={handleFavoriteToggle}
              className={`flex items-center justify-center gap-2 py-3 px-4 rounded-xl text-xs uppercase tracking-widest font-extrabold border transition-all ${
                inFavorites
                  ? "bg-amber-500/20 text-amber-500 border-amber-500/20"
                  : "bg-white/5 text-white border-white/5 hover:bg-white/10"
              }`}
            >
              <Star className={`w-4 h-4 ${inFavorites ? "fill-current" : ""}`} />
              {inFavorites ? "Favorited" : "Favorite"}
            </button>
          </div>

          <button
            onClick={handleShareClick}
            className={`w-full py-3 rounded-xl text-xs uppercase tracking-widest font-extrabold border border-white/5 flex items-center justify-center gap-2 transition-all ${
              isCopied ? "bg-emerald-500/10 text-emerald-400" : "bg-neutral-950 text-gray-300 hover:text-white"
            }`}
          >
            <Share2 className="w-4 h-4" />
            {isCopied ? "Link Copied!" : "Share Link"}
          </button>
        </div>

        {/* Right column: Info & metadata */}
        <div className="col-span-1 lg:col-span-8 flex flex-col gap-6">
          {/* Main Titles */}
          <div className="flex flex-col gap-1">
            <h1 className="font-display font-extrabold text-3xl md:text-5xl text-white tracking-tight leading-none">
              {anime.title}
            </h1>
            <p className="text-gray-400 text-sm font-mono mt-1">
              {anime.japaneseTitle} • {anime.englishTitle}
            </p>
          </div>

          {/* Horizontal Meta Pills */}
          <div className="flex flex-wrap items-center gap-2.5">
            <span className="px-3 py-1 rounded-full text-xs font-semibold bg-brand-primary/10 text-brand-primary border border-brand-primary/10">
              {anime.type}
            </span>
            <span className="px-3 py-1 rounded-full text-xs font-semibold bg-white/5 text-white border border-white/5">
              {anime.releaseYear}
            </span>
            <span className="px-3 py-1 rounded-full text-xs font-semibold bg-white/5 text-white border border-white/5">
              {anime.studio}
            </span>
            <span className="px-3 py-1 rounded-full text-xs font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/10">
              {anime.status}
            </span>
            <span className="px-3 py-1 rounded-full text-xs font-semibold bg-white/5 text-white border border-white/5">
              {anime.duration}
            </span>
          </div>

          {/* Genres Chips */}
          <div className="flex flex-wrap items-center gap-2">
            {anime.genres.map((g) => (
              <span
                key={g}
                className="px-3 py-1.5 rounded-xl text-xs font-medium bg-[#131520]/80 border border-white/5 text-gray-300 hover:bg-[#131520] transition-colors"
              >
                {g}
              </span>
            ))}
          </div>

          {/* Description */}
          <div className="flex flex-col gap-2">
            <h3 className="font-display font-bold text-lg text-white">Overview</h3>
            <p className="text-gray-300 text-sm leading-relaxed md:text-base">
              {anime.description}
            </p>
          </div>

          {/* 3. Average rating & Dynamic Rating voting system */}
          <div className="p-5 rounded-2xl bg-[#131520] border border-white/5 flex flex-col md:flex-row md:items-center justify-between gap-6 shadow-inner mt-4">
            <div className="flex items-center gap-4">
              <div className="w-14 h-14 rounded-xl bg-amber-500/10 flex items-center justify-center text-amber-500 font-display font-extrabold text-2xl shadow-inner">
                {averageRating}
              </div>
              <div>
                <h4 className="font-bold text-sm text-white">Community Score</h4>
                <p className="text-xs text-gray-400 mt-0.5">Scale of 1-10 stars</p>
              </div>
            </div>

            {/* Voting Bar */}
            <div className="flex flex-col gap-1.5">
              <h4 className="text-xs font-semibold text-gray-300">
                {userRating ? `Your Rating: ${userRating} Stars` : "Rate this anime"}
              </h4>
              <div className="flex items-center gap-1">
                {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((star) => (
                  <button
                    key={star}
                    onClick={() => handleRatingSubmit(star)}
                    className="p-1 text-gray-500 hover:text-amber-400 hover:scale-110 active:scale-95 transition-all"
                    title={`Rate ${star} Stars`}
                  >
                    <Star
                      className={`w-4 h-4 ${
                        (userRating && userRating >= star) || (!userRating && star <= averageRating)
                          ? "text-amber-500 fill-current"
                          : "text-neutral-700"
                      }`}
                    />
                  </button>
                ))}
              </div>
            </div>
          </div>

          {/* 4. Episode Grid Playlist */}
          <div className="flex flex-col gap-4 mt-8">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-2 border-b border-white/5">
              <h3 className="font-display font-extrabold text-xl text-white tracking-tight flex items-center gap-2">
                Episode List
                <span className="w-1 h-5 brand-gradient rounded-full" />
              </h3>

              {/* Season Selector Tabs */}
              {sortedSeasonNumbers.length > 1 && (
                <div className="flex items-center gap-2 overflow-x-auto no-scrollbar py-1">
                  {sortedSeasonNumbers.map((sNum) => {
                    const isSelected = selectedSeasonNumber === sNum;
                    const seasonEpsCount = seasonsMap.get(sNum)?.length || 0;
                    return (
                      <button
                        key={sNum}
                        onClick={() => setSelectedSeasonNumber(sNum)}
                        className={`px-3.5 py-1.5 rounded-xl text-xs font-bold font-mono transition-all flex items-center gap-1.5 shrink-0 cursor-pointer ${
                          isSelected
                            ? "bg-brand-primary text-white shadow-lg shadow-brand-glow"
                            : "bg-[#131520] text-gray-400 hover:text-white border border-white/5 hover:border-white/10"
                        }`}
                      >
                        <Film className="w-3.5 h-3.5" />
                        <span>Season {sNum}</span>
                        <span className={`px-1.5 py-0.2 rounded-full text-[10px] ${isSelected ? "bg-black/30 text-white" : "bg-white/10 text-gray-400"}`}>
                          {seasonEpsCount}
                        </span>
                      </button>
                    );
                  })}
                </div>
              )}
            </div>

            {isLoadingEpisodes ? (
              <div className="flex items-center gap-3.5 py-6">
                <Clock className="w-5 h-5 text-brand-primary animate-spin" />
                <span className="text-sm text-gray-400">Syncing stream episodes...</span>
              </div>
            ) : activeSeasonEpisodes.length > 0 ? (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {activeSeasonEpisodes.map((ep) => (
                  <div
                    key={ep.id}
                    onClick={() => {
                      if (setWatchParams) {
                        setWatchParams(animeId, ep.episodeNumber, 0);
                      }
                      setView("watch", animeId);
                    }}
                    className="flex gap-4 p-3 rounded-2xl bg-[#131520]/50 border border-white/5 hover:border-brand-primary/25 cursor-pointer hover:bg-[#131520]/80 transition-all group"
                  >
                    {/* Tiny thumbnail placeholder */}
                    <div className="relative w-28 aspect-video rounded-xl overflow-hidden bg-neutral-900 flex-shrink-0">
                      <img
                        src={ep.thumbnail || ep.thumbnailUrl || anime.banner}
                        alt={ep.title}
                        className="w-full h-full object-cover group-hover:scale-105 transition-transform"
                      />
                      <div className="absolute inset-0 bg-black/40 flex items-center justify-center">
                        <Play className="w-4 h-4 fill-current text-white scale-90 group-hover:scale-110 transition-transform" />
                      </div>
                    </div>

                    <div className="flex flex-col justify-between truncate">
                      <div>
                        <h4 className="font-semibold text-xs text-brand-primary font-mono uppercase tracking-wider">
                          Season {ep.seasonNumber || selectedSeasonNumber} • EP {ep.episodeNumber}
                        </h4>
                        <h5 className="font-bold text-sm text-white group-hover:text-brand-primary transition-colors truncate mt-0.5">
                          {ep.title}
                        </h5>
                      </div>
                      <p className="text-[11px] text-gray-400 line-clamp-1 mt-1">
                        {ep.description}
                      </p>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="p-8 rounded-2xl border border-white/5 bg-[#131520]/30 text-center">
                <Clock className="w-8 h-8 text-neutral-600 mx-auto mb-2.5" />
                <h4 className="font-bold text-sm text-white">No Episodes Found for Season {selectedSeasonNumber}</h4>
                <p className="text-xs text-gray-400 mt-1">
                  Select another season tab above or upload episodes in the Admin Panel.
                </p>
              </div>
            )}
          </div>

          {/* 5. Real-Time Comment Section */}
          <div className="flex flex-col gap-4 mt-8">
            <h3 className="font-display font-extrabold text-xl text-white tracking-tight flex items-center gap-2">
              Discussions
              <span className="w-1 h-5 brand-gradient rounded-full" />
            </h3>

            {/* Comment Post Form */}
            <form onSubmit={handleAddComment} className="flex gap-3">
              <input
                type="text"
                placeholder={user ? "Share your thoughts on this show..." : "Sign in to post comments..."}
                disabled={!user}
                value={newComment}
                onChange={(e) => setNewComment(e.target.value)}
                className="flex-1 px-4 py-3 bg-[#131520] border border-white/5 focus:border-brand-primary/35 rounded-xl text-sm text-white focus:outline-none focus:bg-[#131520]/80 transition-all"
              />
              <button
                type="submit"
                disabled={!user || !newComment.trim()}
                className="p-3 rounded-xl bg-brand-primary hover:bg-brand-primary/95 text-white hover:shadow-lg hover:shadow-brand-glow transition-all transform active:scale-95 disabled:opacity-40 disabled:pointer-events-none"
              >
                <Send className="w-4 h-4" />
              </button>
            </form>

            {/* Comments List */}
            <div className="flex flex-col gap-3 max-h-[350px] overflow-y-auto no-scrollbar pr-1 mt-2">
              {comments.length > 0 ? (
                comments.map((comment) => (
                  <div
                    key={comment.id}
                    className="flex gap-3.5 p-4 rounded-xl bg-[#131520]/30 border border-white/5"
                  >
                    <img
                      src={comment.userPhoto || `https://api.dicebear.com/7.x/adventurer/svg?seed=${comment.userName}`}
                      alt={comment.userName}
                      className="w-9 h-9 rounded-full border border-white/5 bg-neutral-800 object-cover"
                    />
                    <div className="flex-1 flex flex-col gap-0.5">
                      <div className="flex items-center justify-between">
                        <span className="font-bold text-xs text-white">{comment.userName}</span>
                        <span className="text-[9px] font-mono text-gray-500">
                          {comment.createdAt ? new Date(comment.createdAt.toDate ? comment.createdAt.toDate() : comment.createdAt).toLocaleDateString() : "Just now"}
                        </span>
                      </div>
                      <p className="text-xs text-gray-300 leading-relaxed mt-1">{comment.text}</p>
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

      {/* 6. Recommended Rows */}
      {recommendations.length > 0 && (
        <div className="mt-14">
          <HorizontalScrollSection
            title="More Like This"
            items={recommendations}
            renderItem={(anime) => (
              <AnimeCard
                key={anime.id}
                anime={anime}
                onClick={() => setView("anime-details", anime.id)}
              />
            )}
          />
        </div>
      )}
    </div>
  );
};
