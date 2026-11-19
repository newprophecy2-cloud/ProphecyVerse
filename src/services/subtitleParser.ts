export interface SubtitleCue {
  id: string;
  start: number;
  end: number;
  text: string;
}

function decodeSubtitleEntities(text: string): string {
  return text
    .replace(/\{\\an\d+\}/gi, "")
    .replace(/<[^>]*>/g, "")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .replace(/&nbsp;/gi, " ")
    .trim();
}

export function parseVttOrSrtCues(rawContent: string): SubtitleCue[] {
  if (!rawContent || typeof rawContent !== "string") return [];
  const cues: SubtitleCue[] = [];
  const normalized = rawContent
    .replace(/^\uFEFF/, "")
    .replace(/\r\n/g, "\n")
    .replace(/\r/g, "\n");
  const lines = normalized.split("\n");
  let currentStart = -1;
  let currentEnd = -1;
  let textLines: string[] = [];

  const timeRegex = /(?:(\d{1,2}):)?(\d{2}):(\d{2})[\.,](\d{3})/;
  const parseTime = (timeStr: string): number => {
    const match = timeStr.trim().match(timeRegex);
    if (!match) return -1;
    const hours = match[1] ? parseInt(match[1], 10) : 0;
    const minutes = parseInt(match[2], 10);
    const seconds = parseInt(match[3], 10);
    const ms = parseInt(match[4], 10);
    return hours * 3600 + minutes * 60 + seconds + ms / 1000;
  };

  const flushCue = () => {
    if (currentStart >= 0 && currentEnd > currentStart && textLines.length > 0) {
      const cleanedText = decodeSubtitleEntities(textLines.join("\n"));
      if (cleanedText) {
        cues.push({
          id: `cue-${cues.length + 1}`,
          start: currentStart,
          end: currentEnd,
          text: cleanedText,
        });
      }
    }
    currentStart = -1;
    currentEnd = -1;
    textLines = [];
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line || line.startsWith("WEBVTT") || line.startsWith("NOTE") || line.startsWith("STYLE")) {
      flushCue();
      continue;
    }

    if (line.includes("-->")) {
      flushCue();
      const parts = line.split("-->");
      if (parts.length >= 2) {
        const startVal = parseTime(parts[0]);
        const endToken = parts[1].trim().split(/\s+/)[0] || "";
        const endVal = parseTime(endToken);
        if (startVal >= 0 && endVal > startVal) {
          currentStart = startVal;
          currentEnd = endVal;
          textLines = [];
        }
      }
    } else if (currentStart >= 0) {
      if (!/^\d+$/.test(line) || textLines.length > 0) {
        textLines.push(line);
      }
    }
  }

  flushCue();
  return cues;
}
