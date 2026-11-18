/**
 * Resumable Video Upload Session Store
 * Uses IndexedDB to store upload sessions locally.
 * Supports file fingerprinting, session persistence, and recovery.
 */

export interface SavedUploadSession {
  fingerprint: string; // ${filename}_${filesize}_${lastModified}
  uploadId: string;
  uploadUrl?: string;
  storageKey?: string;
  assetId?: string;
  filename: string;
  filesize: number;
  lastModified: number;
  lastUploadedByte: number;
  uploadPercentage: number;
  timestamp: number;
  status: "uploading" | "paused" | "waiting_for_network" | "completed" | "failed";
  // Metadata fields to rebuild queue item on browser restore
  animeId: string;
  animeTitle: string;
  animePoster?: string;
  animeBanner?: string;
  seasonNum: number;
  episodeNum: number;
  episodeTitle: string;
  episodeDesc: string;
  episodeThumbnail: string;
  language: string;
  quality: string;
  notes: string;
  subtitlesUrl: string;
  releaseDate: string;
  isVisible: boolean;
  skipIntroStart?: number;
  skipIntroEnd?: number;
  skipOutroStart?: number;
  skipOutroEnd?: number;
}

const DB_NAME = "AnimeStreamResumableUploadsDB";
const DB_VERSION = 1;
const STORE_NAME = "r2_upload_sessions";

// Chunk size: 8 MB (8 * 1024 * 1024 bytes = 8,388,608 bytes)
export const RESUMABLE_CHUNK_SIZE = 8 * 1024 * 1024;

/**
 * Generate a unique fingerprint for a file
 */
export function getFileFingerprint(file: File): string {
  const sanitizedName = file.name.replace(/\s+/g, "_");
  return `${sanitizedName}_${file.size}_${file.lastModified}`;
}

/**
 * Open or initialize IndexedDB
 */
function openDB(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (typeof window === "undefined" || !window.indexedDB) {
      reject(new Error("IndexedDB is not supported in this environment"));
      return;
    }
    const request = indexedDB.open(DB_NAME, DB_VERSION);

    request.onupgradeneeded = (event) => {
      const db = (event.target as IDBOpenDBRequest).result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        const store = db.createObjectStore(STORE_NAME, { keyPath: "uploadId" });
        store.createIndex("fingerprint", "fingerprint", { unique: false });
        store.createIndex("status", "status", { unique: false });
      }
    };

    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

/**
 * Save or update an upload session in IndexedDB and LocalStorage
 */
export async function saveUploadSession(session: SavedUploadSession): Promise<void> {
  try {
    const db = await openDB();
    const tx = db.transaction(STORE_NAME, "readwrite");
    const store = tx.objectStore(STORE_NAME);
    store.put(session);
    await new Promise((res, rej) => {
      tx.oncomplete = res;
      tx.onerror = rej;
    });
  } catch (err) {
    console.warn("[ResumableUpload] Failed to save session to IndexedDB:", err);
  }

  // Backup key metadata in localStorage for quick synchronous reads
  try {
    localStorage.setItem(
      `r2_resumable_${session.uploadId}`,
      JSON.stringify({
        uploadId: session.uploadId,
        fingerprint: session.fingerprint,
        storageKey: session.storageKey,
        filename: session.filename,
        filesize: session.filesize,
        lastUploadedByte: session.lastUploadedByte,
        percentage: session.uploadPercentage,
        timestamp: session.timestamp,
        status: session.status
      })
    );
  } catch (e) {
    // ignore
  }
}

/**
 * Find existing unfinished upload session by file fingerprint
 */
export async function getUploadSessionByFingerprint(fingerprint: string): Promise<SavedUploadSession | null> {
  try {
    const db = await openDB();
    const tx = db.transaction(STORE_NAME, "readonly");
    const store = tx.objectStore(STORE_NAME);
    const index = store.index("fingerprint");
    const request = index.getAll(fingerprint);

    return new Promise((resolve) => {
      request.onsuccess = () => {
        const sessions: SavedUploadSession[] = request.result || [];
        // Filter sessions less than 24 hours old and not marked completed
        const validSession = sessions
          .filter(s => s.status !== "completed" && Date.now() - s.timestamp < 24 * 60 * 60 * 1000)
          .sort((a, b) => b.timestamp - a.timestamp)[0];
        resolve(validSession || null);
      };
      request.onerror = () => resolve(null);
    });
  } catch (err) {
    console.warn("[ResumableUpload] Failed to query IndexedDB by fingerprint:", err);
    return null;
  }
}

/**
 * Get all unfinished upload sessions (for restoring queue on app launch)
 */
export async function getAllUnfinishedSessions(): Promise<SavedUploadSession[]> {
  try {
    const db = await openDB();
    const tx = db.transaction(STORE_NAME, "readonly");
    const store = tx.objectStore(STORE_NAME);
    const request = store.getAll();

    return new Promise((resolve) => {
      request.onsuccess = () => {
        const sessions: SavedUploadSession[] = request.result || [];
        const activeSessions = sessions.filter(
          s => s.status !== "completed" && Date.now() - s.timestamp < 24 * 60 * 60 * 1000
        );
        resolve(activeSessions);
      };
      request.onerror = () => resolve([]);
    });
  } catch (err) {
    console.warn("[ResumableUpload] Failed to get unfinished sessions:", err);
    return [];
  }
}

/**
 * Update session progress
 */
export async function updateSessionProgress(
  uploadId: string,
  lastUploadedByte: number,
  filesize: number,
  status: SavedUploadSession["status"] = "uploading"
): Promise<void> {
  try {
    const db = await openDB();
    const tx = db.transaction(STORE_NAME, "readwrite");
    const store = tx.objectStore(STORE_NAME);
    const getReq = store.get(uploadId);

    getReq.onsuccess = () => {
      const session = getReq.result as SavedUploadSession;
      if (session) {
        session.lastUploadedByte = lastUploadedByte;
        session.uploadPercentage = Math.min(100, Math.round((lastUploadedByte / filesize) * 100));
        session.status = status;
        session.timestamp = Date.now();
        store.put(session);
      }
    };
  } catch (err) {
    // ignore
  }
}

/**
 * Delete session from IndexedDB and LocalStorage after completion
 */
export async function deleteUploadSession(uploadId: string): Promise<void> {
  try {
    const db = await openDB();
    const tx = db.transaction(STORE_NAME, "readwrite");
    const store = tx.objectStore(STORE_NAME);
    store.delete(uploadId);
  } catch (err) {
    console.warn("[ResumableUpload] Delete session failed:", err);
  }

  try {
    localStorage.removeItem(`r2_resumable_${uploadId}`);
  } catch (e) {
    // ignore
  }
}

export interface ActiveUploadController {
  abort: () => void;
  pause: () => void;
  isPaused: () => boolean;
}
