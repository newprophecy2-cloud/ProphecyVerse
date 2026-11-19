import { MovieBoxPlaybackResult } from "./types";

export const MOCK_SUBJECTS = [
  {
    subjectId: "mock-series-101",
    title: "Neon Horizon: Chronicles",
    originalTitle: "Neon Horizon",
    subjectType: 2,
    releaseDate: "2024-04-12",
    season: 2,
    imdbRatingValue: 8.9,
    countryName: "Japan",
    director: "Kenji Takahashi",
    stars: "Aoi Sato, Ren Kobayashi",
    duration: 1440,
    genre: ["Sci-Fi", "Action", "Animation"],
    description:
      "In a fractured orbital metropolis, an outcast pilot uncovers a signal connecting lost colony fleets.",
    cover: {
      url: "https://images.unsplash.com/photo-1578632767115-351597cf2477?q=80&w=600",
    },
    backdrop: {
      url: "https://images.unsplash.com/photo-1607604276583-eef5d076aa5f?q=80&w=1600",
    },
    seasons: [
      { se: 1, maxEp: 3 },
      { se: 2, maxEp: 2 },
    ],
    dubs: [
      { subjectId: "mock-series-101", lanName: "English", title: "English Audio" },
      { subjectId: "mock-series-101-ja", lanName: "Japanese", title: "Japanese Audio" },
    ],
  },
  {
    subjectId: "mock-movie-202",
    title: "Orbital Drift",
    originalTitle: "Orbital Drift",
    subjectType: 1,
    releaseDate: "2025-01-18",
    imdbRatingValue: 8.4,
    countryName: "United States",
    director: "Elena Vance",
    stars: "Marcus Reed, Claire Lin",
    duration: 6480,
    genre: ["Thriller", "Adventure", "Sci-Fi"],
    description:
      "A salvage crew intercepts a drifting research vessel carrying an encrypted quantum archive.",
    cover: {
      url: "https://images.unsplash.com/photo-1536440136628-849c177e76a1?q=80&w=600",
    },
    backdrop: {
      url: "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?q=80&w=1600",
    },
    seasons: [],
    dubs: [{ subjectId: "mock-movie-202", lanName: "English", title: "English Audio" }],
  },
];

export function getMockHomepagePayload() {
  return {
    tabId: "0",
    items: [
      {
        banner: {
          banners: [{ subject: MOCK_SUBJECTS[0] }],
        },
        subjects: MOCK_SUBJECTS,
      },
    ],
  };
}

export function getMockSearchPayload(query: string) {
  const q = query.trim().toLowerCase();
  const matches = MOCK_SUBJECTS.filter(
    (s) =>
      s.title.toLowerCase().includes(q) ||
      s.description.toLowerCase().includes(q) ||
      s.genre.some((g) => g.toLowerCase().includes(q))
  );
  return {
    results: [{ subjects: matches.length > 0 ? matches : MOCK_SUBJECTS }],
    pager: { hasMore: false, totalCount: matches.length > 0 ? matches.length : MOCK_SUBJECTS.length },
  };
}

export function getMockDetailsPayload(subjectId: string) {
  const normalized = subjectId === "mock-series-101-ja" ? "mock-series-101" : subjectId;
  const match = MOCK_SUBJECTS.find((s) => s.subjectId === normalized) || MOCK_SUBJECTS[0];
  return {
    subject: {
      ...match,
      subjectId,
    },
    seasons: {
      seasons: match.seasons,
    },
  };
}

export function getMockPlaybackResult(
  subjectId: string,
  season: number,
  episode: number
): MovieBoxPlaybackResult {
  const isSeries = subjectId.startsWith("mock-series");
  const se = isSeries ? Math.max(1, season || 1) : 0;
  const ep = isSeries ? Math.max(1, episode || 1) : 0;

  return {
    subjectId,
    season: se,
    episode: ep,
    resourceId: `${subjectId}-res-${se}-${ep}`,
    streams: [
      {
        id: `mock-hls-${se}-${ep}`,
        resourceId: `${subjectId}-res-${se}-${ep}`,
        label: "720p H264 HLS",
        quality: "720p",
        resolution: 720,
        format: "hls",
        codec: "h264",
        language: subjectId.endsWith("-ja") ? "Japanese" : "English",
        url: `/api/v1/mock-media/${encodeURIComponent(subjectId)}/playlist.m3u8`,
        requiresCustomHeaders: false,
        hasSignedCookie: false,
        browserCompatible: true,
      },
      {
        id: `mock-mp4-${se}-${ep}`,
        resourceId: `${subjectId}-res-${se}-${ep}`,
        label: "480p H264 MP4",
        quality: "480p",
        resolution: 480,
        format: "mp4",
        codec: "h264",
        language: subjectId.endsWith("-ja") ? "Japanese" : "English",
        url: `/api/v1/mock-media/${encodeURIComponent(subjectId)}/sample.mp4`,
        requiresCustomHeaders: false,
        hasSignedCookie: false,
        browserCompatible: true,
      },
    ],
    subtitles: [
      {
        id: "mock-sub-en",
        name: "English",
        languageCode: "en",
        url: `/api/v1/mock-media/${encodeURIComponent(subjectId)}/subtitles-en.vtt`,
        format: "vtt",
      },
      {
        id: "mock-sub-es",
        name: "Spanish",
        languageCode: "es",
        url: `/api/v1/mock-media/${encodeURIComponent(subjectId)}/subtitles-es.srt`,
        format: "srt",
      },
    ],
    audioTracks: [
      { subjectId: "mock-series-101", language: "English", label: "English Audio" },
      { subjectId: "mock-series-101-ja", language: "Japanese", label: "Japanese Audio" },
    ],
    placeholderCount: 0,
    fetchedAt: Date.now(),
  };
}
