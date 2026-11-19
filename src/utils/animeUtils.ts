import { Anime, WatchHistory } from "../types";

function extractTitleString(val: unknown): string {
  if (typeof val === "string" && val.trim().length > 0) {
    return val.trim();
  }
  if (val && typeof val === "object") {
    const obj = val as Record<string, unknown>;
    const candidate =
      obj.userPreferred ||
      obj.english ||
      obj.native ||
      obj.title ||
      obj.name ||
      obj.text;
    if (typeof candidate === "string" && candidate.trim().length > 0) {
      return candidate.trim();
    }
  }
  if (val !== null && val !== undefined && typeof val !== "object") {
    return String(val).trim();
  }
  return "";
}

export function getDisplayTitle(
  anime: Partial<Anime> | null | undefined
): string {
  if (!anime) return "Untitled Title";

  const eng = extractTitleString(anime.englishTitle);
  if (eng) return eng;

  const tit = extractTitleString(anime.title);
  if (tit) return tit;

  const orig = extractTitleString(anime.japaneseTitle);
  if (orig) return orig;

  return "Untitled Title";
}

function levenshteinDistance(a: string, b: string): number {
  const matrix: number[][] = [];
  for (let i = 0; i <= b.length; i++) {
    matrix[i] = [i];
  }
  for (let j = 0; j <= a.length; j++) {
    matrix[0][j] = j;
  }
  for (let i = 1; i <= b.length; i++) {
    for (let j = 1; j <= a.length; j++) {
      if (b.charAt(i - 1) === a.charAt(j - 1)) {
        matrix[i][j] = matrix[i - 1][j - 1];
      } else {
        matrix[i][j] = Math.min(
          matrix[i - 1][j - 1] + 1,
          matrix[i][j - 1] + 1,
          matrix[i - 1][j] + 1
        );
      }
    }
  }
  return matrix[b.length][a.length];
}

export function smartSearchAnime(queryText: unknown, animeList: Anime[]): Anime[] {
  const queryStr = extractTitleString(queryText);
  const query = queryStr.toLowerCase();
  if (!query) return animeList || [];

  const scoredResults: { anime: Anime; score: number }[] = [];

  for (const anime of animeList || []) {
    const englishTitleStr = extractTitleString(anime.englishTitle);
    const titleStr = extractTitleString(anime.title);
    const originalTitleStr = extractTitleString(anime.japaneseTitle);

    const englishTitle = (englishTitleStr || titleStr).toLowerCase();
    const title = titleStr.toLowerCase();
    const originalTitle = originalTitleStr.toLowerCase();
    const genres = (Array.isArray(anime.genres) ? anime.genres : [])
      .map((g) => extractTitleString(g).toLowerCase())
      .filter(Boolean);
    const studio = extractTitleString(anime.studio).toLowerCase();

    let score = 0;

    if (englishTitle === query || title === query) {
      score += 100;
    } else if (englishTitle.startsWith(query) || title.startsWith(query)) {
      score += 80;
    } else if (englishTitle.includes(query) || title.includes(query)) {
      score += 60;
    }

    if (originalTitle === query) {
      score += 50;
    } else if (originalTitle.includes(query)) {
      score += 40;
    }

    if (genres.some((g) => g === query)) {
      score += 30;
    } else if (genres.some((g) => g.includes(query))) {
      score += 20;
    }

    if (studio.includes(query)) {
      score += 25;
    }

    if (score === 0 && query.length >= 3) {
      const dist = levenshteinDistance(
        query,
        englishTitle.slice(0, query.length + 3)
      );
      if (dist <= 2) {
        score += 15 - dist * 3;
      }
    }

    if (score > 0) {
      scoredResults.push({ anime, score });
    }
  }

  scoredResults.sort((a, b) => {
    if (b.score !== a.score) return b.score - a.score;
    return (b.anime.score || 0) - (a.anime.score || 0);
  });

  return scoredResults.map((r) => r.anime);
}

export function getPersonalizedRecommendations(
  animeList: Anime[],
  watchHistory: WatchHistory[] = [],
  favorites: string[] = [],
  watchlist: string[] = []
): Anime[] {
  if (!animeList || animeList.length === 0) return [];

  const genreWeights: Record<string, number> = {};
  const studioWeights: Record<string, number> = {};
  const interactedIds = new Set<string>();

  for (const item of watchHistory) {
    interactedIds.add(item.animeId);
    const target = animeList.find((a) => a.id === item.animeId);
    if (target) {
      for (const g of target.genres || []) {
        genreWeights[g] = (genreWeights[g] || 0) + 3;
      }
      if (target.studio) {
        studioWeights[target.studio] = (studioWeights[target.studio] || 0) + 2;
      }
    }
  }

  for (const favId of favorites) {
    interactedIds.add(favId);
    const target = animeList.find((a) => a.id === favId);
    if (target) {
      for (const g of target.genres || []) {
        genreWeights[g] = (genreWeights[g] || 0) + 5;
      }
      if (target.studio) {
        studioWeights[target.studio] = (studioWeights[target.studio] || 0) + 4;
      }
    }
  }

  for (const wlId of watchlist) {
    interactedIds.add(wlId);
    const target = animeList.find((a) => a.id === wlId);
    if (target) {
      for (const g of target.genres || []) {
        genreWeights[g] = (genreWeights[g] || 0) + 2;
      }
    }
  }

  const scoredList = animeList.map((anime) => {
    let recScore = 0;

    for (const g of anime.genres || []) {
      recScore += (genreWeights[g] || 0) * 1.5;
    }

    if (anime.studio && studioWeights[anime.studio]) {
      recScore += studioWeights[anime.studio] * 1.2;
    }

    recScore += (anime.score || 7.0) * 2;
    recScore += anime.isTrending ? 5 : 0;
    recScore += anime.isFeatured ? 4 : 0;

    if (!interactedIds.has(anime.id)) {
      recScore += 3;
    }

    return { anime, recScore };
  });

  scoredList.sort((a, b) => b.recScore - a.recScore);
  return scoredList.map((s) => s.anime);
}
