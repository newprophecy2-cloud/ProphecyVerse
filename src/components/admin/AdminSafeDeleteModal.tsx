import React, { useState } from "react";
import { Trash2, AlertTriangle, X, ShieldAlert, Film } from "lucide-react";

interface AdminSafeDeleteModalProps {
  isOpen: boolean;
  onClose: () => void;
  count: number;
  hasMuxAssets: boolean;
  onConfirm: (deleteMuxAsset: boolean) => void;
}

export const AdminSafeDeleteModal: React.FC<AdminSafeDeleteModalProps> = ({
  isOpen,
  onClose,
  count,
  hasMuxAssets,
  onConfirm
}) => {
  const [deleteMuxAsset, setDeleteMuxAsset] = useState<boolean>(false);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/85 backdrop-blur-md animate-fade-in">
      <div className="relative w-full max-w-lg p-6 bg-[#131520] border border-red-500/30 rounded-2xl shadow-2xl flex flex-col gap-5">
        
        {/* Header */}
        <div className="flex items-center justify-between border-b border-white/10 pb-4">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-xl bg-red-500/20 text-red-400 border border-red-500/30">
              <ShieldAlert className="w-6 h-6" />
            </div>
            <div>
              <h3 className="text-lg font-extrabold text-white font-display">
                Safe Delete Confirmation
              </h3>
              <p className="text-xs text-gray-400 font-mono">
                Action will remove {count} selected episode(s)
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

        {/* Notice */}
        <div className="p-4 rounded-xl bg-red-500/10 border border-red-500/20 flex items-start gap-3 text-xs text-red-200">
          <AlertTriangle className="w-5 h-5 text-red-400 shrink-0 mt-0.5" />
          <div className="flex flex-col gap-1">
            <span className="font-extrabold text-white">Permanent Deletion Warning</span>
            <p className="text-gray-300 leading-relaxed">
              Deleting database records removes these episodes from the public streaming catalog immediately.
            </p>
          </div>
        </div>

        {/* Mux Asset Option */}
        {hasMuxAssets && (
          <div className="p-4 rounded-xl bg-[#0d0e12] border border-white/10 flex items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <Film className="w-5 h-5 text-amber-400 shrink-0" />
              <div className="flex flex-col">
                <span className="text-xs font-bold text-white">Delete Video Files from Mux Cloud</span>
                <span className="text-[10px] text-gray-400 font-mono">
                  Also trigger permanent Mux Asset deletion to free up video storage quota
                </span>
              </div>
            </div>
            <input
              type="checkbox"
              id="deleteMuxAssetCheck"
              checked={deleteMuxAsset}
              onChange={(e) => setDeleteMuxAsset(e.target.checked)}
              className="w-5 h-5 accent-red-500 rounded cursor-pointer shrink-0"
            />
          </div>
        )}

        {/* Actions */}
        <div className="flex items-center justify-end gap-3 pt-2">
          <button
            onClick={onClose}
            className="px-5 py-2.5 rounded-xl bg-white/5 hover:bg-white/10 text-xs font-bold text-gray-400 hover:text-white transition-all uppercase tracking-wider cursor-pointer"
          >
            Cancel
          </button>
          <button
            onClick={() => onConfirm(deleteMuxAsset)}
            className="px-6 py-2.5 rounded-xl bg-red-500 hover:bg-red-600 text-white font-extrabold text-xs uppercase tracking-widest shadow-lg shadow-red-500/20 transition-all cursor-pointer flex items-center gap-2"
          >
            <Trash2 className="w-4 h-4" />
            Confirm Delete ({count})
          </button>
        </div>

      </div>
    </div>
  );
};
