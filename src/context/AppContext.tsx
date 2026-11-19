import React, { createContext, useContext, useState, useEffect, useCallback } from "react";
import { User, onAuthStateChanged, signOut } from "firebase/auth";
import { auth } from "../firebase";
import {
  Anime,
  UserProfile,
  WatchHistory,
} from "../types";
import {
  fetchUserProfile,
  saveUserProfile,
  fetchUserWatchlist,
  fetchUserFavorites,
  fetchUserWatchHistory,
  toggleWatchlist as toggleWatchlistDB,
  toggleFavorite as toggleFavoriteDB,
} from "../services/dbService";
import {
  BackendApiError,
  fetchMovieBoxHomepage,
} from "../services/movieboxApiService";

interface AppContextType {
  user: User | null;
  profile: UserProfile | null;
  animeList: Anime[];
  watchlist: string[];
  favorites: string[];
  watchHistory: WatchHistory[];
  isLoading: boolean;
  catalogLoading: boolean;
  catalogError: { code?: string; message: string; retryable?: boolean } | null;
  refreshAnime: () => Promise<void>;
  upsertCatalogItems: (items: Anime[]) => void;
  toggleWatchlist: (animeId: string) => Promise<void>;
  toggleFavorite: (animeId: string) => Promise<void>;
  logoutUser: () => Promise<void>;
  refreshUserData: () => Promise<void>;
  setGuestUser: () => void;
}

const AppContext = createContext<AppContextType | undefined>(undefined);

export const AppProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [animeList, setAnimeList] = useState<Anime[]>([]);
  const [watchlist, setWatchlist] = useState<string[]>([]);
  const [favorites, setFavorites] = useState<string[]>([]);
  const [watchHistory, setWatchHistory] = useState<WatchHistory[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [catalogLoading, setCatalogLoading] = useState(true);
  const [catalogError, setCatalogError] = useState<{
    code?: string;
    message: string;
    retryable?: boolean;
  } | null>(null);

  const upsertCatalogItems = useCallback((items: Anime[]) => {
    if (!items || items.length === 0) return;
    setAnimeList((prev) => {
      const map = new Map<string, Anime>();
      for (const existing of prev) {
        map.set(existing.id, existing);
      }
      for (const incoming of items) {
        const current = map.get(incoming.id);
        map.set(incoming.id, current ? { ...current, ...incoming } : incoming);
      }
      return Array.from(map.values());
    });
  }, []);

  const refreshAnime = useCallback(async () => {
    setCatalogLoading(true);
    setCatalogError(null);
    try {
      const items = await fetchMovieBoxHomepage("0", 1);
      setAnimeList(items);
    } catch (err) {
      if (err instanceof BackendApiError) {
        setCatalogError({
          code: err.code,
          message: err.message,
          retryable: err.retryable,
        });
      } else {
        setCatalogError({
          code: "CATALOG_ERROR",
          message: err instanceof Error ? err.message : "Failed to load MovieBox catalog.",
          retryable: true,
        });
      }
    } finally {
      setCatalogLoading(false);
    }
  }, []);

  const refreshUserData = useCallback(async () => {
    if (!user) {
      setWatchlist([]);
      setFavorites([]);
      setWatchHistory([]);
      return;
    }
    try {
      const [wl, fav, hist] = await Promise.all([
        user.uid === "guest-user-id" ? Promise.resolve([]) : fetchUserWatchlist(user.uid),
        user.uid === "guest-user-id" ? Promise.resolve([]) : fetchUserFavorites(user.uid),
        fetchUserWatchHistory(user.uid),
      ]);
      setWatchlist(wl);
      setFavorites(fav);
      setWatchHistory(hist);
    } catch (error) {
      console.error("Failed to load user list data:", error);
    }
  }, [user]);

  useEffect(() => {
    const unsubscribeAuth = onAuthStateChanged(auth, async (currentUser) => {
      setUser(currentUser);

      if (currentUser) {
        let userProfile = await fetchUserProfile(currentUser.uid);
        if (!userProfile) {
          const storedDisplayName = localStorage.getItem("signupDisplayName");
          localStorage.removeItem("signupDisplayName");

          userProfile = {
            uid: currentUser.uid,
            email: currentUser.email || "",
            displayName:
              storedDisplayName ||
              currentUser.displayName ||
              currentUser.email?.split("@")[0] ||
              "User",
            role: "User",
            createdAt: new Date().toISOString(),
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
  }, [refreshAnime]);

  useEffect(() => {
    if (user && profile) {
      refreshUserData();
    }
  }, [user, profile, refreshUserData]);

  const toggleWatchlist = async (animeId: string) => {
    if (!user || user.uid === "guest-user-id") return;
    const isAdded = !watchlist.includes(animeId);
    setWatchlist((prev) =>
      isAdded ? [...prev, animeId] : prev.filter((id) => id !== animeId)
    );
    await toggleWatchlistDB(user.uid, animeId, isAdded);
  };

  const toggleFavorite = async (animeId: string) => {
    if (!user || user.uid === "guest-user-id") return;
    const isAdded = !favorites.includes(animeId);
    setFavorites((prev) =>
      isAdded ? [...prev, animeId] : prev.filter((id) => id !== animeId)
    );
    await toggleFavoriteDB(user.uid, animeId, isAdded);
  };

  const logoutUser = async () => {
    await signOut(auth);
    setUser(null);
    setProfile(null);
    setWatchlist([]);
    setFavorites([]);
    setWatchHistory([]);
  };

  const setGuestUser = () => {
    setUser({
      uid: "guest-user-id",
      email: "guest@prophecyverse.local",
      displayName: "Guest Voyager",
      emailVerified: false,
    } as unknown as User);
    setProfile({
      uid: "guest-user-id",
      email: "guest@prophecyverse.local",
      displayName: "Guest Voyager",
      role: "Guest",
      createdAt: new Date().toISOString(),
    });
  };

  return (
    <AppContext.Provider
      value={{
        user,
        profile,
        animeList,
        watchlist,
        favorites,
        watchHistory,
        isLoading,
        catalogLoading,
        catalogError,
        refreshAnime,
        upsertCatalogItems,
        toggleWatchlist,
        toggleFavorite,
        logoutUser,
        refreshUserData,
        setGuestUser,
      }}
    >
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
