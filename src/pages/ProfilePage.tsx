import React, { useState, useEffect } from "react";
import { useApp } from "../context/AppContext";
import { deleteUserProfile, saveUserProfile, clearWatchHistory, fetchNotifications, addNotification } from "../services/dbService";
import { Anime, Notification } from "../types";
import { AnimeCard } from "../components/AnimeCard";
import { 
  User, Settings, Mail, Bell, ShieldCheck, List, Star, History, Trash2, 
  Edit3, Check, Eye, Lock, RefreshCw, LogOut, CheckCircle, HelpCircle
} from "lucide-react";

interface ProfilePageProps {
  setView: (view: string, detailsId?: string) => void;
}

export const ProfilePage: React.FC<ProfilePageProps> = ({ setView }) => {
  const { 
    profile, 
    user, 
    watchlist, 
    favorites, 
    animeList, 
    watchHistory, 
    logoutUser, 
    refreshUserData,
    setProfileRole
  } = useApp();

  const [activeTab, setActiveTab] = useState<"watchlist" | "favorites" | "history" | "notifications" | "settings">("watchlist");
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [editingName, setEditingName] = useState(false);
  const [newName, setNewName] = useState("");
  const [editingAvatar, setEditingAvatar] = useState(false);
  const [selectedAvatar, setSelectedAvatar] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  // Settings mock options
  const [language, setLanguage] = useState("English (US)");
  const [quality, setQuality] = useState("Auto (Adaptive)");
  const [subtitleStyle, setSubtitleStyle] = useState("Yellow outline");
  const [autoPlay, setAutoPlay] = useState(true);

  // Populate form with current values
  useEffect(() => {
    if (profile) {
      setNewName(profile.displayName);
      setSelectedAvatar(profile.photoURL || "");
    }
  }, [profile]);

  // Load Notifications
  useEffect(() => {
    fetchNotifications().then((notifs) => {
      setNotifications(notifs);
    });
  }, [activeTab]);

  const handleUpdateProfile = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!profile) return;
    setIsLoading(true);
    setMsg(null);
    try {
      const updated = {
        ...profile,
        displayName: newName,
        photoURL: selectedAvatar
      };
      await saveUserProfile(updated);
      setEditingName(false);
      setEditingAvatar(false);
      setMsg("Profile updated successfully!");
      setTimeout(() => setMsg(null), 3000);
    } catch (err) {
      console.error(err);
    } finally {
      setIsLoading(false);
    }
  };

  const handleDeleteProfile = async () => {
    if (!window.confirm("Are you absolutely sure you want to delete your profile? This will permanently delete your sync records and data logs. This action cannot be undone.")) return;
    if (!user) return;
    setIsLoading(true);
    try {
      await deleteUserProfile(user.uid);
      await logoutUser();
      setView("home");
    } catch (err) {
      console.error(err);
    } finally {
      setIsLoading(false);
    }
  };

  const handleClearHistory = async () => {
    if (!profile || !user) return;
    if (window.confirm("Do you want to clear your entire watch history?")) {
      await clearWatchHistory(user.uid);
      await refreshUserData();
      setMsg("Watch history cleared successfully!");
      setTimeout(() => setMsg(null), 3000);
    }
  };

  // Filter Anime items for watchlist & favorites
  const watchlistAnime = animeList.filter((a) => watchlist.includes(a.id));
  const favoritesAnime = animeList.filter((a) => favorites.includes(a.id));

  // Avatars list to choose from (Dicebear styles)
  const avatarsList = [
    "https://api.dicebear.com/7.x/adventurer/svg?seed=Natsu",
    "https://api.dicebear.com/7.x/adventurer/svg?seed=Goku",
    "https://api.dicebear.com/7.x/adventurer/svg?seed=Luffy",
    "https://api.dicebear.com/7.x/adventurer/svg?seed=Zoro",
    "https://api.dicebear.com/7.x/adventurer/svg?seed=Sakura",
    "https://api.dicebear.com/7.x/adventurer/svg?seed=Lucy",
  ];

  if (!profile) return null;

  return (
    <div className="w-full min-h-screen pb-20 px-1">
      {/* 1. Header Banner & Profile Snapshot Card */}
      <div className="relative w-full p-6 md:p-8 rounded-3xl bg-gradient-to-r from-[#131520] to-[#0c0d15] border border-white/5 shadow-2xl mb-8 flex flex-col md:flex-row items-center justify-between gap-6 overflow-hidden">
        {/* Glow circles */}
        <div className="absolute -top-16 -right-16 w-36 h-36 bg-brand-primary/15 rounded-full blur-2xl pointer-events-none" />

        <div className="flex flex-col md:flex-row items-center gap-5 z-10">
          <div className="relative group">
            <img
              src={selectedAvatar || `https://api.dicebear.com/7.x/adventurer/svg?seed=${profile.displayName}`}
              alt="Avatar"
              className="w-20 h-20 rounded-full border-2 border-brand-primary/30 bg-neutral-800 object-cover"
            />
            <button
              onClick={() => setEditingAvatar(!editingAvatar)}
              className="absolute bottom-0 right-0 p-1.5 rounded-full bg-brand-primary text-white hover:scale-110 active:scale-95 transition-all shadow-md"
              title="Change Avatar"
            >
              <Edit3 className="w-3 h-3" />
            </button>
          </div>

          <div className="text-center md:text-left flex flex-col gap-1">
            <div className="flex items-center justify-center md:justify-start gap-2.5">
              {editingName ? (
                <form onSubmit={handleUpdateProfile} className="flex items-center gap-2">
                  <input
                    type="text"
                    value={newName}
                    onChange={(e) => setNewName(e.target.value)}
                    className="px-3 py-1 bg-white/5 border border-white/10 rounded-lg text-sm text-white focus:outline-none focus:border-brand-primary/45"
                    required
                  />
                  <button type="submit" className="p-1.5 bg-brand-primary text-white rounded-lg">
                    <Check className="w-4 h-4" />
                  </button>
                </form>
              ) : (
                <>
                  <h2 className="font-display font-extrabold text-2xl text-white">
                    {profile.displayName}
                  </h2>
                  <button
                    onClick={() => setEditingName(true)}
                    className="p-1 hover:text-brand-primary text-gray-500 transition-colors"
                    title="Edit Name"
                  >
                    <Edit3 className="w-3.5 h-3.5" />
                  </button>
                </>
              )}
            </div>

            <div className="flex items-center justify-center md:justify-start gap-2 text-xs font-mono text-gray-400">
              <Mail className="w-3.5 h-3.5" />
              <span>{profile.email}</span>
            </div>

            <div className="flex items-center justify-center md:justify-start gap-1.5 mt-1.5">
              <span className="px-2.5 py-0.5 rounded-md bg-brand-primary/10 border border-brand-primary/15 text-[10px] font-mono tracking-widest uppercase font-extrabold text-brand-primary shadow-sm shadow-brand-glow">
                {profile.role}
              </span>
            </div>
          </div>
        </div>

        {/* Action logouts */}
        <div className="flex flex-col gap-2.5 z-10 w-full md:w-auto">
          <button
            onClick={() => logoutUser()}
            className="w-full md:w-auto px-5 py-2.5 rounded-xl text-xs font-extrabold uppercase tracking-widest bg-white/5 border border-white/5 text-gray-300 hover:text-white hover:bg-red-500/10 hover:border-red-500/15 transition-all flex items-center justify-center gap-2"
          >
            <LogOut className="w-4 h-4" />
            Sign Out
          </button>
        </div>
      </div>

      {/* 2. Photo Avatars Selector Drawer */}
      {editingAvatar && (
        <div className="p-5 rounded-2xl bg-neutral-900 border border-white/5 mb-8 animate-fade-in">
          <h4 className="text-xs font-mono uppercase tracking-wider text-gray-400 mb-3.5">
            Select Premium Avatar
          </h4>
          <div className="flex items-center gap-4 flex-wrap">
            {avatarsList.map((av) => (
              <div
                key={av}
                onClick={() => {
                  setSelectedAvatar(av);
                  // Auto submit
                  const updated = { ...profile, photoURL: av };
                  saveUserProfile(updated).then(() => {
                    setEditingAvatar(false);
                  });
                }}
                className={`w-14 h-14 rounded-full overflow-hidden border-2 cursor-pointer transition-all ${
                  selectedAvatar === av ? "border-brand-primary scale-110 shadow-lg shadow-brand-glow" : "border-transparent opacity-75 hover:opacity-100"
                }`}
              >
                <img src={av} alt="Avatar option" className="w-full h-full object-cover" />
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Notification Toast Message */}
      {msg && (
        <div className="mb-6 p-4 rounded-xl bg-emerald-500/10 border border-emerald-500/10 text-emerald-400 text-xs flex items-center gap-2">
          <CheckCircle className="w-4.5 h-4.5" />
          <span>{msg}</span>
        </div>
      )}

      {/* 3. Tab Grid Panel Controls */}
      <div className="flex border-b border-white/5 mb-8 overflow-x-auto no-scrollbar scroll-smooth">
        {[
          { id: "watchlist", label: "My Watchlist", icon: List },
          { id: "favorites", label: "Favorites", icon: Star },
          { id: "history", label: "Watch History", icon: History },
          { id: "notifications", label: "Notifications", icon: Bell },
          { id: "settings", label: "Settings", icon: Settings },
        ].map((tab) => {
          const Icon = tab.icon;
          const isActive = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id as any)}
              className={`flex items-center gap-2 px-6 py-4 border-b-2 font-semibold text-sm transition-all whitespace-nowrap ${
                isActive
                  ? "border-brand-primary text-brand-primary bg-brand-primary/5"
                  : "border-transparent text-gray-400 hover:text-white"
              }`}
            >
              <Icon className="w-4 h-4" />
              {tab.label}
            </button>
          );
        })}
      </div>

      {/* 4. Tab Display content */}
      <div className="mt-2 min-h-[300px]">
        {activeTab === "watchlist" && (
          watchlistAnime.length > 0 ? (
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-6">
              {watchlistAnime.map((anime) => (
                <div key={anime.id} className="flex justify-center">
                  <AnimeCard anime={anime} onClick={() => setView("anime-details", anime.id)} />
                </div>
              ))}
            </div>
          ) : (
            <div className="text-center py-16 text-gray-500 font-mono text-sm">
              <List className="w-10 h-10 text-neutral-700 mx-auto mb-2" />
              Your Watchlist is empty. Add shows using the details page!
            </div>
          )
        )}

        {activeTab === "favorites" && (
          favoritesAnime.length > 0 ? (
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-6">
              {favoritesAnime.map((anime) => (
                <div key={anime.id} className="flex justify-center">
                  <AnimeCard anime={anime} onClick={() => setView("anime-details", anime.id)} />
                </div>
              ))}
            </div>
          ) : (
            <div className="text-center py-16 text-gray-500 font-mono text-sm">
              <Star className="w-10 h-10 text-neutral-700 mx-auto mb-2" />
              You haven't favorited any anime yet!
            </div>
          )
        )}

        {activeTab === "history" && (
          watchHistory.length > 0 ? (
            <div className="flex flex-col gap-4">
              <div className="flex items-center justify-between border-b border-white/5 pb-2">
                <h3 className="text-xs font-mono uppercase text-gray-400">Recently Viewed</h3>
                <button
                  onClick={handleClearHistory}
                  className="text-xs text-red-400 hover:text-red-500 flex items-center gap-1.5"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                  Clear History
                </button>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {watchHistory.map((item) => {
                  const anime = animeList.find(a => a.id === item.animeId);
                  if (!anime) return null;
                  const pct = Math.min(100, Math.round((item.position / (item.duration || 1)) * 100));
                  return (
                    <div
                      key={item.id}
                      onClick={() => setView("anime-details", item.animeId)}
                      className="flex gap-4 p-3.5 rounded-2xl bg-[#131520]/50 border border-white/5 hover:border-brand-primary/20 cursor-pointer transition-all"
                    >
                      <img
                        src={anime.poster}
                        alt={anime.title}
                        className="w-12 h-18 rounded-lg object-cover flex-shrink-0"
                      />
                      <div className="flex-1 flex flex-col justify-between truncate">
                        <div>
                          <h4 className="font-bold text-sm text-white truncate">{anime.title}</h4>
                          <p className="text-xs text-gray-400 mt-0.5">Watched Episode {item.episodeNumber}</p>
                        </div>
                        {/* Progress Bar */}
                        <div className="mt-2">
                          <div className="flex justify-between text-[10px] text-gray-500 font-mono mb-1">
                            <span>{pct}% watched</span>
                            <span>{Math.round(item.position / 60)} min / {Math.round(item.duration / 60)} min</span>
                          </div>
                          <div className="w-full h-1 bg-white/10 rounded-full overflow-hidden">
                            <div className="h-full brand-gradient" style={{ width: `${pct}%` }} />
                          </div>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          ) : (
            <div className="text-center py-16 text-gray-500 font-mono text-sm">
              <History className="w-10 h-10 text-neutral-700 mx-auto mb-2" />
              No watch history recorded yet. Start streaming an episode!
            </div>
          )
        )}

        {activeTab === "notifications" && (
          <div className="flex flex-col gap-3.5">
            <h3 className="text-xs font-mono uppercase text-gray-400 border-b border-white/5 pb-2 mb-1">
              System & Release Notices
            </h3>
            {notifications.length > 0 ? (
              notifications.map((notif) => (
                <div
                  key={notif.id}
                  className="p-4 rounded-xl bg-white/5 border border-white/5 flex gap-3 items-start"
                >
                  <Bell className="w-5 h-5 text-brand-primary mt-0.5 flex-shrink-0" />
                  <div>
                    <h4 className="font-bold text-sm text-white">{notif.title}</h4>
                    <p className="text-xs text-gray-300 mt-1 leading-relaxed">{notif.message}</p>
                    <span className="text-[9px] text-gray-500 font-mono mt-2 block">
                      {notif.createdAt ? new Date(notif.createdAt.toDate ? notif.createdAt.toDate() : notif.createdAt).toLocaleDateString() : "Just now"}
                    </span>
                  </div>
                </div>
              ))
            ) : (
              <div className="p-8 text-center text-xs text-gray-500 font-mono bg-white/5 rounded-xl border border-white/5">
                No active notifications or updates available.
              </div>
            )}
          </div>
        )}

        {activeTab === "settings" && (
          <div className="max-w-xl flex flex-col gap-6">
            <h3 className="text-xs font-mono uppercase text-gray-400 border-b border-white/5 pb-2">
              User Panel Settings
            </h3>

            {/* Language Selection */}
            <div className="flex items-center justify-between">
              <div>
                <h4 className="text-sm font-semibold text-white">Default Audio/Subtitle Language</h4>
                <p className="text-xs text-gray-400 mt-0.5">Preferred default localized display</p>
              </div>
              <select
                value={language}
                onChange={(e) => setLanguage(e.target.value)}
                className="px-3 py-2 bg-neutral-900 border border-white/10 rounded-xl text-xs text-gray-300"
              >
                <option>English (US)</option>
                <option>Japanese (Original Subbed)</option>
                <option>Spanish (Latin America)</option>
                <option>French (Europe)</option>
              </select>
            </div>

            {/* Quality Selection */}
            <div className="flex items-center justify-between border-t border-white/5 pt-4">
              <div>
                <h4 className="text-sm font-semibold text-white">Default Video Resolution</h4>
                <p className="text-xs text-gray-400 mt-0.5">Configure starting bandwidth quality</p>
              </div>
              <select
                value={quality}
                onChange={(e) => setQuality(e.target.value)}
                className="px-3 py-2 bg-neutral-900 border border-white/10 rounded-xl text-xs text-gray-300"
              >
                <option>Auto (Adaptive)</option>
                <option>1080p Ultra HD</option>
                <option>720p HD</option>
                <option>480p Mobile Saver</option>
              </select>
            </div>

            {/* Subtitle Styles Selection */}
            <div className="flex items-center justify-between border-t border-white/5 pt-4">
              <div>
                <h4 className="text-sm font-semibold text-white">Subtitle Presentation Style</h4>
                <p className="text-xs text-gray-400 mt-0.5">Customize caption visual theme</p>
              </div>
              <select
                value={subtitleStyle}
                onChange={(e) => setSubtitleStyle(e.target.value)}
                className="px-3 py-2 bg-neutral-900 border border-white/10 rounded-xl text-xs text-gray-300"
              >
                <option>Yellow outline</option>
                <option>White outline</option>
                <option>White overlay black box</option>
              </select>
            </div>

            {/* Danger Zone */}
            <div className="border-t border-red-500/10 pt-6 mt-4 flex flex-col gap-3">
              <h4 className="text-xs font-mono uppercase text-red-500 font-bold tracking-wide">Danger Zone</h4>
              <div className="p-4 bg-red-500/5 rounded-2xl border border-red-500/10 flex items-center justify-between gap-4">
                <div>
                  <h5 className="text-sm font-bold text-red-400">Permanently Delete Account</h5>
                  <p className="text-xs text-gray-400 mt-0.5 max-w-sm">
                    Warning: This immediately deletes your streaming history, watchlist items, and profiles permanently.
                  </p>
                </div>
                <button
                  onClick={handleDeleteProfile}
                  disabled={isLoading}
                  className="px-4 py-2.5 bg-red-500/15 hover:bg-red-500 text-red-400 hover:text-white rounded-xl text-xs font-bold uppercase tracking-widest transition-all"
                >
                  Delete Account
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
