import React from "react";
import { Home, Search, Library, User } from "lucide-react";
import { useApp } from "../context/AppContext";

interface BottomNavProps {
  currentView: string;
  setView: (view: string) => void;
}

export const BottomNav: React.FC<BottomNavProps> = ({ currentView, setView }) => {
  const { profile } = useApp();

  return (
    <nav className="md:hidden fixed bottom-0 left-0 right-0 h-16 bg-[#0d0e12]/90 backdrop-blur-xl border-t border-white/5 flex items-center justify-around px-4 z-40">
      <button
        onClick={() => setView("home")}
        className={`flex flex-col items-center gap-1 transition-colors cursor-pointer ${
          currentView === "home"
            ? "text-brand-primary"
            : "text-gray-400 hover:text-white"
        }`}
      >
        <Home className="w-5 h-5" />
        <span className="text-[10px] font-medium">Home</span>
      </button>

      <button
        onClick={() => setView("search")}
        className={`flex flex-col items-center gap-1 transition-colors cursor-pointer ${
          currentView === "search"
            ? "text-brand-primary"
            : "text-gray-400 hover:text-white"
        }`}
      >
        <Search className="w-5 h-5" />
        <span className="text-[10px] font-medium">Search</span>
      </button>

      <button
        onClick={() => setView("library")}
        className={`flex flex-col items-center gap-1 transition-colors cursor-pointer ${
          currentView === "library"
            ? "text-brand-primary"
            : "text-gray-400 hover:text-white"
        }`}
      >
        <Library className="w-5 h-5" />
        <span className="text-[10px] font-medium">Library</span>
      </button>

      <button
        onClick={() => setView(profile ? "profile" : "auth")}
        className={`flex flex-col items-center gap-1 transition-colors cursor-pointer ${
          currentView === "profile" || currentView === "auth"
            ? "text-brand-primary"
            : "text-gray-400 hover:text-white"
        }`}
      >
        <User className="w-5 h-5" />
        <span className="text-[10px] font-medium">Profile</span>
      </button>
    </nav>
  );
};
