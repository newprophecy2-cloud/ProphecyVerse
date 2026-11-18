import { 
  collection, 
  doc, 
  getDocs, 
  getDoc, 
  setDoc, 
  addDoc, 
  updateDoc, 
  deleteDoc, 
  query, 
  where, 
  orderBy, 
  limit, 
  onSnapshot, 
  serverTimestamp 
} from "firebase/firestore";
import { db } from "../firebase";
import { Anime, Episode, WatchHistory, Watchlist, Favorite, Comment, Rating, Notification, UserProfile, VersionHistoryItem } from "../types";
import { SEED_ANIME_LIST, SEED_EPISODES } from "../utils/seedData";
import { searchAnimeSuggestions, compileFullAnimeMetadata, getDisplayTitle } from "./animeApiService";

function getTimestampMs(field: any): number {
  if (!field) return 0;
  if (typeof field.toMillis === "function") {
    return field.toMillis();
  }
  if (typeof field.toDate === "function") {
    return field.toDate().getTime();
  }
  if (field.seconds !== undefined) {
    return field.seconds * 1000 + Math.floor((field.nanoseconds || 0) / 1000000);
  }
  if (field instanceof Date) {
    return field.getTime();
  }
  if (typeof field === "string") {
    return new Date(field).getTime();
  }
  if (typeof field === "number") {
    return field;
  }
  return 0;
}

export function cleanUndefined<T>(obj: T): T {
  if (obj === null || obj === undefined) return obj as any;
  if (typeof obj !== "object") return obj;
  if (obj instanceof Date) return obj as any;

  // Handle FieldValue (like serverTimestamp()) or special Firestore objects
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

// --- Seeding & Initial Database Population ---
export async function seedDatabaseIfEmpty() {
  try {
    const hasSeededBefore = localStorage.getItem("animestream_has_seeded");
    if (hasSeededBefore === "true") {
      return; // Prevent re-seeding if anime are intentionally deleted by user
    }

    const animeRef = collection(db, "anime");
    const snapshot = await getDocs(query(animeRef, limit(1)));
    
    if (snapshot.empty) {
      console.log("Firestore anime collection is empty! Seeding default high-quality anime database...");
      
      // 1. Seed Anime
      for (const anime of SEED_ANIME_LIST) {
        await setDoc(doc(db, "anime", anime.id), cleanUndefined({
          ...anime,
          createdAt: serverTimestamp()
        }));
        
        // 2. Seed Episodes for this anime
        const episodes = SEED_EPISODES[anime.id] || [];
        for (const ep of episodes) {
          await setDoc(doc(db, "episodes", ep.id), cleanUndefined({
            ...ep,
            createdAt: serverTimestamp()
          }));
        }
      }
      localStorage.setItem("animestream_has_seeded", "true");
      console.log("Database seeded successfully!");
    } else {
      localStorage.setItem("animestream_has_seeded", "true");
    }
  } catch (error) {
    console.error("Failed to seed database:", error);
  }
}

// --- Anime Repository ---
export async function fetchAllAnime(): Promise<Anime[]> {
  const animeRef = collection(db, "anime");
  const q = query(animeRef, orderBy("createdAt", "desc"));
  const snapshot = await getDocs(q);
  return snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() } as Anime));
}

export async function fetchAnimeById(id: string): Promise<Anime | null> {
  const docRef = doc(db, "anime", id);
  const snap = await getDoc(docRef);
  if (snap.exists()) {
    return { id: snap.id, ...snap.data() } as Anime;
  }
  return null;
}

export async function saveAnime(anime: Anime): Promise<void> {
  await setDoc(doc(db, "anime", anime.id), cleanUndefined({
    ...anime,
    createdAt: serverTimestamp()
  }));
}

export async function deleteAnime(id: string): Promise<void> {
  const collectionsToClean = ["episodes", "comments", "ratings", "watchlist", "favorites", "watchHistory"];
  
  for (const colName of collectionsToClean) {
    try {
      const colRef = collection(db, colName);
      const q = query(colRef, where("animeId", "==", id));
      const snapshot = await getDocs(q);
      for (const docSnap of snapshot.docs) {
        await deleteDoc(docSnap.ref);
      }
    } catch (err) {
      console.warn(`Non-fatal issue deleting related records in ${colName}:`, err);
    }
  }
  
  // Delete the primary anime document
  await deleteDoc(doc(db, "anime", id));
}

// --- Episodes Repository ---
export async function checkAndUpdatePendingEpisodeStatus(ep: Episode): Promise<Episode> {
  return ep;
}

export async function syncAllPendingEpisodes(episodes?: Episode[]): Promise<{ syncedCount: number; updatedEpisodes: Episode[] }> {
  let list = episodes;
  if (!list || list.length === 0) {
    list = await fetchAllEpisodes();
  }
  return { syncedCount: 0, updatedEpisodes: list };
}

