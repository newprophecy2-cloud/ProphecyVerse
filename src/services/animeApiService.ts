import { Anime, CharacterInfo, RecommendationItem, RelatedAnimeItem } from "../types";

// In-memory caching for API responses to keep app fast and avoid rate-limiting
const animeSearchCache = new Map<string, any[]>();
const animeDetailsCache = new Map<string, Anime>();

// Utility to strip HTML tags from descriptions
export function stripHtml(html: string = ""): string {
  if (!html) return "";
  return html
    .replace(/<br\s*[\/]?>/gi, "\n")
    .replace(/<\/?[^>]+(>|$)/g, "")
    .replace(/&quot;/g, '"')
    .replace(/&amp;/g, "&")
    .replace(/&#039;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .trim();
}

// Utility to deduplicate string arrays ignoring case
export function uniqueArray(arr: string[] = []): string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const item of arr) {
    if (!item) continue;
    const clean = item.trim();
    const lower = clean.toLowerCase();
    if (!seen.has(lower)) {
      seen.add(lower);
      result.push(clean);
    }
  }
  return result;
}

// Default fallback images from Unsplash to ensure no empty image fields
const FALLBACK_BANNERS = [
  "https://images.unsplash.com/photo-1607604276583-eef5d076aa5f?q=80&w=1600",
  "https://images.unsplash.com/photo-1578632767115-351597cf2477?q=80&w=1600",
  "https://images.unsplash.com/photo-1620641788421-7a1c342ea42e?q=80&w=1600",
  "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?q=80&w=1600",
  "https://images.unsplash.com/photo-1541562232579-512a21360020?q=80&w=1600"
];

const FALLBACK_POSTERS = [
  "https://images.unsplash.com/photo-1578632767115-351597cf2477?q=80&w=600",
  "https://images.unsplash.com/photo-1607604276583-eef5d076aa5f?q=80&w=600",
  "https://images.unsplash.com/photo-1620641788421-7a1c342ea42e?q=80&w=600",
  "https://images.unsplash.com/photo-1541562232579-512a21360020?q=80&w=600"
];

function getRandomFallback(array: string[]): string {
  return array[Math.floor(Math.random() * array.length)];
}

// ----------------------------------------------------------------------
// 1. ANILIST GRAPHQL FETCHING (Primary Source)
// ----------------------------------------------------------------------
const ANILIST_QUERY = `
query ($search: String) {
  Page(page: 1, perPage: 8) {
    media(search: $search, type: ANIME, sort: [POPULARITY_DESC, SEARCH_MATCH]) {
      id
      idMal
      title {
        userPreferred
        romaji
        english
        native
      }
      synonyms
      description(asHtml: false)
      bannerImage
      coverImage {
        extraLarge
        large
        medium
        color
      }
      startDate {
        year
        month
        day
      }
      season
      seasonYear
      type
      format
      status
      episodes
      duration
      countryOfOrigin
      source
      trailer {
        id
        site
      }
      genres
      tags {
        name
      }
      averageScore
      popularity
      favourites
      nextAiringEpisode {
        episode
        airingAt
        timeUntilAiring
      }
      studios(isMain: true) {
        nodes {
          name
        }
      }
      relations {
        edges {
          relationType
          node {
            id
            title { userPreferred english romaji }
            format
            status
            seasonYear
            season
            episodes
            coverImage { large medium }
          }
        }
      }
      recommendations(perPage: 6) {
        nodes {
          mediaRecommendation {
            id
            title { userPreferred }
            coverImage { large }
            averageScore
          }
        }
      }
      characters(perPage: 8, sort: [ROLE, RELEVANCE]) {
        edges {
          role
          node {
            name { full native }
            image { large }
          }
          voiceActors(language: JAPANESE) {
            name { full }
            image { large }
          }
        }
      }
      siteUrl
    }
  }
}
`;

export async function safeAniListFetch(query: string, variables?: any): Promise<any> {
  const payload = JSON.stringify({ query, variables });

  // 1. Try local Express proxy /api/anilist first
  try {
    const proxyRes = await fetch("/api/anilist", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Accept": "application/json" },
      body: payload
    });
    if (proxyRes.ok) {
      const json = await proxyRes.json();
      if (json && !json.errors) {
        return json;
      }
    }
  } catch (_) {
    // Proxy call unavailable or failed, fall through
  }

  // 2. Fallback to direct GraphQL fetch
  try {
    const directRes = await fetch("https://graphql.anilist.co", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Accept": "application/json" },
      body: payload
    });
    if (directRes.ok) {
      return await directRes.json();
    }
  } catch (err: any) {
    console.warn("[AniList Fetch] Direct query skipped/failed:", err?.message || err);
  }

  return null;
}

