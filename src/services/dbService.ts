import {
  collection,
  doc,
  getDocs,
  getDoc,
  setDoc,
  addDoc,
  deleteDoc,
  query,
  where,
  orderBy,
  limit,
  onSnapshot,
  serverTimestamp,
} from "firebase/firestore";
import { db } from "../firebase";
import {
  WatchHistory,
  Watchlist,
  Favorite,
  Comment,
  Rating,
  Notification,
  UserProfile,
} from "../types";

function getTimestampMs(field: any): number {
  if (!field) return 0;
  if (typeof field.toMillis === "function") return field.toMillis();
  if (typeof field.toDate === "function") return field.toDate().getTime();
  if (field.seconds !== undefined) {
    return field.seconds * 1000 + Math.floor((field.nanoseconds || 0) / 1000000);
  }
  if (field instanceof Date) return field.getTime();
  if (typeof field === "string") return new Date(field).getTime();
  if (typeof field === "number") return field;
  return 0;
}

export function cleanUndefined<T>(obj: T): T {
  if (obj === null || obj === undefined) return obj as any;
  if (typeof obj !== "object") return obj;
  if (obj instanceof Date) return obj as any;
  if ((obj as any)._methodName || typeof (obj as any).toMillis === "function") {
    return obj;
  }
  if (Array.isArray(obj)) {
    return obj.map(cleanUndefined) as any;
  }
  const cleaned: any = {};
  for (const [key, value] of Object.entries(obj)) {
    if (value !== undefined) {
      cleaned[key] = cleanUndefined(value);
    }
  }
  return cleaned;
}

export const LANG_MAP: Record<string, { code: string; name: string }> = {
  Japanese: { code: "ja", name: "Japanese" },
  ja: { code: "ja", name: "Japanese" },
  English: { code: "en", name: "English" },
  en: { code: "en", name: "English" },
  Hindi: { code: "hi", name: "Hindi" },
  hi: { code: "hi", name: "Hindi" },
  Bangla: { code: "bn", name: "Bangla" },
  bn: { code: "bn", name: "Bangla" },
  Bengali: { code: "bn", name: "Bengali" },
  Spanish: { code: "es", name: "Spanish" },
  es: { code: "es", name: "Spanish" },
  Subbed: { code: "ja", name: "Subbed" },
  Dubbed: { code: "en", name: "Dubbed" },
};

export function getLanguageMetadata(langStr: string) {
  const match = LANG_MAP[langStr];
  if (match) return match;
  const code = (langStr || "en").toLowerCase().slice(0, 2);
  return { code: code || "en", name: langStr || "English" };
}

// --- Watch History Repository ---
export async function updateWatchProgress(
  userId: string,
  animeId: string,
  episodeNumber: number,
  position: number,
  duration: number,
  meta?: { seasonNumber?: number; title?: string; poster?: string; banner?: string }
): Promise<void> {
  const id = `${userId}_${animeId}_${episodeNumber}`;

  try {
    const raw = localStorage.getItem(`animestream_history_${userId}`);
    const list: WatchHistory[] = raw ? JSON.parse(raw) : [];
    const existingIdx = list.findIndex(
      (h) => h.animeId === animeId && h.episodeNumber === episodeNumber
    );
    const entry: WatchHistory = {
      id,
      userId,
      animeId,
      seasonNumber: meta?.seasonNumber,
      episodeNumber,
      position,
      duration,
      title: meta?.title,
      poster: meta?.poster,
      banner: meta?.banner,
      updatedAt: Date.now(),
    };
    if (existingIdx >= 0) {
      list[existingIdx] = { ...list[existingIdx], ...entry };
    } else {
      list.unshift(entry);
    }
    localStorage.setItem(`animestream_history_${userId}`, JSON.stringify(list.slice(0, 100)));
  } catch {
    // ignore localStorage errors
  }

  try {
    const progressRef = doc(db, "watchHistory", id);
    await setDoc(
      progressRef,
      cleanUndefined({
        id,
        userId,
        animeId,
        seasonNumber: meta?.seasonNumber,
        episodeNumber,
        position,
        duration,
        title: meta?.title,
        poster: meta?.poster,
        banner: meta?.banner,
        updatedAt: serverTimestamp(),
      })
    );
  } catch (e) {
    console.warn("Firestore watch progress error:", e);
  }
}

export async function fetchUserWatchHistory(userId: string): Promise<WatchHistory[]> {
  let localList: WatchHistory[] = [];
  try {
    const raw = localStorage.getItem(`animestream_history_${userId}`);
    if (raw) {
      localList = JSON.parse(raw);
    }
  } catch {
    // ignore
  }

  let dbList: WatchHistory[] = [];
  try {
    const historyRef = collection(db, "watchHistory");
    const q = query(historyRef, where("userId", "==", userId));
    const snapshot = await getDocs(q);
    dbList = snapshot.docs.map((d) => d.data() as WatchHistory);
  } catch (e) {
    console.warn("Firestore watchHistory fetch error, using local fallback:", e);
  }

  const map = new Map<string, WatchHistory>();
  for (const item of [...localList, ...dbList]) {
    const key = `${item.animeId}_${item.episodeNumber}`;
    const existing = map.get(key);
    if (!existing || getTimestampMs(item.updatedAt) >= getTimestampMs(existing.updatedAt)) {
      map.set(key, item);
    }
  }

  return Array.from(map.values()).sort(
    (a, b) => getTimestampMs(b.updatedAt) - getTimestampMs(a.updatedAt)
  );
}

