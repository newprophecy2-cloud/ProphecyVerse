export interface DetectedMediaAudioTrack {
  id: string;
  trackIndex: number;
  language: string;       // e.g. "Japanese", "English", "Hindi", "Bengali", "Unknown"
  languageCode: string;   // e.g. "ja", "en", "hi", "bn", "und"
  label: string;          // e.g. "Japanese", "Audio Track 1"
  codec: string;          // e.g. "AAC", "AC-3", "Opus", "MP3", "E-AC3", "FLAC"
  channels: number;       // e.g. 2, 6, 1
  channelLayout: string;  // e.g. "Stereo", "5.1 Surround", "Mono"
  sampleRate: number;     // e.g. 48000, 44100
  bitrate?: number;       // e.g. 192000
  isDefault: boolean;
  disposition?: string;
  isUserModified?: boolean;
}

export interface VideoMediaAnalysisResult {
  hasAudio: boolean;
  audioTracksCount: number;
  tracks: DetectedMediaAudioTrack[];
  durationSeconds?: number;
  resolution?: string;
  warnings: string[];
  error?: string;
}

/**
 * Maps raw ISO language code or string to standardized display language and ISO-2 code.
 */
export function normalizeLanguageCode(raw?: string): { name: string; code: string; flag: string } {
  if (!raw) return { name: "Unknown", code: "und", flag: "🌐" };
  const str = raw.trim().toLowerCase();

  if (str === "und" || str === "unk" || str === "unknown" || str === "undetermined" || str === "root") {
    return { name: "Unknown", code: "und", flag: "🌐" };
  }

  if (str.includes("jpn") || str.includes("ja") || str.includes("japanese")) {
    return { name: "Japanese", code: "ja", flag: "🇯🇵" };
  }
  if (str.includes("eng") || str.includes("en") || str.includes("english")) {
    return { name: "English", code: "en", flag: "🇺🇸" };
  }
  if (str.includes("hin") || str.includes("hi") || str.includes("hindi")) {
    return { name: "Hindi", code: "hi", flag: "🇮🇳" };
  }
  if (str.includes("ben") || str.includes("bn") || str.includes("bengali") || str.includes("bangla")) {
    return { name: "Bengali", code: "bn", flag: "🇧🇩" };
  }
  if (str.includes("spa") || str.includes("es") || str.includes("spanish")) {
    return { name: "Spanish", code: "es", flag: "🇪🇸" };
  }
  if (str.includes("kor") || str.includes("ko") || str.includes("korean")) {
    return { name: "Korean", code: "ko", flag: "🇰🇷" };
  }
  if (str.includes("tam") || str.includes("ta") || str.includes("tamil")) {
    return { name: "Tamil", code: "ta", flag: "🇮🇳" };
  }
  if (str.includes("tel") || str.includes("te") || str.includes("telugu")) {
    return { name: "Telugu", code: "te", flag: "🇮🇳" };
  }
  if (str.includes("fre") || str.includes("fra") || str.includes("fr") || str.includes("french")) {
    return { name: "French", code: "fr", flag: "🇫🇷" };
  }
  if (str.includes("ger") || str.includes("deu") || str.includes("de") || str.includes("german")) {
    return { name: "German", code: "de", flag: "🇩🇪" };
  }

  const capitalized = raw.charAt(0).toUpperCase() + raw.slice(1);
  return { name: capitalized, code: raw.slice(0, 3).toLowerCase(), flag: "🌐" };
}

/**
 * Format channel count to layout description
 */
export function getChannelLayout(channels: number): string {
  if (channels === 1) return "Mono";
  if (channels === 2) return "Stereo";
  if (channels === 6) return "5.1 Surround";
  if (channels === 8) return "7.1 Surround";
  if (channels > 2) return `${channels} Channels`;
  return "Stereo";
}

/**
 * Unpacks MP4 packed ISO 639-2 language code (3 5-bit characters)
 */
function parseMp4LanguageCode(langInt: number): string {
  const c1 = String.fromCharCode(((langInt >> 10) & 0x1f) + 0x60);
  const c2 = String.fromCharCode(((langInt >> 5) & 0x1f) + 0x60);
  const c3 = String.fromCharCode((langInt & 0x1f) + 0x60);
  const code = `${c1}${c2}${c3}`;
  return /^[a-z]{3}$/.test(code) ? code : "und";
}

/**
 * Helper to read UTF-8 string from DataView
 */