export async function safeJikanFetch(subPathAndQuery: string): Promise<any> {
  // 1. Try local Express proxy /api/jikan/ first
  try {
    const proxyRes = await fetch(`/api/jikan/${subPathAndQuery}`);
    if (proxyRes.ok) {
      return await proxyRes.json();
    }
  } catch (_) {}

  // 2. Direct fallback
  try {
    const directRes = await fetch(`https://api.jikan.moe/v4/${subPathAndQuery}`);
    if (directRes.ok) {
      return await directRes.json();
    }
  } catch (err: any) {
    console.warn("[Jikan Fetch] Direct query skipped/failed:", err?.message || err);
  }

  return null;
}

async function fetchFromAniList(queryText: string): Promise<any[]> {
  try {
    const json = await safeAniListFetch(ANILIST_QUERY, { search: queryText });
    return json?.data?.Page?.media || [];
  } catch (err) {
    console.warn("AniList API notice:", err);
    return [];
  }
}

// ----------------------------------------------------------------------
// 2. JIKAN REST API (Secondary Source - MyAnimeList)
// ----------------------------------------------------------------------
async function fetchFromJikan(queryText: string): Promise<any[]> {
  try {
    const json = await safeJikanFetch(`anime?q=${encodeURIComponent(queryText)}&limit=8`);
    return json?.data || [];
  } catch (err) {
    console.warn("Jikan API notice:", err);
    return [];
  }
}

// ----------------------------------------------------------------------
// 3. KITSU REST API (Tertiary Source)
// ----------------------------------------------------------------------
async function fetchFromKitsu(queryText: string): Promise<any[]> {
  try {
    const res = await fetch(`https://kitsu.io/api/edge/anime?filter[text]=${encodeURIComponent(queryText)}&page[limit]=8`);
    if (!res.ok) return [];
    const json = await res.json();
    return json?.data || [];
  } catch (err) {
    console.warn("Kitsu API notice:", err);
    return [];
  }
}

// Helper to safely extract string values from queries, title objects or unknown inputs
export function parseSearchQueryString(val: any): string {
  if (typeof val === "string") {
    return val.trim();
  }
  if (val && typeof val === "object") {
    const candidate = val.userPreferred || val.english || val.romaji || val.native || val.title || val.name || val.text;
    if (typeof candidate === "string") {
      return candidate.trim();
    }
    if (candidate && typeof candidate === "object") {
      return parseSearchQueryString(candidate);
    }
  }
  if (val !== null && val !== undefined) {
    const str = String(val);
    return str === "[object Object]" ? "" : str.trim();
  }
  return "";
}

// ----------------------------------------------------------------------
// SMART SEARCH SUGGESTIONS (Multi-API with automatic Fallback)
// ----------------------------------------------------------------------
export interface AnimeSearchResult {
  id: string;
  title: string;
  englishTitle: string;
  romajiTitle: string;
  japaneseTitle: string;
  synonyms: string[];
  releaseYear: number;
  type: "TV" | "Movie" | "OVA" | "ONA";
  poster: string;
  banner: string;
  sourceApi: "AniList" | "Jikan" | "Kitsu";
  rawObject: any;
}

