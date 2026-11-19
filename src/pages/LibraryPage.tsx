import React, { useState, useEffect } from "react";
import { useApp } from "../context/AppContext";
import { AnimeCard } from "../components/AnimeCard";
import { fetchMovieBoxDetails } from "../services/movieboxApiService";
import { Library, List, Star, Loader } from "lucide-react";

interface LibraryPageProps {
  setView: (view: string, detailsId?: string) => void;
}

export const LibraryPage: React.FC<LibraryPageProps> = ({ setView }) => {
  const { animeList, watchlist, favorites, upsertCatalogItems } = useApp();
  const [activeSubTab, setActiveSubTab] = useState<"watchlist" | "favorites">("watchlist");
  const [hydratingIds, setHydratingIds] = useState(false);

  // Lazy-fetch details for saved IDs that aren't currently in the homepage slice
  useEffect(() => {
    const knownIds = new Set(animeList.map((a) => a.id));
    const missingIds = Array.from(new Set([...watchlist, ...favorites])).filter(
      (id) => !knownIds.has(id)
    );
    if (missingIds.length === 0) return;

    let cancelled = false;
    setHydratingIds(true);
    Promise.allSettled(missingIds.slice(0, 12).map((id) => fetchMovieBoxDetails(id)))
      .then((results) => {
        if (cancelled) return;
        const resolved = results
          .filter(
            (r): r is PromiseFulfilledResult<{ anime: any; episodes: any[] }> =>
              r.status === "fulfilled"
          )
          .map((r) => r.value.anime);
        if (resolved.length > 0) {
          upsertCatalogItems(resolved);
        }
      })
      .finally(() => {
        if (!cancelled) setHydratingIds(false);
      });

    return () => {
      cancelled = true;
    };
  }, [watchlist, favorites, animeList, upsertCatalogItems]);

  const watchlistAnime = animeList.filter((a) => watchlist.includes(a.id));
  const favoritesAnime = animeList.filter((a) => favorites.includes(a.id));

  return (
    <div className="w-full min-h-screen pb-20 px-1">
      <div className="mb-8 mt-4 flex items-center justify-between gap-4">
        <div>
          <h1 className="font-display font-extrabold text-3xl text-white tracking-tight flex items-center gap-2.5">
            <Library className="w-8 h-8 text-brand-primary" />
            My Library
          </h1>
          <p className="text-gray-400 text-sm mt-1">
            Your personal watchlist syncs and favorited titles.
          </p>
        </div>
        {hydratingIds && (
          <div className="flex items-center gap-2 text-xs text-gray-400 font-mono">
            <Loader className="w-4 h-4 animate-spin text-brand-primary" />
            <span>Syncing saved titles...</span>
          </div>
        )}
      </div>

      <div className="flex gap-4 mb-8">
        <button
          onClick={() => setActiveSubTab("watchlist")}
          className={`flex items-center gap-2 px-5 py-2.5 rounded-xl font-bold text-xs uppercase tracking-wider border transition-all cursor-pointer ${
            activeSubTab === "watchlist"
              ? "bg-brand-primary/10 text-brand-primary border-brand-primary/20 shadow-md"
              : "bg-white/5 text-gray-400 border-white/5 hover:text-white"
          }`}
        >
          <List className="w-4 h-4" />
          Watchlist ({watchlist.length})
        </button>

        <button
          onClick={() => setActiveSubTab("favorites")}
          className={`flex items-center gap-2 px-5 py-2.5 rounded-xl font-bold text-xs uppercase tracking-wider border transition-all cursor-pointer ${
            activeSubTab === "favorites"
              ? "bg-brand-primary/10 text-brand-primary border-brand-primary/20 shadow-md"
              : "bg-white/5 text-gray-400 border-white/5 hover:text-white"
          }`}
        >
          <Star className="w-4 h-4" />
          Favorites ({favorites.length})
        </button>
      </div>

      {activeSubTab === "watchlist" ? (
        watchlistAnime.length > 0 ? (
          <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-6">
            {watchlistAnime.map((anime) => (
              <div key={anime.id} className="flex justify-center">
                <AnimeCard
                  anime={anime}
                  onClick={() => setView("anime-details", anime.id)}
                />
              </div>
            ))}
          </div>
        ) : (
          <div className="text-center py-20 text-gray-500 font-mono text-sm border border-white/5 bg-[#131520]/30 rounded-2xl">
            <List className="w-10 h-10 text-neutral-700 mx-auto mb-2" />
            Your Watchlist is empty. Search titles and add them to build your queue!
          </div>
        )
      ) : favoritesAnime.length > 0 ? (
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-6">
          {favoritesAnime.map((anime) => (
            <div key={anime.id} className="flex justify-center">
              <AnimeCard
                anime={anime}
                onClick={() => setView("anime-details", anime.id)}
              />
            </div>
          ))}
        </div>
      ) : (
        <div className="text-center py-20 text-gray-500 font-mono text-sm border border-white/5 bg-[#131520]/30 rounded-2xl">
          <Star className="w-10 h-10 text-neutral-700 mx-auto mb-2" />
          Your Favorites catalog is empty. Star your favorite titles to keep them handy!
        </div>
      )}
    </div>
  );
};
