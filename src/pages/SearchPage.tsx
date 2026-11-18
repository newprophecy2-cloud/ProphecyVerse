import React, { useState, useMemo } from "react";
import { useApp } from "../context/AppContext";
import { AnimeCard } from "../components/AnimeCard";
import { Search, Filter, SlidersHorizontal, RefreshCw, Star } from "lucide-react";
import { getDisplayTitle, smartSearchAnime } from "../utils/animeUtils";


interface SearchPageProps {
  setView: (view: string, detailsId?: string) => void;
}

export const SearchPage: React.FC<SearchPageProps> = ({ setView }) => {
  const { animeList } = useApp();

  const [searchQuery, setSearchQuery] = useState("");
  const [selectedGenre, setSelectedGenre] = useState("");
  const [selectedType, setSelectedType] = useState("");
  const [selectedStatus, setSelectedStatus] = useState("");
  const [selectedYear, setSelectedYear] = useState("");
  const [showFilters, setShowFilters] = useState(false);

  // Extract all unique genres from list
  const allGenres = useMemo(() => {
    const genresSet = new Set<string>();
    animeList.forEach((a) => {
      a.genres?.forEach((g) => genresSet.add(g));
    });
    return Array.from(genresSet);
  }, [animeList]);

  // Extract unique release years
  const allYears = useMemo(() => {
    const yearsSet = new Set<number>();
    animeList.forEach((a) => {
      if (a.releaseYear) yearsSet.add(a.releaseYear);
    });
    return Array.from(yearsSet).sort((a, b) => b - a);
  }, [animeList]);

  // Handle live suggestions
  const suggestions = useMemo(() => {
    if (!searchQuery || !searchQuery.trim()) return [];
    return smartSearchAnime(searchQuery, animeList).slice(0, 5);
  }, [searchQuery, animeList]);

  // Full filtered results list
  const filteredAnime = useMemo(() => {
    const searchMatched = searchQuery ? smartSearchAnime(searchQuery, animeList) : animeList;
    return searchMatched.filter((anime) => {
      const matchesGenre = !selectedGenre || anime.genres?.includes(selectedGenre);
      const matchesType = !selectedType || anime.type === selectedType;
      const matchesStatus = !selectedStatus || anime.status === selectedStatus;
      const matchesYear = !selectedYear || anime.releaseYear.toString() === selectedYear;

      return matchesGenre && matchesType && matchesStatus && matchesYear;
    });
  }, [searchQuery, selectedGenre, selectedType, selectedStatus, selectedYear, animeList]);

  const resetFilters = () => {
    setSearchQuery("");
    setSelectedGenre("");
    setSelectedType("");
    setSelectedStatus("");
    setSelectedYear("");
  };

  return (
    <div className="w-full min-h-screen pb-20 px-1">
      {/* Search Header Banner */}
      <div className="flex flex-col gap-6 mb-8 mt-4">
        <div>
          <h1 className="font-display font-extrabold text-3xl text-white tracking-tight">
            Discover Anime
          </h1>
          <p className="text-gray-400 text-sm mt-1">
            Search titles, genres, studios, status, release years, and formats
          </p>
        </div>

        {/* Input Bar & Controls */}
        <div className="flex flex-col md:flex-row gap-4 items-stretch relative">
          <div className="flex-1 relative">
            <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-500" />
            <input
              type="text"
              placeholder="Search by Title, English/Japanese Title, Studio..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full pl-12 pr-4 py-3.5 bg-[#13141c] border border-white/5 focus:border-brand-primary/45 rounded-2xl text-sm text-white focus:outline-none focus:bg-[#13141c]/80 transition-all shadow-inner"
            />

            {/* Live Suggestion Dropdown Panel */}
            {suggestions.length > 0 && (
              <div className="absolute left-0 right-0 top-full mt-2 bg-[#0d0e12]/95 border border-white/10 rounded-2xl overflow-hidden z-30 shadow-2xl backdrop-blur-xl">
                <div className="px-4 py-2 bg-white/5 text-[10px] uppercase font-mono font-bold text-gray-400 tracking-wider">
                  Suggestions
                </div>
                {suggestions.map((anime) => (
                  <div
                    key={anime.id}
                    onClick={() => setView("anime-details", anime.id)}
                    className="flex items-center gap-3 px-4 py-3 hover:bg-brand-primary/10 cursor-pointer border-b border-white/5 last:border-b-0 transition-colors"
                  >
                    <img
                      src={anime.poster}
                      alt={getDisplayTitle(anime)}
                      className="w-8 h-12 rounded object-cover"
                    />
                    <div>
                      <div className="text-sm font-semibold text-white truncate max-w-[280px]">
                        {getDisplayTitle(anime)}
                      </div>
                      <div className="text-xs text-gray-400 flex items-center gap-2">
                        <span>{anime.type}</span>
                        <span>•</span>
                        <span>{anime.releaseYear}</span>
                        <span>•</span>
                        <span className="text-amber-400 flex items-center">
                          ★ {anime.rating && typeof anime.rating === "string" ? anime.rating.split(" ")[0] : (anime.rating ? String(anime.rating) : "PG-13")}
                        </span>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Toggle Advanced Filters Button */}
          <div className="flex items-center gap-3.5">
            <button
              onClick={() => setShowFilters(!showFilters)}
              className={`flex items-center gap-2 px-5 py-3.5 rounded-2xl text-sm font-semibold border transition-all ${
                showFilters || selectedGenre || selectedType || selectedStatus || selectedYear
                  ? "bg-brand-primary/10 text-brand-primary border-brand-primary/20"
                  : "bg-[#13141c] text-white border-white/5 hover:bg-[#13141c]/75"
              }`}
            >
              <Filter className="w-4 h-4" />
              Filters
              {(selectedGenre || selectedType || selectedStatus || selectedYear) && (
                <span className="w-2 h-2 rounded-full bg-brand-primary animate-pulse" />
              )}
            </button>

            {(selectedGenre || selectedType || selectedStatus || selectedYear || searchQuery) && (
              <button
                onClick={resetFilters}
                className="p-3.5 rounded-2xl bg-neutral-900 border border-white/5 text-gray-400 hover:text-white transition-colors"
                title="Reset All Filters"
              >
                <SlidersHorizontal className="w-4.5 h-4.5" />
              </button>
            )}
          </div>
        </div>

        {/* Filter Selection Panel */}
        {showFilters && (
          <div className="p-6 rounded-2xl bg-[#13141c]/50 border border-white/5 flex flex-wrap gap-5 z-10 transition-all animate-fade-in">
            {/* Genre Filter */}
            <div className="flex flex-col gap-2 min-w-[140px]">
              <label className="text-[10px] text-gray-500 font-mono uppercase tracking-wider font-bold">
                Genre
              </label>
              <select
                value={selectedGenre}
                onChange={(e) => setSelectedGenre(e.target.value)}
                className="w-full px-3.5 py-2.5 bg-[#13141c] border border-white/10 rounded-xl text-xs text-gray-300 focus:outline-none focus:border-brand-primary/45 cursor-pointer"
              >
                <option value="">All Genres</option>
                {allGenres.map((g) => (
                  <option key={g} value={g}>
                    {g}
                  </option>
                ))}
              </select>
            </div>

            {/* Type Filter */}
            <div className="flex flex-col gap-2 min-w-[120px]">
              <label className="text-[10px] text-gray-500 font-mono uppercase tracking-wider font-bold">
                Format / Type
              </label>
              <select
                value={selectedType}
                onChange={(e) => setSelectedType(e.target.value)}
                className="w-full px-3.5 py-2.5 bg-[#13141c] border border-white/10 rounded-xl text-xs text-gray-300 focus:outline-none focus:border-brand-primary/45 cursor-pointer"
              >
                <option value="">All Formats</option>
                <option value="TV">TV Show</option>
                <option value="Movie">Movie</option>
                <option value="OVA">OVA</option>
                <option value="ONA">ONA</option>
              </select>
            </div>

            {/* Status Filter */}
            <div className="flex flex-col gap-2 min-w-[120px]">
              <label className="text-[10px] text-gray-500 font-mono uppercase tracking-wider font-bold">
                Status
              </label>
              <select
                value={selectedStatus}
                onChange={(e) => setSelectedStatus(e.target.value)}
                className="w-full px-3.5 py-2.5 bg-[#13141c] border border-white/10 rounded-xl text-xs text-gray-300 focus:outline-none focus:border-brand-primary/45 cursor-pointer"
              >
                <option value="">All Statuses</option>
                <option value="Ongoing">Ongoing</option>
                <option value="Completed">Completed</option>
              </select>
            </div>

            {/* Year Filter */}
            <div className="flex flex-col gap-2 min-w-[120px]">
              <label className="text-[10px] text-gray-500 font-mono uppercase tracking-wider font-bold">
                Release Year
              </label>
              <select
                value={selectedYear}
                onChange={(e) => setSelectedYear(e.target.value)}
                className="w-full px-3.5 py-2.5 bg-[#13141c] border border-white/10 rounded-xl text-xs text-gray-300 focus:outline-none focus:border-brand-primary/45 cursor-pointer"
              >
                <option value="">All Years</option>
                {allYears.map((yr) => (
                  <option key={yr} value={yr.toString()}>
                    {yr}
                  </option>
                ))}
              </select>
            </div>
          </div>
        )}
      </div>

      {/* Grid Results */}
      {filteredAnime.length > 0 ? (
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-6">
          {filteredAnime.map((anime) => (
            <div key={anime.id} className="flex justify-center">
              <AnimeCard anime={anime} onClick={() => setView("anime-details", anime.id)} />
            </div>
          ))}
        </div>
      ) : (
        <div className="flex flex-col items-center justify-center py-24 text-center">
          <SlidersHorizontal className="w-12 h-12 text-gray-600 animate-pulse mb-4" />
          <h3 className="font-display font-extrabold text-lg text-white">No Results Found</h3>
          <p className="text-gray-400 text-sm mt-1 max-w-md">
            Try adjusting your spelling or removing some active filters to expand your search.
          </p>
          <button
            onClick={resetFilters}
            className="mt-6 px-5 py-2.5 rounded-xl bg-white/5 border border-white/10 text-white font-semibold text-xs uppercase tracking-widest hover:bg-brand-primary hover:border-brand-primary hover:text-white transition-colors"
          >
            Clear Filters
          </button>
        </div>
      )}
    </div>
  );
};