export async function fetchEpisodesForAnime(animeId: string): Promise<Episode[]> {
  const epRef = collection(db, "episodes");
  const q = query(epRef, where("animeId", "==", animeId));
  const snapshot = await getDocs(q);
  
  const eps = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() } as Episode));
  return eps.sort((a, b) => (Number(a.episodeNumber) || 0) - (Number(b.episodeNumber) || 0));
}

export async function saveEpisode(episode: Episode): Promise<void> {
  await setDoc(doc(db, "episodes", episode.id), cleanUndefined({
    ...episode,
    createdAt: serverTimestamp()
  }), { merge: true });
}

export const LANG_MAP: Record<string, { code: string; name: string }> = {
  "Japanese": { code: "ja", name: "Japanese" },
  "ja": { code: "ja", name: "Japanese" },
  "English": { code: "en", name: "English" },
  "en": { code: "en", name: "English" },
  "Hindi": { code: "hi", name: "Hindi" },
  "hi": { code: "hi", name: "Hindi" },
  "Bangla": { code: "bn", name: "Bangla" },
  "bn": { code: "bn", name: "Bangla" },
  "Spanish": { code: "es", name: "Spanish" },
  "es": { code: "es", name: "Spanish" },
  "Subbed": { code: "ja", name: "Subbed" },
  "Dubbed": { code: "en", name: "Dubbed" }
};

export function getLanguageMetadata(langStr: string) {
  const match = LANG_MAP[langStr];
  if (match) return match;
  const code = (langStr || "ja").toLowerCase().slice(0, 2);
  return { code: code || "ja", name: langStr || "Japanese" };
}

