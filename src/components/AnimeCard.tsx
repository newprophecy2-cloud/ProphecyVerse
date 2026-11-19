import React from "react";
import { Play, Star, Calendar } from "lucide-react";
import { Anime } from "../types";
import { getDisplayTitle } from "../utils/animeUtils";

interface AnimeCardProps {
  anime: Anime;
  onClick: () => void;
}

export const AnimeCard: React.FC<AnimeCardProps> = ({ anime, onClick }) => {
  const displayTitle = getDisplayTitle(anime);

  return (
    <div
      onClick={onClick}
      className="flex-shrink-0 w-36 sm:w-44 md:w-52 group cursor-pointer flex flex-col gap-2.5 rounded-2xl overflow-hidden transition-all duration-300 transform hover:-translate-y-1.5"
    >
      {/* Thumbnail Aspect Container */}
      <div className="relative aspect-[2/3] rounded-2xl overflow-hidden shadow-lg border border-white/5 group-hover:border-brand-primary/20 transition-colors duration-300 bg-neutral-900">
        <img
          src={anime.poster}
          alt={displayTitle}
          loading="lazy"
          className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500 ease-out"
          referrerPolicy="no-referrer"
        />

        {/* Shimmer / Hover Overlay */}
        <div className="absolute inset-0 bg-gradient-to-t from-black/80 via-black/10 to-transparent opacity-100 group-hover:from-brand-primary/80 transition-all duration-300 flex items-center justify-center">
          {/* Floating Play Indicator */}
          <div className="p-3.5 rounded-full bg-white/15 text-white scale-75 opacity-0 group-hover:scale-100 group-hover:opacity-100 transition-all duration-300 backdrop-blur-md shadow-lg shadow-brand-glow">
            <Play className="w-5 h-5 fill-current text-white" />
          </div>
        </div>

        {/* Floating Top Left Type Badge */}
        <div className="absolute top-2.5 left-2.5 px-2 py-0.5 rounded-md bg-black/60 backdrop-blur-md border border-white/10 text-[9px] font-mono uppercase tracking-widest font-extrabold text-white">
          {anime.type}
        </div>

        {/* Floating Top Right Score Badge */}
        <div className="absolute top-2.5 right-2.5 flex items-center gap-1 px-2 py-0.5 rounded-md bg-amber-500/90 text-[10px] font-bold text-black shadow-md">
          <Star className="w-3 h-3 fill-current" />
          {anime.rating && typeof anime.rating === "string" ? anime.rating.split(" ")[0] : (anime.rating ? String(anime.rating) : "PG-13")}
        </div>

        {/* Quick Episode Tag at bottom */}
        <div className="absolute bottom-2.5 right-2.5 text-[10px] font-mono px-2 py-0.5 rounded bg-black/60 text-gray-300 backdrop-blur-md">
          {anime.episodesCount} EP
        </div>
      </div>

      {/* Meta Text */}
      <div className="px-1 flex flex-col gap-0.5">
        <h4 className="font-display font-semibold text-sm sm:text-base text-gray-200 group-hover:text-brand-primary transition-colors duration-300 truncate">
          {displayTitle}
        </h4>
        <div className="flex items-center justify-between text-[11px] text-gray-400 font-mono">
          <span className="truncate max-w-[100px]">{anime.studio}</span>
          <span>{anime.releaseYear}</span>
        </div>
      </div>
    </div>
  );
};
