import React, { useState, useMemo, useEffect } from "react";
import { useApp } from "../context/AppContext";
import { AnimeCard } from "../components/AnimeCard";
import { Anime } from "../types";
import {
  BackendApiError,
  searchMovieBoxCatalog,
} from "../services/movieboxApiService";
import {
  Search,
  Filter,
  SlidersHorizontal,
  X,
  Loader,
  AlertTriangle,
  ChevronRight,
} from "lucide-react";

interface SearchPageProps {
  setView: (view: string, detailsId?: string) => void;
}

export const SearchPage: React.FC<SearchPageProps> = ({ setView }) => {
  const { animeList, upsertCatalogItems } = useApp();
  const [searchQuery, setSearchQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const [page, setPage] = useState(1);
  const [searchResults, setSearchResults] = useState<Anime[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [isSearching, setIsSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);

  const [selectedGenre, setSelectedGenre] = useState("");
  const [selectedType, setSelectedType] = useState("");
  const [selectedYear, setSelectedYear] = useState("");
  const [showFilters, setShowFilters] = useState(false);
  const [showSuggestions, setShowSuggestions] = useState(false);

  // Debounce search query
  useEffect(() => {
    const timer = setTimeout(() => {
      setPage(1);
      setDebouncedQuery(searchQuery.trim());
    }, 350);
    return () => clearTimeout(timer);
  }, [searchQuery]);

  // Execute server-backed MovieBox search
  useEffect(() => {
    if (!debouncedQuery) {
      setSearchResults([]);
      setHasMore(false);
      setSearchError(null);
      setIsSearching(false);
      return;
    }

    const controller = new AbortController();
    setIsSearching(true);
    setSearchError(null);

    searchMovieBoxCatalog(debouncedQuery, page, controller.signal)
      .then((res) => {
        upsertCatalogItems(res.items);
        setSearchResults((prev) => {
          if (page === 1) return res.items;
          const map = new Map<string, Anime>();
          for (const item of prev) map.set(item.id, item);
          for (const item of res.items) map.set(item.id, item);
          return Array.from(map.values());
        });
        setHasMore(res.hasMore);
      })
      .catch((err) => {
        if (controller.signal.aborted) return;
        if (err instanceof BackendApiError) {
          setSearchError(err.message);
        } else {
          setSearchError(
            err instanceof Error ? err.message : "Failed to search catalog."
          );
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setIsSearching(false);
        }
      });

    return () => {
      controller.abort();
    };
  }, [debouncedQuery, page, upsertCatalogItems]);

  const baseDataset = debouncedQuery ? searchResults : animeList;

  const allGenres = useMemo(() => {
    const set = new Set<string>();
    baseDataset.forEach((a) => (a.genres || []).forEach((g) => set.add(g)));
    return Array.from(set).sort();
  }, [baseDataset]);

  const allYears = useMemo(() => {
    const set = new Set<number>();
    baseDataset.forEach((a) => {
      if (a.releaseYear) set.add(a.releaseYear);
    });
    return Array.from(set).sort((a, b) => b - a);
  }, [baseDataset]);

  const filteredAnime = useMemo(() => {
    return baseDataset.filter((anime) => {
      const matchesGenre =
        !selectedGenre || (anime.genres || []).includes(selectedGenre);
      const matchesType = !selectedType || anime.type === selectedType;
      const matchesYear =
        !selectedYear || String(anime.releaseYear) === selectedYear;
      return matchesGenre && matchesType && matchesYear;
    });
  }, [baseDataset, selectedGenre, selectedType, selectedYear]);

  const resetFilters = () => {
    setSearchQuery("");
    setDebouncedQuery("");
    setPage(1);
    setSelectedGenre("");
    setSelectedType("");
    setSelectedYear("");
    setSearchError(null);
  };

  return (
    <div className="w-full min-h-screen pb-20 px-1">
      <div className="flex flex-col gap-6 mb-10 pt-4">
        <div className="flex flex-col gap-2">
          <h1 className="font-display font-extrabold text-3xl md:text-4xl text-white tracking-tight">
            Search <span className="brand-gradient-text">Prophecy Verse</span>
          </h1>
          <p className="text-gray-400 text-sm">
            Search movies, anime, and TV series across Prophecy Verse.
          </p>
        </div>

        <div className="relative flex flex-col md:flex-row gap-4 z-20">
          <div className="relative flex-1 group">
            <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-500 group-focus-within:text-brand-primary transition-colors" />
            <input
              type="text"
              placeholder="Search titles (e.g., Inception, One Piece, Breaking Bad)..."
              value={searchQuery}
              onFocus={() => setShowSuggestions(true)}
              onBlur={() => setTimeout(() => setShowSuggestions(false), 200)}
              onChange={(e) => {
                setSearchQuery(e.target.value);
                setShowSuggestions(true);
              }}
              className="w-full pl-12 pr-12 py-4 bg-[#13141c] border border-white/5 focus:border-brand-primary/45 rounded-2xl text-sm md:text-base text-white focus:outline-none focus:bg-[#13141c]/90 transition-all shadow-inner"
            />
            {isSearching ? (
              <Loader className="absolute right-4 top-1/2 -translate-y-1/2 w-4.5 h-4.5 text-brand-primary animate-spin" />
            ) : (
              searchQuery && (
                <button
                  onClick={() => setSearchQuery("")}
                  className="absolute right-4 top-1/2 -translate-y-1/2 p-1 rounded-full bg-white/5 hover:bg-white/15 text-gray-400 hover:text-white transition-all cursor-pointer"
                >
                  <X className="w-4 h-4" />
                </button>
              )
            )}

            {showSuggestions &&
              searchQuery.trim().length > 0 &&
              filteredAnime.length > 0 && (
                <div className="absolute left-0 right-0 top-full mt-2 bg-[#131520]/95 backdrop-blur-2xl border border-white/10 rounded-2xl shadow-2xl overflow-hidden z-50">
                  <div className="p-2 text-[10px] font-mono uppercase tracking-wider text-gray-500 border-b border-white/5">
                    Matches ({filteredAnime.length})
                  </div>
                  {filteredAnime.slice(0, 5).map((anime) => (
                    <div
                      key={anime.id}
                      onMouseDown={() => setView("anime-details", anime.id)}
                      className="flex items-center gap-3 p-3 hover:bg-white/5 cursor-pointer transition-colors"
                    >
                      <img
                        src={anime.poster}
                        alt={anime.title}
                        className="w-8 h-12 rounded object-cover"
                        referrerPolicy="no-referrer"
                      />
                      <div>
                        <div className="text-sm font-semibold text-white truncate max-w-[280px]">
                          {anime.title}
                        </div>
                        <div className="text-xs text-gray-400 flex items-center gap-2">
                          <span>{anime.type}</span>
                          <span>•</span>
                          <span>{anime.releaseYear}</span>
                          {anime.rating && anime.rating !== "NR" && (
                            <>
                              <span>•</span>
                              <span className="text-amber-400">★ {anime.rating}</span>
                            </>
                          )}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
          </div>

          <div className="flex items-center gap-3.5">
            <button
              onClick={() => setShowFilters(!showFilters)}
              className={`flex items-center gap-2 px-5 py-3.5 rounded-2xl text-sm font-semibold border transition-all cursor-pointer ${
                showFilters || selectedGenre || selectedType || selectedYear
                  ? "bg-brand-primary/10 text-brand-primary border-brand-primary/20"
                  : "bg-[#13141c] text-white border-white/5 hover:bg-[#13141c]/75"
              }`}
            >
              <Filter className="w-4 h-4" />
              Filters
              {(selectedGenre || selectedType || selectedYear) && (
                <span className="w-2 h-2 rounded-full bg-brand-primary animate-pulse" />
              )}
            </button>

            {(selectedGenre || selectedType || selectedYear || searchQuery) && (
              <button
                onClick={resetFilters}
                className="p-3.5 rounded-2xl bg-neutral-900 border border-white/5 text-gray-400 hover:text-white transition-colors cursor-pointer"
                title="Reset All Filters"
              >
                <SlidersHorizontal className="w-4.5 h-4.5" />
              </button>
            )}
          </div>
        </div>

        {showFilters && (
          <div className="p-6 rounded-2xl bg-[#13141c]/50 border border-white/5 flex flex-wrap gap-5 z-10 transition-all animate-fade-in">
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
                <option value="TV">Series</option>
                <option value="Movie">Movie</option>
              </select>
            </div>

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

      {searchError && (
        <div className="mb-8 p-5 rounded-2xl bg-amber-500/10 border border-amber-500/20 flex items-start gap-3 text-amber-300 text-xs">
          <AlertTriangle className="w-5 h-5 shrink-0 text-amber-400" />
          <div className="flex flex-col gap-1">
            <span className="font-bold text-sm text-white">Search Request Failed</span>
            <span>{searchError}</span>
          </div>
        </div>
      )}

      {filteredAnime.length > 0 ? (
        <div className="flex flex-col gap-8">
          <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-6">
            {filteredAnime.map((anime) => (
              <div key={anime.id} className="flex justify-center">
                <AnimeCard
                  anime={anime}
                  onClick={() => setView("anime-details", anime.id)}
                />
              </div>
            ))}
          </div>

          {debouncedQuery && hasMore && (
            <div className="flex justify-center">
              <button
                onClick={() => setPage((p) => p + 1)}
                disabled={isSearching}
                className="flex items-center gap-2 px-6 py-3 rounded-xl bg-white/5 hover:bg-white/10 border border-white/10 text-xs font-bold uppercase tracking-wider text-white transition-all cursor-pointer disabled:opacity-50"
              >
                {isSearching ? (
                  <>
                    <Loader className="w-4 h-4 animate-spin" />
                    Loading More...
                  </>
                ) : (
                  <>
                    Load Next Page
                    <ChevronRight className="w-4 h-4" />
                  </>
                )}
              </button>
            </div>
          )}
        </div>
      ) : (
        <div className="flex flex-col items-center justify-center py-24 text-center">
          <SlidersHorizontal className="w-12 h-12 text-gray-600 animate-pulse mb-4" />
          <h3 className="font-display font-extrabold text-lg text-white">
            {isSearching
              ? "Searching Catalog..."
              : debouncedQuery
              ? "No Results Found"
              : "Enter a Search Term"}
          </h3>
          <p className="text-gray-400 text-sm mt-1 max-w-md">
            {debouncedQuery
              ? "Try adjusting your query or clearing active filters."
              : "Type a movie, series, or anime title above to search the Prophecy Verse catalog."}
          </p>
          {(selectedGenre || selectedType || selectedYear || searchQuery) && (
            <button
              onClick={resetFilters}
              className="mt-6 px-5 py-2.5 rounded-xl bg-white/5 border border-white/10 text-white font-semibold text-xs uppercase tracking-widest hover:bg-brand-primary hover:border-brand-primary hover:text-white transition-colors cursor-pointer"
            >
              Clear Filters
            </button>
          )}
        </div>
      )}
    </div>
  );
};