export async function saveOrMergeEpisodeLanguage(
  animeId: string,
  episodeNumber: number,
  language: string,
  languageData: any,
  episodeMeta?: Partial<Episode>
): Promise<Episode> {
  const targetSeasonNum = episodeMeta?.seasonNumber || 1;
  const epId = episodeMeta?.id || `${animeId}_s${targetSeasonNum}_ep${episodeNumber}`;
  let docRef = doc(db, "episodes", epId);
  let snap = await getDoc(docRef);

  if (!snap.exists() && targetSeasonNum === 1) {
    const legacyDocRef = doc(db, "episodes", `${animeId}_ep${episodeNumber}`);
    const legacySnap = await getDoc(legacyDocRef);
    if (legacySnap.exists() && ((legacySnap.data() as any).seasonNumber || 1) === 1) {
      docRef = legacyDocRef;
      snap = legacySnap;
    }
  }

  let existingEp: Episode | null = snap.exists() ? ({ id: snap.id, ...snap.data() } as Episode) : null;

  const { code: langCode, name: langName } = getLanguageMetadata(language);

  // Validate incoming playbackId: must be non-empty and NOT a placeholder string starting with upload_ or asset_
  const rawIncomingPlaybackId = (languageData.playbackId || languageData.muxPlaybackId || episodeMeta?.muxPlaybackId || (episodeMeta as any)?.playbackId || "").trim();
  const isValidIncomingPlaybackId = !!rawIncomingPlaybackId && !rawIncomingPlaybackId.startsWith("upload_") && !rawIncomingPlaybackId.startsWith("asset_");

  const existingPlaybackId = (existingEp?.muxPlaybackId || (existingEp as any)?.playbackId || "").trim();
  const isValidExistingPlaybackId = !!existingPlaybackId && !existingPlaybackId.startsWith("upload_") && !existingPlaybackId.startsWith("asset_");

  const finalPlaybackId = isValidIncomingPlaybackId ? rawIncomingPlaybackId : (isValidExistingPlaybackId ? existingPlaybackId : "");

  // Asset ID resolution
  const rawIncomingAssetId = (languageData.assetId || languageData.muxAssetId || episodeMeta?.muxAssetId || (episodeMeta as any)?.assetId || "").trim();
  const existingAssetId = (existingEp?.muxAssetId || (existingEp as any)?.assetId || "").trim();
  const finalAssetId = rawIncomingAssetId || existingAssetId;

  // Upload ID resolution
  const finalUploadId = (languageData.uploadId || episodeMeta?.uploadId || (existingEp as any)?.uploadId || "").trim();

  // Primary stream URL from validated Playback ID
  const finalStreamUrl = finalPlaybackId
    ? `https://stream.mux.com/${finalPlaybackId}.m3u8`
    : (languageData.muxPlaybackUrl || languageData.videoUrl || episodeMeta?.videoUrl || existingEp?.videoUrl || "");

  const resolvedSourceType = languageData.sourceType || episodeMeta?.sourceType || (existingEp?.sourceType ? existingEp.sourceType : (finalPlaybackId ? "mux" : "cloudflare"));
  const resolvedStorageKey = languageData.storageKey || episodeMeta?.storageKey || existingEp?.storageKey || "";

  const isCloudflare = resolvedSourceType === "cloudflare";

  const cleanedData = cleanUndefined({
    ...languageData,
    language: langName,
    languageCode: langCode,
    languageName: langName,
    sourceType: resolvedSourceType,
    storageKey: resolvedStorageKey || undefined,
    playbackId: isCloudflare ? undefined : (finalPlaybackId || undefined),
    muxPlaybackId: isCloudflare ? undefined : (finalPlaybackId || undefined),
    assetId: isCloudflare ? undefined : (finalAssetId || undefined),
    muxAssetId: isCloudflare ? undefined : (finalAssetId || undefined),
    uploadId: isCloudflare ? undefined : (finalUploadId || undefined),
    videoUrl: finalStreamUrl,
    muxPlaybackUrl: isCloudflare ? undefined : (finalPlaybackId ? `https://stream.mux.com/${finalPlaybackId}.m3u8` : undefined),
    subtitlesUrl: languageData.subtitlesUrl || languageData.subtitleUrl || "",
    subtitleStatus: languageData.subtitleStatus || (languageData.subtitlesUrl ? "Ready" : "Pending"),
    subtitleGeneratedAt: languageData.subtitleGeneratedAt || new Date().toISOString(),
    status: languageData.status || "Published"
  });

  const currentLanguages = existingEp?.languages || {};
  const updatedLanguages = cleanUndefined({
    ...currentLanguages,
    [langName]: cleanedData,
    [langCode]: cleanedData
  });

  const chosenDefaultLang = episodeMeta?.defaultLanguage || existingEp?.defaultLanguage || langName;

  const thumbUrl = episodeMeta?.thumbnail || episodeMeta?.thumbnailUrl || existingEp?.thumbnail || existingEp?.thumbnailUrl || (!isCloudflare && finalPlaybackId ? `https://image.mux.com/${finalPlaybackId}/thumbnail.jpg?time=15` : "");

  const finalEp: Episode = {
    id: docRef.id || epId,
    animeId,
    animeTitle: episodeMeta?.animeTitle || existingEp?.animeTitle || "",
    seasonNumber: episodeMeta?.seasonNumber || existingEp?.seasonNumber || 1,
    episodeNumber,
    title: episodeMeta?.title || existingEp?.title || `Episode ${episodeNumber}`,
    description: episodeMeta?.description || existingEp?.description || `Watch Episode ${episodeNumber} streaming in HD.`,
    thumbnail: thumbUrl,
    thumbnailUrl: thumbUrl,
    releaseDate: episodeMeta?.releaseDate || existingEp?.releaseDate || new Date().toISOString().split("T")[0],
    duration: episodeMeta?.duration || existingEp?.duration || "24 min",
    sourceType: resolvedSourceType,
    storageKey: resolvedStorageKey || undefined,
    videoUrl: finalStreamUrl,
    hlsUrl: finalStreamUrl,
    status: cleanedData.status || existingEp?.status || "Published",
    defaultLanguage: chosenDefaultLang as any,
    languages: updatedLanguages,
    // Cloudflare R2 Database Structure
    source: isCloudflare ? {
      type: "cloudflare",
      provider: "cloudflare_r2"
    } : (existingEp?.source || undefined),
    storage: (isCloudflare && resolvedStorageKey) ? {
      bucket: "anime-videos",
      objectKey: resolvedStorageKey
    } : (existingEp?.storage || undefined),
    playback: isCloudflare ? {
      provider: "cloudflare_worker",
      url: finalStreamUrl
    } : (existingEp?.playback || undefined),
    file: languageData.file || episodeMeta?.file || existingEp?.file || (languageData.fileName ? {
      name: languageData.fileName,
      contentType: languageData.contentType || "video/mp4",
      size: languageData.fileSize || 0
    } : undefined),
    upload: languageData.upload || episodeMeta?.upload || existingEp?.upload || (languageData.uploadSessionId || languageData.uploadId ? {
      uploadSessionId: languageData.uploadSessionId,
      uploadId: languageData.uploadId
    } : undefined),
    assetId: isCloudflare ? undefined : (finalAssetId || undefined),
    muxAssetId: isCloudflare ? undefined : (finalAssetId || undefined),
    playbackId: isCloudflare ? undefined : (finalPlaybackId || undefined),
    muxPlaybackId: isCloudflare ? undefined : (finalPlaybackId || undefined),
    muxPlaybackUrl: isCloudflare ? undefined : (finalPlaybackId ? `https://stream.mux.com/${finalPlaybackId}.m3u8` : undefined),
    uploadId: isCloudflare ? undefined : (finalUploadId || undefined),
    muxStatus: isCloudflare ? undefined : (cleanedData.muxStatus || existingEp?.muxStatus || "Ready"),
    subtitlesUrl: cleanedData.subtitlesUrl || existingEp?.subtitlesUrl || "",
    isVisible: true,
    hasMultipleAudio: episodeMeta?.hasMultipleAudio ?? existingEp?.hasMultipleAudio ?? false,
    audioTracks: episodeMeta?.audioTracks || existingEp?.audioTracks || [],
    availableAudioTracks: episodeMeta?.availableAudioTracks || existingEp?.availableAudioTracks || [],
    defaultAudio: episodeMeta?.defaultAudio || existingEp?.defaultAudio || "Japanese"
  };

  await setDoc(docRef, cleanUndefined({
    ...finalEp,
    updatedAt: serverTimestamp(),
    createdAt: existingEp ? undefined : serverTimestamp()
  }), { merge: true });

  console.log("Episode saved successfully");

  return finalEp;
}