export async function searchAnimeSuggestions(query: any): Promise<AnimeSearchResult[]> {
  const queryText = parseSearchQueryString(query);
  const cleanQuery = queryText.toLowerCase();
  if (!cleanQuery) return [];

  if (animeSearchCache.has(cleanQuery)) {
    return animeSearchCache.get(cleanQuery)!;
  }

  const results: AnimeSearchResult[] = [];
  const seenTitles = new Set<string>();

  // Priority 1: AniList
  const aniListItems = await fetchFromAniList(queryText);
  for (const item of aniListItems) {
    const mainTitle = parseSearchQueryString(item.title?.userPreferred || item.title?.english || item.title?.romaji || item.title);
    if (!mainTitle || seenTitles.has(mainTitle.toLowerCase())) continue;
    seenTitles.add(mainTitle.toLowerCase());

    const poster = item.coverImage?.extraLarge || item.coverImage?.large || getRandomFallback(FALLBACK_POSTERS);
    const banner = item.bannerImage || item.coverImage?.extraLarge || getRandomFallback(FALLBACK_BANNERS);

    results.push({
      id: `anilist-${item.id}`,
      title: mainTitle,
      englishTitle: parseSearchQueryString(item.title?.english) || mainTitle,
      romajiTitle: parseSearchQueryString(item.title?.romaji) || mainTitle,
      japaneseTitle: parseSearchQueryString(item.title?.native) || "",
      synonyms: item.synonyms ? item.synonyms.map((s: any) => parseSearchQueryString(s)).filter(Boolean) : [],
      releaseYear: item.startDate?.year || item.seasonYear || new Date().getFullYear(),
      type: (item.format === "MOVIE" ? "Movie" : item.format === "OVA" ? "OVA" : item.format === "ONA" ? "ONA" : "TV"),
      poster,
      banner,
      sourceApi: "AniList",
      rawObject: item
    });
  }

  // Priority 2: Jikan (If AniList yielded few or failed)
  if (results.length < 4) {
    const jikanItems = await fetchFromJikan(queryText);
    for (const item of jikanItems) {
      const mainTitle = parseSearchQueryString(item.title || item.title_english || item.title_japanese);
      if (!mainTitle || seenTitles.has(mainTitle.toLowerCase())) continue;
      seenTitles.add(mainTitle.toLowerCase());

      const poster = item.images?.jpg?.large_image_url || item.images?.jpg?.image_url || getRandomFallback(FALLBACK_POSTERS);
      const banner = item.images?.jpg?.large_image_url || getRandomFallback(FALLBACK_BANNERS);

      results.push({
        id: `jikan-${item.mal_id}`,
        title: mainTitle,
        englishTitle: parseSearchQueryString(item.title_english) || mainTitle,
        romajiTitle: parseSearchQueryString(item.title_japanese) || mainTitle,
        japaneseTitle: parseSearchQueryString(item.title_japanese) || "",
        synonyms: item.titles ? item.titles.map((t: any) => parseSearchQueryString(t.title)).filter(Boolean) : [],
        releaseYear: item.year || (item.aired?.prop?.from?.year) || new Date().getFullYear(),
        type: (item.type === "Movie" ? "Movie" : item.type === "OVA" ? "OVA" : item.type === "ONA" ? "ONA" : "TV"),
        poster,
        banner,
        sourceApi: "Jikan",
        rawObject: item
      });
    }
  }

  // Priority 3: Kitsu (If still empty/low)
  if (results.length < 3) {
    const kitsuItems = await fetchFromKitsu(queryText);
    for (const item of kitsuItems) {
      const attr = item.attributes || {};
      const mainTitle = parseSearchQueryString(attr.canonicalTitle || attr.titles?.en || attr.titles?.en_jp);
      if (!mainTitle || seenTitles.has(mainTitle.toLowerCase())) continue;
      seenTitles.add(mainTitle.toLowerCase());

      const poster = attr.posterImage?.original || attr.posterImage?.large || getRandomFallback(FALLBACK_POSTERS);
      const banner = attr.coverImage?.original || attr.posterImage?.large || getRandomFallback(FALLBACK_BANNERS);

      results.push({
        id: `kitsu-${item.id}`,
        title: mainTitle,
        englishTitle: parseSearchQueryString(attr.titles?.en) || mainTitle,
        romajiTitle: parseSearchQueryString(attr.titles?.en_jp) || mainTitle,
        japaneseTitle: parseSearchQueryString(attr.titles?.ja_jp) || "",
        synonyms: Object.values(attr.titles || {}).map(s => parseSearchQueryString(s)).filter(Boolean),
        releaseYear: attr.startDate ? new Date(attr.startDate).getFullYear() : new Date().getFullYear(),
        type: (attr.subtype === "movie" ? "Movie" : attr.subtype === "OVA" ? "OVA" : attr.subtype === "ONA" ? "ONA" : "TV"),
        poster,
        banner,
        sourceApi: "Kitsu",
        rawObject: item
      });
    }
  }

  animeSearchCache.set(cleanQuery, results);
  return results;
}