function readUtf8String(view: DataView, offset: number, length: number): string {
  let str = "";
  for (let i = 0; i < length; i++) {
    const charCode = view.getUint8(offset + i);
    if (charCode === 0) break;
    str += String.fromCharCode(charCode);
  }
  return str;
}

/**
 * Fast Binary Header Parser for Matroska (.mkv, .webm) files
 */
async function inspectMatroskaHeader(file: File): Promise<DetectedMediaAudioTrack[]> {
  const maxBytesToRead = Math.min(file.size, 12 * 1024 * 1024); // read up to 12MB for tracks header
  const slice = file.slice(0, maxBytesToRead);
  const buffer = await slice.arrayBuffer();
  const view = new DataView(buffer);
  const u8 = new Uint8Array(buffer);

  const tracks: DetectedMediaAudioTrack[] = [];

  // Helper to read VarInt (EBML ID or Size)
  const readVarInt = (offset: number): { value: number; length: number; rawBytes: number } => {
    if (offset >= u8.length) return { value: -1, length: 0, rawBytes: 0 };
    const firstByte = u8[offset];
    let mask = 0x80;
    let length = 1;

    while (length <= 8 && (firstByte & mask) === 0) {
      mask >>= 1;
      length++;
    }

    if (length > 8) return { value: -1, length: 0, rawBytes: 0 };

    let value = firstByte & (mask - 1);
    for (let i = 1; i < length; i++) {
      value = (value << 8) | u8[offset + i];
    }

    let rawBytes = 0;
    for (let i = 0; i < length; i++) {
      rawBytes = (rawBytes << 8) | u8[offset + i];
    }

    return { value, length, rawBytes };
  };

  // Find Tracks Element ID 0x1654AE6B
  let tracksOffset = -1;
  for (let i = 0; i < u8.length - 4; i++) {
    if (u8[i] === 0x16 && u8[i + 1] === 0x54 && u8[i + 2] === 0xAE && u8[i + 3] === 0x6B) {
      tracksOffset = i;
      break;
    }
  }

  if (tracksOffset === -1) return tracks;

  const tracksHeaderSize = readVarInt(tracksOffset + 4);
  let pos = tracksOffset + 4 + tracksHeaderSize.length;
  const endPos = Math.min(u8.length, pos + tracksHeaderSize.value);

  let audioTrackCounter = 0;

  while (pos < endPos - 4) {
    const elemId = readVarInt(pos);
    if (elemId.length === 0) break;
    pos += elemId.length;

    const elemSize = readVarInt(pos);
    if (elemSize.length === 0) break;
    pos += elemSize.length;

    // TrackEntry ID is 0xAE
    if (elemId.value === 0x2E || (u8[pos - elemSize.length - elemId.length] === 0xAE)) {
      const trackEntryEnd = pos + elemSize.value;
      let trackType = 0;
      let codecId = "";
      let lang = "und";
      let name = "";
      let isDefault = false;
      let channels = 2;
      let sampleRate = 48000;

      let childPos = pos;
      while (childPos < trackEntryEnd && childPos < u8.length - 2) {
        const cId = readVarInt(childPos);
        if (cId.length === 0) break;
        childPos += cId.length;

        const cSize = readVarInt(childPos);
        if (cSize.length === 0) break;
        childPos += cSize.length;

        const valStart = childPos;
        childPos += cSize.value;

        // TrackType ID 0x83
        if (u8[valStart - cSize.length - cId.length] === 0x83 && cSize.value === 1) {
          trackType = u8[valStart];
        }
        // CodecID ID 0x86
        else if (u8[valStart - cSize.length - cId.length] === 0x86) {
          codecId = readUtf8String(view, valStart, cSize.value);
        }
        // Language ID 0x22B59C (3 bytes ID: 22 B5 9C) or LanguageIETF
        else if (cSize.value <= 20 && (readUtf8String(view, valStart, cSize.value).length > 1)) {
          const possibleLang = readUtf8String(view, valStart, cSize.value).toLowerCase();
          if (/^[a-z]{2,3}(-[a-z0-9]+)?$/.test(possibleLang)) {
            lang = possibleLang;
          }
        }
        // Name ID 0x536E
        else if (u8[valStart - cSize.length - cId.length] === 0x53 && u8[valStart - cSize.length - cId.length + 1] === 0x6E) {
          name = readUtf8String(view, valStart, cSize.value);
        }
        // FlagDefault ID 0x88
        else if (u8[valStart - cSize.length - cId.length] === 0x88 && cSize.value === 1) {
          isDefault = u8[valStart] === 1;
        }
        // Audio Settings Container ID 0xE1
        else if (u8[valStart - cSize.length - cId.length] === 0xE1) {
          let aPos = valStart;
          const aEnd = valStart + cSize.value;
          while (aPos < aEnd && aPos < u8.length) {
            const aId = readVarInt(aPos);
            if (aId.length === 0) break;
            aPos += aId.length;
            const aSize = readVarInt(aPos);
            if (aSize.length === 0) break;
            aPos += aSize.length;
            const aVal = aPos;
            aPos += aSize.value;

            // Channels ID 0x9F
            if (u8[aVal - aSize.length - aId.length] === 0x9F && aSize.value === 1) {
              channels = u8[aVal];
            }
            // SamplingFrequency ID 0xB5
            else if (u8[aVal - aSize.length - aId.length] === 0xB5) {
              if (aSize.value === 4) {
                sampleRate = Math.round(view.getFloat32(aVal));
              } else if (aSize.value === 8) {
                sampleRate = Math.round(view.getFloat64(aVal));
              }
            }
          }
        }
      }

      // TrackType 2 is Audio
      if (trackType === 2) {
        audioTrackCounter++;
        const langInfo = normalizeLanguageCode(lang);
        let cleanCodec = "AAC";
        if (codecId.includes("AC3")) cleanCodec = "AC-3";
        else if (codecId.includes("EAC3")) cleanCodec = "E-AC3";
        else if (codecId.includes("OPUS")) cleanCodec = "Opus";
        else if (codecId.includes("MPEG/L3")) cleanCodec = "MP3";
        else if (codecId.includes("FLAC")) cleanCodec = "FLAC";
        else if (codecId.includes("PCM")) cleanCodec = "PCM";

        tracks.push({
          id: `track-${audioTrackCounter}`,
          trackIndex: audioTrackCounter,
          language: langInfo.name,
          languageCode: langInfo.code,
          label: name || `${langInfo.name} (${getChannelLayout(channels)})`,
          codec: cleanCodec,
          channels: channels || 2,
          channelLayout: getChannelLayout(channels || 2),
          sampleRate: sampleRate || 48000,
          isDefault: isDefault || audioTrackCounter === 1
        });
      }

      pos = trackEntryEnd;
    } else {
      pos += elemSize.value;
    }
  }

  return tracks;
}

