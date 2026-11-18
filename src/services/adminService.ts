import { Episode, Anime } from "../types";
import { saveEpisode, deleteEpisode } from "./dbService";

export interface AdminActivityLogItem {
  id: string;
  timestamp: number;
  actor: string;
  action: string;
  details: string;
  affectedCount?: number;
  status: "Success" | "Warning" | "Error";
}

const ACTIVITY_LOG_KEY = "animestream_admin_activity_logs_v1";

export function getAdminActivityLogs(): AdminActivityLogItem[] {
  return [];
}

export function logAdminAction(
  action: string,
  details: string,
  actor: string = "Admin",
  affectedCount: number = 1,
  status: "Success" | "Warning" | "Error" = "Success"
): AdminActivityLogItem {
  return {
    id: "disabled",
    timestamp: Date.now(),
    actor: "Admin",
    action,
    details,
    affectedCount,
    status
  };
}

export function clearAdminActivityLogs(): void {
  // No-op
}

export interface ConflictCheckResult {
  hasConflicts: boolean;
  conflictingEpisodes: {
    episodeId: string;
    targetSeason: number;
    targetEpNum: number;
    existingEpisodeId?: string;
    existingTitle?: string;
  }[];
}

export function checkEpisodeConflicts(
  episodesToMoveOrRenumber: Episode[],
  targetSeason: number,
  startFromEpNum?: number,
  allCatalogEpisodes: Episode[] = []
): ConflictCheckResult {
  const conflicts: ConflictCheckResult["conflictingEpisodes"] = [];

  for (let idx = 0; idx < episodesToMoveOrRenumber.length; idx++) {
    const ep = episodesToMoveOrRenumber[idx];
    const targetEpNum = startFromEpNum !== undefined ? startFromEpNum + idx : ep.episodeNumber;

    // Search existing episodes in the same anime and target season
    const match = allCatalogEpisodes.find(
      (e) =>
        e.animeId === ep.animeId &&
        (e.seasonNumber || 1) === targetSeason &&
        e.episodeNumber === targetEpNum &&
        e.id !== ep.id // exclude self
    );

    if (match) {
      conflicts.push({
        episodeId: ep.id,
        targetSeason,
        targetEpNum,
        existingEpisodeId: match.id,
        existingTitle: match.title
      });
    }
  }

  return {
    hasConflicts: conflicts.length > 0,
    conflictingEpisodes: conflicts
  };
}

export type ConflictStrategy = "replace" | "skip" | "auto_renumber" | "cancel";

export async function executeBulkMoveSeason(
  selectedEpisodes: Episode[],
  targetSeason: number,
  allCatalogEpisodes: Episode[],
  strategy: ConflictStrategy = "auto_renumber",
  actorEmail: string = "Admin"
): Promise<{ updatedCount: number; skippedCount: number }> {
  if (selectedEpisodes.length === 0) return { updatedCount: 0, skippedCount: 0 };

  let updatedCount = 0;
  let skippedCount = 0;

  // Group by anime
  const animeMap = new Map<string, Episode[]>();
  for (const ep of selectedEpisodes) {
    const list = animeMap.get(ep.animeId) || [];
    list.push(ep);
    animeMap.set(ep.animeId, list);
  }

  for (const [animeId, eps] of animeMap.entries()) {
    const catalogInAnimeAndTargetSeason = allCatalogEpisodes.filter(
      (e) => e.animeId === animeId && (e.seasonNumber || 1) === targetSeason
    );

    // Existing numbers set
    const occupiedNumbers = new Set(catalogInAnimeAndTargetSeason.map((e) => e.episodeNumber));

    // Determine current max ep in target season if auto renumbering
    let nextAvailableEpNum = catalogInAnimeAndTargetSeason.reduce(
      (max, e) => (e.episodeNumber > max ? e.episodeNumber : max),
      0
    ) + 1;

    for (const ep of eps) {
      let finalEpNum = ep.episodeNumber;

      if (occupiedNumbers.has(finalEpNum)) {
        if (strategy === "skip") {
          skippedCount++;
          continue;
        } else if (strategy === "auto_renumber") {
          finalEpNum = nextAvailableEpNum++;
        }
        // strategy "replace" keeps finalEpNum, overwriting metadata for that number
      }

      occupiedNumbers.add(finalEpNum);

      const updatedEp: Episode = {
        ...ep,
        seasonNumber: targetSeason,
        episodeNumber: finalEpNum
      };

      await saveEpisode(updatedEp);
      updatedCount++;
    }
  }

  logAdminAction(
    "Bulk Move Season",
    `Moved ${updatedCount} episode(s) to Season ${targetSeason}. (${skippedCount} skipped)`,
    actorEmail,
    updatedCount
  );

  return { updatedCount, skippedCount };
}

