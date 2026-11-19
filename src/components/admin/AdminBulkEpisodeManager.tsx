import React, { useState } from "react";
import { 
  Search, Filter, CheckSquare, Square, Edit, Trash2, Globe, Layers, 
  ArrowUpDown, RefreshCw, Plus, CheckCircle, AlertTriangle, Shield, 
  Sparkles, Lock, Eye, EyeOff, ListFilter, Languages, Film, PlaySquare
} from "lucide-react";
import { Anime, Episode, SupportedLanguage } from "../../types";
import { 
  checkEpisodeConflicts, executeBulkMoveSeason, executeBulkRenumber, 
  executeBatchEditEpisodes, executeSafeDeleteEpisodes, ConflictStrategy 
} from "../../services/adminService";
import { AdminConflictResolverModal } from "./AdminConflictResolverModal";
import { AdminSafeDeleteModal } from "./AdminSafeDeleteModal";
import { AdminBatchEditModal } from "./AdminBatchEditModal";

interface AdminBulkEpisodeManagerProps {
  animeList: Anime[];
  allEpisodesList: Episode[];
  onRefresh: () => void;
  onOpenUpload?: () => void;
  onEditEpisode?: (ep: Episode) => void;
  onEditSingleEpisode?: (ep: Episode) => void;
  onOpenLanguageMgmt?: (ep: Episode, animeTitle?: string) => void;
  onOpenLanguageManagement?: (ep: Episode, animeTitle?: string) => void;
  onTranslateSubtitles?: (videoUrl?: string, defaultLang?: string) => void;
}

