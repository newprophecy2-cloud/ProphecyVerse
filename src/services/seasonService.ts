import { Episode } from "../types";

const SEASON_COUNTS_STORAGE_KEY = "animestream_season_counts_v1";

interface SeasonCountMap {
  [animeId: string]: {
    [seasonNum: number]: number;
  };
}

function getStoredSeasonCounts(): SeasonCountMap {
  try {
    const raw = localStorage.getItem(SEASON_COUNTS_STORAGE_KEY);
    if (!raw) return {};
    return JSON.parse(raw);
  } catch (err) {
    console.error("Failed to parse stored season counts:", err);
    return {};
  }
}

function saveStoredSeasonCounts(map: SeasonCountMap): void {
  try {
    localStorage.setItem(SEASON_COUNTS_STORAGE_KEY, JSON.stringify(map));
  } catch (err) {
    console.error("Failed to save season counts to localStorage:", err);
  }
}

/**
 * Get configured Season Episode Count for an Anime & Season
 */
export function getSeasonEpisodeCount(
  animeId: string,
  seasonNum: number,
  uploadedEpisodes: Episode[] = []
): number {
  if (!animeId) return 12;
  const map = getStoredSeasonCounts();
  const animeSeasons = map[animeId];
  if (animeSeasons && typeof animeSeasons[seasonNum] === "number") {
    // Return saved count, but ensure it's at least as large as the highest uploaded episode number
    const maxUploadedNum = uploadedEpisodes
      .filter((e) => e.animeId === animeId && (e.seasonNumber || 1) === seasonNum)
      .reduce((max, e) => (e.episodeNumber > max ? e.episodeNumber : max), 0);
    return Math.max(animeSeasons[seasonNum], maxUploadedNum, 12);
  }

  // Default fallback: 12 or max uploaded episode number
  const maxUploadedNum = uploadedEpisodes
    .filter((e) => e.animeId === animeId && (e.seasonNumber || 1) === seasonNum)
    .reduce((max, e) => (e.episodeNumber > max ? e.episodeNumber : max), 0);
  return Math.max(12, maxUploadedNum);
}

/**
 * Set configured Season Episode Count for an Anime & Season
 */
export function setSeasonEpisodeCount(
  animeId: string,
  seasonNum: number,
  count: number
): void {
  if (!animeId || seasonNum < 1) return;
  const map = getStoredSeasonCounts();
  if (!map[animeId]) map[animeId] = {};
  map[animeId][seasonNum] = Math.max(1, count);
  saveStoredSeasonCounts(map);
  window.dispatchEvent(new Event("animestream_season_count_updated"));
}

export interface SeasonStats {
  seasonNum: number;
  expectedCount: number;
  uploadedCount: number;
  missingCount: number;
  uploadedEpisodes: Episode[];
  missingSlotNumbers: number[];
}

/**
 * Calculate detailed Season stats (Expected, Uploaded, Missing Slots)
 */
export function getSeasonStats(
  animeId: string,
  seasonNum: number,
  allEpisodesList: Episode[]
): SeasonStats {
  const seasonEps = allEpisodesList.filter(
    (e) => e.animeId === animeId && (e.seasonNumber || 1) === seasonNum
  );
  
  const expectedCount = getSeasonEpisodeCount(animeId, seasonNum, seasonEps);
  const uploadedSet = new Set(seasonEps.map((e) => e.episodeNumber));
  const uploadedCount = seasonEps.length;

  const missingSlotNumbers: number[] = [];
  for (let i = 1; i <= expectedCount; i++) {
    if (!uploadedSet.has(i)) {
      missingSlotNumbers.push(i);
    }
  }

  return {
    seasonNum,
    expectedCount,
    uploadedCount,
    missingCount: missingSlotNumbers.length,
    uploadedEpisodes: seasonEps,
    missingSlotNumbers
  };
}

/**
 * Get summary of all seasons for a specific Anime
 */
export function getAnimeSeasonsSummary(
  animeId: string,
  allEpisodesList: Episode[]
): SeasonStats[] {
  const seasonEps = allEpisodesList.filter((e) => e.animeId === animeId);
  const existingSeasons = new Set<number>([1]);

  seasonEps.forEach((e) => {
    existingSeasons.add(e.seasonNumber || 1);
  });

  const sortedSeasons = Array.from(existingSeasons).sort((a, b) => a - b);
  return sortedSeasons.map((sNum) => getSeasonStats(animeId, sNum, allEpisodesList));
}