// --- Firestore Upload Sessions Repository ---
export async function saveFirestoreUploadSession(session: any): Promise<void> {
  if (!session || !session.uploadSessionId) return;
  try {
    const sessionRef = doc(db, "uploadSessions", session.uploadSessionId);
    await setDoc(sessionRef, cleanUndefined({
      ...session,
      updatedAt: serverTimestamp(),
      createdAt: session.createdAt || serverTimestamp()
    }), { merge: true });
  } catch (err) {
    console.warn("[Firestore Upload Session] Save error:", err);
  }
}

export async function getFirestoreUploadSession(uploadSessionId: string): Promise<any | null> {
  if (!uploadSessionId) return null;
  try {
    const sessionRef = doc(db, "uploadSessions", uploadSessionId);
    const snap = await getDoc(sessionRef);
    if (snap.exists()) {
      return snap.data();
    }
    return null;
  } catch (err) {
    console.warn("[Firestore Upload Session] Get error:", err);
    return null;
  }
}

export async function findFirestoreUploadSession(
  animeId: string,
  seasonNumber: number,
  episodeNumber: number,
  language: string,
  fileFingerprint?: string
): Promise<any | null> {
  try {
    const sessionRef = collection(db, "uploadSessions");
    const q = query(
      sessionRef,
      where("animeId", "==", animeId),
      where("seasonNumber", "==", seasonNumber),
      where("episodeNumber", "==", episodeNumber),
      where("language", "==", language)
    );
    const snap = await getDocs(q);
    if (snap.empty) return null;

    const sessions = snap.docs.map(d => d.data());
    if (fileFingerprint) {
      const match = sessions.find(s => s.fileFingerprint === fileFingerprint && s.status !== "failed");
      if (match) return match;
    }
    return sessions[0] || null;
  } catch (err) {
    console.warn("[Firestore Upload Session] Query error:", err);
    return null;
  }
}

export async function updateFirestoreUploadSessionStatus(
  uploadSessionId: string,
  status: string,
  completedParts?: number
): Promise<void> {
  if (!uploadSessionId) return;
  try {
    const sessionRef = doc(db, "uploadSessions", uploadSessionId);
    const updateData: any = { status, updatedAt: serverTimestamp() };
    if (completedParts !== undefined) {
      updateData.completedParts = completedParts;
    }
    await updateDoc(sessionRef, cleanUndefined(updateData));
  } catch (err) {
    console.warn("[Firestore Upload Session] Status update error:", err);
  }
}

export async function deleteEpisodeLanguage(
  episodeId: string,
  languageNameOrCode: string
): Promise<Episode | null> {
  const docRef = doc(db, "episodes", episodeId);
  const snap = await getDoc(docRef);
  if (!snap.exists()) return null;

  const ep = { id: snap.id, ...snap.data() } as Episode;
  const currentLanguages = { ...(ep.languages || {}) };

  const { code: langCode, name: langName } = getLanguageMetadata(languageNameOrCode);

  delete currentLanguages[langName];
  delete currentLanguages[langCode];
  delete currentLanguages[languageNameOrCode];

  // If deleting default language, pick remaining language if available
  const remainingKeys = Object.keys(currentLanguages);
  const newDefaultLang = currentLanguages[ep.defaultLanguage || ""]
    ? ep.defaultLanguage
    : (remainingKeys[0] ? getLanguageMetadata(remainingKeys[0]).name : "Japanese");

  const activeLang = currentLanguages[newDefaultLang || "Japanese"] || (remainingKeys[0] ? currentLanguages[remainingKeys[0]] : null);

  const updatedEp: Episode = {
    ...ep,
    languages: currentLanguages,
    defaultLanguage: (newDefaultLang || "Japanese") as any,
    videoUrl: activeLang?.videoUrl || activeLang?.muxPlaybackUrl || ep.videoUrl,
    hlsUrl: activeLang?.hlsUrl || activeLang?.muxPlaybackUrl || ep.hlsUrl,
    muxAssetId: activeLang?.muxAssetId || ep.muxAssetId,
    muxPlaybackId: activeLang?.muxPlaybackId || ep.muxPlaybackId,
    muxPlaybackUrl: activeLang?.muxPlaybackUrl || ep.muxPlaybackUrl,
    subtitlesUrl: activeLang?.subtitlesUrl || ep.subtitlesUrl
  };

  await setDoc(docRef, cleanUndefined(updatedEp), { merge: true });
  return updatedEp;
}

