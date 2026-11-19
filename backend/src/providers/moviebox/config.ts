import { env } from "../../config/env";

export const MOVIEBOX_CONFIG = {
  hostPool: env.movieboxHosts,
  requestTimeoutMs: 12_000,
  captionTimeoutMs: 6_000,
  retryBackoffMs: 50,
  maxRateLimitDelayMs: 3_000,
  sessionMaxAgeSeconds: 7 * 24 * 3600,
  sessionExpiryBufferSeconds: 60,
  signatureBodyMaxBytes: 102_400,
  perPageSearch: 15,
  perPageResources: 20,
  endpoints: {
    visitorLogin: "/wefeed-mobile-bff/user-api/visitor-login",
    homepage: "/wefeed-mobile-bff/tab-operating",
    search: "/wefeed-mobile-bff/subject-api/search/v2",
    details: "/wefeed-mobile-bff/subject-api/get",
    seasonInfo: "/wefeed-mobile-bff/subject-api/season-info",
    resourceV1: "/wefeed-mobile-bff/subject-api/resource",
    resourceV2: "/wefeed-mobile-bff/subject-api/resource/v2",
    playInfoV2: "/wefeed-mobile-bff/subject-api/play-info/v2",
    resourcePositionV2: "/wefeed-mobile-bff/subject-api/resource-position/v2",
    extCaptions: "/wefeed-mobile-bff/subject-api/get-ext-captions",
  },
} as const;