/**
 * Fast Binary Header Parser for MP4 / MOV files
 */
async function inspectMp4Header(file: File): Promise<DetectedMediaAudioTrack[]> {
  const maxBytesToRead = Math.min(file.size, 16 * 1024 * 1024);
  const slice = file.slice(0, maxBytesToRead);
  const buffer = await slice.arrayBuffer();
  const view = new DataView(buffer);
  const u8 = new Uint8Array(buffer);

  const tracks: DetectedMediaAudioTrack[] = [];
  let pos = 0;
  let audioTrackCounter = 0;

  // Search for 'moov' atom
  let moovOffset = -1;
  while (pos < u8.length - 8) {
    const boxSize = view.getUint32(pos);
    const boxType = readUtf8String(view, pos + 4, 4);

    if (boxType === "moov") {
      moovOffset = pos;
      break;
    }

    if (boxSize === 0) break;
    pos += (boxSize === 1 ? view.getUint32(pos + 8) : boxSize);
  }

  if (moovOffset === -1) return tracks;

  // Search for 'trak' atoms inside moov
  const moovEnd = moovOffset + view.getUint32(moovOffset);
  let trakPos = moovOffset + 8;

  while (trakPos < moovEnd - 8 && trakPos < u8.length - 8) {
    const tSize = view.getUint32(trakPos);
    const tType = readUtf8String(view, trakPos + 4, 4);

    if (tType === "trak") {
      const trakEnd = trakPos + tSize;
      let isAudioTrak = false;
      let langCode = "und";
      let codec = "AAC";
      let channels = 2;
      let sampleRate = 48000;
      let trackName = "";

      let subPos = trakPos + 8;
      while (subPos < trakEnd - 8 && subPos < u8.length - 8) {
        const sSize = view.getUint32(subPos);
        const sType = readUtf8String(view, subPos + 4, 4);

        if (sType === "mdia") {
          const mdiaEnd = subPos + sSize;
          let mPos = subPos + 8;

          while (mPos < mdiaEnd - 8 && mPos < u8.length - 8) {
            const mSize = view.getUint32(mPos);
            const mType = readUtf8String(view, mPos + 4, 4);

            if (mType === "hdlr") {
              const handlerType = readUtf8String(view, mPos + 16, 4);
              if (handlerType === "soun") {
                isAudioTrak = true;
              }
            } else if (mType === "mdhd") {
              const version = view.getUint8(mPos + 8);
              const langOffset = version === 1 ? mPos + 28 : mPos + 20;
              const rawLang = view.getUint16(langOffset);
              langCode = parseMp4LanguageCode(rawLang);
            } else if (mType === "minf") {
              const minfEnd = mPos + mSize;
              let minfPos = mPos + 8;
              while (minfPos < minfEnd - 8 && minfPos < u8.length - 8) {
                const stSize = view.getUint32(minfPos);
                const stType = readUtf8String(view, minfPos + 4, 4);
                if (stType === "stbl") {
                  let stblPos = minfPos + 8;
                  const stblEnd = minfPos + stSize;
                  while (stblPos < stblEnd - 8 && stblPos < u8.length - 8) {
                    const sdSize = view.getUint32(stblPos);
                    const sdType = readUtf8String(view, stblPos + 4, 4);
                    if (sdType === "stsd") {
                      // First entry in stsd is sample description
                      const entryType = readUtf8String(view, stblPos + 20, 4);
                      if (entryType) {
                        if (entryType === "mp4a") codec = "AAC";
                        else if (entryType === "ac-3") codec = "AC-3";
                        else if (entryType === "ec-3") codec = "E-AC3";
                        else if (entryType === "opus") codec = "Opus";
                        else codec = entryType.toUpperCase();
                      }
                      channels = view.getUint16(stblPos + 32) || 2;
                      sampleRate = (view.getUint32(stblPos + 40) >> 16) || 48000;
                    }
                    stblPos += sdSize;
                  }
                }
                minfPos += stSize;
              }
            }
            mPos += mSize;
          }
        }
        subPos += sSize;
      }

      if (isAudioTrak) {
        audioTrackCounter++;
        const langInfo = normalizeLanguageCode(langCode);
        tracks.push({
          id: `track-${audioTrackCounter}`,
          trackIndex: audioTrackCounter,
          language: langInfo.name,
          languageCode: langInfo.code,
          label: trackName || `${langInfo.name} (${getChannelLayout(channels)})`,
          codec,
          channels,
          channelLayout: getChannelLayout(channels),
          sampleRate,
          isDefault: audioTrackCounter === 1
        });
      }

      trakPos += tSize;
    } else {
      trakPos += tSize;
    }
  }

  return tracks;
}