export async function fetchAllEpisodes(): Promise<Episode[]> {
  const epRef = collection(db, "episodes");
  const snapshot = await getDocs(epRef);
  return snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() } as Episode));
}

export async function deleteEpisode(id: string): Promise<void> {
  // Retrieve episode doc first to fetch animeId & episodeNumber for cleanup
  const docRef = doc(db, "episodes", id);
  const snap = await getDoc(docRef);
  if (snap.exists()) {
    const epData = snap.data() as Episode;
    if (epData.animeId && epData.episodeNumber) {
      try {
        const histRef = collection(db, "watchHistory");
        const q = query(
          histRef, 
          where("animeId", "==", epData.animeId), 
          where("episodeNumber", "==", epData.episodeNumber)
        );
        const histSnap = await getDocs(q);
        for (const hDoc of histSnap.docs) {
          await deleteDoc(hDoc.ref);
        }
      } catch (err) {
        console.warn("Non-fatal issue deleting episode watchHistory:", err);
      }
    }
  }
  await deleteDoc(docRef);
}

// --- Watch History & Continue Watching ---
export async function updateWatchProgress(
  userId: string, 
  animeId: string, 
  episodeNumber: number, 
  position: number, 
  duration: number
): Promise<void> {
  const id = `${userId}_${animeId}_${episodeNumber}`;
  const nowIso = new Date().toISOString();

  // 1. Instant local storage cache
  try {
    const raw = localStorage.getItem(`animestream_history_${userId}`);
    const localList: WatchHistory[] = raw ? JSON.parse(raw) : [];
    const idx = localList.findIndex(h => h.animeId === animeId && h.episodeNumber === episodeNumber);
    const item: WatchHistory = {
      id,
      userId,
      animeId,
      episodeNumber,
      position,
      duration,
      updatedAt: nowIso as any
    };
    if (idx >= 0) {
      localList[idx] = item;
    } else {
      localList.unshift(item);
    }
    localStorage.setItem(`animestream_history_${userId}`, JSON.stringify(localList));
  } catch (e) {
    console.warn("LocalStorage save error:", e);
  }

  // 2. Persist to Firestore
  try {
    const progressRef = doc(db, "watchHistory", id);
    await setDoc(progressRef, {
      id,
      userId,
      animeId,
      episodeNumber,
      position,
      duration,
      updatedAt: serverTimestamp()
    });
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
  } catch (e) {}

  let dbList: WatchHistory[] = [];
  try {
    const historyRef = collection(db, "watchHistory");
    const q = query(historyRef, where("userId", "==", userId));
    const snapshot = await getDocs(q);
    dbList = snapshot.docs.map(doc => doc.data() as WatchHistory);
  } catch (e) {
    console.warn("Firestore fetch error, using local fallback:", e);
  }

  const map = new Map<string, WatchHistory>();
  for (const item of [...localList, ...dbList]) {
    const key = `${item.animeId}_${item.episodeNumber}`;
    const existing = map.get(key);
    if (!existing) {
      map.set(key, item);
    } else {
      const tNew = getTimestampMs(item.updatedAt);
      const tOld = getTimestampMs(existing.updatedAt);
      if (tNew >= tOld) {
        map.set(key, item);
      }
    }
  }

  const combined = Array.from(map.values());
  return combined.sort((a, b) => getTimestampMs(b.updatedAt) - getTimestampMs(a.updatedAt));
}

export async function clearWatchHistory(userId: string): Promise<void> {
  const historyRef = collection(db, "watchHistory");
  const q = query(historyRef, where("userId", "==", userId));
  const snapshot = await getDocs(q);
  for (const itemDoc of snapshot.docs) {
    await deleteDoc(itemDoc.ref);
  }
}

