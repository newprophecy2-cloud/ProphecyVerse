import React, { useMemo } from "react";
import { useApp } from "../context/AppContext";
import { HeroSection } from "../components/HeroSection";
import { HorizontalScrollSection } from "../components/HorizontalScrollSection";
import { AnimeCard } from "../components/AnimeCard";
import { Play, Sparkles, AlertTriangle, RefreshCw, Search } from "lucide-react";

interface HomePageProps {
  setView: (view: string, detailsId?: string) => void;
  setWatchParams?: (
    animeId: string,
    episodeNumber: number,
    initialPosition: number,
    seasonNumber?: number
  ) => void;
}

export const HomePage: React.FC<HomePageProps> = ({ setView, setWatchParams }) => {
  const {
    animeList,
    watchHistory,
    catalogLoading,
    catalogError,
    refreshAnime,
  } = useApp();

  const featuredAnime = useMemo(() => {
    return animeList.find((a) => a.isFeatured) || animeList[0];
  }, [animeList]);

  const trendingAnime = useMemo(() => {
    return animeList.slice(0, 14);
  }, [animeList]);

  const seriesList = useMemo(() => {
    return animeList.filter((a) => a.type === "TV");
  }, [animeList]);

  const moviesList = useMemo(() => {
    return animeList.filter((a) => a.type === "Movie");
  }, [animeList]);

  const exploreMoreList = useMemo(() => {
    return animeList.slice(6);
  }, [animeList]);

  const continueWatchingItems = useMemo(() => {
    if (!watchHistory || watchHistory.length === 0) return [];
    return watchHistory.map((hist) => {
      const matched = animeList.find((a) => a.id === hist.animeId);
      return {
        ...hist,
        title: matched?.title || hist.title || `Title #${hist.animeId}`,
        banner:
          matched?.banner ||
          hist.banner ||
          matched?.poster ||
          hist.poster ||
          "https://images.unsplash.com/photo-1607604276583-eef5d076aa5f?q=80&w=1600",
      };
    });
  }, [watchHistory, animeList]);

  if (catalogLoading && animeList.length === 0) {
    return (
      <div className="w-full min-h-[70vh] flex flex-col items-center justify-center px-6 text-center">
        <div className="max-w-md p-8 rounded-3xl bg-[#131520] border border-white/5 shadow-2xl flex flex-col items-center gap-4">
          <RefreshCw className="w-8 h-8 text-brand-primary animate-spin" />
          <h2 className="font-display font-extrabold text-xl text-white">
            Loading MovieBox Catalog
          </h2>
          <p className="text-gray-400 text-xs">
            Connecting to the ProphecyVerse MovieBox Provider backend...
          </p>
        </div>
      </div>
    );
  }

  if (animeList.length === 0) {
    return (
      <div className="w-full min-h-[70vh] flex flex-col items-center justify-center px-6 text-center">
        <div className="max-w-lg p-8 rounded-3xl bg-[#131520] border border-white/10 shadow-2xl flex flex-col items-center gap-5">
          <div className="w-16 h-16 rounded-full bg-amber-500/10 flex items-center justify-center text-amber-400">
            {catalogError ? (
              <AlertTriangle className="w-8 h-8" />
            ) : (
              <Sparkles className="w-8 h-8 animate-pulse" />
            )}
          </div>
          <div className="flex flex-col gap-2">
            <h2 className="font-display font-extrabold text-2xl text-white">
              {catalogError
                ? catalogError.code === "PROVIDER_AUTH_REQUIRED"
                  ? "MovieBox Credentials Required"
                  : "Unable to Reach MovieBox Catalog"
                : "No Catalog Items Returned"}
            </h2>
            <p className="text-gray-400 text-sm leading-relaxed">
              {catalogError?.message ||
                "The MovieBox Provider did not return any homepage items right now."}
            </p>
            {catalogError?.code && (
              <span className="text-[11px] font-mono text-amber-400 uppercase tracking-wider">
                Code: {catalogError.code}
              </span>
            )}
          </div>
          <div className="flex flex-wrap items-center justify-center gap-3">
            <button
              onClick={() => refreshAnime()}
              className="flex items-center gap-2 px-5 py-2.5 rounded-xl bg-brand-primary text-white font-bold text-xs uppercase tracking-wider hover:opacity-90 transition-all cursor-pointer"
            >
              <RefreshCw className="w-4 h-4" />
              Retry Connection
            </button>
            <button
              onClick={() => setView("search")}
              className="flex items-center gap-2 px-5 py-2.5 rounded-xl bg-white/5 border border-white/10 text-gray-200 font-bold text-xs uppercase tracking-wider hover:bg-white/10 transition-all cursor-pointer"
            >
              <Search className="w-4 h-4" />
              Open Search
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="w-full min-h-screen pb-20">
      {featuredAnime && <HeroSection anime={featuredAnime} setView={setView} />}

      <div className="flex flex-col gap-2">
        {continueWatchingItems.length > 0 && (
          <HorizontalScrollSection
            title="Continue Watching"
            items={continueWatchingItems}
            renderItem={(item) => {
              const progressPct = Math.min(
                100,
                Math.round((item.position / (item.duration || 1)) * 100)
              );
              return (
                <div
                  key={item.id}
                  onClick={() => {
                    if (setWatchParams) {
                      setWatchParams(
                        item.animeId,
                        item.episodeNumber,
                        item.position,
                        item.seasonNumber || 1
                      );
                    }
                    setView("watch", item.animeId);
                  }}
                  className="flex-shrink-0 w-64 md:w-72 group cursor-pointer relative rounded-2xl overflow-hidden bg-[#131520] border border-white/5 hover:border-brand-primary/20 transition-all duration-300 transform hover:-translate-y-1"
                >
                  <div className="relative aspect-video overflow-hidden bg-neutral-900">
                    <img
                      src={item.banner}
                      alt={item.title}
                      className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
                      referrerPolicy="no-referrer"
                    />
                    <div className="absolute inset-0 bg-gradient-to-t from-black/85 via-black/20 to-transparent flex items-center justify-center">
                      <div className="p-2.5 rounded-full bg-brand-primary/95 text-white scale-90 group-hover:scale-100 transition-all shadow-lg shadow-brand-glow">
                        <Play className="w-4.5 h-4.5 fill-current" />
                      </div>
                    </div>

                    <div className="absolute bottom-0 inset-x-0 h-1 bg-white/20">
                      <div
                        className="h-full brand-gradient"
                        style={{ width: `${progressPct}%` }}
                      />
                    </div>
                  </div>

                  <div className="p-3.5 flex flex-col gap-0.5">
                    <h4 className="font-display font-semibold text-sm text-gray-200 truncate group-hover:text-brand-primary transition-colors">
                      {item.title}
                    </h4>
                    <div className="flex items-center justify-between text-xs text-gray-400 font-mono">
                      <span>
                        {item.seasonNumber ? `S${item.seasonNumber} ` : ""}EP{" "}
                        {item.episodeNumber}
                      </span>
                      <span>{progressPct}% Played</span>
                    </div>
                  </div>
                </div>
              );
            }}
          />
        )}

        <HorizontalScrollSection
          title="Trending on MovieBox"
          items={trendingAnime}
          renderItem={(anime) => (
            <AnimeCard
              key={anime.id}
              anime={anime}
              onClick={() => setView("anime-details", anime.id)}
            />
          )}
        />

        {seriesList.length > 0 && (
          <HorizontalScrollSection
            title="TV Series & Anime"
            items={seriesList}
            renderItem={(anime) => (
              <AnimeCard
                key={anime.id}
                anime={anime}
                onClick={() => setView("anime-details", anime.id)}
              />
            )}
          />
        )}

        {moviesList.length > 0 && (
          <HorizontalScrollSection
            title="Movies"
            items={moviesList}
            renderItem={(anime) => (
              <AnimeCard
                key={anime.id}
                anime={anime}
                onClick={() => setView("anime-details", anime.id)}
              />
            )}
          />
        )}

        {exploreMoreList.length > 0 && (
          <HorizontalScrollSection
            title="Explore Catalog"
            items={exploreMoreList}
            renderItem={(anime) => (
              <AnimeCard
                key={anime.id}
                anime={anime}
                onClick={() => setView("anime-details", anime.id)}
              />
            )}
          />
        )}
      </div>
    </div>
  );
};
