import React, { useState, useEffect } from "react";
import { 
  X, Plus, Upload, Trash2, Edit, Play, RefreshCw, CheckCircle2, 
  AlertCircle, Sparkles, FileVideo, Languages, Eye, Globe, Clock
} from "lucide-react";
import { Episode, SupportedLanguage, EpisodeLanguageData } from "../types";
import { 
  saveOrMergeEpisodeLanguage, 
  deleteEpisodeLanguage, 
  getLanguageMetadata, 
  saveEpisode, 
  LANG_MAP 
} from "../services/dbService";
import { buildCloudflareVideoUrl } from "../services/cloudflareService";
import { VideoPlayer } from "./VideoPlayer";

interface LanguageManagementModalProps {
  isOpen: boolean;
  onClose: () => void;
  episode: Episode | null;
  animeTitle?: string;
  onSaved: () => void;
}

export const LanguageManagementModal: React.FC<LanguageManagementModalProps> = ({
  isOpen,
  onClose,
  episode,
  animeTitle,
  onSaved,
}) => {
  const [currentEpisode, setCurrentEpisode] = useState<Episode | null>(episode);
  const [activeTab, setActiveTab] = useState<"list" | "add" | "replace" | "edit">("list");
  
  // Selected language for operations
  const [selectedLangName, setSelectedLangName] = useState<string>("Japanese");
  const [targetLangToReplace, setTargetLangToReplace] = useState<string>("");
  
  // Form inputs for Add / Replace
  const [newLangName, setNewLangName] = useState<string>("English");
  const [customLangName, setCustomLangName] = useState<string>("");
  const [manualStreamUrl, setManualStreamUrl] = useState<string>("");
  const [qualityLabel, setQualityLabel] = useState<string>("1080p HD");
  const [trackNotes, setTrackNotes] = useState<string>("");
  const [publishStatus, setPublishStatus] = useState<"Published" | "Draft">("Published");
  const [durationStr, setDurationStr] = useState<string>("24 min");

  // API & Action states
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);
  const [isGeneratingSubtitle, setIsGeneratingSubtitle] = useState<Record<string, boolean>>({});
  const [actionError, setActionError] = useState<string | null>(null);
  const [actionSuccess, setActionSuccess] = useState<string | null>(null);

  // Preview Modal state
  const [previewLangData, setPreviewLangData] = useState<{ lang: string; data: EpisodeLanguageData } | null>(null);

  // Sync internal episode state when prop changes
  useEffect(() => {
    if (episode) {
      setCurrentEpisode(episode);
    }
  }, [episode]);

  if (!isOpen || !episode || !currentEpisode) return null;

  // Derive existing languages list
  const languagesMap = currentEpisode.languages || {};
  const existingLangEntries = Object.entries(languagesMap).filter(([key, val]) => {
    // Filter out duplicate code entries if name entry exists
    const meta = getLanguageMetadata(key);
    return val && (meta.name === key || !languagesMap[meta.name]);
  });

  // If no languages array exists yet, synthesize default entry
  const displayLanguages = existingLangEntries.length > 0 ? existingLangEntries : [
    [
      currentEpisode.defaultLanguage || "Japanese",
      {
        language: (currentEpisode.defaultLanguage || "Japanese") as SupportedLanguage,
        videoUrl: currentEpisode.videoUrl,
        storageKey: currentEpisode.storageKey,
        sourceType: currentEpisode.sourceType || "cloudflare",
        subtitlesUrl: currentEpisode.subtitlesUrl,
        status: currentEpisode.status || "Published"
      } as EpisodeLanguageData
    ]
  ];

  const showNotification = (msg: string, isError = false) => {
    if (isError) {
      setActionError(msg);
      setTimeout(() => setActionError(null), 5000);
    } else {
      setActionSuccess(msg);
      setTimeout(() => setActionSuccess(null), 4000);
    }
  };

  // Save Stream URL / Cloudflare Track for specified language
  const handleSaveStreamTrack = async (targetLang: string) => {
    const trimmedInput = manualStreamUrl.trim();
    if (!trimmedInput) {
      showNotification("Video URL or Storage Key is required.", true);
      return;
    }

    setIsSubmitting(true);
    try {
      const finalStreamUrl = trimmedInput.startsWith("http")
        ? trimmedInput
        : buildCloudflareVideoUrl(trimmedInput);

      const storageKey = trimmedInput.startsWith("http") ? undefined : trimmedInput;

      // Generate AssemblyAI Subtitle if possible
      let generatedSubUrl = "";
      let subStatus = "Pending";
      try {
        const subRes = await fetch("/api/subtitles/generate", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ videoUrl: finalStreamUrl, language: targetLang })
        });
        const subData = await subRes.json();
        if (subData.subtitlesUrl) {
          generatedSubUrl = subData.subtitlesUrl;
          subStatus = "Ready";
        }
      } catch (subErr) {
        console.warn("Subtitle auto-generation note:", subErr);
      }

      const langMetaData: EpisodeLanguageData = {
        language: targetLang as SupportedLanguage,
        sourceType: "cloudflare",
        videoUrl: finalStreamUrl,
        hlsUrl: finalStreamUrl,
        storageKey,
        quality: qualityLabel.trim() || undefined,
        notes: trackNotes.trim() || undefined,
        subtitlesUrl: generatedSubUrl,
        subtitleStatus: subStatus,
        status: publishStatus,
        duration: durationStr,
        uploadDate: new Date().toISOString()
      };

      const updatedEp = await saveOrMergeEpisodeLanguage(
        currentEpisode.animeId,
        currentEpisode.episodeNumber,
        targetLang,
        langMetaData,
        {
          title: currentEpisode.title,
          description: currentEpisode.description,
          duration: durationStr
        }
      );

      setCurrentEpisode(updatedEp);
      setManualStreamUrl("");
      setQualityLabel("1080p HD");
      setTrackNotes("");
      setActiveTab("list");
      showNotification(`Language '${targetLang}' stream saved successfully!`);
      onSaved();
    } catch (err: any) {
      console.error("Save language failed:", err);
      showNotification(`Failed to save: ${err.message}`, true);
    } finally {
      setIsSubmitting(false);
    }
  };

  // Helper to retry or generate English subtitle for an existing language
  const handleGenerateEnglishSubtitle = async (langName: string, langData: EpisodeLanguageData) => {
    setIsGeneratingSubtitle(prev => ({ ...prev, [langName]: true }));
    const videoUrl = langData.videoUrl || currentEpisode.videoUrl;

    try {
      const res = await fetch("/api/subtitles/generate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ videoUrl, language: langName })
      });
      const data = await res.json();

      if (data.subtitlesUrl) {
        const updatedLangData = {
          ...langData,
          subtitlesUrl: data.subtitlesUrl,
          subtitleStatus: "Ready",
          subtitleGeneratedAt: new Date().toISOString()
        };

        const updatedEp = await saveOrMergeEpisodeLanguage(
          currentEpisode.animeId,
          currentEpisode.episodeNumber,
          langName,
          updatedLangData
        );

        setCurrentEpisode(updatedEp);
        showNotification(`English subtitle generated for '${langName}'!`);
        onSaved();
      } else {
        showNotification("Failed to generate subtitles: " + (data.error || "Unknown error"), true);
      }
    } catch (err: any) {
      showNotification(`Error: ${err.message}`, true);
    } finally {
      setIsGeneratingSubtitle(prev => ({ ...prev, [langName]: false }));
    }
  };

  // Delete a language version
  const handleDeleteLanguageVersion = async (langName: string) => {
    if (displayLanguages.length <= 1) {
      showNotification("Cannot delete the only remaining language version for this episode.", true);
      return;
    }

    if (!window.confirm(`Are you sure you want to remove the '${langName}' audio version for Episode ${currentEpisode.episodeNumber}?`)) {
      return;
    }

    try {
      const updatedEp = await deleteEpisodeLanguage(
        currentEpisode.id,
        langName
      );
      if (updatedEp) {
        setCurrentEpisode(updatedEp);
        showNotification(`Language '${langName}' deleted successfully!`);
        onSaved();
      }
    } catch (err: any) {
      showNotification(`Failed to delete language: ${err.message}`, true);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-sm animate-fade-in">
      <div className="relative w-full max-w-3xl bg-[#131520] border border-white/10 rounded-3xl shadow-2xl overflow-hidden flex flex-col max-h-[90vh]">
        
        {/* Modal Header */}
        <div className="p-6 border-b border-white/5 flex items-center justify-between bg-[#0d0e12]/60">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-2xl bg-amber-500/10 border border-amber-500/20 flex items-center justify-center text-amber-400">
              <Languages className="w-5 h-5" />
            </div>
            <div>
              <h3 className="font-display font-extrabold text-lg text-white">
                Multi-Language & Audio Tracks
              </h3>
              <p className="text-xs text-gray-400">
                {animeTitle || "Anime"} • Episode {currentEpisode.episodeNumber}: {currentEpisode.title}
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 text-gray-400 hover:text-white hover:bg-white/5 rounded-xl transition-colors cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Action Notifications */}
        {actionError && (
          <div className="mx-6 mt-4 p-3 bg-red-500/10 border border-red-500/20 rounded-xl flex items-center gap-2 text-xs text-red-400">
            <AlertCircle className="w-4 h-4 shrink-0" />
            <span>{actionError}</span>
          </div>
        )}
        {actionSuccess && (
          <div className="mx-6 mt-4 p-3 bg-emerald-500/10 border border-emerald-500/20 rounded-xl flex items-center gap-2 text-xs text-emerald-400">
            <CheckCircle2 className="w-4 h-4 shrink-0" />
            <span>{actionSuccess}</span>
          </div>
        )}

        {/* Modal Body with Tabs */}
        <div className="p-6 overflow-y-auto space-y-6 flex-1">
          
          {/* TAB 1: LIST OF AUDIO TRACKS */}
          {activeTab === "list" && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <span className="text-xs font-bold text-gray-400 uppercase tracking-wider font-mono">
                  Configured Audio Versions ({displayLanguages.length})
                </span>
                <button
                  type="button"
                  onClick={() => {
                    setManualStreamUrl("");
                    setNewLangName("English");
                    setActiveTab("add");
                  }}
                  className="px-3.5 py-1.5 rounded-xl bg-amber-500 hover:bg-amber-400 text-black text-xs font-extrabold flex items-center gap-1.5 transition-all cursor-pointer"
                >
                  <Plus className="w-4 h-4" />
                  Add Audio Version
                </button>
              </div>

              {/* Grid of Audio Tracks */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3.5">
                {displayLanguages.map(([langKey, langData]) => {
                  const meta = getLanguageMetadata(langKey);
                  const isGenerating = isGeneratingSubtitle[meta.name];
                  const hasSubtitle = !!langData.subtitlesUrl;

                  return (
                    <div
                      key={langKey}
                      className="p-4 rounded-2xl bg-[#0d0e12] border border-white/5 hover:border-amber-500/30 transition-all flex flex-col justify-between gap-3 group"
                    >
                      {/* Language Header & Status Badges */}
                      <div className="flex items-start justify-between gap-2">
                        <div className="flex items-center gap-2.5">
                          <div className="w-8 h-8 rounded-lg bg-amber-500/10 text-amber-400 font-extrabold flex items-center justify-center font-mono text-xs">
                            {meta.code.toUpperCase()}
                          </div>
                          <div>
                            <h4 className="font-extrabold text-sm text-white">{meta.name}</h4>
                            <p className="text-[10px] text-gray-400 font-mono">
                              {langData.duration || currentEpisode.duration || "24 min"}
                            </p>
                          </div>
                        </div>

                        {/* Status Badges */}
                        <div className="flex flex-wrap gap-1 items-end justify-end">
                          <span className="text-[9px] font-mono px-2 py-0.5 rounded uppercase font-bold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                            Stream: {langData.status || "Ready"}
                          </span>
                          <span className={`text-[9px] font-mono px-2 py-0.5 rounded uppercase font-bold ${
                            hasSubtitle
                              ? "bg-blue-500/10 text-blue-400 border border-blue-500/20"
                              : "bg-amber-500/10 text-amber-400 border border-amber-500/20"
                          }`}>
                            Subtitle: {hasSubtitle ? "Ready" : "Pending"}
                          </span>
                        </div>
                      </div>

                      {/* Technical Info */}
                      <div className="text-[11px] font-mono text-gray-400 bg-black/40 p-3 rounded-xl border border-white/5 space-y-1">
                        <div className="flex justify-between">
                          <span>Source:</span>
                          <span className="text-gray-200 truncate max-w-[140px]">
                            {langData.storageKey ? "Cloudflare R2" : (langData.sourceType || "Direct Stream")}
                          </span>
                        </div>
                        <div className="flex justify-between">
                          <span>English Subtitle:</span>
                          <span className={hasSubtitle ? "text-emerald-400" : "text-amber-400"}>
                            {hasSubtitle ? "AssemblyAI Track Attached" : "None"}
                          </span>
                        </div>
                      </div>

                      {/* Card Action Buttons */}
                      <div className="flex items-center justify-between gap-2 border-t border-white/5 pt-3">
                        <div className="flex items-center gap-1.5">
                          {/* Preview button */}
                          <button
                            type="button"
                            onClick={() => setPreviewLangData({ lang: meta.name, data: langData })}
                            className="px-2.5 py-1.5 bg-white/5 hover:bg-white/10 text-gray-200 text-xs font-bold rounded-lg transition-all flex items-center gap-1 border border-white/10 cursor-pointer"
                            title="Preview video stream"
                          >
                            <Eye className="w-3.5 h-3.5 text-amber-400" />
                            Preview
                          </button>

                          {/* Replace video button */}
                          <button
                            type="button"
                            onClick={() => {
                              setTargetLangToReplace(meta.name);
                              setActiveTab("replace");
                            }}
                            className="px-2.5 py-1.5 bg-amber-500/10 hover:bg-amber-500/20 text-amber-400 text-xs font-bold rounded-lg transition-all flex items-center gap-1 border border-amber-500/20 cursor-pointer"
                            title="Replace video for this language"
                          >
                            <Upload className="w-3.5 h-3.5" />
                            Replace
                          </button>
                        </div>

                        <div className="flex items-center gap-1.5">
                          {/* Generate / Retry Subtitle */}
                          <button
                            type="button"
                            onClick={() => handleGenerateEnglishSubtitle(meta.name, langData)}
                            disabled={isGenerating}
                            className="px-2.5 py-1.5 bg-blue-500/10 hover:bg-blue-500/20 text-blue-400 text-xs font-bold rounded-lg transition-all flex items-center gap-1 border border-blue-500/20 disabled:opacity-40 cursor-pointer"
                            title="Generate or Retry English AssemblyAI Subtitle"
                          >
                            <Sparkles className={`w-3.5 h-3.5 ${isGenerating ? "animate-spin" : ""}`} />
                            {isGenerating ? "AI..." : hasSubtitle ? "Regen Sub" : "Retry Sub"}
                          </button>

                          {/* Delete language button */}
                          <button
                            type="button"
                            onClick={() => handleDeleteLanguageVersion(meta.name)}
                            className="p-1.5 text-gray-400 hover:text-red-400 hover:bg-red-500/10 rounded-lg transition-colors cursor-pointer"
                            title="Delete this language version"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* TAB 2: ADD NEW LANGUAGE */}
          {(activeTab === "add" || activeTab === "replace") && (
            <div className="p-6 rounded-2xl bg-[#0d0e12] border border-white/10 space-y-5">
              <div className="flex items-center justify-between border-b border-white/5 pb-3">
                <h4 className="font-extrabold text-white text-base flex items-center gap-2">
                  <FileVideo className="w-5 h-5 text-amber-400" />
                  {activeTab === "replace" ? `Replace Video for '${targetLangToReplace}'` : "Add New Language Version"}
                </h4>
                <button
                  type="button"
                  onClick={() => setActiveTab("list")}
                  className="text-xs text-gray-400 hover:text-white"
                >
                  Back to Languages List
                </button>
              </div>

              {/* Language Selection */}
              {activeTab === "add" && (
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div className="flex flex-col gap-1.5">
                    <label className="text-xs font-bold text-gray-400 font-mono uppercase">
                      Select Language
                    </label>
                    <select
                      value={newLangName}
                      onChange={(e) => setNewLangName(e.target.value)}
                      className="px-3.5 py-2.5 bg-[#131520] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                    >
                      <option value="Japanese">Japanese (Subbed)</option>
                      <option value="English">English (Dubbed)</option>
                      <option value="Hindi">Hindi (हिंदी)</option>
                      <option value="Bangla">Bangla (বাংলা)</option>
                      <option value="Spanish">Spanish (Español)</option>
                      <option value="French">French (Français)</option>
                      <option value="German">German (Deutsch)</option>
                      <option value="Custom">Custom / Other Language</option>
                    </select>
                  </div>

                  {newLangName === "Custom" && (
                    <div className="flex flex-col gap-1.5">
                      <label className="text-xs font-bold text-gray-400 font-mono uppercase">
                        Custom Language Name
                      </label>
                      <input
                        type="text"
                        placeholder="e.g. Portuguese"
                        value={customLangName}
                        onChange={(e) => setCustomLangName(e.target.value)}
                        className="px-3.5 py-2.5 bg-[#131520] border border-white/10 rounded-xl text-sm text-white focus:outline-none focus:border-amber-500"
                      />
                    </div>
                  )}
                </div>
              )}

              {/* Video Stream URL / R2 Storage Key Input */}
              <div className="flex flex-col gap-1.5">
                <label className="text-xs font-bold text-gray-400 font-mono uppercase flex items-center justify-between">
                  <span>Cloudflare Video Stream URL or R2 Key <span className="text-red-400">*</span></span>
                  <span className="text-[10px] text-amber-400/80 normal-case">Direct Stream URL</span>
                </label>
                <input
                  type="text"
                  placeholder="e.g. https://prophecy-verse-video-api.newprophecy4.workers.dev/video/anime/ep1.mp4"
                  value={manualStreamUrl}
                  onChange={(e) => setManualStreamUrl(e.target.value)}
                  className="px-3.5 py-2.5 bg-[#131520] border border-white/10 rounded-xl text-xs text-white focus:outline-none focus:border-amber-500 font-mono"
                  required
                />
                <p className="text-[10px] text-gray-500">
                  Paste the Cloudflare R2 worker video streaming URL or R2 object key here.
                </p>
              </div>

              {/* Quality Label & Notes (Optional) */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div className="flex flex-col gap-1.5">
                  <label className="text-xs font-bold text-gray-400 font-mono uppercase">
                    Quality Label (Optional)
                  </label>
                  <input
                    type="text"
                    placeholder="e.g. 1080p HD, 720p, 4K"
                    value={qualityLabel}
                    onChange={(e) => setQualityLabel(e.target.value)}
                    className="px-3.5 py-2.5 bg-[#131520] border border-white/10 rounded-xl text-xs text-white focus:outline-none focus:border-amber-500 font-mono"
                  />
                </div>

                <div className="flex flex-col gap-1.5">
                  <label className="text-xs font-bold text-gray-400 font-mono uppercase">
                    Notes / Version Info (Optional)
                  </label>
                  <input
                    type="text"
                    placeholder="e.g. Uncut, TV Broadcast, Remastered"
                    value={trackNotes}
                    onChange={(e) => setTrackNotes(e.target.value)}
                    className="px-3.5 py-2.5 bg-[#131520] border border-white/10 rounded-xl text-xs text-white focus:outline-none focus:border-amber-500"
                  />
                </div>
              </div>

              {/* Submit Button */}
              <div className="flex justify-end gap-3 pt-2 border-t border-white/5">
                <button
                  type="button"
                  onClick={() => setActiveTab("list")}
                  className="px-5 py-2.5 rounded-xl bg-white/5 hover:bg-white/10 text-xs font-bold text-gray-300 transition-colors"
                >
                  Cancel
                </button>

                <button
                  type="button"
                  disabled={isSubmitting || !manualStreamUrl.trim()}
                  onClick={() => {
                    const targetLang = activeTab === "replace"
                      ? targetLangToReplace
                      : (newLangName === "Custom" ? customLangName : newLangName);
                    
                    if (!targetLang) {
                      showNotification("Please select or enter a valid language name", true);
                      return;
                    }
                    handleSaveStreamTrack(targetLang);
                  }}
                  className="px-6 py-2.5 rounded-xl bg-amber-500 hover:bg-amber-400 text-black font-extrabold text-xs uppercase tracking-widest transition-all disabled:opacity-40 cursor-pointer flex items-center gap-2"
                >
                  {isSubmitting ? (
                    <>
                      <RefreshCw className="w-4 h-4 animate-spin" />
                      Saving...
                    </>
                  ) : (
                    <>
                      <Globe className="w-4 h-4" />
                      {activeTab === "replace" ? "Replace Video Version" : "Save Language Track"}
                    </>
                  )}
                </button>
              </div>
            </div>
          )}

        </div>

        {/* Video Preview Modal */}
        {previewLangData && (
          <div className="fixed inset-0 z-60 flex items-center justify-center p-4 bg-black/90 backdrop-blur-lg">
            <div className="relative w-full max-w-4xl bg-black rounded-2xl overflow-hidden border border-white/20 flex flex-col">
              <div className="flex items-center justify-between p-4 bg-[#0d0e12] border-b border-white/10">
                <span className="text-sm font-extrabold text-white font-mono flex items-center gap-2">
                  <Play className="w-4 h-4 text-amber-400 fill-current" />
                  Preview: {previewLangData.lang} Version
                </span>
                <button
                  onClick={() => setPreviewLangData(null)}
                  className="p-1.5 text-gray-400 hover:text-white rounded-lg cursor-pointer"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>
              <div className="p-4">
                <VideoPlayer
                  episode={{
                    ...currentEpisode,
                    defaultLanguage: previewLangData.lang as any,
                    videoUrl: previewLangData.data.videoUrl,
                    subtitlesUrl: previewLangData.data.subtitlesUrl || currentEpisode.subtitlesUrl
                  }}
                />
              </div>
            </div>
          </div>
        )}

      </div>
    </div>
  );
};