// --- Watchlist Repository ---
export async function toggleWatchlist(userId: string, animeId: string, isAdded: boolean): Promise<void> {
  const id = `${userId}_${animeId}`;
  const ref = doc(db, "watchlist", id);
  if (isAdded) {
    await setDoc(ref, {
      userId,
      animeId,
      addedAt: serverTimestamp()
    });
  } else {
    await deleteDoc(ref);
  }
}

export async function fetchUserWatchlist(userId: string): Promise<string[]> {
  const ref = collection(db, "watchlist");
  const q = query(ref, where("userId", "==", userId));
  const snapshot = await getDocs(q);
  return snapshot.docs.map(doc => (doc.data() as Watchlist).animeId);
}

// --- Favorites Repository ---
export async function toggleFavorite(userId: string, animeId: string, isAdded: boolean): Promise<void> {
  const id = `${userId}_${animeId}`;
  const ref = doc(db, "favorites", id);
  if (isAdded) {
    await setDoc(ref, {
      userId,
      animeId,
      addedAt: serverTimestamp()
    });
  } else {
    await deleteDoc(ref);
  }
}

export async function fetchUserFavorites(userId: string): Promise<string[]> {
  const ref = collection(db, "favorites");
  const q = query(ref, where("userId", "==", userId));
  const snapshot = await getDocs(q);
  return snapshot.docs.map(doc => (doc.data() as Favorite).animeId);
}

// --- User Profile / Role management ---
export async function saveUserProfile(profile: UserProfile): Promise<void> {
  await setDoc(doc(db, "users", profile.uid), cleanUndefined(profile));
}

export async function fetchUserProfile(uid: string): Promise<UserProfile | null> {
  const docRef = doc(db, "users", uid);
  const snap = await getDoc(docRef);
  if (snap.exists()) {
    return snap.data() as UserProfile;
  }
  return null;
}

export async function fetchAllUsers(): Promise<UserProfile[]> {
  const ref = collection(db, "users");
  const snap = await getDocs(ref);
  return snap.docs.map(d => d.data() as UserProfile);
}

export async function updateUserRole(uid: string, role: string): Promise<void> {
  const ref = doc(db, "users", uid);
  await updateDoc(ref, { role });
}

export async function deleteUserProfile(uid: string): Promise<void> {
  await deleteDoc(doc(db, "users", uid));
}

// --- Comments System ---
export function subscribeToComments(animeId: string, callback: (comments: Comment[]) => void) {
  const ref = collection(db, "comments");
  const q = query(ref, where("animeId", "==", animeId));
  return onSnapshot(q, (snapshot) => {
    const comments = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() } as Comment));
    
    // Sort client-side to prevent Firestore composite index requirement
    comments.sort((a, b) => {
      const tA = getTimestampMs(a.createdAt);
      const tB = getTimestampMs(b.createdAt);
      return tB - tA; // descending
    });
    
    callback(comments);
  });
}

export async function addComment(animeId: string, episodeNumber: number | undefined, userId: string, userName: string, text: string, userPhoto?: string): Promise<void> {
  await addDoc(collection(db, "comments"), {
    animeId,
    episodeNumber: episodeNumber || null,
    userId,
    userName,
    userPhoto: userPhoto || "",
    text,
    createdAt: serverTimestamp()
  });
}

export async function deleteComment(id: string): Promise<void> {
  await deleteDoc(doc(db, "comments", id));
}

// --- Ratings System ---
export async function rateAnime(userId: string, animeId: string, score: number): Promise<void> {
  const id = `${userId}_${animeId}`;
  await setDoc(doc(db, "ratings", id), {
    id,
    userId,
    animeId,
    score,
    updatedAt: serverTimestamp()
  });
}

export async function fetchAnimeRatings(animeId: string): Promise<number> {
  const ref = collection(db, "ratings");
  const q = query(ref, where("animeId", "==", animeId));
  const snap = await getDocs(q);
  if (snap.empty) return 0;
  
  const scores = snap.docs.map(d => (d.data() as Rating).score);
  const sum = scores.reduce((a, b) => a + b, 0);
  return parseFloat((sum / scores.length).toFixed(1));
}

export async function fetchUserRating(userId: string, animeId: string): Promise<number | null> {
  const id = `${userId}_${animeId}`;
  const snap = await getDoc(doc(db, "ratings", id));
  if (snap.exists()) {
    return (snap.data() as Rating).score;
  }
  return null;
}

// --- Notifications System ---
export async function fetchNotifications(): Promise<Notification[]> {
  const ref = collection(db, "notifications");
  const q = query(ref, orderBy("createdAt", "desc"), limit(20));
  const snap = await getDocs(q);
  return snap.docs.map(d => ({ id: d.id, ...d.data() } as Notification));
}

