import { 
  MissingEpisodeAlert, 
  MissingEpisodeReason, 
  MissingEpisodePriority, 
  MissingEpisodeStatus 
} from "../types";

const LOCAL_STORAGE_KEY = "animestream_missing_episode_alerts";

// Helper to get alerts from localStorage
export function getMissingAlerts(): MissingEpisodeAlert[] {
  try {
    const raw = localStorage.getItem(LOCAL_STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      return parsed;
    }
    return [];
  } catch (err) {
    console.error("Error loading missing episode alerts from localStorage:", err);
    return [];
  }
}

// Helper to save alerts to localStorage and notify listeners
export function saveMissingAlerts(alerts: MissingEpisodeAlert[]): void {
  try {
    localStorage.setItem(LOCAL_STORAGE_KEY, JSON.stringify(alerts));
    window.dispatchEvent(new CustomEvent("animestream_missing_alerts_updated"));
  } catch (err) {
    console.error("Error saving missing episode alerts to localStorage:", err);
  }
}

// Add a new missing alert
export function addMissingAlert(
  data: Omit<MissingEpisodeAlert, "id" | "createdAt">
): MissingEpisodeAlert {
  const alerts = getMissingAlerts();
  
  // Check if an alert for this exact anime, season, and episode number already exists
  const existingIndex = alerts.findIndex(
    (a) =>
      a.animeName.trim().toLowerCase() === data.animeName.trim().toLowerCase() &&
      String(a.season) === String(data.season) &&
      Number(a.episodeNumber) === Number(data.episodeNumber) &&
      a.status !== "Uploaded" &&
      a.status !== "Ignored"
  );

  if (existingIndex >= 0) {
    // Update existing
    alerts[existingIndex] = {
      ...alerts[existingIndex],
      ...data,
      updatedAt: Date.now(),
    };
    saveMissingAlerts(alerts);
    return alerts[existingIndex];
  }

  const newAlert: MissingEpisodeAlert = {
    ...data,
    id: `missing_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`,
    createdAt: Date.now(),
  };

  alerts.unshift(newAlert);
  saveMissingAlerts(alerts);
  return newAlert;
}

// Update existing alert
export function updateMissingAlert(id: string, updates: Partial<MissingEpisodeAlert>): void {
  const alerts = getMissingAlerts();
  const index = alerts.findIndex((a) => a.id === id);
  if (index >= 0) {
    alerts[index] = {
      ...alerts[index],
      ...updates,
      updatedAt: Date.now(),
    };
    saveMissingAlerts(alerts);
  }
}

// Delete alert
export function deleteMissingAlert(id: string): void {
  const alerts = getMissingAlerts();
  const filtered = alerts.filter((a) => a.id !== id);
  saveMissingAlerts(filtered);
}

// Auto detection helper for missing episode numbers in a sequence
export function detectMissingEpisodeNumbers(episodesList: number[]): number[] {
  if (!episodesList || episodesList.length === 0) return [];
  
  // Clean and sort unique numbers
  const sorted = Array.from(new Set(episodesList))
    .map((num) => Number(num))
    .filter((num) => !isNaN(num) && num > 0)
    .sort((a, b) => a - b);

  if (sorted.length <= 1) return [];

  const missing: number[] = [];
  const min = sorted[0];
  const max = sorted[sorted.length - 1];

  for (let i = min; i < max; i++) {
    if (!sorted.includes(i)) {
      missing.push(i);
    }
  }

  return missing;
}

// Automatically resolve / mark uploaded when an episode is uploaded
export function resolveMissingAlertOnUpload(
  animeName: string,
  season: number | string,
  episodeNumber: number
): boolean {
  const alerts = getMissingAlerts();
  let modified = false;

  const targetAnime = animeName.trim().toLowerCase();
  const targetSeason = String(season);
  const targetEp = Number(episodeNumber);

  alerts.forEach((alert) => {
    if (
      alert.animeName.trim().toLowerCase() === targetAnime &&
      String(alert.season) === targetSeason &&
      Number(alert.episodeNumber) === targetEp &&
      alert.status !== "Uploaded"
    ) {
      alert.status = "Uploaded";
      alert.updatedAt = Date.now();
      modified = true;
    }
  });

  if (modified) {
    saveMissingAlerts(alerts);
  }

  return modified;
}

// Export CSV
export function exportAlertsToCSV(alerts: MissingEpisodeAlert[]): void {
  const headers = [
    "ID",
    "Anime Name",
    "Season",
    "Episode Number",
    "Language",
    "Reason",
    "Priority",
    "Status",
    "Notes",
    "Date Added"
  ];

  const rows = alerts.map((a) => [
    `"${a.id}"`,
    `"${a.animeName.replace(/"/g, '""')}"`,
    `"${a.season}"`,
    a.episodeNumber,
    `"${a.language.replace(/"/g, '""')}"`,
    `"${a.reason}"`,
    `"${a.priority}"`,
    `"${a.status}"`,
    `"${(a.notes || "").replace(/"/g, '""')}"`,
    `"${new Date(a.createdAt).toLocaleString()}"`
  ]);

  const csvContent = [headers.join(","), ...rows.map((r) => r.join(","))].join("\n");
  const blob = new Blob([csvContent], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.setAttribute("href", url);
  link.setAttribute("download", `missing_episodes_export_${new Date().toISOString().slice(0, 10)}.csv`);
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
}

// Export JSON
export function exportAlertsToJSON(alerts: MissingEpisodeAlert[]): void {
  const jsonStr = JSON.stringify(alerts, null, 2);
  const blob = new Blob([jsonStr], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.setAttribute("href", url);
  link.setAttribute("download", `missing_episodes_export_${new Date().toISOString().slice(0, 10)}.json`);
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
}

// Import JSON
export function importAlertsFromJSON(jsonString: string): number {
  try {
    const parsed = JSON.parse(jsonString);
    if (!Array.isArray(parsed)) return 0;

    const currentAlerts = getMissingAlerts();
    let importedCount = 0;

    parsed.forEach((item: any) => {
      if (item.animeName && item.episodeNumber) {
        const newAlert: MissingEpisodeAlert = {
          id: item.id || `missing_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`,
          animeName: String(item.animeName),
          season: item.season || 1,
          episodeNumber: Number(item.episodeNumber),
          language: item.language || "Sub",
          reason: item.reason || "Video Not Available",
          priority: item.priority || "Medium",
          status: item.status || "Missing",
          notes: item.notes || "",
          createdAt: item.createdAt || Date.now(),
        };
        currentAlerts.unshift(newAlert);
        importedCount++;
      }
    });

    saveMissingAlerts(currentAlerts);
    return importedCount;
  } catch (err) {
    console.error("JSON Import failed:", err);
    throw new Error("Invalid JSON file format.");
  }
}
