import React, { useMemo } from "react";
import { useApp } from "../context/AppContext";
import { HeroSection } from "../components/HeroSection";
import { HorizontalScrollSection } from "../components/HorizontalScrollSection";
import { AnimeCard } from "../components/AnimeCard";
import { Play, Sparkles } from "lucide-react";


interface HomePageProps {
  setView: (view: string, detailsId?: string) => void;
  setWatchParams?: (animeId: string, episodeNumber: number, initialPosition: number) => void;
}

export const HomePage: React.FC<HomePageProps> = ({ setView, setWatchParams }) => {
  const { animeList, watchHistory } = useApp();

  // Filter lists based on type or tag
  const featuredAnime = useMemo(() => {
    return animeList.find(a => a.isFeatured) || animeList[0];
  }, [animeList]);

  const trendingAnime = useMemo(() => {
    return animeList.filter(a => a.isTrending || a.isFeatured);
  }, [animeList]);

  const popularAnime = useMemo(() => {
    return animeList.filter(a => a.releaseYear >= 2020);
  }, [animeList]);

  const moviesList = useMemo(() => {
    return animeList.filter(a => a.type === "Movie");
  }, [animeList]);

  const tvSeriesList = useMemo(() => {
    return animeList.filter(a => a.type === "TV");
  }, [animeList]);

  const ovaOnaList = useMemo(() => {
    return animeList.filter(a => a.type === "OVA" || a.type === "ONA");
  }, [animeList]);

  const recommendedList = useMemo(() => {
    return animeList.filter(a => a.isRecommended);
  }, [animeList]);

  // Combine watchHistory with anime items to display "Continue Watching" wide cards!
  const continueWatchingItems = useMemo(() => {
    if (!watchHistory || watchHistory.length === 0) return [];
    return watchHistory.map(hist => {
      const anime = animeList.find(a => a.id === hist.animeId);
      if (!anime) return null;
      return {
        ...hist,
        anime
      };
    }).filter(Boolean);
  }, [watchHistory, animeList]);

  if (animeList.length === 0) {
    return (
      <div className="w-full min-h-[70vh] flex flex-col items-center justify-center px-6 text-center">
        <div className="max-w-md p-8 rounded-3xl bg-[#131520] border border-white/5 shadow-2xl flex flex-col items-center gap-5">
          <div className="w-16 h-16 rounded-full bg-amber-500/10 flex items-center justify-center text-amber-500">
            <Sparkles className="w-8 h-8 animate-pulse" />
          </div>
          <div className="flex flex-col gap-2">
            <h2 className="font-display font-extrabold text-2xl text-white">No Anime Found</h2>
            <p className="text-gray-400 text-sm leading-relaxed">
              Our library is currently empty. Access the Admin Panel to import titles or publish new streamable episodes!
            </p>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="w-full min-h-screen pb-20">
      {/* Featured Anime Hero Banner */}
      {featuredAnime && (
        <HeroSection anime={featuredAnime} setView={setView} />
      )}

      {/* Main Content Rows */}
      <div className="flex flex-col gap-2">

        {/* Continue Watching Section (Netflix style wide progress cards) */}
        {continueWatchingItems.length > 0 && (
          <HorizontalScrollSection
            title="Continue Watching"
            items={continueWatchingItems}
            renderItem={(item) => {
              const progressPct = Math.min(100, Math.round((item.position / (item.duration || 1)) * 100));
              return (
                <div
                  key={item.id}
                  onClick={() => {
                    if (setWatchParams) {
                      setWatchParams(item.animeId, item.episodeNumber, item.position);
                    }
                    setView("watch", item.animeId);
                  }}
                  className="flex-shrink-0 w-64 md:w-72 group cursor-pointer relative rounded-2xl overflow-hidden bg-[#131520] border border-white/5 hover:border-brand-primary/20 transition-all duration-300 transform hover:-translate-y-1"
                >
                  {/* Thumbnail Banner aspect 16:9 */}
                  <div className="relative aspect-video overflow-hidden bg-neutral-900">
                    <img
                      src={item.anime.banner}
                      alt={item.anime.title}
                      className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
                    />
                    <div className="absolute inset-0 bg-gradient-to-t from-black/85 via-black/20 to-transparent flex items-center justify-center">
                      {/* Central small Play button */}
                      <div className="p-2.5 rounded-full bg-brand-primary/95 text-white scale-90 group-hover:scale-100 transition-all shadow-lg shadow-brand-glow">
                        <Play className="w-4.5 h-4.5 fill-current" />
                      </div>
                    </div>

                    {/* Progress Bar overlay */}
                    <div className="absolute bottom-0 inset-x-0 h-1 bg-white/20">
                      <div
                        className="h-full brand-gradient"
                        style={{ width: `${progressPct}%` }}
                      />
                    </div>
                  </div>

                  {/* Wide Metadata Text */}
                  <div className="p-3.5 flex flex-col gap-0.5">
                    <h4 className="font-display font-semibold text-sm text-gray-200 truncate group-hover:text-brand-primary transition-colors">
                      {item.anime.title}
                    </h4>
                    <div className="flex items-center justify-between text-xs text-gray-400 font-mono">
                      <span>EP {item.episodeNumber}</span>
                      <span>{progressPct}% Played</span>
                    </div>
                  </div>
                </div>
              );
            }}
          />
        )}

        {/* Standard Horizontal Categories */}
        <HorizontalScrollSection
          title="Trending Now"
          items={trendingAnime}
          renderItem={(anime) => (
            <AnimeCard
              key={anime.id}
              anime={anime}
              onClick={() => setView("anime-details", anime.id)}
            />
          )}
        />

        <HorizontalScrollSection
          title="Popular Releases"
          items={popularAnime}
          renderItem={(anime) => (
            <AnimeCard
              key={anime.id}
              anime={anime}
              onClick={() => setView("anime-details", anime.id)}
            />
          )}
        />

        <HorizontalScrollSection
          title="Recommended For You"
          items={recommendedList}
          renderItem={(anime) => (
            <AnimeCard
              key={anime.id}
              anime={anime}
              onClick={() => setView("anime-details", anime.id)}
            />
          )}
        />

        <HorizontalScrollSection
          title="TV Series"
          items={tvSeriesList}
          renderItem={(anime) => (
            <AnimeCard
              key={anime.id}
              anime={anime}
              onClick={() => setView("anime-details", anime.id)}
            />
          )}
        />

        {moviesList.length > 0 && (
          <HorizontalScrollSection
            title="Anime Movies"
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

        <HorizontalScrollSection
          title="OVA & ONA Specials"
          items={ovaOnaList}
          renderItem={(anime) => (
            <AnimeCard
              key={anime.id}
              anime={anime}
              onClick={() => setView("anime-details", anime.id)}
            />
          )}
        />
      </div>
    </div>
  );
};