export async function addNotification(title: string, message: string, type: "episode" | "anime" | "system" | "maintenance"): Promise<void> {
  await addDoc(collection(db, "notifications"), {
    title,
    message,
    type,
    createdAt: serverTimestamp()
  });
}

// --- Duplicate Episode Protection & Version History (Requirements 8 & 9) ---
export async function checkDuplicateEpisode(
  animeId: string, 
  seasonNumber: number, 
  episodeNumber: number
): Promise<{ exists: boolean; existingEpisode?: Episode }> {
  try {
    const epRef = collection(db, "episodes");
    const q = query(
      epRef, 
      where("animeId", "==", animeId),
      where("episodeNumber", "==", episodeNumber)
    );
    const snap = await getDocs(q);
    const matches = snap.docs.map(d => ({ id: d.id, ...d.data() } as Episode));
    const found = matches.find(e => (e.seasonNumber || 1) === seasonNumber);
    if (found) {
      return { exists: true, existingEpisode: found };
    }
  } catch (e) {
    console.warn("Duplicate episode check error:", e);
  }
  return { exists: false };
}

export async function recordEpisodeVersionHistory(historyItem: Partial<VersionHistoryItem>): Promise<void> {
  try {
    await addDoc(collection(db, "episodeVersionHistory"), cleanUndefined({
      ...historyItem,
      editedAt: historyItem.editedAt || new Date().toISOString(),
      createdAt: serverTimestamp()
    }));
  } catch (e) {
    console.warn("Failed to record version history:", e);
  }
}

export async function fetchEpisodeVersionHistory(episodeId?: string): Promise<VersionHistoryItem[]> {
  try {
    const ref = collection(db, "episodeVersionHistory");
    const q = episodeId 
      ? query(ref, where("episodeId", "==", episodeId))
      : query(ref, orderBy("createdAt", "desc"), limit(50));
    const snap = await getDocs(q);
    return snap.docs.map(d => ({ id: d.id, ...d.data() } as VersionHistoryItem));
  } catch (e) {
    console.warn("Failed to fetch version history:", e);
    return [];
  }
}

// --- GLOBAL API SYNC & DATABASE INTEGRITY DOCTOR (Requirements 3, 7, 8 & 9) ---

export async function syncSingleAnimeMetadataAndSeasons(animeId: string): Promise<{
  success: boolean;
  message: string;
  updatedAnime?: Anime;
}> {
  try {
    const animeRef = doc(db, "anime", animeId);
    const animeSnap = await getDoc(animeRef);
    if (!animeSnap.exists()) {
      return { success: false, message: `Anime document with ID ${animeId} not found.` };
    }

    const currentAnime = { id: animeSnap.id, ...animeSnap.data() } as Anime;
    const searchTitle = getDisplayTitle(currentAnime);
    if (!searchTitle || searchTitle === "Untitled Anime") {
      return { success: false, message: "Anime has no valid title to sync from API." };
    }

    const suggestions = await searchAnimeSuggestions(searchTitle);
    if (!suggestions || suggestions.length === 0) {
      return { success: false, message: `No API results found for "${searchTitle}".` };
    }

    const freshMeta = await compileFullAnimeMetadata(suggestions[0]);
    if (!freshMeta) {
      return { success: false, message: "Failed to compile metadata from API." };
    }

    // Preserve existing manual title or custom overrides if present
    const safeTitle = (typeof currentAnime.title === "string" && currentAnime.title.trim()) ? currentAnime.title : freshMeta.title;
    const safeEnglishTitle = (typeof currentAnime.englishTitle === "string" && currentAnime.englishTitle.trim()) ? currentAnime.englishTitle : freshMeta.englishTitle;
    const safePoster = currentAnime.poster || freshMeta.poster;
    const safeBanner = currentAnime.banner || freshMeta.banner;

    const mergedAnime: Anime = {
      ...freshMeta,
      ...currentAnime,
      title: safeTitle,
      englishTitle: safeEnglishTitle,
      poster: safePoster,
      banner: safeBanner,
      totalEpisodes: Math.max(currentAnime.totalEpisodes || 0, freshMeta.totalEpisodes || 12),
      episodesCount: Math.max(currentAnime.episodesCount || 0, freshMeta.episodesCount || 12),
      lastUpdated: Date.now()
    };

    await saveAnime(mergedAnime);

    // Re-verify all episodes for this anime and ensure seasonNumber is set
    const eps = await fetchEpisodesForAnime(animeId);
    for (const ep of eps) {
      if (!ep.seasonNumber || ep.seasonNumber < 1) {
        await saveEpisode({
          ...ep,
          seasonNumber: 1
        });
      }
    }

    return {
      success: true,
      message: `Successfully re-synced metadata & seasons for "${safeTitle}" (Official Episode Count: ${mergedAnime.totalEpisodes}).`,
      updatedAnime: mergedAnime
    };
  } catch (err: any) {
    console.error("syncSingleAnimeMetadataAndSeasons error:", err);
    return { success: false, message: err.message || "Sync failed due to an unknown error." };
  }
}

