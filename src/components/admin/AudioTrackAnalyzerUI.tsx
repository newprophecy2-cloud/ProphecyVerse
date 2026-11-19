import React from "react";
import { DetectedMediaAudioTrack } from "../../utils/mediaInspector";
import { Volume2, VolumeX, CheckCircle, AlertTriangle, RefreshCw, Radio, Sparkles, Sliders } from "lucide-react";

interface AudioTrackAnalyzerUIProps {
  tracks: DetectedMediaAudioTrack[];
  isAnalyzing: boolean;
  warnings?: string[];
  error?: string | null;
  onUpdateTrackLanguage?: (trackIndex: number, newLang: string) => void;
  onSetDefaultTrack?: (trackIndex: number) => void;
  onReanalyze?: () => void;
  compact?: boolean;
}

const LANGUAGE_OPTIONS = [
  "Japanese",
  "English",
  "Hindi",
  "Bengali",
  "Spanish",
  "Korean",
  "French",
  "German",
  "Unknown"
];

export const AudioTrackAnalyzerUI: React.FC<AudioTrackAnalyzerUIProps> = ({
  tracks,
  isAnalyzing,
  warnings = [],
  error,
  onUpdateTrackLanguage,
  onSetDefaultTrack,
  onReanalyze,
  compact = false
}) => {
  if (isAnalyzing) {
    return (
      <div className="p-4 rounded-xl bg-slate-900/80 border border-indigo-500/30 flex items-center gap-3 animate-pulse">
        <RefreshCw className="w-5 h-5 text-indigo-400 animate-spin shrink-0" />
        <div>
          <p className="text-xs font-semibold text-indigo-300">Analyzing Video Audio Tracks...</p>
          <p className="text-[11px] text-slate-400">Inspecting container header for embedded audio streams</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="p-3.5 rounded-xl bg-red-950/40 border border-red-500/30 flex items-center justify-between gap-3 text-red-300">
        <div className="flex items-center gap-2.5">
          <AlertTriangle className="w-4 h-4 text-red-400 shrink-0" />
          <span className="text-xs font-medium">Audio Analysis Error: {error}</span>
        </div>
        {onReanalyze && (
          <button
            type="button"
            onClick={onReanalyze}
            className="px-2.5 py-1 rounded-lg bg-red-900/50 hover:bg-red-800/50 text-[11px] font-semibold text-red-200 border border-red-500/30 transition flex items-center gap-1"
          >
            <RefreshCw className="w-3 h-3" /> Retry
          </button>
        )}
      </div>
    );
  }

  if (!tracks || tracks.length === 0) {
    return (
      <div className="p-3.5 rounded-xl bg-amber-950/30 border border-amber-500/30 flex items-center justify-between gap-3 text-amber-300">
        <div className="flex items-center gap-2.5">
          <VolumeX className="w-4 h-4 text-amber-400 shrink-0" />
          <div>
            <p className="text-xs font-semibold text-amber-200">⚠ No Audio Tracks Detected</p>
            <p className="text-[11px] text-amber-400/80">Source video file appears to have no embedded audio streams.</p>
          </div>
        </div>
        {onReanalyze && (
          <button
            type="button"
            onClick={onReanalyze}
            className="px-2.5 py-1 rounded-lg bg-amber-900/50 hover:bg-amber-800/50 text-[11px] font-semibold text-amber-200 border border-amber-500/30 transition flex items-center gap-1 shrink-0"
          >
            <RefreshCw className="w-3 h-3" /> Re-check
          </button>
        )}
      </div>
    );
  }

  return (
    <div className={`p-4 rounded-2xl bg-slate-900/90 border border-slate-800 space-y-3.5 ${compact ? "text-xs" : ""}`}>
      {/* Header */}
      <div className="flex items-center justify-between gap-2 border-b border-slate-800 pb-2.5">
        <div className="flex items-center gap-2">
          <div className="p-1.5 rounded-lg bg-indigo-500/20 text-indigo-400">
            <Volume2 className="w-4 h-4" />
          </div>
          <div>
            <span className="text-xs font-bold text-slate-200 flex items-center gap-2">
              Audio Tracks Detected
              <span className="px-2 py-0.5 rounded-full text-[10px] font-mono font-black bg-indigo-500/20 text-indigo-300 border border-indigo-500/30">
                {tracks.length} {tracks.length === 1 ? "Track" : "Tracks"}
              </span>
            </span>
          </div>
        </div>

        {onReanalyze && (
          <button
            type="button"
            onClick={onReanalyze}
            title="Re-analyze video audio tracks"
            className="p-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-400 hover:text-white transition"
          >
            <RefreshCw className="w-3.5 h-3.5" />
          </button>
        )}
      </div>

      {/* Warnings Banner */}
      {warnings.length > 0 && (
        <div className="p-2.5 rounded-xl bg-amber-950/40 border border-amber-500/20 flex items-start gap-2 text-amber-300/90 text-[11px]">
          <AlertTriangle className="w-3.5 h-3.5 text-amber-400 shrink-0 mt-0.5" />
          <div className="space-y-0.5">
            {warnings.map((w, idx) => (
              <p key={idx}>{w}</p>
            ))}
          </div>
        </div>
      )}

      {/* Track List */}
      <div className="space-y-2">
        {tracks.map((track) => (
          <div
            key={track.id || track.trackIndex}
            className={`p-3 rounded-xl border transition-all flex flex-col sm:flex-row sm:items-center justify-between gap-3 ${
              track.isDefault
                ? "bg-indigo-950/30 border-indigo-500/40 shadow-sm"
                : "bg-slate-950/50 border-slate-800/80 hover:border-slate-700"
            }`}
          >
            {/* Left: Track Details */}
            <div className="flex items-center gap-3">
              <button
                type="button"
                onClick={() => onSetDefaultTrack && onSetDefaultTrack(track.trackIndex)}
                title={track.isDefault ? "Current Default Audio Track" : "Click to set as Default Audio Track"}
                className={`p-1.5 rounded-lg border transition ${
                  track.isDefault
                    ? "bg-indigo-500 text-white border-indigo-400"
                    : "bg-slate-800 text-slate-400 border-slate-700 hover:text-white hover:bg-slate-700"
                }`}
              >
                <Radio className="w-3.5 h-3.5" />
              </button>

              <div className="space-y-0.5">
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="text-xs font-bold text-white">Track #{track.trackIndex}</span>

                  {track.isDefault && (
                    <span className="px-2 py-0.2 rounded-md text-[9px] font-black uppercase bg-indigo-500/30 text-indigo-200 border border-indigo-400/30">
                      Default
                    </span>
                  )}

                  {track.isUserModified ? (
                    <span className="px-1.5 py-0.2 rounded text-[9px] bg-emerald-500/20 text-emerald-300 border border-emerald-500/30">
                      Admin Overridden
                    </span>
                  ) : (
                    <span className="px-1.5 py-0.2 rounded text-[9px] bg-slate-800 text-slate-400">
                      Auto-detected
                    </span>
                  )}
                </div>

                <div className="flex items-center gap-2 text-[11px] text-slate-400 flex-wrap">
                  <span className="font-mono text-slate-300">{track.codec}</span>
                  <span>•</span>
                  <span>{track.channelLayout}</span>
                  <span>•</span>
                  <span>{track.sampleRate ? `${(track.sampleRate / 1000).toFixed(1)} kHz` : "48 kHz"}</span>
                </div>
              </div>
            </div>

            {/* Right: Language Selection Dropdown */}
            <div className="flex items-center gap-2 shrink-0">
              <label className="text-[11px] text-slate-400 hidden sm:inline">Language:</label>
              <select
                value={LANGUAGE_OPTIONS.includes(track.language) ? track.language : "Unknown"}
                onChange={(e) => onUpdateTrackLanguage && onUpdateTrackLanguage(track.trackIndex, e.target.value)}
                className="bg-slate-900 text-slate-200 text-xs font-medium px-2.5 py-1.5 rounded-lg border border-slate-700 focus:border-indigo-500 focus:outline-none"
              >
                {LANGUAGE_OPTIONS.map((lang) => (
                  <option key={lang} value={lang}>
                    {lang}
                  </option>
                ))}
              </select>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};