export async function executeBulkRenumber(
  selectedEpisodes: Episode[],
  startFrom: number,
  actorEmail: string = "Admin"
): Promise<number> {
  if (selectedEpisodes.length === 0) return 0;

  // Sort by current season & episode number to preserve chronological sequence
  const sorted = [...selectedEpisodes].sort((a, b) => {
    const sA = a.seasonNumber || 1;
    const sB = b.seasonNumber || 1;
    if (sA !== sB) return sA - sB;
    return a.episodeNumber - b.episodeNumber;
  });

  let count = 0;
  for (let i = 0; i < sorted.length; i++) {
    const ep = sorted[i];
    const newEpNum = startFrom + i;
    const updatedEp: Episode = {
      ...ep,
      episodeNumber: newEpNum,
      title: ep.title.startsWith("Episode ") ? `Episode ${newEpNum}` : ep.title
    };
    await saveEpisode(updatedEp);
    count++;
  }

  logAdminAction(
    "Bulk Renumber Episodes",
    `Renumbered ${count} episode(s) starting from Episode ${startFrom}`,
    actorEmail,
    count
  );

  return count;
}

export async function executeBatchEditEpisodes(
  selectedEpisodes: Episode[],
  edits: {
    seasonNumber?: number;
    language?: string;
    isVisible?: boolean;
    isPremium?: boolean;
    status?: "Draft" | "Waiting For Encoding" | "Published";
    releaseDate?: string;
    notes?: string;
  },
  actorEmail: string = "Admin"
): Promise<number> {
  if (selectedEpisodes.length === 0) return 0;

  let count = 0;
  for (const ep of selectedEpisodes) {
    const updatedEp: Episode = {
      ...ep,
      ...(edits.seasonNumber !== undefined ? { seasonNumber: edits.seasonNumber } : {}),
      ...(edits.language ? { defaultLanguage: edits.language as any } : {}),
      ...(edits.isVisible !== undefined ? { isVisible: edits.isVisible } : {}),
      ...(edits.isPremium !== undefined ? { isPremium: edits.isPremium } : {}),
      ...(edits.status ? { status: edits.status } : {}),
      ...(edits.releaseDate ? { releaseDate: edits.releaseDate } : {}),
      ...(edits.notes !== undefined ? { notes: edits.notes } : {})
    };

    await saveEpisode(updatedEp);
    count++;
  }

  logAdminAction(
    "Batch Edit Episodes",
    `Updated ${count} episode(s) metadata (Status: ${edits.status || "Unchanged"}, Visibility: ${edits.isVisible ?? "Unchanged"})`,
    actorEmail,
    count
  );

  return count;
}

export async function executeSafeDeleteEpisodes(
  selectedEpisodes: Episode[],
  deleteStorageObject: boolean = false,
  actorEmail: string = "Admin"
): Promise<number> {
  if (selectedEpisodes.length === 0) return 0;

  let count = 0;
  for (const ep of selectedEpisodes) {
    // Optionally delete Cloudflare R2 object if requested and key exists
    const targetStorageKey = ep.storageKey || (ep as any).assetId;
    if (deleteStorageObject && targetStorageKey) {
      try {
        await fetch(`/api/cloudflare/delete?key=${encodeURIComponent(targetStorageKey)}`, { method: "DELETE" });
      } catch (err) {
        console.warn(`Could not delete Cloudflare R2 Object ${targetStorageKey}:`, err);
      }
    }

    await deleteEpisode(ep.id);
    count++;
  }

  logAdminAction(
    "Safe Delete Episodes",
    `Permanently deleted ${count} episode(s) ${deleteStorageObject ? "(and attempted Cloudflare R2 deletion)" : "(Database only)"}`,
    actorEmail,
    count,
    "Warning"
  );

  return count;
}

export interface MissingEpisodeGap {
  animeId: string;
  animeTitle: string;
  poster?: string;
  seasonNumber: number;
  missingNumbers: number[];
}

export function detectMissingEpisodesInCatalog(
  animeList: Anime[],
  allEpisodes: Episode[]
): MissingEpisodeGap[] {
  const gaps: MissingEpisodeGap[] = [];

  for (const anime of animeList) {
    const animeEps = allEpisodes.filter((e) => e.animeId === anime.id);
    const officialTotal = anime.totalEpisodes || anime.episodesCount || 0;

    // Group by season
    const seasonMap = new Map<number, number[]>();
    for (const ep of animeEps) {
      const s = ep.seasonNumber || 1;
      const list = seasonMap.get(s) || [];
      list.push(ep.episodeNumber);
      seasonMap.set(s, list);
    }

    // Ensure season 1 is checked even if 0 episodes exist
    if (seasonMap.size === 0 && officialTotal > 0) {
      seasonMap.set(1, []);
    }

    for (const [seasonNum, numbers] of seasonMap.entries()) {
      const sortedNums = [...numbers].sort((a, b) => a - b);
      const maxUploaded = sortedNums.length > 0 ? Math.max(...sortedNums) : 0;
      
      // Target count is at least official total or max uploaded number
      const targetLimit = Math.max(maxUploaded, officialTotal > 0 ? officialTotal : 12);

      const missing: number[] = [];
      const numSet = new Set(sortedNums);

      for (let n = 1; n <= targetLimit; n++) {
        if (!numSet.has(n)) {
          missing.push(n);
        }
      }

      if (missing.length > 0) {
        gaps.push({
          animeId: anime.id,
          animeTitle: anime.title,
          poster: anime.poster,
          seasonNumber: seasonNum,
          missingNumbers: missing
        });
      }
    }
  }

  return gaps;
}
