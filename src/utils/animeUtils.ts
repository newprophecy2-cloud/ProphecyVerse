import { Anime, WatchHistory } from "../types";

/**
 * Helper to extract clean string from potentially nested title object or primitive
 */
function extractTitleString(val: any): string {
  if (typeof val === "string" && val.trim().length > 0) {
    return val.trim();
  }
  if (val && typeof val === "object") {
    const candidate = val.userPreferred || val.english || val.romaji || val.native || val.title || val.name || val.text;
    if (typeof candidate === "string" && candidate.trim().length > 0) {
      return candidate.trim();
    }
  }
  if (val !== null && val !== undefined && typeof val !== "object") {
    return String(val).trim();
  }
  return "";
}

/**
 * Requirement 9: English Title Rule
 * Returns the English anime title as primary display title whenever available.
 */
export function getDisplayTitle(anime: Partial<Anime> | null | undefined): string {
  if (!anime) return "Untitled Anime";

  const eng = extractTitleString(anime.englishTitle);
  if (eng) return eng;

  const tit = extractTitleString(anime.title);
  if (tit) return tit;

  const rom = extractTitleString(anime.romajiTitle);
  if (rom) return rom;

  const jap = extractTitleString(anime.japaneseTitle);
  if (jap) return jap;

  return "Untitled Anime";
}

/**
 * Simple Levenshtein distance for typo tolerance in smart search
 */
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

/**
 * Requirement 5: Smart Search Algorithm
 * Understands English Title, Alternative Titles, Japanese Title, Synonyms, Genres, Studio.
 * Prioritizes English results, supports typo tolerance, and sorts by relevance score.
 */
export function smartSearchAnime(queryText: any, animeList: Anime[]): Anime[] {
  const queryStr = extractTitleString(queryText);
  const query = queryStr.toLowerCase();
  if (!query) return animeList || [];

  const scoredResults: { anime: Anime; score: number }[] = [];

  for (const anime of animeList || []) {
    const englishTitleStr = extractTitleString(anime.englishTitle);
    const titleStr = extractTitleString(anime.title);
    const japaneseTitleStr = extractTitleString(anime.japaneseTitle);
    const romajiTitleStr = extractTitleString(anime.romajiTitle);

    const englishTitle = (englishTitleStr || titleStr).toLowerCase();
    const title = titleStr.toLowerCase();
    const japaneseTitle = japaneseTitleStr.toLowerCase();
    const romajiTitle = romajiTitleStr.toLowerCase();
    const synonyms = (Array.isArray(anime.synonyms) ? anime.synonyms : []).map(s => extractTitleString(s).toLowerCase()).filter(Boolean);
    const genres = (Array.isArray(anime.genres) ? anime.genres : []).map(g => extractTitleString(g).toLowerCase()).filter(Boolean);
    const studio = extractTitleString(anime.studio).toLowerCase();

    let score = 0;

    // 1. Exact Match on English Title (Highest Priority)
    if (englishTitle === query || title === query) {
      score += 100;
    } 
    // 2. Starts With English Title
    else if (englishTitle.startsWith(query) || title.startsWith(query)) {
      score += 80;
    } 
    // 3. Substring in English Title
    else if (englishTitle.includes(query) || title.includes(query)) {
      score += 60;
    }

    // 4. Match in Japanese / Romaji / Native Title or Synonyms
    if (japaneseTitle === query || romajiTitle === query) {
      score += 50;
    } else if (japaneseTitle.includes(query) || romajiTitle.includes(query)) {
      score += 40;
    } else if (synonyms.some(syn => syn.includes(query))) {
      score += 35;
    }

    // 5. Match in Genres or Studio
    if (genres.some(g => g === query)) {
      score += 30;
    } else if (genres.some(g => g.includes(query))) {
      score += 20;
    }
    if (studio.includes(query)) {
      score += 25;
    }

    // 6. Typo tolerance check for English title if short distance
    if (score === 0 && query.length >= 3) {
      const dist = levenshteinDistance(query, englishTitle.slice(0, query.length + 3));
      if (dist <= 2) {
        score += 15 - dist * 3;
      }
    }

    if (score > 0) {
      scoredResults.push({ anime, score });
    }
  }

  // Sort by score descending, then by score rating / popularity
  scoredResults.sort((a, b) => {
    if (b.score !== a.score) return b.score - a.score;
    return (b.anime.score || 0) - (a.anime.score || 0);
  });

  return scoredResults.map(r => r.anime);
}

/**
 * Requirement 4: Smart User Recommendation Engine
 * Personalizes recommendations based on Watch History, Favorite Genres, Favorite Studios,
 * Watchlist, Favorites, and Popularity.
 */
export function getPersonalizedRecommendations(
  animeList: Anime[],
  watchHistory: WatchHistory[] = [],
  favorites: string[] = [],
  watchlist: string[] = []
): Anime[] {
  if (!animeList || animeList.length === 0) return [];

  const genreWeights: Record<string, number> = {};
  const studioWeights: Record<string, number> = {};
  const interactedAnimeIds = new Set<string>();

  // Aggregate user interest from Watch History
  for (const item of watchHistory) {
    interactedAnimeIds.add(item.animeId);
    const target = animeList.find(a => a.id === item.animeId);
    if (target) {
      for (const g of target.genres || []) {
        genreWeights[g] = (genreWeights[g] || 0) + 3;
      }
      if (target.studio) {
        studioWeights[target.studio] = (studioWeights[target.studio] || 0) + 2;
      }
    }
  }

  // Aggregate from Favorites (High Weight)
  for (const favId of favorites) {
    interactedAnimeIds.add(favId);
    const target = animeList.find(a => a.id === favId);
    if (target) {
      for (const g of target.genres || []) {
        genreWeights[g] = (genreWeights[g] || 0) + 5;
      }
      if (target.studio) {
        studioWeights[target.studio] = (studioWeights[target.studio] || 0) + 4;
      }
    }
  }

  // Aggregate from Watchlist
  for (const wlId of watchlist) {
    interactedAnimeIds.add(wlId);
    const target = animeList.find(a => a.id === wlId);
    if (target) {
      for (const g of target.genres || []) {
        genreWeights[g] = (genreWeights[g] || 0) + 2;
      }
    }
  }

  const hasUserData = Object.keys(genreWeights).length > 0;

  // Calculate recommendation scores for all candidate anime
  const scoredList = animeList.map(anime => {
    let recScore = 0;

    // Genre Affinity
    for (const g of anime.genres || []) {
      recScore += (genreWeights[g] || 0) * 1.5;
    }

    // Studio Affinity
    if (anime.studio && studioWeights[anime.studio]) {
      recScore += studioWeights[anime.studio] * 1.2;
    }

    // Baseline Quality & Popularity
    recScore += (anime.score || 7.0) * 2;
    recScore += (anime.isTrending ? 5 : 0);
    recScore += (anime.isFeatured ? 4 : 0);

    // Boost if not already fully watched
    if (!interactedAnimeIds.has(anime.id)) {
      recScore += 3;
    }

    return { anime, recScore };
  });

  // Sort descending by calculated score
  scoredList.sort((a, b) => b.recScore - a.recScore);

  return scoredList.map(s => s.anime);
}