export const AdminBulkEpisodeManager: React.FC<AdminBulkEpisodeManagerProps> = ({
  animeList,
  allEpisodesList,
  onRefresh,
  onOpenUpload,
  onEditEpisode,
  onEditSingleEpisode,
  onOpenLanguageMgmt,
  onOpenLanguageManagement,
  onTranslateSubtitles
}) => {
  const safeAnimeList = Array.isArray(animeList) ? animeList : [];
  const safeEpisodesList = Array.isArray(allEpisodesList) ? allEpisodesList : [];

  const handleTriggerEdit = (ep: Episode) => {
    if (onEditEpisode) onEditEpisode(ep);
    else if (onEditSingleEpisode) onEditSingleEpisode(ep);
  };

  const handleTriggerLangMgmt = (ep: Episode, animeTitle?: string) => {
    if (onOpenLanguageMgmt) onOpenLanguageMgmt(ep, animeTitle);
    else if (onOpenLanguageManagement) onOpenLanguageManagement(ep, animeTitle);
  };
  // Selection State
  const [selectedEpIds, setSelectedEpIds] = useState<Set<string>>(new Set());

  // Filter & Search State
  const [searchQuery, setSearchQuery] = useState("");
  const [filterAnimeId, setFilterAnimeId] = useState("");
  const [filterSeason, setFilterSeason] = useState<string>("All");
  const [filterLanguage, setFilterLanguage] = useState<string>("All");
  const [filterStatus, setFilterStatus] = useState<string>("All");
  const [filterMuxStatus, setFilterMuxStatus] = useState<string>("All");
  const [filterPremium, setFilterPremium] = useState<string>("All");

  // Sort State
  const [sortBy, setSortBy] = useState<"epNumber" | "releaseDate" | "views" | "title">("epNumber");
  const [sortOrder, setSortOrder] = useState<"asc" | "desc">("asc");

  // Modals state
  const [showConflictModal, setShowConflictModal] = useState(false);
  const [conflictsList, setConflictsList] = useState<any[]>([]);
  const [pendingMoveTargetSeason, setPendingMoveTargetSeason] = useState<number>(1);

  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [showBatchEditModal, setShowBatchEditModal] = useState(false);

  // Renumber inline prompt state
  const [showRenumberInput, setShowRenumberInput] = useState(false);
  const [renumberStartFrom, setRenumberStartFrom] = useState<number>(1);

  // Quick Move Season inline dropdown
  const [showMoveSeasonDropdown, setShowMoveSeasonDropdown] = useState(false);
  const [moveSeasonTarget, setMoveSeasonTarget] = useState<number>(1);

  // Filtering Logic
  const filteredEpisodes = allEpisodesList.filter((ep) => {
    if (filterAnimeId && ep.animeId !== filterAnimeId) return false;
    
    if (filterSeason !== "All") {
      const sNum = parseInt(filterSeason) || 1;
      if ((ep.seasonNumber || 1) !== sNum) return false;
    }

    if (filterLanguage !== "All") {
      const hasLang = ep.languages ? ep.languages[filterLanguage] : ep.defaultLanguage === filterLanguage;
      if (!hasLang) return false;
    }

    if (filterStatus !== "All") {
      if (filterStatus === "Published" && ep.status !== "Published" && ep.isVisible === false) return false;
      if (filterStatus === "Draft" && ep.status !== "Draft" && ep.isVisible !== false) return false;
    }

    if (filterMuxStatus !== "All") {
      const pid = (ep.muxPlaybackId || (ep as any).playbackId || "").trim();
      const hasPid = !!pid && !pid.startsWith("upload_") && !pid.startsWith("asset_");
      if (filterMuxStatus === "Ready" && !hasPid) return false;
      if (filterMuxStatus === "Publishing" && hasPid) return false;
      if (filterMuxStatus === "Errored" && ep.muxStatus !== "Errored") return false;
    }

    if (filterPremium !== "All") {
      if (filterPremium === "Premium" && !ep.isPremium) return false;
      if (filterPremium === "Free" && ep.isPremium) return false;
    }

    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      const matchTitle = ep.title.toLowerCase().includes(q);
      const matchNum = ep.episodeNumber.toString() === q;
      const matchAnimeId = ep.animeId.toLowerCase().includes(q);
      const matchPlayback = (ep.muxPlaybackId || "").toLowerCase().includes(q);
      if (!matchTitle && !matchNum && !matchAnimeId && !matchPlayback) return false;
    }

    return true;
  });

  // Sorting Logic
  const sortedEpisodes = [...filteredEpisodes].sort((a, b) => {
    let result = 0;
    if (sortBy === "epNumber") {
      const sA = a.seasonNumber || 1;
      const sB = b.seasonNumber || 1;
      if (sA !== sB) result = sA - sB;
      else result = a.episodeNumber - b.episodeNumber;
    } else if (sortBy === "releaseDate") {
      result = new Date(b.releaseDate || 0).getTime() - new Date(a.releaseDate || 0).getTime();
    } else if (sortBy === "views") {
      result = (b.views || 0) - (a.views || 0);
    } else if (sortBy === "title") {
      result = a.title.localeCompare(b.title);
    }
    return sortOrder === "asc" ? result : -result;
  });

  // Selection Handlers
  const toggleSelectEp = (id: string) => {
    setSelectedEpIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const handleSelectAllFiltered = () => {
    if (selectedEpIds.size === sortedEpisodes.length && sortedEpisodes.length > 0) {
      setSelectedEpIds(new Set());
    } else {
      setSelectedEpIds(new Set(sortedEpisodes.map((e) => e.id)));
    }
  };

  const selectedEpisodesList = allEpisodesList.filter((e) => selectedEpIds.has(e.id));

  // Bulk Actions
  const handleInitiateMoveSeason = (targetSeason: number) => {
    if (selectedEpisodesList.length === 0) return;
    setPendingMoveTargetSeason(targetSeason);

    // Check conflicts
    const conflictResult = checkEpisodeConflicts(selectedEpisodesList, targetSeason, undefined, allEpisodesList);

    if (conflictResult.hasConflicts) {
      setConflictsList(conflictResult.conflictingEpisodes);
      setShowConflictModal(true);
    } else {
      // Execute directly
      executeMoveSeasonWithStrategy(targetSeason, "auto_renumber");
    }
  };

  const executeMoveSeasonWithStrategy = async (targetSeason: number, strategy: ConflictStrategy) => {
    await executeBulkMoveSeason(selectedEpisodesList, targetSeason, allEpisodesList, strategy);
    setShowConflictModal(false);
    setShowMoveSeasonDropdown(false);
    setSelectedEpIds(new Set());
    onRefresh();
  };

  const handleExecuteRenumber = async () => {
    if (selectedEpisodesList.length === 0) return;
    await executeBulkRenumber(selectedEpisodesList, renumberStartFrom);
    setShowRenumberInput(false);
    setSelectedEpIds(new Set());
    onRefresh();
  };

  const handleExecuteBatchEdit = async (edits: any) => {
    await executeBatchEditEpisodes(selectedEpisodesList, edits);
    setShowBatchEditModal(false);
    setSelectedEpIds(new Set());
    onRefresh();
  };

  const handleExecuteDelete = async (deleteMuxAsset: boolean) => {
    await executeSafeDeleteEpisodes(selectedEpisodesList, deleteMuxAsset);
    setShowDeleteModal(false);
    setSelectedEpIds(new Set());
    onRefresh();
  };

  const handleQuickPublishToggle = async (publish: boolean) => {
    await executeBatchEditEpisodes(selectedEpisodesList, {
      status: publish ? "Published" : "Draft",
      isVisible: publish
    });
    setSelectedEpIds(new Set());
    onRefresh();
  };

  const handleQuickPremiumToggle = async (premium: boolean) => {
    await executeBatchEditEpisodes(selectedEpisodesList, { isPremium: premium });
    setSelectedEpIds(new Set());
    onRefresh();
  };

  return (
    <div className="flex flex-col gap-6 max-w-7xl animate-fade-in relative pb-20">
      
      {/* Header & Controls */}
      <div className="p-6 rounded-2xl bg-[#131520] border border-white/5 flex flex-col gap-6">
        
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 pb-4 border-b border-white/5">
          <div>
            <h3 className="font-display font-extrabold text-xl text-white flex items-center gap-2">
              <ListFilter className="w-6 h-6 text-amber-500" />
              Advanced Episode Management & Bulk Actions
            </h3>
            <p className="text-gray-400 text-xs mt-1">
              Select multiple episodes to perform batch renumbering, season moves, metadata updates, visibility toggles, and safe deletions.
            </p>
          </div>
          <button
            onClick={onOpenUpload}
            className="px-5 py-2.5 bg-amber-500 hover:bg-amber-400 text-black font-extrabold text-xs rounded-xl shadow-lg transition-all flex items-center gap-2 cursor-pointer self-start md:self-auto"
          >
            <Plus className="w-4 h-4" />
            Add New Episode
          </button>
        </div>

        {/* Filter Controls Row 1 */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 bg-[#0d0e12] p-4 rounded-xl border border-white/5">
          
          {/* Search Input */}
          <div className="relative">
            <Search className="w-4 h-4 text-gray-500 absolute left-3 top-3" />
            <input
              type="text"
              placeholder="Search title, EP#, Playback ID..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full pl-9 pr-3 py-2 bg-[#131520] border border-white/10 rounded-lg text-xs text-white focus:outline-none focus:border-amber-500"
            />
          </div>

          {/* Filter by Anime */}
          <div>
            <select
              value={filterAnimeId}
              onChange={(e) => setFilterAnimeId(e.target.value)}
              className="w-full px-3 py-2 bg-[#131520] border border-white/10 rounded-lg text-xs text-white focus:outline-none"
            >
              <option value="">All Anime ({animeList.length})</option>
              {animeList.map((a) => (
                <option key={a.id} value={a.id}>{a.title}</option>
              ))}
            </select>
          </div>

          {/* Filter by Season */}
          <div>
            <select
              value={filterSeason}
              onChange={(e) => setFilterSeason(e.target.value)}
              className="w-full px-3 py-2 bg-[#131520] border border-white/10 rounded-lg text-xs text-white focus:outline-none"
            >
              <option value="All">All Seasons</option>
              {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((s) => (
                <option key={s} value={s}>Season {s}</option>
              ))}
            </select>
          </div>

          {/* Filter by Audio Language */}
          <div>
            <select
              value={filterLanguage}
              onChange={(e) => setFilterLanguage(e.target.value)}
              className="w-full px-3 py-2 bg-[#131520] border border-white/10 rounded-lg text-xs text-white focus:outline-none"
            >
              <option value="All">All Languages</option>
              {["Japanese", "English", "Hindi", "Bangla", "Spanish"].map((l) => (
                <option key={l} value={l}>{l}</option>
              ))}
            </select>
          </div>

        </div>

        {/* Filter Controls Row 2 */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 bg-[#0d0e12] p-4 rounded-xl border border-white/5">
          
          {/* Filter by Mux Status */}
          <div>
            <select
              value={filterMuxStatus}
              onChange={(e) => setFilterMuxStatus(e.target.value)}
              className="w-full px-3 py-2 bg-[#131520] border border-white/10 rounded-lg text-xs text-white focus:outline-none"
            >
              <option value="All">All Mux Stream Statuses</option>
              <option value="Ready">Ready / Streamable</option>
              <option value="Publishing">Publishing / Pending</option>
              <option value="Errored">Errored / Failed</option>
            </select>
          </div>

          {/* Filter by Premium */}
          <div>
            <select
              value={filterPremium}
              onChange={(e) => setFilterPremium(e.target.value)}
              className="w-full px-3 py-2 bg-[#131520] border border-white/10 rounded-lg text-xs text-white focus:outline-none"
            >
              <option value="All">All Access Levels</option>
              <option value="Free">Free Stream</option>
              <option value="Premium">Premium Only</option>
            </select>
          </div>

          {/* Sort Field */}
          <div>
            <select
              value={sortBy}
              onChange={(e) => setSortBy(e.target.value as any)}
              className="w-full px-3 py-2 bg-[#131520] border border-white/10 rounded-lg text-xs text-white focus:outline-none"
            >
              <option value="epNumber">Sort by Season & EP Number</option>
              <option value="releaseDate">Sort by Release Date</option>
              <option value="views">Sort by Views / Popularity</option>
              <option value="title">Sort by Title A-Z</option>
            </select>
          </div>

          {/* Sort Direction Toggle */}
          <button
            onClick={() => setSortOrder(prev => prev === "asc" ? "desc" : "asc")}
            className="w-full px-3 py-2 bg-[#131520] border border-white/10 rounded-lg text-xs text-gray-300 hover:text-white flex items-center justify-between cursor-pointer"
          >
            <span className="font-mono">Order: {sortOrder.toUpperCase()}</span>
            <ArrowUpDown className="w-3.5 h-3.5 text-amber-400" />
          </button>

        </div>

        {/* Selection Status & Refresh Bar */}
        <div className="flex items-center justify-between text-xs font-mono text-gray-400 border-b border-white/5 pb-2">
          <div className="flex items-center gap-3">
            <button
              onClick={handleSelectAllFiltered}
              className="flex items-center gap-2 text-white hover:text-amber-400 font-bold transition-colors cursor-pointer"
            >
              {selectedEpIds.size > 0 && selectedEpIds.size === sortedEpisodes.length ? (
                <CheckSquare className="w-4 h-4 text-amber-500" />
              ) : (
                <Square className="w-4 h-4 text-gray-500" />
              )}
              Select All ({sortedEpisodes.length})
            </button>
            <span>• {selectedEpIds.size} Selected</span>
          </div>

          <button
            onClick={onRefresh}
            className="text-amber-500 hover:underline flex items-center gap-1 font-bold text-[11px] cursor-pointer"
          >
            <RefreshCw className="w-3.5 h-3.5" />
            Refresh Catalog
          </button>
        </div>

        {/* Episode Items List */}
        {sortedEpisodes.length === 0 ? (
          <div className="p-12 text-center rounded-2xl bg-[#0d0e12] border border-white/5 flex flex-col items-center gap-3">
            <Film className="w-10 h-10 text-gray-600" />
            <p className="text-sm font-bold text-gray-400">No episodes found matching search or filter conditions.</p>
          </div>
        ) : (
          <div className="flex flex-col gap-3 max-h-[650px] overflow-y-auto no-scrollbar pr-1">
            {sortedEpisodes.map((ep) => {
              const isSelected = selectedEpIds.has(ep.id);
              const parentAnime = animeList.find((a) => a.id === ep.animeId);
              const availableLangs = ep.languages ? Object.keys(ep.languages) : [ep.defaultLanguage || "Japanese"];
              const pid = (ep.muxPlaybackId || (ep as any).playbackId || "").trim();
              const isPublishing = !pid || pid.startsWith("upload_") || pid.startsWith("asset_");

              return (
                <div
                  key={ep.id}
                  className={`p-4 rounded-xl border transition-all flex flex-col md:flex-row md:items-center justify-between gap-4 ${
                    isSelected
                      ? "bg-amber-500/10 border-amber-500/40"
                      : "bg-[#0d0e12] border-white/5 hover:border-white/10"
                  }`}
                >
                  <div className="flex items-center gap-4 min-w-0">
                    
                    {/* Checkbox */}
                    <button
                      onClick={() => toggleSelectEp(ep.id)}
                      className="text-gray-400 hover:text-amber-400 cursor-pointer shrink-0"
                    >
                      {isSelected ? (
                        <CheckSquare className="w-5 h-5 text-amber-500" />
                      ) : (
                        <Square className="w-5 h-5 text-gray-600" />
                      )}
                    </button>

                    {/* Thumbnail */}
                    <div className="relative w-20 h-12 bg-gray-800 rounded-lg overflow-hidden shrink-0 border border-white/5">
                      {ep.thumbnail || ep.thumbnailUrl || parentAnime?.poster ? (
                        <img
                          src={ep.thumbnail || ep.thumbnailUrl || parentAnime?.poster}
                          alt={ep.title}
                          loading="lazy"
                          className="w-full h-full object-cover"
                          referrerPolicy="no-referrer"
                        />
                      ) : (
                        <div className="w-full h-full flex items-center justify-center text-xs font-mono text-gray-500">EP {ep.episodeNumber}</div>
                      )}
                      <div className="absolute inset-0 bg-black/40 flex items-center justify-center">
                        <span className="text-[10px] font-extrabold text-amber-400 bg-black/80 px-1.5 py-0.5 rounded font-mono">
                          S{ep.seasonNumber || 1} E{ep.episodeNumber}
                        </span>
                      </div>
                    </div>

                    {/* Meta info */}
                    <div className="flex flex-col min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="text-xs font-extrabold text-white truncate max-w-xs">{ep.title}</span>
                        <span className="text-[10px] font-mono text-amber-400/90 font-bold bg-amber-500/10 px-2 py-0.5 rounded border border-amber-500/20">
                          {parentAnime?.title || ep.animeId}
                        </span>
                        <span className={`text-[9px] font-extrabold uppercase px-2 py-0.5 rounded border ${
                          ep.isVisible !== false ? "bg-emerald-500/10 text-emerald-400 border-emerald-500/20" : "bg-red-500/10 text-red-400 border-red-500/20"
                        }`}>
                          {ep.isVisible !== false ? "Visible" : "Hidden"}
                        </span>
                        {ep.isPremium && (
                          <span className="text-[9px] font-extrabold uppercase px-2 py-0.5 rounded bg-amber-500/20 text-amber-300 border border-amber-500/30 flex items-center gap-1">
                            <Lock className="w-2.5 h-2.5" />
                            Premium
                          </span>
                        )}
                        {isPublishing && (
                          <span className="text-[9px] font-extrabold uppercase px-2 py-0.5 rounded bg-amber-500/10 text-amber-400 border border-amber-500/20 animate-pulse">
                            Publishing
                          </span>
                        )}
                      </div>

                      <div className="flex items-center gap-2 mt-1.5 flex-wrap">
                        <span className="text-[10px] font-mono text-gray-400">Audio:</span>
                        {availableLangs.map((lang) => (
                          <span key={lang} className="text-[9px] font-mono bg-white/5 text-gray-300 px-1.5 py-0.5 rounded border border-white/10">
                            {lang}
                          </span>
                        ))}

                        {ep.subtitlesUrl ? (
                          <span className="text-[9px] font-mono bg-blue-500/10 text-blue-400 px-1.5 py-0.5 rounded border border-blue-500/20 flex items-center gap-1">
                            <Languages className="w-3 h-3" />
                            English Subs
                          </span>
                        ) : (
                          <button
                            onClick={() => onTranslateSubtitles(ep.videoUrl || ep.hlsUrl, ep.defaultLanguage || "Japanese")}
                            className="text-[9px] font-mono text-amber-400 hover:underline flex items-center gap-1 cursor-pointer"
                          >
                            <Sparkles className="w-2.5 h-2.5" />
                            + AI English Subs
                          </button>
                        )}
                      </div>
                    </div>

                  </div>

                  {/* Actions */}
                  <div className="flex items-center gap-2 shrink-0 self-end md:self-auto flex-wrap">
                    <button
                      onClick={() => handleTriggerLangMgmt(ep, parentAnime?.title)}
                      className="px-3 py-1.5 bg-blue-500/10 hover:bg-blue-500/20 text-blue-400 font-bold text-xs rounded-lg transition-all border border-blue-500/20 flex items-center gap-1.5 cursor-pointer"
                    >
                      <Globe className="w-3.5 h-3.5" />
                      Languages
                    </button>
                    <button
                      onClick={() => handleTriggerEdit(ep)}
                      className="px-3 py-1.5 bg-amber-500/10 hover:bg-amber-500/20 text-amber-400 font-bold text-xs rounded-lg transition-all border border-amber-500/20 flex items-center gap-1.5 cursor-pointer"
                    >
                      <Edit className="w-3.5 h-3.5" />
                      Edit
                    </button>
                  </div>

                </div>
              );
            })}
          </div>
        )}

      </div>

      {/* FLOATING STICKY BULK ACTION BAR */}
      {selectedEpIds.size > 0 && (
        <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-40 w-full max-w-4xl px-4 animate-bounce-short">
          <div className="p-4 rounded-2xl bg-[#131520]/95 backdrop-blur-xl border border-amber-500/50 shadow-2xl flex flex-wrap items-center justify-between gap-3 text-xs">
            
            <div className="flex items-center gap-3">
              <span className="px-3 py-1 rounded-full bg-amber-500 text-black font-extrabold text-xs font-mono shadow">
                {selectedEpIds.size} Selected
              </span>
              <button
                onClick={() => setSelectedEpIds(new Set())}
                className="text-gray-400 hover:text-white font-mono text-[11px] underline cursor-pointer"
              >
                Clear Selection
              </button>
            </div>

            {/* Actions group */}
            <div className="flex items-center gap-2 flex-wrap">
              
              {/* Move to Season */}
              <div className="relative">
                <button
                  onClick={() => setShowMoveSeasonDropdown(!showMoveSeasonDropdown)}
                  className="px-3 py-1.5 bg-blue-500/20 hover:bg-blue-500/30 text-blue-300 font-bold rounded-lg border border-blue-500/30 flex items-center gap-1 cursor-pointer"
                >
                  <Layers className="w-3.5 h-3.5" />
                  Move Season
                </button>

                {showMoveSeasonDropdown && (
                  <div className="absolute bottom-10 left-0 z-50 p-3 bg-[#0d0e12] border border-amber-500/30 rounded-xl shadow-xl flex items-center gap-2">
                    <span className="text-[11px] text-gray-300 font-mono">Target:</span>
                    <select
                      value={moveSeasonTarget}
                      onChange={(e) => setMoveSeasonTarget(parseInt(e.target.value) || 1)}
                      className="px-2 py-1 bg-[#131520] border border-white/10 rounded text-xs text-white"
                    >
                      {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((s) => (
                        <option key={s} value={s}>Season {s}</option>
                      ))}
                    </select>
                    <button
                      onClick={() => handleInitiateMoveSeason(moveSeasonTarget)}
                      className="px-3 py-1 bg-amber-500 text-black font-extrabold rounded text-xs cursor-pointer"
                    >
                      Go
                    </button>
                  </div>
                )}
              </div>

              {/* Bulk Renumber */}
              <div className="relative">
                <button
                  onClick={() => setShowRenumberInput(!showRenumberInput)}
                  className="px-3 py-1.5 bg-purple-500/20 hover:bg-purple-500/30 text-purple-300 font-bold rounded-lg border border-purple-500/30 flex items-center gap-1 cursor-pointer"
                >
                  Renumber #
                </button>

                {showRenumberInput && (
                  <div className="absolute bottom-10 left-0 z-50 p-3 bg-[#0d0e12] border border-amber-500/30 rounded-xl shadow-xl flex items-center gap-2">
                    <span className="text-[11px] text-gray-300 font-mono">Start from:</span>
                    <input
                      type="number"
                      min={1}
                      value={renumberStartFrom}
                      onChange={(e) => setRenumberStartFrom(parseInt(e.target.value) || 1)}
                      className="w-16 px-2 py-1 bg-[#131520] border border-white/10 rounded text-xs text-white"
                    />
                    <button
                      onClick={handleExecuteRenumber}
                      className="px-3 py-1 bg-amber-500 text-black font-extrabold rounded text-xs cursor-pointer"
                    >
                      Apply
                    </button>
                  </div>
                )}
              </div>

              {/* Publish / Unpublish */}
              <button
                onClick={() => handleQuickPublishToggle(true)}
                className="px-3 py-1.5 bg-emerald-500/20 hover:bg-emerald-500/30 text-emerald-300 font-bold rounded-lg border border-emerald-500/30 flex items-center gap-1 cursor-pointer"
              >
                <Eye className="w-3.5 h-3.5" />
                Publish
              </button>

              <button
                onClick={() => handleQuickPublishToggle(false)}
                className="px-3 py-1.5 bg-gray-700/50 hover:bg-gray-700 text-gray-300 font-bold rounded-lg border border-white/10 flex items-center gap-1 cursor-pointer"
              >
                <EyeOff className="w-3.5 h-3.5" />
                Hide
              </button>

              {/* Batch Edit Modal trigger */}
              <button
                onClick={() => setShowBatchEditModal(true)}
                className="px-3 py-1.5 bg-amber-500/20 hover:bg-amber-500/30 text-amber-300 font-bold rounded-lg border border-amber-500/30 flex items-center gap-1 cursor-pointer"
              >
                <Edit className="w-3.5 h-3.5" />
                Batch Edit
              </button>

              {/* Safe Delete trigger */}
              <button
                onClick={() => setShowDeleteModal(true)}
                className="px-3 py-1.5 bg-red-500/20 hover:bg-red-500/30 text-red-300 font-bold rounded-lg border border-red-500/30 flex items-center gap-1 cursor-pointer"
              >
                <Trash2 className="w-3.5 h-3.5" />
                Delete
              </button>

            </div>

          </div>
        </div>
      )}

      {/* Modals */}
      <AdminConflictResolverModal
        isOpen={showConflictModal}
        onClose={() => setShowConflictModal(false)}
        conflictingEpisodes={conflictsList}
        onResolve={(strategy) => executeMoveSeasonWithStrategy(pendingMoveTargetSeason, strategy)}
      />

      <AdminSafeDeleteModal
        isOpen={showDeleteModal}
        onClose={() => setShowDeleteModal(false)}
        count={selectedEpIds.size}
        hasMuxAssets={selectedEpisodesList.some(e => !!e.muxAssetId)}
        onConfirm={handleExecuteDelete}
      />

      <AdminBatchEditModal
        isOpen={showBatchEditModal}
        onClose={() => setShowBatchEditModal(false)}
        selectedCount={selectedEpIds.size}
        onSave={handleExecuteBatchEdit}
      />

    </div>
  );
};
