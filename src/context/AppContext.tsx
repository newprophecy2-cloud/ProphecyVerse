import React, { createContext, useContext, useState, useEffect } from "react";
import { onAuthStateChanged, User, signOut, signInWithEmailAndPassword, createUserWithEmailAndPassword } from "firebase/auth";
import { auth } from "../firebase";
import { Anime, UserProfile, UserRole } from "../types";
import { 
  seedDatabaseIfEmpty, 
  fetchAllAnime, 
  saveAnime,
  deleteAnime as deleteAnimeDB,
  fetchUserProfile, 
  saveUserProfile, 
  fetchUserWatchlist, 
  fetchUserFavorites,
  fetchUserWatchHistory,
  toggleWatchlist as toggleWatchlistDB,
  toggleFavorite as toggleFavoriteDB
} from "../services/dbService";
import { searchAnimeSuggestions, compileFullAnimeMetadata } from "../services/animeApiService";
import { getDisplayTitle } from "../utils/animeUtils";

interface AppContextType {
  user: User | null;
  profile: UserProfile | null;
  animeList: Anime[];
  watchlist: string[]; // animeIds
  favorites: string[]; // animeIds
  watchHistory: any[];
  isLoading: boolean;
  refreshAnime: () => Promise<void>;
  deleteAnimeFromApp: (animeId: string) => Promise<void>;
  toggleWatchlist: (animeId: string) => Promise<void>;
  toggleFavorite: (animeId: string) => Promise<void>;
  logoutUser: () => Promise<void>;
  refreshUserData: () => Promise<void>;
  setProfileRole: (role: UserRole) => Promise<void>;
  setGuestUser: () => void;
}

const AppContext = createContext<AppContextType | undefined>(undefined);

