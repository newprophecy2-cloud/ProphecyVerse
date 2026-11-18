import React from "react";
import { AlertTriangle, CheckCircle, RefreshCw, X } from "lucide-react";
import { ConflictStrategy } from "../../services/adminService";

interface ConflictResolverModalProps {
  isOpen: boolean;
  onClose: () => void;
  conflictingEpisodes: {
    episodeId: string;
    targetSeason: number;
    targetEpNum: number;
    existingEpisodeId?: string;
    existingTitle?: string;
  }[];
  onResolve: (strategy: ConflictStrategy) => void;
}

export const AdminConflictResolverModal: React.FC<ConflictResolverModalProps> = ({
  isOpen,
  onClose,
  conflictingEpisodes,
  onResolve
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-fade-in">
      <div className="relative w-full max-w-lg p-6 bg-[#131520] border border-amber-500/30 rounded-2xl shadow-2xl flex flex-col gap-5">
        
        {/* Header */}
        <div className="flex items-center justify-between border-b border-white/10 pb-4">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-xl bg-amber-500/20 text-amber-400 border border-amber-500/30">
              <AlertTriangle className="w-6 h-6" />
            </div>
            <div>
              <h3 className="text-lg font-extrabold text-white font-display">
                Episode Number Conflicts Detected
              </h3>
              <p className="text-xs text-gray-400 font-mono">
                {conflictingEpisodes.length} episode(s) collide with existing numbers in target season
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 rounded-lg text-gray-400 hover:text-white hover:bg-white/10 transition-all cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Details list */}
        <div className="flex flex-col gap-2 max-h-48 overflow-y-auto no-scrollbar bg-[#0d0e12] p-3.5 rounded-xl border border-white/5 text-xs text-gray-300">
          <span className="font-bold text-amber-400 uppercase text-[10px] tracking-wider mb-1">
            Colliding Episode Numbers:
          </span>
          {conflictingEpisodes.map((item, idx) => (
            <div key={idx} className="flex items-center justify-between py-1 border-b border-white/5 last:border-none font-mono">
              <span>Season {item.targetSeason} - EP {item.targetEpNum}</span>
              <span className="text-gray-400 truncate max-w-[200px]">
                {item.existingTitle ? `(Existing: ${item.existingTitle})` : "(Already Occupied)"}
              </span>
            </div>
          ))}
        </div>

        {/* Resolution Options */}
        <div className="flex flex-col gap-3">
          <span className="text-xs font-bold text-gray-300">Choose Resolution Strategy:</span>
          
          <button
            onClick={() => onResolve("auto_renumber")}
            className="p-3.5 rounded-xl bg-amber-500/10 hover:bg-amber-500/20 border border-amber-500/30 text-amber-400 font-bold text-xs text-left flex items-start gap-3 transition-all cursor-pointer group"
          >
            <RefreshCw className="w-5 h-5 text-amber-400 shrink-0 mt-0.5 group-hover:rotate-180 transition-transform duration-500" />
            <div className="flex flex-col">
              <span className="font-extrabold text-white text-sm">Auto Renumber (Recommended)</span>
              <span className="text-[11px] text-gray-400">
                Automatically assign next available sequential episode numbers to avoid overwriting.
              </span>
            </div>
          </button>

          <button
            onClick={() => onResolve("replace")}
            className="p-3.5 rounded-xl bg-red-500/10 hover:bg-red-500/20 border border-red-500/30 text-red-400 font-bold text-xs text-left flex items-start gap-3 transition-all cursor-pointer"
          >
            <AlertTriangle className="w-5 h-5 text-red-400 shrink-0 mt-0.5" />
            <div className="flex flex-col">
              <span className="font-extrabold text-white text-sm">Replace Existing</span>
              <span className="text-[11px] text-gray-400">
                Overwrite existing metadata for those episode numbers in target season.
              </span>
            </div>
          </button>

          <button
            onClick={() => onResolve("skip")}
            className="p-3.5 rounded-xl bg-blue-500/10 hover:bg-blue-500/20 border border-blue-500/30 text-blue-400 font-bold text-xs text-left flex items-start gap-3 transition-all cursor-pointer"
          >
            <CheckCircle className="w-5 h-5 text-blue-400 shrink-0 mt-0.5" />
            <div className="flex flex-col">
              <span className="font-extrabold text-white text-sm">Skip Conflicting Episodes</span>
              <span className="text-[11px] text-gray-400">
                Move/renumber non-conflicting episodes and skip colliding ones.
              </span>
            </div>
          </button>
        </div>

        {/* Footer */}
        <div className="flex justify-end pt-2">
          <button
            onClick={onClose}
            className="px-5 py-2.5 rounded-xl bg-white/5 hover:bg-white/10 text-xs font-bold text-gray-400 hover:text-white transition-all uppercase tracking-wider cursor-pointer"
          >
            Cancel Operation
          </button>
        </div>

      </div>
    </div>
  );
};
