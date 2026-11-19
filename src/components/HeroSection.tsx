import React, { useState } from "react";
import { Play, Plus, Check, X } from "lucide-react";
import { Anime } from "../types";
import { useApp } from "../context/AppContext";
import { getDisplayTitle } from "../utils/animeUtils";

interface HeroSectionProps {
  anime: Anime;
  setView: (view: string, detailsId?: string) => void;
}

export const HeroSection: React.FC<HeroSectionProps> = ({ anime, setView }) => {
  const { watchlist, toggleWatchlist, user } = useApp();
  const [showTrailer, setShowTrailer] = useState(false);
  const inWatchlist = watchlist.includes(anime.id);
  const displayTitle = getDisplayTitle(anime);

  const handleWatchlistClick = () => {
    if (!user || user.uid === "guest-user-id") {
      setView("auth");
    } else {
      toggleWatchlist(anime.id);
    }
  };

  return (
    <div className="relative w-full h-[70vh] md:h-[80vh] rounded-3xl overflow-hidden mb-10 group shadow-2xl">
      <div className="absolute inset-0 select-none">
        <img
          src={anime.banner || anime.poster}
          alt={displayTitle}
          className="w-full h-full object-cover object-center scale-100 group-hover:scale-[1.02] transition-transform duration-1000 ease-out"
          referrerPolicy="no-referrer"
        />
        <div className="absolute inset-0 bg-gradient-to-t from-[#0b0c10] via-[#0b0c10]/45 to-transparent" />
        <div className="absolute inset-0 bg-gradient-to-r from-[#0b0c10]/90 via-[#0b0c10]/30 to-transparent" />
      </div>

      <div className="absolute inset-x-0 bottom-0 p-6 md:p-12 flex flex-col justify-end gap-4 max-w-3xl z-10">
        <div className="flex flex-wrap items-center gap-3">
          <span className="px-3 py-1 rounded-full text-[10px] font-mono tracking-widest uppercase font-extrabold brand-gradient text-white shadow-lg shadow-brand-glow">
            {anime.type}
          </span>
          <span className="px-3 py-1 rounded-full text-[11px] font-semibold bg-white/10 text-white backdrop-blur-md border border-white/5">
            {anime.releaseYear}
          </span>
          <span className="px-3 py-1 rounded-full text-[11px] font-semibold bg-emerald-500/10 text-emerald-400 backdrop-blur-md border border-emerald-500/10">
            {anime.status}
          </span>
          {anime.rating && anime.rating !== "NR" && (
            <span className="text-amber-400 text-sm font-semibold flex items-center gap-1">
              ★ {typeof anime.rating === "string" ? anime.rating.split(" ")[0] : String(anime.rating)}
            </span>
          )}
        </div>

        <h1 className="font-display font-extrabold text-3xl md:text-5xl lg:text-6xl text-white leading-tight tracking-tight drop-shadow-xl">
          {displayTitle}
        </h1>

        <p className="text-gray-300 text-sm md:text-base leading-relaxed line-clamp-3 drop-shadow-md">
          {anime.description}
        </p>

        <div className="flex flex-wrap items-center gap-4 mt-4">
          <button
            onClick={() => setView("anime-details", anime.id)}
            className="flex items-center gap-2.5 px-7 py-3.5 rounded-xl font-bold text-sm bg-white text-black hover:bg-brand-primary hover:text-white transition-all duration-300 transform active:scale-95 shadow-xl cursor-pointer"
          >
            <Play className="w-4 h-4 fill-current" />
            Watch Now
          </button>

          {anime.trailerUrl && (
            <button
              onClick={() => setShowTrailer(true)}
              className="flex items-center gap-2.5 px-6 py-3.5 rounded-xl font-semibold text-sm bg-white/10 text-white hover:bg-white/15 border border-white/10 backdrop-blur-md transition-all active:scale-95 cursor-pointer"
            >
              Trailer
            </button>
          )}

          <button
            onClick={handleWatchlistClick}
            className={`p-3.5 rounded-xl border border-white/10 backdrop-blur-md transition-all active:scale-95 cursor-pointer ${
              inWatchlist
                ? "bg-brand-primary/20 text-brand-primary border-brand-primary/20"
                : "bg-white/10 text-white hover:bg-white/15"
            }`}
            title={inWatchlist ? "Remove from Watchlist" : "Add to Watchlist"}
          >
            {inWatchlist ? (
              <Check className="w-5 h-5" />
            ) : (
              <Plus className="w-5 h-5" />
            )}
          </button>
        </div>
      </div>

      {showTrailer && anime.trailerUrl && (
        <div className="fixed inset-0 bg-black/90 backdrop-blur-md flex items-center justify-center z-50 p-4">
          <div className="relative w-full max-w-4xl aspect-video rounded-2xl overflow-hidden bg-neutral-900 border border-white/10 shadow-2xl">
            <button
              onClick={() => setShowTrailer(false)}
              className="absolute top-4 right-4 p-2.5 bg-black/60 hover:bg-red-500/20 hover:text-red-500 rounded-full text-white z-10 transition-all border border-white/5 cursor-pointer"
            >
              <X className="w-5 h-5" />
            </button>
            <iframe
              src={anime.trailerUrl}
              title={`${displayTitle} Trailer`}
              className="w-full h-full border-0"
              allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
              allowFullScreen
            />
          </div>
        </div>
      )}
    </div>
  );
};