// ----------------------------------------------------------------------
// AUTO METADATA IMPORT & MULTI-API DATA MERGING
// ----------------------------------------------------------------------
export async function compileFullAnimeMetadata(searchResult: AnimeSearchResult): Promise<Anime> {
  const cacheKey = `details-${searchResult.id}`;
  if (animeDetailsCache.has(cacheKey)) {
    return animeDetailsCache.get(cacheKey)!;
  }

  let finalAnime: Partial<Anime> = {};

  // If initial source was AniList
  if (searchResult.sourceApi === "AniList" && searchResult.rawObject) {
    const ani = searchResult.rawObject;
    
    // Parse characters
    const charactersList: CharacterInfo[] = [];
    if (ani.characters?.edges) {
      for (const edge of ani.characters.edges) {
        const node = edge.node;
        const va = edge.voiceActors?.[0];
        if (node) {
          charactersList.push({
            name: node.name?.full || node.name?.native || "Unknown",
            role: edge.role === "MAIN" ? "Main" : "Supporting",
            image: node.image?.large || getRandomFallback(FALLBACK_POSTERS),
            voiceActor: va?.name?.full,
            voiceActorImage: va?.image?.large
          });
        }
      }
    }

    // Parse recommendations
    const recommendationsList: RecommendationItem[] = [];
    if (ani.recommendations?.nodes) {
      for (const rec of ani.recommendations.nodes) {
        const media = rec.mediaRecommendation;
        if (media) {
          recommendationsList.push({
            id: `anilist-${media.id}`,
            title: media.title?.userPreferred || "Recommended Anime",
            image: media.coverImage?.large || getRandomFallback(FALLBACK_POSTERS),
            score: media.averageScore ? media.averageScore / 10 : 8.0
          });
        }
      }
    }

    // Parse relations
    const relatedList: RelatedAnimeItem[] = [];
    if (ani.relations?.edges) {
      for (const rel of ani.relations.edges) {
        if (rel.node) {
          relatedList.push({
            id: `anilist-${rel.node.id}`,
            title: rel.node.title?.userPreferred || "Related Anime",
            relationType: rel.relationType || "Sequel",
            image: rel.node.coverImage?.medium
          });
        }
      }
    }

    const studioName = ani.studios?.nodes?.[0]?.name || "Animation Studio";

    // Trailer URL construction
    let trailerUrl = "";
    if (ani.trailer?.site === "youtube" && ani.trailer?.id) {
      trailerUrl = `https://www.youtube.com/embed/${ani.trailer.id}`;
    }

    // Extract tags
    const tagsList = ani.tags ? uniqueArray(ani.tags.map((t: any) => t.name)) : [];

    // Extract nextAiringEpisode
    const nextAiring = ani.nextAiringEpisode ? {
      episode: ani.nextAiringEpisode.episode,
      airingAt: ani.nextAiringEpisode.airingAt,
      timeUntilAiring: ani.nextAiringEpisode.timeUntilAiring
    } : null;

    // Detect sequel/next season in relations
    let sequelInfo = null;
    if (ani.relations?.edges) {
      const sequelEdge = ani.relations.edges.find((e: any) => e.relationType === "SEQUEL" || e.relationType === "SIDE_STORY");
      if (sequelEdge?.node) {
        const sqNode = sequelEdge.node;
        sequelInfo = {
          title: sqNode.title?.userPreferred || sqNode.title?.english || sqNode.title?.romaji || "Next Season",
          releaseDate: sqNode.seasonYear ? `${sqNode.season || ''} ${sqNode.seasonYear}` : undefined,
          status: sqNode.status,
          anilistMediaId: sqNode.id,
          poster: sqNode.coverImage?.large
        };
      }
    }

    // Determine series status
    let seriesStatusVal: "Airing" | "Finished" | "Upcoming" | "Cancelled" | "Hiatus" | "Not Yet Released" | "Completed" | "Officially Announced" | "Coming Soon" = "Finished";
    if (ani.status === "RELEASING") seriesStatusVal = "Airing";
    else if (ani.status === "NOT_YET_RELEASED") seriesStatusVal = "Not Yet Released";
    else if (ani.status === "CANCELLED") seriesStatusVal = "Cancelled";
    else if (ani.status === "HIATUS") seriesStatusVal = "Hiatus";
    else if (ani.status === "FINISHED") {
      seriesStatusVal = sequelInfo ? "Officially Announced" : "Finished";
    }

    // Calculate official episode count including multi-cour/split-cour relations
    let calculatedEpisodes = ani.episodes || 12;
    if (ani.relations?.edges) {
      for (const relEdge of ani.relations.edges) {
        if (relEdge.relationType === "SEQUEL" && relEdge.node) {
          const sqTitle = (relEdge.node.title?.userPreferred || relEdge.node.title?.english || relEdge.node.title?.romaji || "").toLowerCase();
          if (/part\s*2|cour\s*2|2nd\s*cour|part\s*ii/i.test(sqTitle)) {
            const extraEps = Number(relEdge.node.episodes) || 0;
            if (extraEps > 0) {
              calculatedEpisodes += extraEps;
            }
          }
        }
      }
    }

    // Specific title checks for official multi-cour season counts (e.g. Spy x Family Season 1 = 25 episodes)
    const lowerTitleStr = (ani.title?.userPreferred || searchResult.title || "").toLowerCase();
    if ((lowerTitleStr.includes("spy") && lowerTitleStr.includes("family")) && !lowerTitleStr.includes("season 2")) {
      calculatedEpisodes = 25;
    }

    finalAnime = {
      anilistMediaId: ani.id,
      title: ani.title?.userPreferred || searchResult.title,
      englishTitle: ani.title?.english || ani.title?.userPreferred || "",
      japaneseTitle: ani.title?.native || "",
      nativeTitle: ani.title?.native || "",
      romajiTitle: ani.title?.romaji || "",
      synonyms: uniqueArray(ani.synonyms || []),
      description: stripHtml(ani.description || "No description provided."),
      genres: uniqueArray(ani.genres || ["Action", "Adventure"]),
      tags: tagsList,
      themes: uniqueArray(["Anime", "Action"]),
      studio: studioName,
      producers: [studioName],
      licensors: ["Crunchyroll", "Funimation"],
      releaseYear: ani.startDate?.year || ani.seasonYear || new Date().getFullYear(),
      season: ani.season ? (ani.season.charAt(0) + ani.season.slice(1).toLowerCase()) : "Spring",
      status: ani.status === "FINISHED" ? "Completed" : ani.status === "RELEASING" ? "Ongoing" : "Completed",
      seriesStatus: seriesStatusVal,
      nextAiringEpisode: nextAiring,
      nextSeasonInfo: sequelInfo,
      hasNewSeasonAlert: !!sequelInfo,
      duration: ani.duration ? `${ani.duration} min` : "24 min",
      rating: "PG-13",
      score: ani.averageScore ? Number((ani.averageScore / 10).toFixed(1)) : 8.2,
      popularity: ani.popularity || 10000,
      favorites: ani.favourites || 1200,
      totalEpisodes: calculatedEpisodes,
      episodesCount: calculatedEpisodes,
      trailerUrl,
      banner: ani.bannerImage || ani.coverImage?.extraLarge || searchResult.banner || getRandomFallback(FALLBACK_BANNERS),
      poster: ani.coverImage?.extraLarge || ani.coverImage?.large || searchResult.poster || getRandomFallback(FALLBACK_POSTERS),
      type: searchResult.type,
      source: ani.source ? (ani.source.charAt(0) + ani.source.slice(1).toLowerCase()) : "Original",
      ageRating: "PG-13 - Teens 13 or older",
      officialWebsite: ani.siteUrl || "",
      characters: charactersList,
      recommendations: recommendationsList,
      relatedAnime: relatedList,
      coverColor: ani.coverImage?.color || "#131520"
    };
  }

  // Fallback / Merging with Jikan if missing description or banner or trailer or characters
  if (!finalAnime.description || !finalAnime.trailerUrl || !finalAnime.studio || (finalAnime.characters && finalAnime.characters.length === 0)) {
    try {
      const jikanList = await fetchFromJikan(searchResult.title);
      if (jikanList.length > 0) {
        const jik = jikanList[0];
        
        if (!finalAnime.description) {
          finalAnime.description = stripHtml(jik.synopsis || "");
        }
        if (!finalAnime.trailerUrl && jik.trailer?.embed_url) {
          finalAnime.trailerUrl = jik.trailer.embed_url;
        }
        if ((!finalAnime.studio || finalAnime.studio === "Animation Studio") && jik.studios?.[0]?.name) {
          finalAnime.studio = jik.studios[0].name;
        }
        if ((!finalAnime.producers || finalAnime.producers.length === 0) && jik.producers) {
          finalAnime.producers = uniqueArray(jik.producers.map((p: any) => p.name));
        }
        if (!finalAnime.rating && jik.rating) {
          finalAnime.rating = jik.rating;
        }
        if (!finalAnime.japaneseTitle && jik.title_japanese) {
          finalAnime.japaneseTitle = jik.title_japanese;
        }
        if (!finalAnime.score && jik.score) {
          finalAnime.score = jik.score;
        }
      }
    } catch (e) {
      console.warn("Secondary Jikan merge skipped:", e);
    }
  }

  // Fallback / Merging with Kitsu if still missing banner or poster
  if (!finalAnime.banner || !finalAnime.poster) {
    try {
      const kitsuList = await fetchFromKitsu(searchResult.title);
      if (kitsuList.length > 0) {
        const kitAttr = kitsuList[0].attributes;
        if (!finalAnime.banner && kitAttr.coverImage?.original) {
          finalAnime.banner = kitAttr.coverImage.original;
        }
        if (!finalAnime.poster && kitAttr.posterImage?.original) {
          finalAnime.poster = kitAttr.posterImage.original;
        }
      }
    } catch (e) {
      console.warn("Tertiary Kitsu merge skipped:", e);
    }
  }

  // Guarantee high quality non-empty images
  if (!finalAnime.banner) finalAnime.banner = getRandomFallback(FALLBACK_BANNERS);
  if (!finalAnime.poster) finalAnime.poster = getRandomFallback(FALLBACK_POSTERS);
  
  const finalTitle = parseSearchQueryString(finalAnime.title) || parseSearchQueryString(searchResult.title) || "Anime";
  const finalEnglishTitle = parseSearchQueryString(finalAnime.englishTitle) || finalTitle;
  const finalJapaneseTitle = parseSearchQueryString(finalAnime.japaneseTitle);
  const finalNativeTitle = parseSearchQueryString(finalAnime.nativeTitle) || finalJapaneseTitle;
  const finalRomajiTitle = parseSearchQueryString(finalAnime.romajiTitle) || finalTitle;

  if (!finalAnime.description) finalAnime.description = `${finalTitle} - Full stream series available now.`;

  const cleanId = finalTitle.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/(^-|-$)/g, "") || `anime-${Date.now()}`;

  const completedAnime: Anime = {
    id: cleanId,
    title: finalTitle,
    englishTitle: finalEnglishTitle,
    japaneseTitle: finalJapaneseTitle,
    nativeTitle: finalNativeTitle,
    romajiTitle: finalRomajiTitle,
    synonyms: finalAnime.synonyms || [],
    description: finalAnime.description,
    genres: finalAnime.genres && finalAnime.genres.length > 0 ? finalAnime.genres : ["Action", "Fantasy"],
    tags: finalAnime.tags || [],
    themes: finalAnime.themes || ["Action"],
    studio: finalAnime.studio || "Anime Studio",
    producers: finalAnime.producers || ["Toei Animation"],
    licensors: finalAnime.licensors || ["Crunchyroll"],
    releaseYear: finalAnime.releaseYear || new Date().getFullYear(),
    season: finalAnime.season || "Spring",
    status: finalAnime.status || "Ongoing",
    seriesStatus: finalAnime.seriesStatus || "Finished",
    rating: finalAnime.rating || "PG-13",
    score: finalAnime.score || 8.0,
    popularity: finalAnime.popularity || 1000,
    favorites: finalAnime.favorites || 500,
    duration: finalAnime.duration || "24 min",
    totalEpisodes: finalAnime.totalEpisodes || 12,
    episodesCount: finalAnime.episodesCount || 12,
    trailerUrl: finalAnime.trailerUrl || "",
    banner: finalAnime.banner,
    poster: finalAnime.poster,
    type: finalAnime.type || "TV",
    source: finalAnime.source || "Manga",
    ageRating: finalAnime.ageRating || "PG-13",
    officialWebsite: finalAnime.officialWebsite || "",
    characters: finalAnime.characters || [],
    recommendations: finalAnime.recommendations || [],
    relatedAnime: finalAnime.relatedAnime || [],
    coverColor: finalAnime.coverColor || "#131520",
    anilistMediaId: finalAnime.anilistMediaId,
    nextAiringEpisode: finalAnime.nextAiringEpisode,
    nextSeasonInfo: finalAnime.nextSeasonInfo,
    hasNewSeasonAlert: finalAnime.hasNewSeasonAlert,
    currentSeason: 1,
    currentEpisode: 0,
    uploadedEpisodeCount: 0,
    hasDub: false,
    hasSub: true,
    lastUploadDate: new Date().toISOString()
  };

  animeDetailsCache.set(cacheKey, completedAnime);
  return completedAnime;
}