/**
 * Fallback via HTML5 <video> element audioTracks API
 */
async function inspectVideoViaMediaElement(file: File): Promise<{ tracks: DetectedMediaAudioTrack[]; duration?: number; resolution?: string }> {
  return new Promise((resolve) => {
    const video = document.createElement("video");
    video.preload = "metadata";
    video.muted = true;
    video.playsInline = true;

    const objectUrl = URL.createObjectURL(file);
    video.src = objectUrl;

    const cleanup = () => {
      URL.revokeObjectURL(objectUrl);
      video.remove();
    };

    const timeout = setTimeout(() => {
      cleanup();
      resolve({ tracks: [] });
    }, 4000);

    video.onloadedmetadata = () => {
      clearTimeout(timeout);
      const duration = video.duration || 0;
      const resolution = video.videoWidth && video.videoHeight ? `${video.videoWidth}x${video.videoHeight}` : undefined;

      const detectedTracks: DetectedMediaAudioTrack[] = [];
      const nativeTracks = (video as any).audioTracks;

      if (nativeTracks && nativeTracks.length > 0) {
        for (let i = 0; i < nativeTracks.length; i++) {
          const tr = nativeTracks[i];
          const langInfo = normalizeLanguageCode(tr.language || tr.label);
          detectedTracks.push({
            id: tr.id || `track-${i + 1}`,
            trackIndex: i + 1,
            language: langInfo.name,
            languageCode: langInfo.code,
            label: tr.label || `${langInfo.name} (Track ${i + 1})`,
            codec: "AAC",
            channels: 2,
            channelLayout: "Stereo",
            sampleRate: 48000,
            isDefault: tr.enabled || i === 0
          });
        }
      }

      cleanup();
      resolve({ tracks: detectedTracks, duration, resolution });
    };

    video.onerror = () => {
      clearTimeout(timeout);
      cleanup();
      resolve({ tracks: [] });
    };
  });
}

