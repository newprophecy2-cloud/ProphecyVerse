import React, { useState, useEffect } from "react";
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
import { AdminPage } from "./pages/AdminPage";
import { AuthPage } from "./pages/AuthPage";
import { Film, User, Search, PlaySquare, ShieldAlert, LogIn, Loader } from "lucide-react";

const MainAppContent: React.FC = () => {
  const { profile, isLoading, setGuestUser } = useApp();
  const { playerMode, minimizePlayer, activeEpisode } = usePlayer();

  // Initial 3D loading screen state
  const [initialLoadingComplete, setInitialLoadingComplete] = useState<boolean>(false);

  // Navigation router states
  const [currentView, setCurrentView] = useState<string>("home");
  const [selectedDetailsId, setSelectedDetailsId] = useState<string>("");
  const [watchEpisodeNumber, setWatchEpisodeNumber] = useState<number>(1);
  const [watchInitialPosition, setWatchInitialPosition] = useState<number>(0);

  // Helper setter to launch watch page
  const setWatchParams = (animeId: string, episodeNumber: number, initialPosition: number) => {
    setSelectedDetailsId(animeId);
    setWatchEpisodeNumber(episodeNumber);
    setWatchInitialPosition(initialPosition);
  };

  const setView = (view: string, detailsId?: string) => {
    if (currentView === "watch" && view !== "watch" && playerMode === "full" && activeEpisode) {
      minimizePlayer();
    }
    setCurrentView(view);
    if (detailsId) {
      setSelectedDetailsId(detailsId);
    }
    // Scroll to top on navigation change
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  // Safe Role check
  const isAdmin = profile?.role === "Admin" || profile?.role === "Super Admin";

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
            episodeNumber={watchEpisodeNumber}
            initialPosition={watchInitialPosition}
          />
        );
      case "profile":
        if (!profile) return <AuthPage />;
        return <ProfilePage setView={setView} />;
      case "admin":
        return <AdminPage />;
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
        minDurationMs={2200}
      />
    );
  }

  return (
    <div className="min-h-screen bg-[#0b0c10] text-[#f3f4f6] font-sans antialiased">
      
      {/* 1. Desktop Sidebar */}
      <Sidebar currentView={currentView} setView={setView} />

      {/* 2. Main Content Wrapper */}
      <div className="md:pl-64 min-h-screen flex flex-col">
        
        {/* Sticky Global Top Header (Beautiful Glassmorphism) */}
        <header className="sticky top-0 h-18 bg-[#0b0c10]/75 backdrop-blur-md border-b border-white/5 flex items-center justify-between px-6 md:px-10 z-30 select-none">
          {/* Logo on mobile only */}
          <div 
            onClick={() => setView("home")}
            className="flex items-center gap-2 md:hidden cursor-pointer"
          >
            <div className="w-8 h-8 rounded-lg brand-gradient flex items-center justify-center shadow">
              <Film className="w-4 h-4 text-white" />
            </div>
            <h1 className="font-display font-black text-md text-white tracking-tight">
              Anime<span className="brand-gradient-text">Stream</span>
            </h1>
          </div>

          <div className="hidden md:block">
            {/* Quick search input overlay trigger */}
            <div 
              onClick={() => setView("search")}
              className="flex items-center gap-3 px-4 py-2 bg-white/5 rounded-full border border-white/5 text-gray-500 hover:text-gray-300 hover:bg-white/10 cursor-pointer transition-all w-60 text-xs"
            >
              <Search className="w-4 h-4" />
              <span>Quick Search catalog...</span>
            </div>
          </div>

          {/* Top Right Controls */}
          <div className="flex items-center gap-4">
            <button
              onClick={() => setView("search")}
              className="p-2 rounded-lg text-gray-400 hover:text-white hover:bg-white/5 transition-all md:hidden"
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
                  src={profile.photoURL || `https://api.dicebear.com/7.x/adventurer/svg?seed=${profile.displayName}`}
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
                className="flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold uppercase tracking-widest text-white brand-gradient shadow-md shadow-brand-glow hover:opacity-95 active:scale-95 transform transition-all"
              >
                <LogIn className="w-4 h-4" />
                Sign In
              </button>
            )}
          </div>
        </header>

        {/* 3. Render Router Page View */}
        <main className="flex-1 p-6 md:p-10 max-w-7xl w-full mx-auto">
          {renderActiveView()}
        </main>

      </div>

      {/* Global Geo-Smart Notice Popup Modal */}
      <NoticePopupModal />

      {/* 4. Floating Mini-Player */}
      <MiniPlayer setView={setView} />


      {/* 5. Mobile Bottom Nav */}
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