/**
 * Dynamically check AniList for new season / sequel alerts for an existing Anime document
 */
export async function checkAniListNewSeasonAlert(anime: Anime): Promise<{
  hasNewSeason: boolean;
  message?: string;
  nextSeasonInfo?: any;
}> {
  try {
    const query = `
    query ($id: Int, $search: String) {
      Media(id: $id, search: $search, type: ANIME) {
        id
        status
        episodes
        relations {
          edges {
            relationType
            node {
              id
              title { userPreferred english romaji }
              format
              status
              season
              seasonYear
              coverImage { large }
            }
          }
        }
      }
    }
    `;
    const variables = anime.anilistMediaId ? { id: anime.anilistMediaId } : { search: anime.title };
    const json = await safeAniListFetch(query, variables);
    const media = json?.data?.Media;
    if (!media) return { hasNewSeason: false };

    const sequelEdge = media.relations?.edges?.find((e: any) => e.relationType === "SEQUEL");
    if (sequelEdge?.node) {
      const sq = sequelEdge.node;
      const sqTitle = sq.title?.userPreferred || sq.title?.english || sq.title?.romaji || "New Season";
      return {
        hasNewSeason: true,
        message: `New Season Announced: "${sqTitle}" (${sq.status || 'Announced'})`,
        nextSeasonInfo: {
          title: sqTitle,
          releaseDate: sq.seasonYear ? `${sq.season || ''} ${sq.seasonYear}` : 'TBA',
          status: sq.status,
          anilistMediaId: sq.id,
          poster: sq.coverImage?.large
        }
      };
    }
  } catch (err) {
    console.warn("AniList update check notice:", err);
  }
  return { hasNewSeason: false };
}