/**
 * Main Analysis Function: Inspects video file and returns audio track metadata.
 */
export async function analyzeVideoAudioTracks(file: File): Promise<VideoMediaAnalysisResult> {
  const warnings: string[] = [];

  if (!file) {
    return {
      hasAudio: false,
      audioTracksCount: 0,
      tracks: [],
      warnings: ["No file provided."]
    };
  }

  // 1. Try server-side FFprobe media analysis first for 100% authoritative detection
  try {
    const maxSliceSize = Math.min(file.size, 32 * 1024 * 1024); // 32MB slice is more than enough for MKV/MP4 headers
    const slice = file.slice(0, maxSliceSize);
    const serverRes = await fetch("/api/analyze-video", {
      method: "POST",
      headers: { "Content-Type": "application/octet-stream" },
      body: slice
    });

    if (serverRes.ok) {
      const data = await serverRes.json();
      if (data.success && Array.isArray(data.tracks) && data.tracks.length > 0) {
        return {
          hasAudio: true,
          audioTracksCount: data.tracks.length,
          tracks: data.tracks,
          durationSeconds: data.durationSeconds,
          warnings: data.warnings || []
        };
      }
    }
  } catch (serverErr) {
    console.warn("Server FFprobe analysis unavailable, falling back to local binary parser:", serverErr);
  }

  try {
    let tracks: DetectedMediaAudioTrack[] = [];
    const ext = file.name.split(".").pop()?.toLowerCase() || "";

    if (ext === "mkv" || ext === "webm") {
      tracks = await inspectMatroskaHeader(file);
    } else if (ext === "mp4" || ext === "mov" || ext === "m4v") {
      tracks = await inspectMp4Header(file);
    }

    // Fallback to HTMLMediaElement if container header parse returned empty or for other video formats
    const mediaElementRes = await inspectVideoViaMediaElement(file);

    if (tracks.length === 0 && mediaElementRes.tracks.length > 0) {
      tracks = mediaElementRes.tracks;
    }

    // If still empty but browser can play audio, check if it has 1 default audio track
    if (tracks.length === 0) {
      // If extension is known video format, we assume 1 audio track unless media analysis explicitly fails
      if (["mp4", "mkv", "mov", "webm", "avi"].includes(ext)) {
        tracks = [{
          id: "track-1",
          trackIndex: 1,
          language: "Unknown",
          languageCode: "und",
          label: "Unknown / Undetermined",
          codec: "AAC",
          channels: 2,
          channelLayout: "Stereo",
          sampleRate: 48000,
          isDefault: true
        }];
        warnings.push("Language metadata was missing. Assigned Unknown / Undetermined label.");
      }
    }

    if (tracks.length === 0) {
      return {
        hasAudio: false,
        audioTracksCount: 0,
        tracks: [],
        durationSeconds: mediaElementRes.duration,
        resolution: mediaElementRes.resolution,
        warnings: ["⚠ No audio track detected in source video file."]
      };
    }

    // Check for unknown languages or missing default track
    const hasUnknown = tracks.some(t => t.language === "Unknown" || t.languageCode === "und");
    if (hasUnknown) {
      warnings.push("One or more audio tracks have unknown language metadata.");
    }

    // Ensure exactly 1 track is default
    const defaultCount = tracks.filter(t => t.isDefault).length;
    if (defaultCount === 0 && tracks.length > 0) {
      tracks[0].isDefault = true;
    } else if (defaultCount > 1) {
      tracks.forEach((t, i) => { t.isDefault = i === 0; });
    }

    return {
      hasAudio: tracks.length > 0,
      audioTracksCount: tracks.length,
      tracks,
      durationSeconds: mediaElementRes.duration,
      resolution: mediaElementRes.resolution,
      warnings
    };

  } catch (err: any) {
    console.error("Error during video audio track analysis:", err);
    return {
      hasAudio: false,
      audioTracksCount: 0,
      tracks: [],
      warnings: ["Media analysis failed or file is corrupted."],
      error: String(err?.message || err)
    };
  }
}
