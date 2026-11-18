import React from "react";
import { AlertTriangle, Upload, CheckCircle2, Film } from "lucide-react";
import { Anime, Episode } from "../../types";
import { detectMissingEpisodesInCatalog, MissingEpisodeGap } from "../../services/adminService";

interface AdminMissingEpisodesWidgetProps {
  animeList: Anime[];
  allEpisodesList: Episode[];
  onUploadMissing?: (animeId: string, seasonNum: number, missingEpNum: number) => void;
  onUploadMissingEpisode?: (animeId: string, seasonNum: number, missingEpNum: number) => void;
}

export const AdminMissingEpisodesWidget: React.FC<AdminMissingEpisodesWidgetProps> = ({
  animeList,
  allEpisodesList,
  onUploadMissing,
  onUploadMissingEpisode
}) => {
  const safeAnimeList = Array.isArray(animeList) ? animeList : [];
  const safeEpisodesList = Array.isArray(allEpisodesList) ? allEpisodesList : [];
  const gaps: MissingEpisodeGap[] = detectMissingEpisodesInCatalog(safeAnimeList, safeEpisodesList);

  const handleUploadClick = (animeId: string, seasonNumber: number, epNum: number) => {
    if (onUploadMissing) {
      onUploadMissing(animeId, seasonNumber, epNum);
    } else if (onUploadMissingEpisode) {
      onUploadMissingEpisode(animeId, seasonNumber, epNum);
    }
  };

  return (
    <div className="p-6 rounded-2xl bg-[#131520] border border-amber-500/20 flex flex-col gap-6 animate-fade-in">
      
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-white/5">
        <div>
          <h3 className="font-display font-extrabold text-xl text-white flex items-center gap-2">
            <AlertTriangle className="w-6 h-6 text-amber-500" />
            Automatic Missing Episode Detection Center
          </h3>
          <p className="text-gray-400 text-xs mt-1">
            Algorithmic sequence auditor scanning for missing episode gaps in published anime seasons.
          </p>
        </div>
        <span className="px-3 py-1.5 rounded-full text-xs font-mono font-bold bg-amber-500/10 text-amber-400 border border-amber-500/30">
          {gaps.length} Season Gap(s) Found
        </span>
      </div>

      {/* Cards List */}
      {gaps.length === 0 ? (
        <div className="p-12 text-center rounded-2xl bg-[#0d0e12] border border-white/5 flex flex-col items-center gap-3">
          <CheckCircle2 className="w-12 h-12 text-emerald-400" />
          <h4 className="text-base font-extrabold text-white">All Published Seasons are Complete!</h4>
          <p className="text-xs text-gray-400 max-w-md">
            No sequence gaps detected across your entire anime catalog. Every published season is perfectly sequential.
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {gaps.map((gap, idx) => (
            <div
              key={idx}
              className="p-5 rounded-2xl bg-[#0d0e12] border border-amber-500/20 hover:border-amber-500/40 transition-all flex flex-col gap-4 justify-between"
            >
              <div className="flex items-start gap-4">
                <img
                  src={gap.poster}
                  alt={gap.animeTitle}
                  className="w-12 h-16 rounded-xl object-cover border border-white/10 shrink-0"
                />
                <div className="flex flex-col min-w-0">
                  <span className="font-extrabold text-white text-sm truncate">{gap.animeTitle}</span>
                  <span className="text-xs font-mono text-amber-400 mt-0.5">
                    Season {gap.seasonNumber}
                  </span>
                  <div className="flex items-center gap-1.5 mt-2 flex-wrap">
                    <span className="text-[10px] font-mono text-gray-400">Missing Gaps:</span>
                    {gap.missingNumbers.map((num) => (
                      <span
                        key={num}
                        className="text-[10px] font-mono font-bold px-2 py-0.5 rounded bg-amber-500/20 text-amber-300 border border-amber-500/30"
                      >
                        EP {num}
                      </span>
                    ))}
                  </div>
                </div>
              </div>

              <div className="flex items-center justify-end gap-2 pt-2 border-t border-white/5">
                {gap.missingNumbers.map((num) => (
                  <button
                    key={num}
                    onClick={() => handleUploadClick(gap.animeId, gap.seasonNumber, num)}
                    className="px-3 py-1.5 bg-amber-500 hover:bg-amber-400 text-black font-extrabold text-xs rounded-lg shadow transition-all flex items-center gap-1 cursor-pointer"
                  >
                    <Upload className="w-3.5 h-3.5" />
                    Upload EP {num}
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

    </div>
  );
};