export async function clearWatchHistory(userId: string): Promise<void> {
  try {
    localStorage.removeItem(`animestream_history_${userId}`);
  } catch {
    // ignore
  }
  const historyRef = collection(db, "watchHistory");
  const q = query(historyRef, where("userId", "==", userId));
  const snapshot = await getDocs(q);
  for (const itemDoc of snapshot.docs) {
    await deleteDoc(itemDoc.ref);
  }
}

// --- Watchlist Repository ---
export async function toggleWatchlist(
  userId: string,
  animeId: string,
  isAdded: boolean
): Promise<void> {
  const id = `${userId}_${animeId}`;
  const ref = doc(db, "watchlist", id);
  if (isAdded) {
    await setDoc(ref, {
      userId,
      animeId,
      addedAt: serverTimestamp(),
    });
  } else {
    await deleteDoc(ref);
  }
}

export async function fetchUserWatchlist(userId: string): Promise<string[]> {
  const ref = collection(db, "watchlist");
  const q = query(ref, where("userId", "==", userId));
  const snapshot = await getDocs(q);
  return snapshot.docs.map((d) => (d.data() as Watchlist).animeId);
}

// --- Favorites Repository ---
export async function toggleFavorite(
  userId: string,
  animeId: string,
  isAdded: boolean
): Promise<void> {
  const id = `${userId}_${animeId}`;
  const ref = doc(db, "favorites", id);
  if (isAdded) {
    await setDoc(ref, {
      userId,
      animeId,
      addedAt: serverTimestamp(),
    });
  } else {
    await deleteDoc(ref);
  }
}

export async function fetchUserFavorites(userId: string): Promise<string[]> {
  const ref = collection(db, "favorites");
  const q = query(ref, where("userId", "==", userId));
  const snapshot = await getDocs(q);
  return snapshot.docs.map((d) => (d.data() as Favorite).animeId);
}

// --- User Profile Management ---
export async function saveUserProfile(profile: UserProfile): Promise<void> {
  await setDoc(doc(db, "users", profile.uid), cleanUndefined(profile), { merge: true });
}

export async function fetchUserProfile(uid: string): Promise<UserProfile | null> {
  const docRef = doc(db, "users", uid);
  const snap = await getDoc(docRef);
  if (snap.exists()) {
    return snap.data() as UserProfile;
  }
  return null;
}

export async function deleteUserProfile(uid: string): Promise<void> {
  await deleteDoc(doc(db, "users", uid));
}

// --- Comments System ---
export function subscribeToComments(
  animeId: string,
  callback: (comments: Comment[]) => void
) {
  const ref = collection(db, "comments");
  const q = query(ref, where("animeId", "==", animeId));
  return onSnapshot(
    q,
    (snapshot) => {
      const comments = snapshot.docs.map((d) => ({ id: d.id, ...d.data() } as Comment));
      comments.sort((a, b) => getTimestampMs(b.createdAt) - getTimestampMs(a.createdAt));
      callback(comments);
    },
    (err) => {
      console.warn("Comments subscription error:", err);
      callback([]);
    }
  );
}

export async function addComment(
  animeId: string,
  episodeNumber: number | undefined,
  userId: string,
  userName: string,
  text: string,
  userPhoto?: string
): Promise<void> {
  await addDoc(collection(db, "comments"), {
    animeId,
    episodeNumber: episodeNumber || null,
    userId,
    userName,
    userPhoto: userPhoto || "",
    text,
    createdAt: serverTimestamp(),
  });
}

// --- Ratings System ---
export async function rateAnime(
  userId: string,
  animeId: string,
  score: number
): Promise<void> {
  const id = `${userId}_${animeId}`;
  await setDoc(doc(db, "ratings", id), {
    id,
    userId,
    animeId,
    score,
    updatedAt: serverTimestamp(),
  });
}

export async function fetchAnimeRatings(animeId: string): Promise<number> {
  const ref = collection(db, "ratings");
  const q = query(ref, where("animeId", "==", animeId));
  const snap = await getDocs(q);
  if (snap.empty) return 0;
  const scores = snap.docs.map((d) => (d.data() as Rating).score);
  const sum = scores.reduce((a, b) => a + b, 0);
  return parseFloat((sum / scores.length).toFixed(1));
}

export async function fetchUserRating(
  userId: string,
  animeId: string
): Promise<number | null> {
  const id = `${userId}_${animeId}`;
  const snap = await getDoc(doc(db, "ratings", id));
  if (snap.exists()) {
    return (snap.data() as Rating).score;
  }
  return null;
}

// --- Notifications System ---
export async function fetchNotifications(): Promise<Notification[]> {
  try {
    const ref = collection(db, "notifications");
    const q = query(ref, orderBy("createdAt", "desc"), limit(20));
    const snap = await getDocs(q);
    return snap.docs.map((d) => ({ id: d.id, ...d.data() } as Notification));
  } catch {
    return [];
  }
}
