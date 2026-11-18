import React from "react";
import { Home, Search, Library, User, ShieldAlert, LogOut, Film } from "lucide-react";
import { useApp } from "../context/AppContext";

interface SidebarProps {
  currentView: string;
  setView: (view: string, detailsId?: string) => void;
}

export const Sidebar: React.FC<SidebarProps> = ({ currentView, setView }) => {
  const { profile, logoutUser } = useApp();
  const isAdmin = profile?.role === "Admin" || profile?.role === "Super Admin";

  const menuItems = [
    { id: "home", label: "Home", icon: Home },
    { id: "search", label: "Search", icon: Search },
    { id: "library", label: "My Library", icon: Library },
    { id: "profile", label: "Profile", icon: User },
  ];

  return (
    <aside className="hidden md:flex flex-col w-64 h-screen fixed left-0 top-0 bg-[#0d0e12]/95 border-r border-white/5 p-6 z-40 justify-between">
      <div className="flex flex-col gap-8">
        {/* Brand Logo */}
        <div 
          onClick={() => setView("home")}
          className="flex items-center gap-3 cursor-pointer group"
        >
          <div className="w-10 h-10 rounded-xl brand-gradient flex items-center justify-center shadow-lg shadow-brand-glow group-hover:scale-105 transition-transform duration-300">
            <Film className="w-5 h-5 text-white" />
          </div>
          <div>
            <h1 className="font-display font-extrabold text-lg tracking-tight text-white group-hover:text-brand-primary transition-colors duration-300">
              Prophecy <span className="brand-gradient-text">Verse</span>
            </h1>
            <p className="text-[10px] text-amber-400 uppercase tracking-widest font-mono font-bold">Studio Engine</p>
          </div>
        </div>

        {/* Navigation Menus */}
        <nav className="flex flex-col gap-1.5">
          <p className="text-[10px] text-gray-500 font-mono uppercase tracking-widest px-3 mb-2">Navigation</p>
          {menuItems.map((item) => {
            const Icon = item.icon;
            const isActive = currentView === item.id || (item.id === "library" && currentView === "library");
            return (
              <button
                key={item.id}
                onClick={() => setView(item.id)}
                className={`flex items-center gap-3.5 px-4 py-3 rounded-xl font-medium text-sm transition-all duration-300 ${
                  isActive
                    ? "bg-brand-primary/10 text-brand-primary shadow-inner border border-brand-primary/10"
                    : "text-gray-400 hover:bg-white/5 hover:text-white"
                }`}
              >
                <Icon className={`w-5 h-5 ${isActive ? "text-brand-primary" : "text-gray-400 group-hover:text-white"}`} />
                {item.label}
              </button>
            );
          })}

          {/* Management Control */}
          <p className="text-[10px] text-gray-500 font-mono uppercase tracking-widest px-3 mt-6 mb-2">Management</p>
          <button
            onClick={() => setView("admin")}
            className={`w-full flex items-center justify-between px-4 py-3 rounded-xl font-medium text-sm transition-all duration-300 ${
              currentView === "admin"
                ? "bg-amber-500/10 text-amber-500 border border-amber-500/10 shadow-inner"
                : "text-gray-400 hover:bg-white/5 hover:text-white"
            }`}
          >
            <div className="flex items-center gap-3.5">
              <ShieldAlert className="w-5 h-5 text-amber-400" />
              <span>Admin Panel</span>
            </div>
            {!isAdmin && (
              <span className="px-1.5 py-0.5 rounded text-[9px] font-mono font-bold uppercase bg-amber-500/20 text-amber-400 border border-amber-500/30">
                Access
              </span>
            )}
          </button>
        </nav>
      </div>

      {/* User Footer Profile */}
      <div className="flex flex-col gap-4 border-t border-white/5 pt-6">
        {profile ? (
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <img
                src={profile.photoURL || `https://api.dicebear.com/7.x/adventurer/svg?seed=${profile.displayName}`}
                alt="Profile"
                className="w-10 h-10 rounded-full border border-white/10 object-cover bg-neutral-800"
              />
              <div className="max-w-[120px]">
                <h4 className="font-semibold text-sm text-white truncate">{profile.displayName}</h4>
                <p className="text-xs text-brand-primary font-mono truncate">{profile.role}</p>
              </div>
            </div>
            <button
              onClick={() => logoutUser()}
              title="Logout"
              className="p-2 rounded-lg text-gray-400 hover:bg-red-500/10 hover:text-red-500 transition-all"
            >
              <LogOut className="w-4.5 h-4.5" />
            </button>
          </div>
        ) : (
          <button
            onClick={() => setView("auth")}
            className="w-full py-3 rounded-xl text-center font-semibold text-sm brand-gradient text-white shadow-lg shadow-brand-glow hover:opacity-95 transition-opacity"
          >
            Sign In
          </button>
        )}
      </div>
    </aside>
  );
};