/**
 * Fetches top popular anime from AniList API and orders/filters them 
 * strictly against the website's existing catalog (`animeList`).
 */
export async function getPopularCatalogRecommendations(
  catalogList: Anime[],
  currentAnimeId?: string
): Promise<Anime[]> {
  if (!catalogList || catalogList.length === 0) return [];

  // Filter out invisible anime or the currently watched anime
  const validCatalog = catalogList.filter(
    (a) => a.id !== currentAnimeId && (a.isVisible !== false)
  );

  if (validCatalog.length === 0) return [];

  try {
    const POPULAR_ANIME_QUERY = `
    query {
      Page(page: 1, perPage: 50) {
        media(type: ANIME, sort: [POPULARITY_DESC]) {
          id
          title {
            userPreferred
            english
            romaji
            native
          }
          synonyms
          popularity
          averageScore
        }
      }
    }
    `;

    const json = await safeAniListFetch(POPULAR_ANIME_QUERY);
    if (json) {
      const apiPopularList: any[] = json?.data?.Page?.media || [];

      if (apiPopularList.length > 0) {
        const orderedRecommendations: Anime[] = [];
        const addedIds = new Set<string>();

        // Match API popular order against local catalog
        for (const apiItem of apiPopularList) {
          const apiTitles = [
            apiItem.title?.userPreferred,
            apiItem.title?.english,
            apiItem.title?.romaji,
            apiItem.title?.native,
            ...(apiItem.synonyms || [])
          ]
            .filter(Boolean)
            .map((t) => String(t).toLowerCase().trim());

          const matchedCatalogItem = validCatalog.find((catItem) => {
            if (addedIds.has(catItem.id)) return false;

            const catTitles = [
              catItem.title,
              catItem.englishTitle,
              catItem.japaneseTitle,
              catItem.romajiTitle,
              catItem.nativeTitle,
              ...(catItem.synonyms || [])
            ]
              .filter(Boolean)
              .map((t) => String(t).toLowerCase().trim());

            return catTitles.some((catT) =>
              apiTitles.some(
                (apiT) =>
                  catT === apiT ||
                  (catT.length > 3 && apiT.includes(catT)) ||
                  (apiT.length > 3 && catT.includes(apiT))
              )
            );
          });

          if (matchedCatalogItem && !addedIds.has(matchedCatalogItem.id)) {
            addedIds.add(matchedCatalogItem.id);
            orderedRecommendations.push({
              ...matchedCatalogItem,
              popularity: apiItem.popularity || matchedCatalogItem.popularity,
              score: apiItem.averageScore ? Number((apiItem.averageScore / 10).toFixed(1)) : matchedCatalogItem.score
            });
          }
        }

        // Append remaining catalog items sorted by local score/popularity
        const remainingCatalog = validCatalog
          .filter((catItem) => !addedIds.has(catItem.id))
          .sort((a, b) => (b.popularity || b.score || 0) - (a.popularity || a.score || 0));

        return [...orderedRecommendations, ...remainingCatalog];
      }
    }
  } catch (err) {
    console.warn("API popularity recommendation fetch notice, using catalog sort:", err);
  }

  // Fallback: Sort catalog by popularity / score descending
  return [...validCatalog].sort((a, b) => (b.popularity || b.score || 0) - (a.popularity || a.score || 0));
}