export const AppProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [animeList, setAnimeList] = useState<Anime[]>([]);
  const [watchlist, setWatchlist] = useState<string[]>([]);
  const [favorites, setFavorites] = useState<string[]>([]);
  const [watchHistory, setWatchHistory] = useState<any[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  // Load Anime and seed
  const refreshAnime = async () => {
    await seedDatabaseIfEmpty();
    const list = await fetchAllAnime();
    setAnimeList(list);
    syncStaleAnime(list);
  };

  // Auto-update registered anime details every 1 hour accurately
  const syncStaleAnime = async (list: Anime[]) => {
    if (!list || list.length === 0) return;
    const ONE_HOUR = 3600000;
    const now = Date.now();
    let updatedAny = false;

    for (const anime of list) {
      if (!anime.lastUpdated || (now - anime.lastUpdated > ONE_HOUR)) {
        try {
          const searchTitle = getDisplayTitle(anime);
          if (!searchTitle || searchTitle === "Untitled Anime") continue;

          const suggestions = await searchAnimeSuggestions(searchTitle);
          if (suggestions && suggestions.length > 0) {
            const freshMeta = await compileFullAnimeMetadata(suggestions[0]);
            if (freshMeta) {
              const safeTitle = (typeof anime.title === "string" && anime.title.trim()) ? anime.title : freshMeta.title;
              const safeEnglishTitle = (typeof anime.englishTitle === "string" && anime.englishTitle.trim()) ? anime.englishTitle : freshMeta.englishTitle;
              
              const updatedAnime: Anime = {
                ...anime,
                ...freshMeta,
                id: anime.id, // maintain primary key
                title: safeTitle,
                englishTitle: safeEnglishTitle,
                lastUpdated: Date.now()
              };
              await saveAnime(updatedAnime);
              updatedAny = true;
            }
          }
        } catch (err) {
          console.error(`Auto 1-hour update error for ${getDisplayTitle(anime)}:`, err);
        }
      }
    }

    if (updatedAny) {
      const refreshedList = await fetchAllAnime();
      setAnimeList(refreshedList);
    }
  };

  // Periodic 1-hour sync timer
  useEffect(() => {
    const ONE_HOUR = 3600000;
    const interval = setInterval(() => {
      if (animeList.length > 0) {
        syncStaleAnime(animeList);
      }
    }, ONE_HOUR);

    return () => clearInterval(interval);
  }, [animeList]);

  const refreshUserData = async () => {
    if (!user) {
      setWatchlist([]);
      setFavorites([]);
      setWatchHistory([]);
      return;
    }
    try {
      const wl = await fetchUserWatchlist(user.uid);
      const fav = await fetchUserFavorites(user.uid);
      const hist = await fetchUserWatchHistory(user.uid);
      setWatchlist(wl);
      setFavorites(fav);
      setWatchHistory(hist);
    } catch (error) {
      console.error("Failed to load user list data:", error);
    }
  };

  // Listen to Auth State
  useEffect(() => {
    let unsubscribeProfile: (() => void) | null = null;

    const unsubscribeAuth = onAuthStateChanged(auth, async (currentUser) => {
      setUser(currentUser);
      
      if (currentUser) {
        // Fetch or create user profile
        let userProfile = await fetchUserProfile(currentUser.uid);
        if (!userProfile) {
          // Retrieve selected role & display name from localStorage (from signup)
          const storedRole = localStorage.getItem("signupRole") as UserRole | null;
          const storedDisplayName = localStorage.getItem("signupDisplayName");
          localStorage.removeItem("signupRole");
          localStorage.removeItem("signupDisplayName");

          // Create custom user profile
          userProfile = {
            uid: currentUser.uid,
            email: currentUser.email || "",
            displayName: storedDisplayName || currentUser.displayName || currentUser.email?.split("@")[0] || "User",
            role: storedRole || (currentUser.email === "admin@animestream.com" ? "Super Admin" : "User"),
            createdAt: new Date().toISOString()
          };
          await saveUserProfile(userProfile);
        }
        setProfile(userProfile);
      } else {
        setProfile(null);
      }
      setIsLoading(false);
    });

    refreshAnime();

    return () => {
      unsubscribeAuth();
    };
  }, []);

  // Sync user list data when profile changes
  useEffect(() => {
    if (user && profile) {
      refreshUserData();
    }
  }, [user, profile]);

  const toggleWatchlist = async (animeId: string) => {
    if (!user) return;
    const isAdded = !watchlist.includes(animeId);
    if (isAdded) {
      setWatchlist(prev => [...prev, animeId]);
    } else {
      setWatchlist(prev => prev.filter(id => id !== animeId));
    }
    await toggleWatchlistDB(user.uid, animeId, isAdded);
  };

  const toggleFavorite = async (animeId: string) => {
    if (!user) return;
    const isAdded = !favorites.includes(animeId);
    if (isAdded) {
      setFavorites(prev => [...prev, animeId]);
    } else {
      setFavorites(prev => prev.filter(id => id !== animeId));
    }
    await toggleFavoriteDB(user.uid, animeId, isAdded);
  };

  const logoutUser = async () => {
    await signOut(auth);
    setUser(null);
    setProfile(null);
  };

  // Utility to set profile role for testing/reviewing!
  const setProfileRole = async (role: UserRole) => {
    if (!user || !profile) return;
    const updated = { ...profile, role };
    setProfile(updated);
    await saveUserProfile(updated);
  };

  const setGuestUser = () => {
    // Client-side visual guest mode
    setUser({
      uid: "guest-user-id",
      email: "guest@animestream.com",
      displayName: "Guest Voyager",
      emailVerified: false,
    } as any);
    setProfile({
      uid: "guest-user-id",
      email: "guest@animestream.com",
      displayName: "Guest Voyager",
      role: "Guest",
      createdAt: new Date().toISOString()
    });
  };

  const deleteAnimeFromApp = async (animeId: string) => {
    // 1. Optimistically update local state immediately
    setAnimeList(prev => prev.filter(a => a.id !== animeId));
    setWatchlist(prev => prev.filter(id => id !== animeId));
    setFavorites(prev => prev.filter(id => id !== animeId));
    setWatchHistory(prev => prev.filter(item => item.animeId !== animeId));

    // 2. Safely perform database deletion
    await deleteAnimeDB(animeId);
  };

  return (
    <AppContext.Provider value={{
      user,
      profile,
      animeList,
      watchlist,
      favorites,
      watchHistory,
      isLoading,
      refreshAnime,
      deleteAnimeFromApp,
      toggleWatchlist,
      toggleFavorite,
      logoutUser,
      refreshUserData,
      setProfileRole,
      setGuestUser
    }}>
      {children}
    </AppContext.Provider>
  );
};

export const useApp = () => {
  const context = useContext(AppContext);
  if (!context) {
    throw new Error("useApp must be used within an AppProvider");
  }
  return context;
};
