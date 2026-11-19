import React, { useState } from "react";
import { 
  Layers, ChevronDown, ChevronRight, Plus, Edit, Trash2, ArrowRight, 
  PlaySquare, CheckCircle, AlertTriangle, MoveRight, Settings2, Sliders, Hash
} from "lucide-react";
import { Anime, Episode } from "../../types";
import { saveEpisode, deleteEpisode } from "../../services/dbService";
import { logAdminAction } from "../../services/adminService";
import { getSeasonStats, setSeasonEpisodeCount } from "../../services/seasonService";

interface AdminSeasonManagerProps {
  anime?: Anime;
  episodes?: Episode[];
  allCatalogEpisodes?: Episode[];
  animeList?: Anime[];
  allEpisodesList?: Episode[];
  onRefresh: () => void;
  onUploadEpisodeForSeason?: (animeId: string, seasonNum: number) => void;
  onNavigateToEpisodes?: (animeId: string) => void;
}

export const AdminSeasonManager: React.FC<AdminSeasonManagerProps> = ({
  anime,
  episodes,
  allCatalogEpisodes,
  animeList,
  allEpisodesList,
  onRefresh,
  onUploadEpisodeForSeason,
  onNavigateToEpisodes
}) => {
  const safeAnimeList = Array.isArray(animeList) ? animeList : [];
  const safeAllEpisodes = Array.isArray(allEpisodesList) 
    ? allEpisodesList 
    : (Array.isArray(allCatalogEpisodes) ? allCatalogEpisodes : []);

  const [selectedAnimeId, setSelectedAnimeId] = useState<string>(
    anime?.id || (safeAnimeList.length > 0 ? safeAnimeList[0].id : "")
  );

  const activeAnime = anime || safeAnimeList.find(a => a.id === selectedAnimeId) || safeAnimeList[0];
  
  const activeEpisodes = Array.isArray(episodes) && episodes.length > 0
    ? episodes
    : (activeAnime ? safeAllEpisodes.filter(e => e.animeId === activeAnime.id) : []);

  const safeEpisodes = Array.isArray(activeEpisodes) ? activeEpisodes : [];

  const [collapsedSeasons, setCollapsedSeasons] = useState<Record<number, boolean>>({});
  const [newSeasonNumberInput, setNewSeasonNumberInput] = useState<number>(1);

  // Rename/Change Season state
  const [editingSeasonNum, setEditingSeasonNum] = useState<number | null>(null);
  const [targetSeasonNumInput, setTargetSeasonNumInput] = useState<number>(1);

  // Edit Season Episode Count state
  const [editingCountSeasonNum, setEditingCountSeasonNum] = useState<number | null>(null);
  const [seasonCountInput, setSeasonCountInput] = useState<number>(12);

  // Merge Seasons state
  const [showMergeModal, setShowMergeModal] = useState(false);
  const [sourceMergeSeason, setSourceMergeSeason] = useState<number>(1);
  const [targetMergeSeason, setTargetMergeSeason] = useState<number>(2);

  // Move Episode state
  const [movingEp, setMovingEp] = useState<Episode | null>(null);
  const [destinationSeason, setDestinationSeason] = useState<number>(1);

  // Group episodes by season
  const seasonMap = new Map<number, Episode[]>();
  for (const ep of safeEpisodes) {
    const s = ep.seasonNumber || 1;
    const list = seasonMap.get(s) || [];
    list.push(ep);
    seasonMap.set(s, list);
  }

  // Ensure season 1 exists if map is empty
  if (seasonMap.size === 0) {
    seasonMap.set(1, []);
  }

  const seasonNumbers = Array.from(seasonMap.keys()).sort((a, b) => a - b);

  const toggleCollapse = (sNum: number) => {
    setCollapsedSeasons(prev => ({ ...prev, [sNum]: !prev[sNum] }));
  };

  const handleCreateSeason = () => {
    if (!activeAnime) return;
    const nextSeason = seasonNumbers.length > 0 ? Math.max(...seasonNumbers) + 1 : 1;
    setCollapsedSeasons(prev => ({ ...prev, [nextSeason]: false }));
    logAdminAction("Create Season", `Created Season ${nextSeason} for ${activeAnime.title}`);
    if (onUploadEpisodeForSeason) {
      onUploadEpisodeForSeason(activeAnime.id, nextSeason);
    } else if (onNavigateToEpisodes) {
      onNavigateToEpisodes(activeAnime.id);
    }
  };

  const handleChangeSeasonNumber = async (oldSeasonNum: number) => {
    if (targetSeasonNumInput === oldSeasonNum) {
      setEditingSeasonNum(null);
      return;
    }

    const epsInSeason = seasonMap.get(oldSeasonNum) || [];
    for (const ep of epsInSeason) {
      await saveEpisode({
        ...ep,
        seasonNumber: targetSeasonNumInput
      });
    }

    logAdminAction("Change Season Number", `Renumbered Season ${oldSeasonNum} to Season ${targetSeasonNumInput} for ${activeAnime?.title || "Anime"}`);
    setEditingSeasonNum(null);
    onRefresh();
  };

  const handleMergeSeasons = async () => {
    if (sourceMergeSeason === targetMergeSeason) {
      alert("Source and Target seasons must be different.");
      return;
    }

    const sourceEps = seasonMap.get(sourceMergeSeason) || [];
    if (sourceEps.length === 0) {
      alert(`Season ${sourceMergeSeason} has no episodes to merge.`);
      return;
    }

    const targetEps = seasonMap.get(targetMergeSeason) || [];
    let nextEpNum = targetEps.reduce((max, e) => (e.episodeNumber > max ? e.episodeNumber : max), 0) + 1;

    for (const ep of sourceEps) {
      await saveEpisode({
        ...ep,
        seasonNumber: targetMergeSeason,
        episodeNumber: nextEpNum++
      });
    }

    logAdminAction("Merge Seasons", `Merged Season ${sourceMergeSeason} into Season ${targetMergeSeason} (${sourceEps.length} episodes)`);
    setShowMergeModal(false);
    onRefresh();
  };

  const handleDeleteSeason = async (sNum: number) => {
    const epsInSeason = seasonMap.get(sNum) || [];
    if (epsInSeason.length > 0) {
      if (!window.confirm(`Season ${sNum} contains ${epsInSeason.length} episode(s). Are you sure you want to delete all episodes in Season ${sNum}?`)) {
        return;
      }
      for (const ep of epsInSeason) {
        await deleteEpisode(ep.id);
      }
    } else {
      if (!window.confirm(`Delete empty Season ${sNum}?`)) return;
    }

    logAdminAction("Delete Season", `Deleted Season ${sNum} of ${activeAnime?.title || "Anime"}`);
    onRefresh();
  };

  const handleMoveSingleEpisode = async () => {
    if (!movingEp) return;
    if (destinationSeason === (movingEp.seasonNumber || 1)) {
      setMovingEp(null);
      return;
    }

    const targetEps = seasonMap.get(destinationSeason) || [];
    const maxEpNum = targetEps.reduce((max, e) => (e.episodeNumber > max ? e.episodeNumber : max), 0);

    await saveEpisode({
      ...movingEp,
      seasonNumber: destinationSeason,
      episodeNumber: maxEpNum + 1
    });

    logAdminAction("Move Episode", `Moved ${movingEp.title} to Season ${destinationSeason} EP ${maxEpNum + 1}`);
    setMovingEp(null);
    onRefresh();
  };

  return (
    <div className="flex flex-col gap-6 p-6 rounded-2xl bg-[#131520] border border-white/5 animate-fade-in">
      
      {/* Anime Selector Bar if multiple anime provided */}
      {safeAnimeList.length > 0 && !anime && (
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 p-4 rounded-xl bg-[#0d0e12] border border-white/5">
          <label className="text-xs font-bold text-gray-300 uppercase tracking-wider font-mono">
            Select Anime for Season Structure:
          </label>
          <select
            value={selectedAnimeId}
            onChange={(e) => setSelectedAnimeId(e.target.value)}
            className="px-3.5 py-2 bg-[#131520] border border-white/10 rounded-xl text-xs font-bold text-white focus:outline-none focus:border-amber-500/50"
          >
            {safeAnimeList.map((a) => (
              <option key={a.id} value={a.id}>
                {a.title} ({safeAllEpisodes.filter(e => e.animeId === a.id).length} EPs)
              </option>
            ))}
          </select>
        </div>
      )}

      {/* Header */}
      {activeAnime ? (
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-white/5">
          <div className="flex items-center gap-3">
            <img
              src={activeAnime.poster}
              alt={activeAnime.title}
              className="w-12 h-16 rounded-lg object-cover border border-white/10 shrink-0"
            />
            <div>
              <h3 className="font-display font-extrabold text-lg text-white">
                {activeAnime.title}
              </h3>
              <span className="text-xs text-amber-400 font-mono">
                Season & Sequence Structure ({seasonNumbers.length} Seasons • {safeEpisodes.length} Episodes)
              </span>
            </div>
          </div>

          <div className="flex items-center gap-2 flex-wrap">
            <button
              onClick={() => setShowMergeModal(true)}
              className="px-3.5 py-2 bg-blue-500/10 hover:bg-blue-500/20 text-blue-400 font-bold text-xs rounded-xl border border-blue-500/20 transition-all cursor-pointer flex items-center gap-1.5"
            >
              <Layers className="w-4 h-4" />
              Merge Seasons
            </button>
            <button
              onClick={handleCreateSeason}
              className="px-4 py-2 bg-amber-500 hover:bg-amber-400 text-black font-extrabold text-xs rounded-xl shadow-lg transition-all cursor-pointer flex items-center gap-1.5"
            >
              <Plus className="w-4 h-4" />
              Add New Season
            </button>
          </div>
        </div>
      ) : (
        <div className="p-8 text-center text-xs text-gray-400 font-mono">
          No anime series found in catalog to manage seasons.
        </div>
      )}

      {/* Seasons Collapsible List */}
      <div className="flex flex-col gap-4">
        {seasonNumbers.map((sNum) => {
          const isCollapsed = !!collapsedSeasons[sNum];
          const eps = (seasonMap.get(sNum) || []).sort((a, b) => a.episodeNumber - b.episodeNumber);
          const isEditing = editingSeasonNum === sNum;
          const isEditingCount = editingCountSeasonNum === sNum;

          const stats = activeAnime ? getSeasonStats(activeAnime.id, sNum, safeEpisodes) : null;

          return (
            <div
              key={sNum}
              className="rounded-2xl bg-[#0d0e12] border border-white/5 hover:border-white/10 transition-all overflow-hidden"
            >
              {/* Season Header Bar */}
              <div className="p-4 bg-[#131520] border-b border-white/5 flex items-center justify-between gap-4 flex-wrap">
                <div className="flex items-center gap-3 min-w-0">
                  <button
                    onClick={() => toggleCollapse(sNum)}
                    className="p-1 rounded text-gray-400 hover:text-white transition-colors cursor-pointer"
                  >
                    {isCollapsed ? <ChevronRight className="w-5 h-5 text-amber-400" /> : <ChevronDown className="w-5 h-5 text-amber-400" />}
                  </button>

                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="px-2.5 py-1 rounded-lg bg-amber-500/10 text-amber-400 font-mono font-extrabold text-xs border border-amber-500/20">
                      Season {sNum}
                    </span>
                    {stats && (
                      <div className="flex items-center gap-1.5 text-xs font-mono">
                        <span className="px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 font-bold">
                          {stats.uploadedCount} Uploaded
                        </span>
                        <span className="px-2 py-0.5 rounded bg-blue-500/10 text-blue-400 border border-blue-500/20 font-bold">
                          {stats.expectedCount} Total Slots
                        </span>
                        {stats.missingCount > 0 && (
                          <span className="px-2 py-0.5 rounded bg-amber-500/10 text-amber-400 border border-amber-500/20 font-bold">
                            {stats.missingCount} Missing
                          </span>
                        )}
                      </div>
                    )}
                  </div>
                </div>

                {/* Season Actions */}
                <div className="flex items-center gap-2 flex-wrap">
                  {/* Season Episode Count Edit Button */}
                  {isEditingCount ? (
                    <div className="flex items-center gap-1 bg-[#0d0e12] p-1 rounded-lg border border-blue-500/30">
                      <span className="text-[10px] text-gray-400 font-mono pl-1">Season Slots:</span>
                      <input
                        type="number"
                        min={1}
                        max={100}
                        value={seasonCountInput}
                        onChange={(e) => setSeasonCountInput(parseInt(e.target.value) || 1)}
                        className="w-14 px-1.5 py-0.5 bg-[#131520] border border-white/10 rounded text-xs text-white"
                      />
                      <button
                        onClick={() => {
                          if (activeAnime) {
                            setSeasonEpisodeCount(activeAnime.id, sNum, seasonCountInput);
                            setEditingCountSeasonNum(null);
                          }
                        }}
                        className="px-2 py-0.5 bg-blue-500 text-white text-xs font-bold rounded cursor-pointer"
                      >
                        Save
                      </button>
                      <button
                        onClick={() => setEditingCountSeasonNum(null)}
                        className="px-1.5 py-0.5 text-gray-400 hover:text-white text-xs"
                      >
                        ✕
                      </button>
                    </div>
                  ) : (
                    <button
                      onClick={() => {
                        setEditingCountSeasonNum(sNum);
                        setSeasonCountInput(stats ? stats.expectedCount : 12);
                      }}
                      className="px-2.5 py-1 bg-blue-500/10 hover:bg-blue-500/20 text-blue-400 text-xs font-bold rounded-lg border border-blue-500/20 transition-all flex items-center gap-1 cursor-pointer"
                      title="Set Total Episodes for Season"
                    >
                      <Sliders className="w-3.5 h-3.5" />
                      Season Slots ({stats?.expectedCount || 12})
                    </button>
                  )}

                  <button
                    onClick={() => {
                      if (activeAnime) {
                        if (onUploadEpisodeForSeason) onUploadEpisodeForSeason(activeAnime.id, sNum);
                        else if (onNavigateToEpisodes) onNavigateToEpisodes(activeAnime.id);
                      }
                    }}
                    className="px-3 py-1.5 bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-400 text-xs font-bold rounded-lg border border-emerald-500/20 transition-all flex items-center gap-1 cursor-pointer"
                  >
                    <Plus className="w-3.5 h-3.5" />
                    Upload EP
                  </button>

                  {isEditing ? (
                    <div className="flex items-center gap-1 bg-[#0d0e12] p-1 rounded-lg border border-amber-500/30">
                      <span className="text-[10px] text-gray-400 font-mono pl-1">New #:</span>
                      <input
                        type="number"
                        min={1}
                        value={targetSeasonNumInput}
                        onChange={(e) => setTargetSeasonNumInput(parseInt(e.target.value) || 1)}
                        className="w-12 px-1.5 py-0.5 bg-[#131520] border border-white/10 rounded text-xs text-white"
                      />
                      <button
                        onClick={() => handleChangeSeasonNumber(sNum)}
                        className="px-2 py-0.5 bg-amber-500 text-black text-xs font-bold rounded cursor-pointer"
                      >
                        Save
                      </button>
                      <button
                        onClick={() => setEditingSeasonNum(null)}
                        className="px-1.5 py-0.5 text-gray-400 hover:text-white text-xs"
                      >
                        ✕
                      </button>
                    </div>
                  ) : (
                    <button
                      onClick={() => {
                        setEditingSeasonNum(sNum);
                        setTargetSeasonNumInput(sNum);
                      }}
                      className="p-1.5 rounded-lg text-gray-400 hover:text-amber-400 hover:bg-amber-500/10 transition-all cursor-pointer"
                      title="Change Season Number"
                    >
                      <Edit className="w-4 h-4" />
                    </button>
                  )}

                  <button
                    onClick={() => handleDeleteSeason(sNum)}
                    className="p-1.5 rounded-lg text-gray-400 hover:text-red-400 hover:bg-red-500/10 transition-all cursor-pointer"
                    title="Delete Season"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </div>
              </div>

              {/* Season Episode List & Missing Slots */}
              {!isCollapsed && (
                <div className="p-4 flex flex-col gap-4">
                  {/* Uploaded Episodes */}
                  <div className="flex flex-col gap-2.5">
                    {eps.length === 0 ? (
                      <div className="py-4 text-center text-xs text-gray-500 font-mono">
                        No episodes published in Season {sNum} yet. Click "Upload EP" above to add.
                      </div>
                    ) : (
                      eps.map((ep) => (
                        <div
                          key={ep.id}
                          className="p-3 rounded-xl bg-[#131520] border border-white/5 hover:border-white/10 transition-all flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-xs"
                        >
                          <div className="flex items-center gap-3 min-w-0">
                            <div className="w-8 h-8 rounded-lg bg-amber-500/10 text-amber-400 font-bold font-mono text-xs flex items-center justify-center shrink-0">
                              EP {ep.episodeNumber}
                            </div>
                            <div className="flex flex-col min-w-0">
                              <span className="font-bold text-white truncate">{ep.title}</span>
                              <span className="text-[10px] text-gray-400 font-mono truncate">
                                {ep.duration || "24 min"} • {ep.defaultLanguage || "Japanese"} • Mux ID: {ep.muxPlaybackId || "None"}
                              </span>
                            </div>
                          </div>

                          <div className="flex items-center gap-2 shrink-0">
                            <button
                              onClick={() => {
                                setMovingEp(ep);
                                setDestinationSeason(sNum === 1 ? 2 : 1);
                              }}
                              className="px-2.5 py-1 bg-white/5 hover:bg-white/10 text-gray-300 font-mono text-[11px] rounded-lg border border-white/10 transition-all flex items-center gap-1 cursor-pointer"
                            >
                              <MoveRight className="w-3 h-3 text-amber-400" />
                              Move
                            </button>
                          </div>
                        </div>
                      ))
                    )}
                  </div>

                  {/* Missing Episode Slots Banner */}
                  {stats && stats.missingSlotNumbers.length > 0 && (
                    <div className="p-3.5 rounded-xl bg-amber-500/5 border border-amber-500/20 flex flex-col gap-2">
                      <div className="flex items-center justify-between text-xs font-mono">
                        <span className="font-bold text-amber-400 flex items-center gap-1.5">
                          <AlertTriangle className="w-3.5 h-3.5" />
                          Missing Episode Slots ({stats.missingSlotNumbers.length} Unassigned)
                        </span>
                        <span className="text-[10px] text-gray-400">
                          Season Target: {stats.expectedCount} Episodes
                        </span>
                      </div>
                      <div className="flex items-center gap-1.5 flex-wrap">
                        {stats.missingSlotNumbers.map((mNum) => (
                          <button
                            key={mNum}
                            onClick={() => {
                              if (activeAnime) {
                                if (onUploadEpisodeForSeason) onUploadEpisodeForSeason(activeAnime.id, sNum);
                                else if (onNavigateToEpisodes) onNavigateToEpisodes(activeAnime.id);
                              }
                            }}
                            className="px-2.5 py-1 rounded-lg bg-[#131520] hover:bg-amber-500/20 border border-amber-500/30 text-amber-300 text-[11px] font-mono font-bold transition-all flex items-center gap-1 cursor-pointer"
                          >
                            <Plus className="w-3 h-3 text-amber-400" />
                            EP {mNum}
                          </button>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              )}

            </div>
          );
        })}
      </div>

      {/* Move Episode Modal */}
      {movingEp && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md">
          <div className="w-full max-w-md p-6 bg-[#131520] border border-amber-500/30 rounded-2xl flex flex-col gap-4">
            <h4 className="font-extrabold text-white text-base">Move Episode to Another Season</h4>
            <p className="text-xs text-gray-400 font-mono">
              Moving "{movingEp.title}" (Currently Season {movingEp.seasonNumber || 1})
            </p>

            <div className="flex flex-col gap-2">
              <label className="text-xs font-bold text-gray-300">Target Season:</label>
              <select
                value={destinationSeason}
                onChange={(e) => setDestinationSeason(parseInt(e.target.value) || 1)}
                className="px-3.5 py-2.5 bg-[#0d0e12] border border-white/10 rounded-xl text-sm text-white focus:outline-none"
              >
                {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((s) => (
                  <option key={s} value={s}>Season {s}</option>
                ))}
              </select>
            </div>

            <div className="flex justify-end gap-3 pt-2">
              <button
                onClick={() => setMovingEp(null)}
                className="px-4 py-2 rounded-xl bg-white/5 text-xs text-gray-400 hover:text-white"
              >
                Cancel
              </button>
              <button
                onClick={handleMoveSingleEpisode}
                className="px-5 py-2 rounded-xl bg-amber-500 text-black font-extrabold text-xs"
              >
                Confirm Move
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Merge Seasons Modal */}
      {showMergeModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md">
          <div className="w-full max-w-md p-6 bg-[#131520] border border-amber-500/30 rounded-2xl flex flex-col gap-4">
            <h4 className="font-extrabold text-white text-base">Merge Seasons</h4>
            <p className="text-xs text-gray-400 font-mono">
              Combine all episodes from source season into target season seamlessly.
            </p>

            <div className="grid grid-cols-2 gap-4">
              <div className="flex flex-col gap-2">
                <label className="text-xs font-bold text-gray-300">Source Season:</label>
                <select
                  value={sourceMergeSeason}
                  onChange={(e) => setSourceMergeSeason(parseInt(e.target.value) || 1)}
                  className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white"
                >
                  {seasonNumbers.map((s) => (
                    <option key={s} value={s}>Season {s}</option>
                  ))}
                </select>
              </div>

              <div className="flex flex-col gap-2">
                <label className="text-xs font-bold text-gray-300">Target Season:</label>
                <select
                  value={targetMergeSeason}
                  onChange={(e) => setTargetMergeSeason(parseInt(e.target.value) || 1)}
                  className="px-3 py-2 bg-[#0d0e12] border border-white/10 rounded-xl text-xs text-white"
                >
                  {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((s) => (
                    <option key={s} value={s}>Season {s}</option>
                  ))}
                </select>
              </div>
            </div>

            <div className="flex justify-end gap-3 pt-2">
              <button
                onClick={() => setShowMergeModal(false)}
                className="px-4 py-2 rounded-xl bg-white/5 text-xs text-gray-400 hover:text-white"
              >
                Cancel
              </button>
              <button
                onClick={handleMergeSeasons}
                className="px-5 py-2 rounded-xl bg-amber-500 text-black font-extrabold text-xs"
              >
                Merge Seasons
              </button>
            </div>
          </div>
        </div>
      )}

    </div>
  );
};