// ----------------------------------------------------------------------
// OFFICIAL EPISODE METADATA FETCHING (AniList, Jikan, Kitsu)
// ----------------------------------------------------------------------
export interface OfficialEpisodeMetadata {
  title: string;
  thumbnail: string;
  description: string;
  releaseDate?: string;
}

const episodeMetadataCache = new Map<string, OfficialEpisodeMetadata>();

export async function fetchOfficialEpisodeMetadata(
  animeTitle: string,
  seasonNum: number,
  episodeNum: number,
  anilistMediaId?: number
): Promise<OfficialEpisodeMetadata> {
  const cacheKey = `${animeTitle.toLowerCase().trim()}_s${seasonNum}_e${episodeNum}`;
  const cached = episodeMetadataCache.get(cacheKey);
  if (cached) {
    return cached;
  }

  let fetchedTitle = "";
  let fetchedThumbnail = "";
  let fetchedDesc = "";
  let fetchedReleaseDate = "";

  // 1. AniList GraphQL API (Streaming episodes)
  try {
    const query = `
      query ($search: String, $id: Int) {
        Media(search: $search, id: $id, type: ANIME) {
          id
          title { userPreferred english romaji }
          bannerImage
          coverImage { extraLarge large }
          streamingEpisodes {
            title
            thumbnail
            url
          }
        }
      }
    `;

    const variables: any = anilistMediaId ? { id: anilistMediaId } : { search: animeTitle };
    const json = await safeAniListFetch(query, variables);

    if (json) {
      const media = json?.data?.Media;
      if (media) {
        const streaming = media.streamingEpisodes || [];
        const match = streaming.find((ep: any) => {
          if (!ep?.title) return false;
          const numMatch = ep.title.match(/(?:Episode|Ep\.?|#)\s*(\d+)/i) || ep.title.match(/^\s*(\d+)\s*[-:]/);
          if (numMatch) {
            return parseInt(numMatch[1], 10) === episodeNum;
          }
          return false;
        }) || (episodeNum > 0 && episodeNum <= streaming.length ? streaming[episodeNum - 1] : null);

        if (match) {
          const rawTitle = match.title || "";
          const cleanTitle = rawTitle.replace(/^(?:Episode|Ep\.?|#)\s*\d+\s*[-:]\s*/i, "").trim();
          if (cleanTitle && !/^Episode\s*\d+$/i.test(cleanTitle)) {
            fetchedTitle = cleanTitle;
          }
          if (match.thumbnail) {
            fetchedThumbnail = match.thumbnail;
          }
        }
      }
    }
  } catch (err) {
    console.warn("[AniList Episode Metadata] fetch notice:", err);
  }

  // 2. Jikan API (MyAnimeList v4)
  if (!fetchedTitle || !fetchedThumbnail) {
    try {
      const searchJson = await safeJikanFetch(`anime?q=${encodeURIComponent(animeTitle)}&limit=1`);
      if (searchJson) {
        const malId = searchJson?.data?.[0]?.mal_id;
        if (malId) {
          const epJson = await safeJikanFetch(`anime/${malId}/episodes/${episodeNum}`);
          if (epJson) {
            const epData = epJson?.data;
            if (epData) {
              const jikanTitle = epData.title || epData.title_romanji || epData.title_japanese;
              if (jikanTitle && !/^Episode\s*\d+$/i.test(jikanTitle) && !fetchedTitle) {
                fetchedTitle = jikanTitle;
              }
              if (epData.aired && !fetchedReleaseDate) {
                fetchedReleaseDate = new Date(epData.aired).toISOString().split("T")[0];
              }
            }
          }
        }
      }
    } catch (err) {
      console.warn("[Jikan Episode Metadata] fetch notice:", err);
    }
  }

  // 3. Kitsu API
  if (!fetchedTitle || !fetchedThumbnail) {
    try {
      const kRes = await fetch(`https://kitsu.io/api/edge/anime?filter[text]=${encodeURIComponent(animeTitle)}&page[limit]=1`);
      if (kRes.ok) {
        const kJson = await kRes.json();
        const kitsuId = kJson?.data?.[0]?.id;
        if (kitsuId) {
          const kEpRes = await fetch(`https://kitsu.io/api/edge/episodes?filter[mediaId]=${kitsuId}&filter[number]=${episodeNum}`);
          if (kEpRes.ok) {
            const kEpJson = await kEpRes.json();
            const kEpItem = kEpJson?.data?.[0];
            if (kEpItem?.attributes) {
              const attr = kEpItem.attributes;
              const kTitle = attr.canonicalTitle || attr.titles?.en || attr.titles?.en_jp;
              if (kTitle && !/^Episode\s*\d+$/i.test(kTitle) && !fetchedTitle) {
                fetchedTitle = kTitle;
              }
              if (attr.thumbnail?.original || attr.thumbnail?.medium) {
                if (!fetchedThumbnail) {
                  fetchedThumbnail = attr.thumbnail.original || attr.thumbnail.medium;
                }
              }
              if (attr.synopsis && !fetchedDesc) {
                fetchedDesc = attr.synopsis;
              }
              if (attr.airdate && !fetchedReleaseDate) {
                fetchedReleaseDate = attr.airdate;
              }
            }
          }
        }
      }
    } catch (err) {
      console.warn("[Kitsu Episode Metadata] fetch error:", err);
    }
  }

  const result: OfficialEpisodeMetadata = {
    title: fetchedTitle || `Episode ${episodeNum}`,
    thumbnail: fetchedThumbnail,
    description: fetchedDesc || `Watch ${animeTitle} Season ${seasonNum} Episode ${episodeNum} in HD with multiple audio support and English subtitles.`,
    releaseDate: fetchedReleaseDate || new Date().toISOString().split("T")[0]
  };

  episodeMetadataCache.set(cacheKey, result);
  return result;
}

// Helper to parse filename metadata (e.g. "One Piece E1138 Hindi 1080p.mp4")
export function parseEpisodeFilename(filename: string) {
  const cleanName = filename.replace(/\.[^/.]+$/, ""); // strip extension

  // 1. Detect Episode Number
  let episodeNumber = 1;
  const epMatch = cleanName.match(/(?:E|Ep|Episode|#)\s*(\d+)/i) || cleanName.match(/\b(\d{1,4})\b/);
  if (epMatch) {
    episodeNumber = parseInt(epMatch[1], 10);
  }

  // 2. Detect Language
  let language: "Japanese" | "English" | "Hindi" | "Bangla" | "Spanish" | "Subbed" | "Dubbed" = "Japanese";
  if (/hindi/i.test(cleanName)) {
    language = "Hindi";
  } else if (/bangla|bengali/i.test(cleanName)) {
    language = "Bangla";
  } else if (/english|eng|dub/i.test(cleanName)) {
    language = "English";
  } else if (/spanish|esp/i.test(cleanName)) {
    language = "Spanish";
  } else if (/sub|jap|japanese/i.test(cleanName)) {
    language = "Japanese";
  }

  // 3. Detect Quality
  let quality = "1080p";
  if (/4k|2160p/i.test(cleanName)) quality = "4K";
  else if (/1080p|fhd/i.test(cleanName)) quality = "1080p";
  else if (/720p|hd/i.test(cleanName)) quality = "720p";
  else if (/480p|sd/i.test(cleanName)) quality = "480p";

  return {
    filename,
    episodeNumber,
    language,
    quality,
    suggestedTitle: `Episode ${episodeNumber}`
  };
}

export function getDisplayTitle(anime: Partial<Anime> | undefined | null): string {
  if (!anime) return "Untitled Anime";
  return anime.title || anime.englishTitle || anime.japaneseTitle || anime.romajiTitle || "Untitled Anime";
}
