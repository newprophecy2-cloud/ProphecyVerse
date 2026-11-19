// Geolocation & Language detection utilities for Notice system

let cachedIsBangladesh: boolean | null = null;

export async function detectIsBangladeshUser(): Promise<boolean> {
  if (cachedIsBangladesh !== null) {
    return cachedIsBangladesh;
  }

  // Check SessionStorage first
  const sessionValue = sessionStorage.getItem("animestream_user_country_bd");
  if (sessionValue !== null) {
    cachedIsBangladesh = sessionValue === "true";
    return cachedIsBangladesh;
  }

  try {
    // Try fast IP geolocation service with 2.5s timeout
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 2500);

    const res = await fetch("https://ipapi.co/json/", { signal: controller.signal });
    clearTimeout(timeoutId);

    if (res.ok) {
      const data = await res.json();
      const countryCode = data?.country_code || data?.country;
      const isBD = countryCode === "BD";
      cachedIsBangladesh = isBD;
      sessionStorage.setItem("animestream_user_country_bd", isBD ? "true" : "false");
      return isBD;
    }
  } catch {
    // Fallback service 2: ipwho.is
    try {
      const controller2 = new AbortController();
      const timeoutId2 = setTimeout(() => controller2.abort(), 2000);
      const res2 = await fetch("https://ipwho.is/", { signal: controller2.signal });
      clearTimeout(timeoutId2);
      if (res2.ok) {
        const data2 = await res2.json();
        const isBD = data2?.country_code === "BD";
        cachedIsBangladesh = isBD;
        sessionStorage.setItem("animestream_user_country_bd", isBD ? "true" : "false");
        return isBD;
      }
    } catch {
      // Fallback 3: Navigator browser locale
      const navLangs = [...(navigator.languages || []), navigator.language || ""];
      const isBD = navLangs.some(
        (lang) => lang.toLowerCase().includes("bn") || lang.toLowerCase().includes("bd")
      );
      cachedIsBangladesh = isBD;
      sessionStorage.setItem("animestream_user_country_bd", isBD ? "true" : "false");
      return isBD;
    }
  }

  // Default fallback: Browser language check
  const navLangs = [...(navigator.languages || []), navigator.language || ""];
  const isBD = navLangs.some(
    (lang) => lang.toLowerCase().includes("bn") || lang.toLowerCase().includes("bd")
  );
  cachedIsBangladesh = isBD;
  sessionStorage.setItem("animestream_user_country_bd", isBD ? "true" : "false");
  return isBD;
}
