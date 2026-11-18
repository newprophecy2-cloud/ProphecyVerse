import React, { useState } from "react";
import { Edit3, X, Check, Globe, Lock, Calendar, Layers } from "lucide-react";
import { SupportedLanguage } from "../../types";

interface AdminBatchEditModalProps {
  isOpen: boolean;
  onClose: () => void;
  selectedCount: number;
  onSave: (edits: {
    seasonNumber?: number;
    language?: string;
    isVisible?: boolean;
    isPremium?: boolean;
    status?: "Draft" | "Published";
    releaseDate?: string;
    notes?: string;
  }) => void;
}

export const AdminBatchEditModal: React.FC<AdminBatchEditModalProps> = ({
  isOpen,
  onClose,
  selectedCount,
  onSave
}) => {
  const [changeSeason, setChangeSeason] = useState(false);
  const [seasonNumber, setSeasonNumber] = useState<number>(1);

  const [changeLang, setChangeLang] = useState(false);
  const [language, setLanguage] = useState<SupportedLanguage>("Japanese");

  const [changeVisibility, setChangeVisibility] = useState(false);
  const [isVisible, setIsVisible] = useState(true);

  const [changePremium, setChangePremium] = useState(false);
  const [isPremium, setIsPremium] = useState(false);

  const [changeStatus, setChangeStatus] = useState(false);
  const [status, setStatus] = useState<"Draft" | "Published">("Published");

  const [changeReleaseDate, setChangeReleaseDate] = useState(false);
  const [releaseDate, setReleaseDate] = useState(new Date().toISOString().split("T")[0]);

  const [changeNotes, setChangeNotes] = useState(false);
  const [notes, setNotes] = useState("");

  if (!isOpen) return null;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSave({
      seasonNumber: changeSeason ? seasonNumber : undefined,
      language: changeLang ? language : undefined,
      isVisible: changeVisibility ? isVisible : undefined,
      isPremium: changePremium ? isPremium : undefined,
      status: changeStatus ? status : undefined,
      releaseDate: changeReleaseDate ? releaseDate : undefined,
      notes: changeNotes ? notes : undefined
    });
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/85 backdrop-blur-md animate-fade-in">
      <div className="relative w-full max-w-xl p-6 bg-[#131520] border border-amber-500/30 rounded-2xl shadow-2xl flex flex-col gap-5">
        
        {/* Header */}
        <div className="flex items-center justify-between border-b border-white/10 pb-4">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-xl bg-amber-500/20 text-amber-400 border border-amber-500/30">
              <Edit3 className="w-6 h-6" />
            </div>
            <div>
              <h3 className="text-lg font-extrabold text-white font-display">
                Batch Edit {selectedCount} Episode(s)
              </h3>
              <p className="text-xs text-gray-400 font-mono">
                Check the fields you want to update simultaneously
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="p-2 rounded-lg text-gray-400 hover:text-white hover:bg-white/10 transition-all cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="flex flex-col gap-4 max-h-[60vh] overflow-y-auto no-scrollbar pr-1">
          
          {/* Season Number */}
          <div className="p-3.5 rounded-xl bg-[#0d0e12] border border-white/5 flex items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <input
                type="checkbox"
                id="checkSeason"
                checked={changeSeason}
                onChange={(e) => setChangeSeason(e.target.checked)}
                className="w-4 h-4 accent-amber-500 rounded cursor-pointer"
              />
              <label htmlFor="checkSeason" className="text-xs font-bold text-gray-300 cursor-pointer select-none flex items-center gap-2">
                <Layers className="w-4 h-4 text-amber-400" />
                Change Season Number
              </label>
            </div>
            {changeSeason && (
              <input
                type="number"
                min={1}
                value={seasonNumber}
                onChange={(e) => setSeasonNumber(parseInt(e.target.value) || 1)}
                className="w-24 px-3 py-1.5 bg-[#131520] border border-amber-500/30 rounded-lg text-xs text-white focus:outline-none"
              />
            )}
          </div>

          {/* Audio Language */}
          <div className="p-3.5 rounded-xl bg-[#0d0e12] border border-white/5 flex items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <input
                type="checkbox"
                id="checkLang"
                checked={changeLang}
                onChange={(e) => setChangeLang(e.target.checked)}
                className="w-4 h-4 accent-amber-500 rounded cursor-pointer"
              />
              <label htmlFor="checkLang" className="text-xs font-bold text-gray-300 cursor-pointer select-none flex items-center gap-2">
                <Globe className="w-4 h-4 text-amber-400" />
                Change Default Audio Language
              </label>
            </div>
            {changeLang && (
              <select
                value={language}
                onChange={(e) => setLanguage(e.target.value as SupportedLanguage)}
                className="px-3 py-1.5 bg-[#131520] border border-amber-500/30 rounded-lg text-xs text-white focus:outline-none"
              >
                {["Japanese", "English", "Hindi", "Bangla", "Spanish"].map((l) => (
                  <option key={l} value={l}>{l}</option>
                ))}
              </select>
            )}
          </div>

          {/* Visibility */}
          <div className="p-3.5 rounded-xl bg-[#0d0e12] border border-white/5 flex items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <input
                type="checkbox"
                id="checkVisibility"
                checked={changeVisibility}
                onChange={(e) => setChangeVisibility(e.target.checked)}
                className="w-4 h-4 accent-amber-500 rounded cursor-pointer"
              />
              <label htmlFor="checkVisibility" className="text-xs font-bold text-gray-300 cursor-pointer select-none flex items-center gap-2">
                Visibility Status
              </label>
            </div>
            {changeVisibility && (
              <select
                value={isVisible ? "visible" : "hidden"}
                onChange={(e) => setIsVisible(e.target.value === "visible")}
                className="px-3 py-1.5 bg-[#131520] border border-amber-500/30 rounded-lg text-xs text-white focus:outline-none"
              >
                <option value="visible">Visible to Users</option>
                <option value="hidden">Hidden / Private</option>
              </select>
            )}
          </div>

          {/* Premium Status */}
          <div className="p-3.5 rounded-xl bg-[#0d0e12] border border-white/5 flex items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <input
                type="checkbox"
                id="checkPremium"
                checked={changePremium}
                onChange={(e) => setChangePremium(e.target.checked)}
                className="w-4 h-4 accent-amber-500 rounded cursor-pointer"
              />
              <label htmlFor="checkPremium" className="text-xs font-bold text-gray-300 cursor-pointer select-none flex items-center gap-2">
                <Lock className="w-4 h-4 text-amber-400" />
                Premium Access Level
              </label>
            </div>
            {changePremium && (
              <select
                value={isPremium ? "premium" : "free"}
                onChange={(e) => setIsPremium(e.target.value === "premium")}
                className="px-3 py-1.5 bg-[#131520] border border-amber-500/30 rounded-lg text-xs text-white focus:outline-none"
              >
                <option value="free">Free Stream</option>
                <option value="premium">Premium Only</option>
              </select>
            )}
          </div>

          {/* Publishing Status */}
          <div className="p-3.5 rounded-xl bg-[#0d0e12] border border-white/5 flex items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <input
                type="checkbox"
                id="checkStatus"
                checked={changeStatus}
                onChange={(e) => setChangeStatus(e.target.checked)}
                className="w-4 h-4 accent-amber-500 rounded cursor-pointer"
              />
              <label htmlFor="checkStatus" className="text-xs font-bold text-gray-300 cursor-pointer select-none flex items-center gap-2">
                Publish Status
              </label>
            </div>
            {changeStatus && (
              <select
                value={status}
                onChange={(e) => setStatus(e.target.value as any)}
                className="px-3 py-1.5 bg-[#131520] border border-amber-500/30 rounded-lg text-xs text-white focus:outline-none"
              >
                <option value="Published">Published</option>
                <option value="Draft">Draft</option>
              </select>
            )}
          </div>

          {/* Release Date */}
          <div className="p-3.5 rounded-xl bg-[#0d0e12] border border-white/5 flex items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <input
                type="checkbox"
                id="checkReleaseDate"
                checked={changeReleaseDate}
                onChange={(e) => setChangeReleaseDate(e.target.checked)}
                className="w-4 h-4 accent-amber-500 rounded cursor-pointer"
              />
              <label htmlFor="checkReleaseDate" className="text-xs font-bold text-gray-300 cursor-pointer select-none flex items-center gap-2">
                <Calendar className="w-4 h-4 text-amber-400" />
                Release Date
              </label>
            </div>
            {changeReleaseDate && (
              <input
                type="date"
                value={releaseDate}
                onChange={(e) => setReleaseDate(e.target.value)}
                className="px-3 py-1.5 bg-[#131520] border border-amber-500/30 rounded-lg text-xs text-white focus:outline-none"
              />
            )}
          </div>

          {/* Notes */}
          <div className="p-3.5 rounded-xl bg-[#0d0e12] border border-white/5 flex items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <input
                type="checkbox"
                id="checkNotes"
                checked={changeNotes}
                onChange={(e) => setChangeNotes(e.target.checked)}
                className="w-4 h-4 accent-amber-500 rounded cursor-pointer"
              />
              <label htmlFor="checkNotes" className="text-xs font-bold text-gray-300 cursor-pointer select-none flex items-center gap-2">
                Video Notes
              </label>
            </div>
            {changeNotes && (
              <input
                type="text"
                placeholder="e.g. Director's Cut"
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
                className="w-48 px-3 py-1.5 bg-[#131520] border border-amber-500/30 rounded-lg text-xs text-white focus:outline-none"
              />
            )}
          </div>

          <div className="flex items-center justify-end gap-3 pt-3">
            <button
              type="button"
              onClick={onClose}
              className="px-5 py-2.5 rounded-xl bg-white/5 hover:bg-white/10 text-xs font-bold text-gray-400 hover:text-white transition-all uppercase tracking-wider cursor-pointer"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="px-6 py-2.5 rounded-xl bg-amber-500 hover:bg-amber-400 text-black font-extrabold text-xs uppercase tracking-widest shadow-lg transition-all cursor-pointer flex items-center gap-2"
            >
              <Check className="w-4 h-4" />
              Apply Changes to {selectedCount} EP(s)
            </button>
          </div>

        </form>

      </div>
    </div>
  );
};
