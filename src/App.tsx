import React, { useState } from "react";
import { AppProvider, useApp } from "./context/AppContext";
import { PlayerProvider, usePlayer } from "./context/PlayerContext";
import { Sidebar } from "./components/Sidebar";
import { BottomNav } from "./components/BottomNav";
import { MiniPlayer } from "./components/MiniPlayer";
import { NoticePopupModal } from "./components/NoticePopupModal";
import { ThreeDLoadingScreen } from "./components/ThreeDLoadingScreen";
import { HomePage } from "./pages/HomePage";
import { SearchPage } from "./pages/SearchPage";
import { LibraryPage } from "./pages/LibraryPage";
import { DetailsPage } from "./pages/DetailsPage";
import { WatchPage } from "./pages/WatchPage";
import { ProfilePage } from "./pages/ProfilePage";
import { AuthPage } from "./pages/AuthPage";
import { Film, Search, LogIn } from "lucide-react";

const MainAppContent: React.FC = () => {
  const { profile, isLoading } = useApp();
  const { playerMode, minimizePlayer, activeEpisode } = usePlayer();

  const [initialLoadingComplete, setInitialLoadingComplete] = useState<boolean>(false);
  const [currentView, setCurrentView] = useState<string>("home");
  const [selectedDetailsId, setSelectedDetailsId] = useState<string>("");
  const [watchSeasonNumber, setWatchSeasonNumber] = useState<number>(1);
  const [watchEpisodeNumber, setWatchEpisodeNumber] = useState<number>(1);
  const [watchInitialPosition, setWatchInitialPosition] = useState<number>(0);

  const setWatchParams = (
    animeId: string,
    episodeNumber: number,
    initialPosition: number,
    seasonNumber: number = 1
  ) => {
    setSelectedDetailsId(animeId);
    setWatchSeasonNumber(seasonNumber);
    setWatchEpisodeNumber(episodeNumber);
    setWatchInitialPosition(initialPosition);
  };

  const setView = (view: string, detailsId?: string) => {
    if (
      currentView === "watch" &&
      view !== "watch" &&
      playerMode === "full" &&
      activeEpisode
    ) {
      minimizePlayer();
    }
    setCurrentView(view);
    if (detailsId) {
      setSelectedDetailsId(detailsId);
    }
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  const renderActiveView = () => {
    switch (currentView) {
      case "home":
        return <HomePage setView={setView} setWatchParams={setWatchParams} />;
      case "search":
        return <SearchPage setView={setView} />;
      case "library":
        if (!profile) return <AuthPage />;
        return <LibraryPage setView={setView} />;
      case "anime-details":
        return (
          <DetailsPage
            animeId={selectedDetailsId}
            setView={setView}
            setWatchParams={setWatchParams}
          />
        );
      case "watch":
        return (
          <WatchPage
            animeId={selectedDetailsId}
            setView={setView}
            seasonNumber={watchSeasonNumber}
            episodeNumber={watchEpisodeNumber}
            initialPosition={watchInitialPosition}
          />
        );
      case "profile":
        if (!profile) return <AuthPage />;
        return <ProfilePage setView={setView} />;
      case "auth":
        return <AuthPage />;
      default:
        return <HomePage setView={setView} setWatchParams={setWatchParams} />;
    }
  };

  if (isLoading || !initialLoadingComplete) {
    return (
      <ThreeDLoadingScreen
        onComplete={() => setInitialLoadingComplete(true)}
        message="Prophecy Verse HD"
        minDurationMs={1800}
      />
    );
  }

  return (
    <div className="min-h-screen bg-[#0b0c10] text-[#f3f4f6] font-sans antialiased">
      <Sidebar currentView={currentView} setView={setView} />

      <div className="md:pl-64 min-h-screen flex flex-col">
        <header className="sticky top-0 h-18 bg-[#0b0c10]/75 backdrop-blur-md border-b border-white/5 flex items-center justify-between px-6 md:px-10 z-30 select-none">
          <div
            onClick={() => setView("home")}
            className="flex items-center gap-2 md:hidden cursor-pointer"
          >
            <div className="w-8 h-8 rounded-lg brand-gradient flex items-center justify-center shadow">
              <Film className="w-4 h-4 text-white" />
            </div>
            <h1 className="font-display font-black text-md text-white tracking-tight">
              Prophecy<span className="brand-gradient-text">Verse</span>
            </h1>
          </div>

          <div className="hidden md:block">
            <div
              onClick={() => setView("search")}
              className="flex items-center gap-3 px-4 py-2 bg-white/5 rounded-full border border-white/5 text-gray-500 hover:text-gray-300 hover:bg-white/10 cursor-pointer transition-all w-64 text-xs"
            >
              <Search className="w-4 h-4" />
              <span>Search MovieBox catalog...</span>
            </div>
          </div>

          <div className="flex items-center gap-4">
            <button
              onClick={() => setView("search")}
              className="p-2 rounded-lg text-gray-400 hover:text-white hover:bg-white/5 transition-all md:hidden cursor-pointer"
              title="Search"
            >
              <Search className="w-5 h-5" />
            </button>

            {profile ? (
              <div
                onClick={() => setView("profile")}
                className="flex items-center gap-2.5 cursor-pointer group"
              >
                <img
                  src={
                    profile.photoURL ||
                    `https://api.dicebear.com/7.x/adventurer/svg?seed=${encodeURIComponent(
                      profile.displayName
                    )}`
                  }
                  alt="User avatar"
                  className="w-8 h-8 rounded-full border border-brand-primary/20 object-cover bg-neutral-800"
                />
                <span className="text-xs font-semibold text-gray-300 group-hover:text-white hidden sm:block transition-colors">
                  {profile.displayName}
                </span>
              </div>
            ) : (
              <button
                onClick={() => setView("auth")}
                className="flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold uppercase tracking-widest text-white brand-gradient shadow-md shadow-brand-glow hover:opacity-95 active:scale-95 transform transition-all cursor-pointer"
              >
                <LogIn className="w-4 h-4" />
                Sign In
              </button>
            )}
          </div>
        </header>

        <main className="flex-1 p-6 md:p-10 max-w-7xl w-full mx-auto">
          {renderActiveView()}
        </main>
      </div>

      <NoticePopupModal />
      <MiniPlayer setView={setView} />
      <BottomNav currentView={currentView} setView={setView} />
    </div>
  );
};

export default function App() {
  return (
    <AppProvider>
      <PlayerProvider>
        <MainAppContent />
      </PlayerProvider>
    </AppProvider>
  );
}