export async function syncAllAnimeCatalog(): Promise<{
  totalSynced: number;
  results: { animeTitle: string; message: string }[];
}> {
  const allAnime = await fetchAllAnime();
  const results: { animeTitle: string; message: string }[] = [];
  let totalSynced = 0;

  for (const anime of allAnime) {
    const res = await syncSingleAnimeMetadataAndSeasons(anime.id);
    if (res.success) totalSynced++;
    results.push({
      animeTitle: anime.title || anime.id,
      message: res.message
    });
  }

  return { totalSynced, results };
}

export async function validateAndRepairDatabaseInvariants(): Promise<{
  totalCheckedEpisodes: number;
  repairedEpisodesCount: number;
  duplicateMergedCount: number;
  fixedSeasonMappingsCount: number;
}> {
  const allAnime = await fetchAllAnime();
  const validAnimeIds = new Set(allAnime.map(a => a.id));
  const allEps = await fetchAllEpisodes();

  let repairedEpisodesCount = 0;
  let duplicateMergedCount = 0;
  let fixedSeasonMappingsCount = 0;

  const episodeGroupMap = new Map<string, Episode[]>();

  for (const ep of allEps) {
    let updatedEp = { ...ep };
    if (!updatedEp.seasonNumber || updatedEp.seasonNumber < 1) {
      updatedEp.seasonNumber = 1;
      fixedSeasonMappingsCount++;
    }

    const key = `${updatedEp.animeId}_s${updatedEp.seasonNumber}_ep${updatedEp.episodeNumber}`;
    const list = episodeGroupMap.get(key) || [];
    list.push(updatedEp);
    episodeGroupMap.set(key, list);
  }

  for (const [key, group] of episodeGroupMap.entries()) {
    if (group.length === 1) {
      const single = group[0];
      const orig = allEps.find(e => e.id === single.id);
      if (orig && orig.seasonNumber !== single.seasonNumber) {
        await saveEpisode(single);
        repairedEpisodesCount++;
      }
    } else if (group.length > 1) {
      const primary = group.find(e => (e.sourceType === "cloudflare" && e.videoUrl) || (e.muxPlaybackId && !e.muxPlaybackId.startsWith("upload_"))) || group[0];
      
      let mergedPlaybackId = primary.muxPlaybackId || "";
      let mergedVideoUrl = primary.videoUrl || "";
      let mergedSubtitlesUrl = primary.subtitlesUrl || "";
      let mergedThumbnail = primary.thumbnail || "";
      let mergedStorageKey = primary.storageKey || "";

      for (const dupe of group) {
        if (!mergedPlaybackId && dupe.muxPlaybackId && !dupe.muxPlaybackId.startsWith("upload_")) {
          mergedPlaybackId = dupe.muxPlaybackId;
        }
        if (!mergedVideoUrl && dupe.videoUrl) mergedVideoUrl = dupe.videoUrl;
        if (!mergedSubtitlesUrl && dupe.subtitlesUrl) mergedSubtitlesUrl = dupe.subtitlesUrl;
        if (!mergedThumbnail && dupe.thumbnail) mergedThumbnail = dupe.thumbnail;
        if (!mergedStorageKey && dupe.storageKey) mergedStorageKey = dupe.storageKey;
      }

      const isCloudflare = primary.sourceType === "cloudflare" || !!mergedStorageKey;

      const cleanMasterEp: Episode = {
        ...primary,
        seasonNumber: primary.seasonNumber || 1,
        sourceType: isCloudflare ? "cloudflare" : (primary.sourceType || "mux"),
        storageKey: mergedStorageKey || undefined,
        muxPlaybackId: isCloudflare ? undefined : (mergedPlaybackId || undefined),
        playbackId: isCloudflare ? undefined : (mergedPlaybackId || undefined),
        videoUrl: mergedVideoUrl,
        hlsUrl: mergedVideoUrl,
        subtitlesUrl: mergedSubtitlesUrl,
        thumbnail: mergedThumbnail,
        thumbnailUrl: mergedThumbnail
      };

      await saveEpisode(cleanMasterEp);
      repairedEpisodesCount++;

      for (const dupe of group) {
        if (dupe.id !== primary.id) {
          await deleteEpisode(dupe.id);
          duplicateMergedCount++;
        }
      }
    }
  }

  return {
    totalCheckedEpisodes: allEps.length,
    repairedEpisodesCount,
    duplicateMergedCount,
    fixedSeasonMappingsCount
  };
}
