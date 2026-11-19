import React, { useState, useEffect, useMemo, useRef } from "react";
import { 
  MissingEpisodeAlert, 
  MissingEpisodeReason, 
  MissingEpisodePriority, 
  MissingEpisodeStatus 
} from "../types";
import { 
  getMissingAlerts, 
  saveMissingAlerts, 
  addMissingAlert, 
  updateMissingAlert, 
  deleteMissingAlert,
  exportAlertsToCSV,
  exportAlertsToJSON,
  importAlertsFromJSON
} from "../services/missingEpisodeService";
import { useApp } from "../context/AppContext";
import { 
  AlertTriangle, 
  Plus, 
  Search, 
  Filter, 
  Download, 
  Upload as UploadIcon, 
  CheckCircle2, 
  X, 
  Edit3, 
  Trash2, 
  EyeOff, 
  ArrowUpDown, 
  FileSpreadsheet, 
  FileCode, 
  Clock, 
  ShieldAlert, 
  ListFilter,
  Check,
  AlertCircle
} from "lucide-react";

const REASONS_LIST: MissingEpisodeReason[] = [
  "Video Not Available",
  "Source Missing",
  "Upload Failed",
  "Copyright",
  "Broken Video",
  "Waiting For Better Quality",
  "Temporary Skip",
  "Other"
];

const PRIORITIES_LIST: MissingEpisodePriority[] = ["High", "Medium", "Low"];

const STATUSES_LIST: MissingEpisodeStatus[] = [
  "Missing",
  "Waiting",
  "Uploading",
  "Uploaded",
  "Ignored"
];

interface Props {
  onNavigateToUploadEpisode?: (animeName?: string, season?: number | string, episodeNumber?: number) => void;
}

export const EpisodeMissingAlertManager: React.FC<Props> = ({ onNavigateToUploadEpisode }) => {
  const { animeList } = useApp();
  const [alerts, setAlerts] = useState<MissingEpisodeAlert[]>([]);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  // Form State
  const [animeName, setAnimeName] = useState("");
  const [season, setSeason] = useState<string | number>(1);
  const [episodeNumber, setEpisodeNumber] = useState<string | number>(1);
  const [language, setLanguage] = useState("Sub");
  const [priority, setPriority] = useState<MissingEpisodePriority>("High");
  const [reason, setReason] = useState<MissingEpisodeReason>("Video Not Available");
  const [status, setStatus] = useState<MissingEpisodeStatus>("Missing");
  const [notes, setNotes] = useState("");

  // Filters & Search State
  const [searchQuery, setSearchQuery] = useState("");
  const [filterPriority, setFilterPriority] = useState<string>("all");
  const [filterStatus, setFilterStatus] = useState<string>("all");
  const [filterReason, setFilterReason] = useState<string>("all");
  const [filterLanguage, setFilterLanguage] = useState<string>("all");
  const [sortBy, setSortBy] = useState<"newest" | "oldest" | "anime_az" | "priority" | "episode_num">("newest");

  // Notification Toast
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  // File Input Ref for Import JSON
  const fileInputRef = useRef<HTMLInputElement>(null);

  const showToast = (msg: string) => {
    setToastMessage(msg);
    setTimeout(() => setToastMessage(null), 3000);
  };

  // Sync state from localStorage
  const loadAlerts = () => {
    setAlerts(getMissingAlerts());
  };

  useEffect(() => {
    loadAlerts();

    const handleUpdate = () => {
      loadAlerts();
    };

    window.addEventListener("animestream_missing_alerts_updated", handleUpdate);
    return () => {
      window.removeEventListener("animestream_missing_alerts_updated", handleUpdate);
    };
  }, []);

  // Modal Handlers
  const handleOpenAddModal = () => {
    setEditingId(null);
    setAnimeName("");
    setSeason(1);
    setEpisodeNumber(1);
    setLanguage("Sub");
    setPriority("High");
    setReason("Video Not Available");
    setStatus("Missing");
    setNotes("");
    setIsModalOpen(true);
  };

  const handleOpenEditModal = (alert: MissingEpisodeAlert) => {
    setEditingId(alert.id);
    setAnimeName(alert.animeName);
    setSeason(alert.season);
    setEpisodeNumber(alert.episodeNumber);
    setLanguage(alert.language || "Sub");
    setPriority(alert.priority);
    setReason(alert.reason);
    setStatus(alert.status);
    setNotes(alert.notes || "");
    setIsModalOpen(true);
  };

  const handleSaveForm = (e: React.FormEvent) => {
    e.preventDefault();
    if (!animeName.trim()) {
      alert("Please enter or select an anime name.");
      return;
    }

    if (!episodeNumber || Number(episodeNumber) <= 0) {
      alert("Please enter a valid episode number.");
      return;
    }

    if (editingId) {
      updateMissingAlert(editingId, {
        animeName: animeName.trim(),
        season: String(season),
        episodeNumber: Number(episodeNumber),
        language: language.trim() || "Sub",
        priority,
        reason,
        status,
        notes: notes.trim(),
      });
      showToast("Missing episode alert updated!");
    } else {
      addMissingAlert({
        animeName: animeName.trim(),
        season: String(season),
        episodeNumber: Number(episodeNumber),
        language: language.trim() || "Sub",
        priority,
        reason,
        status,
        notes: notes.trim(),
      });
      showToast("Missing episode alert added!");
    }

    setIsModalOpen(false);
  };

  const handleDelete = (id: string) => {
    if (confirm("Are you sure you want to delete this missing episode alert?")) {
      deleteMissingAlert(id);
      showToast("Alert deleted.");
    }
  };

  const handleMarkUploaded = (id: string) => {
    updateMissingAlert(id, { status: "Uploaded" });
    showToast("Status updated to Uploaded!");
  };

  const handleMarkIgnored = (id: string) => {
    updateMissingAlert(id, { status: "Ignored" });
    showToast("Status updated to Ignored.");
  };

  // Import JSON handler
  const handleImportFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (event) => {
      try {
        const content = event.target?.result as string;
        const importedCount = importAlertsFromJSON(content);
        showToast(`Successfully imported ${importedCount} missing episode alerts!`);
      } catch (err: any) {
        alert(err?.message || "Failed to import JSON file.");
      }
    };
    reader.readAsText(file);
    // Reset file input
    e.target.value = "";
  };

  // Unique Languages in dataset for dropdown
  const uniqueLanguages = useMemo(() => {
    const langs = new Set<string>();
    alerts.forEach((a) => {
      if (a.language) langs.add(a.language);
    });
    return Array.from(langs);
  }, [alerts]);

  // Filtered & Sorted Alerts
  const filteredAlerts = useMemo(() => {
    return alerts
      .filter((item) => {
        // Query search
        if (searchQuery.trim()) {
          const q = searchQuery.toLowerCase().trim();
          const matchAnime = item.animeName.toLowerCase().includes(q);
          const matchEp = String(item.episodeNumber).includes(q);
          const matchSeason = String(item.season).includes(q);
          const matchLang = (item.language || "").toLowerCase().includes(q);
          if (!matchAnime && !matchEp && !matchSeason && !matchLang) {
            return false;
          }
        }

        // Priority filter
        if (filterPriority !== "all" && item.priority !== filterPriority) {
          return false;
        }

        // Status filter
        if (filterStatus !== "all" && item.status !== filterStatus) {
          return false;
        }

        // Reason filter
        if (filterReason !== "all" && item.reason !== filterReason) {
          return false;
        }

        // Language filter
        if (filterLanguage !== "all" && item.language !== filterLanguage) {
          return false;
        }

        return true;
      })
      .sort((a, b) => {
        if (sortBy === "newest") {
          return b.createdAt - a.createdAt;
        }
        if (sortBy === "oldest") {
          return a.createdAt - b.createdAt;
        }
        if (sortBy === "anime_az") {
          return a.animeName.localeCompare(b.animeName);
        }
        if (sortBy === "priority") {
          const pOrder = { High: 3, Medium: 2, Low: 1 };
          return (pOrder[b.priority] || 0) - (pOrder[a.priority] || 0);
        }
        if (sortBy === "episode_num") {
          return Number(a.episodeNumber) - Number(b.episodeNumber);
        }
        return 0;
      });
  }, [alerts, searchQuery, filterPriority, filterStatus, filterReason, filterLanguage, sortBy]);

  // Statistics Summary Math
  const stats = useMemo(() => {
    const totalMissing = alerts.filter((a) => a.status === "Missing" || a.status === "Waiting" || a.status === "Uploading").length;
    const highPriority = alerts.filter((a) => a.priority === "High" && a.status !== "Uploaded" && a.status !== "Ignored").length;
    const waitingUpload = alerts.filter((a) => a.status === "Waiting").length;
    const uploaded = alerts.filter((a) => a.status === "Uploaded").length;
    const ignored = alerts.filter((a) => a.status === "Ignored").length;

    return { totalMissing, highPriority, waitingUpload, uploaded, ignored };
  }, [alerts]);

  return (
    <div className="flex flex-col gap-6 text-white animate-fade-in">
      {/* Toast Notification */}
      {toastMessage && (
        <div className="fixed top-20 right-6 z-50 bg-emerald-500 text-black font-extrabold px-4 py-2.5 rounded-xl shadow-2xl flex items-center gap-2 animate-bounce-in text-xs">
          <CheckCircle2 className="w-4 h-4 text-black shrink-0" />
          <span>{toastMessage}</span>
        </div>
      )}

      {/* Header Banner */}
      <div className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4 p-6 rounded-3xl bg-gradient-to-r from-[#1d161d] via-[#2a1723] to-[#181a26] border border-rose-500/30 shadow-2xl">
        <div className="flex items-center gap-4">
          <div className="p-3.5 rounded-2xl bg-rose-500 text-white shadow-lg shadow-rose-500/20">
            <AlertTriangle className="w-7 h-7" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-black tracking-tight text-white">🔴 Episode Missing Alert</h2>
              <span className="text-[10px] font-mono font-black uppercase px-2 py-0.5 rounded bg-rose-500/20 text-rose-300 border border-rose-500/30">
                Offline LocalStorage
              </span>
            </div>
            <p className="text-xs text-gray-400 mt-1">
              Track and manage anime episodes that are missing or skipped so you can upload them later.
            </p>
          </div>
        </div>

        {/* Action Buttons */}
        <div className="flex items-center gap-2 flex-wrap shrink-0">
          <button
            type="button"
            onClick={handleOpenAddModal}
            className="px-4 py-2.5 rounded-xl bg-rose-500 hover:bg-rose-400 text-white font-extrabold text-xs flex items-center gap-2 transition-all cursor-pointer shadow-lg shadow-rose-500/20"
          >
            <Plus className="w-4 h-4" />
            <span>Add Missing Episode</span>
          </button>

          <button
            type="button"
            onClick={() => exportAlertsToCSV(filteredAlerts)}
            className="px-3.5 py-2.5 rounded-xl bg-white/5 hover:bg-white/10 text-gray-200 font-bold text-xs flex items-center gap-1.5 transition-all cursor-pointer border border-white/10"
            title="Export CSV"
          >
            <FileSpreadsheet className="w-4 h-4 text-emerald-400" />
            <span>Export CSV</span>
          </button>

          <button
            type="button"
            onClick={() => exportAlertsToJSON(filteredAlerts)}
            className="px-3.5 py-2.5 rounded-xl bg-white/5 hover:bg-white/10 text-gray-200 font-bold text-xs flex items-center gap-1.5 transition-all cursor-pointer border border-white/10"
            title="Export JSON"
          >
            <FileCode className="w-4 h-4 text-amber-400" />
            <span>Export JSON</span>
          </button>

          <button
            type="button"
            onClick={() => fileInputRef.current?.click()}
            className="px-3.5 py-2.5 rounded-xl bg-white/5 hover:bg-white/10 text-gray-200 font-bold text-xs flex items-center gap-1.5 transition-all cursor-pointer border border-white/10"
            title="Import JSON"
          >
            <UploadIcon className="w-4 h-4 text-blue-400" />
            <span>Import JSON</span>
          </button>
          <input
            type="file"
            ref={fileInputRef}
            onChange={handleImportFileChange}
            accept=".json"
            className="hidden"
          />
        </div>
      </div>

      {/* Dashboard Summary Cards */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-4">
        <div className="p-4 rounded-2xl bg-[#131520]/80 border border-white/5 flex flex-col gap-1.5">
          <div className="flex items-center justify-between text-gray-400 text-[11px] font-bold font-mono uppercase">
            <span>Total Missing</span>
            <AlertTriangle className="w-4 h-4 text-rose-400" />
          </div>
          <div className="text-2xl font-black text-rose-400">{stats.totalMissing}</div>
          <span className="text-[10px] text-gray-400 font-mono">Episodes needing upload</span>
        </div>

        <div className="p-4 rounded-2xl bg-[#131520]/80 border border-white/5 flex flex-col gap-1.5">
          <div className="flex items-center justify-between text-gray-400 text-[11px] font-bold font-mono uppercase">
            <span>High Priority</span>
            <ShieldAlert className="w-4 h-4 text-amber-400" />
          </div>
          <div className="text-2xl font-black text-amber-400">{stats.highPriority}</div>
          <span className="text-[10px] text-amber-400/80 font-mono">Urgent attention needed</span>
        </div>

        <div className="p-4 rounded-2xl bg-[#131520]/80 border border-white/5 flex flex-col gap-1.5">
          <div className="flex items-center justify-between text-gray-400 text-[11px] font-bold font-mono uppercase">
            <span>Waiting Upload</span>
            <Clock className="w-4 h-4 text-blue-400" />
          </div>
          <div className="text-2xl font-black text-blue-400">{stats.waitingUpload}</div>
          <span className="text-[10px] text-gray-400 font-mono">In queue or scheduled</span>
        </div>

        <div className="p-4 rounded-2xl bg-[#131520]/80 border border-white/5 flex flex-col gap-1.5">
          <div className="flex items-center justify-between text-gray-400 text-[11px] font-bold font-mono uppercase">
            <span>Uploaded</span>
            <CheckCircle2 className="w-4 h-4 text-emerald-400" />
          </div>
          <div className="text-2xl font-black text-emerald-400">{stats.uploaded}</div>
          <span className="text-[10px] text-emerald-400/80 font-mono">Successfully resolved</span>
        </div>

        <div className="p-4 rounded-2xl bg-[#131520]/80 border border-white/5 flex flex-col gap-1.5">
          <div className="flex items-center justify-between text-gray-400 text-[11px] font-bold font-mono uppercase">
            <span>Ignored</span>
            <EyeOff className="w-4 h-4 text-gray-400" />
          </div>
          <div className="text-2xl font-black text-gray-400">{stats.ignored}</div>
          <span className="text-[10px] text-gray-400 font-mono">Dismissed or skipped</span>
        </div>
      </div>

      {/* Search & Filter Bar */}
      <div className="p-4 rounded-2xl bg-[#131520] border border-white/10 flex flex-col lg:flex-row items-center justify-between gap-4">
        {/* Search Input */}
        <div className="relative w-full lg:w-80">
          <Search className="w-4 h-4 absolute left-3.5 top-1/2 -translate-y-1/2 text-gray-400" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search anime, ep #, season..."
            className="w-full pl-10 pr-4 py-2 bg-[#1a1d2d] border border-white/10 rounded-xl text-xs text-white placeholder-gray-500 focus:outline-none focus:border-rose-500 transition-all"
          />
        </div>

        {/* Filter Dropdowns */}
        <div className="flex items-center gap-2 flex-wrap w-full lg:w-auto text-xs">
          {/* Priority */}
          <select
            value={filterPriority}
            onChange={(e) => setFilterPriority(e.target.value)}
            className="px-3 py-2 bg-[#1a1d2d] border border-white/10 rounded-xl text-gray-200 cursor-pointer focus:outline-none focus:border-rose-500"
          >
            <option value="all">All Priorities</option>
            {PRIORITIES_LIST.map((p) => (
              <option key={p} value={p}>{p} Priority</option>
            ))}
          </select>

          {/* Status */}
          <select
            value={filterStatus}
            onChange={(e) => setFilterStatus(e.target.value)}
            className="px-3 py-2 bg-[#1a1d2d] border border-white/10 rounded-xl text-gray-200 cursor-pointer focus:outline-none focus:border-rose-500"
          >
            <option value="all">All Statuses</option>
            {STATUSES_LIST.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>

          {/* Reason */}
          <select
            value={filterReason}
            onChange={(e) => setFilterReason(e.target.value)}
            className="px-3 py-2 bg-[#1a1d2d] border border-white/10 rounded-xl text-gray-200 cursor-pointer focus:outline-none focus:border-rose-500 max-w-[150px] truncate"
          >
            <option value="all">All Reasons</option>
            {REASONS_LIST.map((r) => (
              <option key={r} value={r}>{r}</option>
            ))}
          </select>

          {/* Language */}
          {uniqueLanguages.length > 0 && (
            <select
              value={filterLanguage}
              onChange={(e) => setFilterLanguage(e.target.value)}
              className="px-3 py-2 bg-[#1a1d2d] border border-white/10 rounded-xl text-gray-200 cursor-pointer focus:outline-none focus:border-rose-500"
            >
              <option value="all">All Languages</option>
              {uniqueLanguages.map((l) => (
                <option key={l} value={l}>{l}</option>
              ))}
            </select>
          )}

          {/* Sort By */}
          <div className="flex items-center gap-1 bg-[#1a1d2d] border border-white/10 rounded-xl px-2 py-1">
            <ArrowUpDown className="w-3.5 h-3.5 text-gray-400" />
            <select
              value={sortBy}
              onChange={(e) => setSortBy(e.target.value as any)}
              className="bg-transparent text-gray-200 cursor-pointer focus:outline-none py-1 text-xs font-bold"
            >
              <option value="newest">Newest First</option>
              <option value="oldest">Oldest First</option>
              <option value="anime_az">Anime A-Z</option>
              <option value="priority">Priority</option>
              <option value="episode_num">Episode #</option>
            </select>
          </div>
        </div>
      </div>

      {/* Missing Episodes Table */}
      <div className="overflow-x-auto rounded-2xl border border-white/10 bg-[#131520]/60">
        <table className="w-full text-left text-xs">
          <thead className="bg-[#1a1d2d] text-gray-400 font-mono uppercase text-[11px] border-b border-white/10">
            <tr>
              <th className="p-4">Anime Name</th>
              <th className="p-4">Season</th>
              <th className="p-4">Episode</th>
              <th className="p-4">Language</th>
              <th className="p-4">Priority</th>
              <th className="p-4">Status</th>
              <th className="p-4">Reason</th>
              <th className="p-4">Date Added</th>
              <th className="p-4 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-white/5 text-gray-200">
            {filteredAlerts.length === 0 ? (
              <tr>
                <td colSpan={9} className="p-8 text-center text-gray-400 font-mono">
                  No missing episode alerts found. Click <span className="text-rose-400 font-bold">"Add Missing Episode"</span> to create one.
                </td>
              </tr>
            ) : (
              filteredAlerts.map((item) => (
                <tr key={item.id} className="hover:bg-white/5 transition-colors">
                  {/* Anime */}
                  <td className="p-4 font-bold">
                    <div className="flex flex-col">
                      <span className="text-white font-extrabold text-sm">{item.animeName}</span>
                      {item.notes && (
                        <span className="text-[10px] text-gray-400 line-clamp-1 italic">{item.notes}</span>
                      )}
                    </div>
                  </td>

                  {/* Season */}
                  <td className="p-4 font-mono font-bold text-gray-300">
                    S{item.season}
                  </td>

                  {/* Episode */}
                  <td className="p-4 font-mono font-black text-rose-400 text-sm">
                    EP {item.episodeNumber}
                  </td>

                  {/* Language */}
                  <td className="p-4 font-mono">
                    <span className="px-2 py-0.5 rounded bg-white/5 text-gray-300 border border-white/10 text-[10px]">
                      {item.language}
                    </span>
                  </td>

                  {/* Priority */}
                  <td className="p-4 font-mono">
                    <span
                      className={`px-2 py-0.5 rounded text-[10px] font-black uppercase ${
                        item.priority === "High"
                          ? "bg-rose-500/20 text-rose-400 border border-rose-500/30"
                          : item.priority === "Medium"
                          ? "bg-amber-500/20 text-amber-400 border border-amber-500/30"
                          : "bg-blue-500/20 text-blue-400 border border-blue-500/30"
                      }`}
                    >
                      {item.priority}
                    </span>
                  </td>

                  {/* Status */}
                  <td className="p-4 font-mono">
                    <span
                      className={`px-2.5 py-0.5 rounded text-[10px] font-extrabold uppercase ${
                        item.status === "Missing"
                          ? "bg-rose-500/20 text-rose-400 border border-rose-500/30"
                          : item.status === "Waiting"
                          ? "bg-blue-500/20 text-blue-400 border border-blue-500/30"
                          : item.status === "Uploading"
                          ? "bg-amber-500/20 text-amber-400 border border-amber-500/30"
                          : item.status === "Uploaded"
                          ? "bg-emerald-500/20 text-emerald-400 border border-emerald-500/30"
                          : "bg-gray-500/20 text-gray-400 border border-gray-500/30"
                      }`}
                    >
                      {item.status}
                    </span>
                  </td>

                  {/* Reason */}
                  <td className="p-4 font-mono text-gray-300 text-[11px]">
                    {item.reason}
                  </td>

                  {/* Date Added */}
                  <td className="p-4 font-mono text-gray-400 text-[11px]">
                    {new Date(item.createdAt).toLocaleDateString()}
                  </td>

                  {/* Actions */}
                  <td className="p-4 text-right">
                    <div className="flex items-center justify-end gap-1.5">
                      {item.status !== "Uploaded" && (
                        <button
                          type="button"
                          onClick={() => handleMarkUploaded(item.id)}
                          className="p-1.5 rounded-lg text-emerald-400 bg-emerald-500/10 hover:bg-emerald-500/20 transition-all"
                          title="Mark Uploaded"
                        >
                          <Check className="w-4 h-4" />
                        </button>
                      )}

                      {item.status !== "Ignored" && (
                        <button
                          type="button"
                          onClick={() => handleMarkIgnored(item.id)}
                          className="p-1.5 rounded-lg text-gray-400 hover:text-gray-200 hover:bg-white/10 transition-all"
                          title="Mark Ignored"
                        >
                          <EyeOff className="w-4 h-4" />
                        </button>
                      )}

                      <button
                        type="button"
                        onClick={() => handleOpenEditModal(item)}
                        className="p-1.5 rounded-lg text-gray-400 hover:text-amber-400 hover:bg-amber-500/10 transition-all"
                        title="Edit Alert"
                      >
                        <Edit3 className="w-4 h-4" />
                      </button>

                      <button
                        type="button"
                        onClick={() => handleDelete(item.id)}
                        className="p-1.5 rounded-lg text-gray-400 hover:text-rose-400 hover:bg-rose-500/10 transition-all"
                        title="Delete Alert"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* CREATE / EDIT MODAL */}
      {isModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md">
          <div className="w-full max-w-lg bg-[#131520] border border-rose-500/30 rounded-3xl p-6 flex flex-col gap-5 max-h-[90vh] overflow-y-auto animate-scale-up">
            <div className="flex items-center justify-between border-b border-white/10 pb-4">
              <div className="flex items-center gap-2">
                <AlertTriangle className="w-5 h-5 text-rose-400" />
                <h3 className="text-lg font-black text-white">
                  {editingId ? "Edit Missing Episode Alert" : "Add Missing Episode Alert"}
                </h3>
              </div>
              <button
                type="button"
                onClick={() => setIsModalOpen(false)}
                className="text-gray-400 hover:text-white cursor-pointer"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            <form onSubmit={handleSaveForm} className="flex flex-col gap-4 text-xs">
              {/* Anime Select / Input */}
              <div className="flex flex-col gap-1.5">
                <label className="font-bold text-gray-300">Anime Name *</label>
                <input
                  type="text"
                  list="anime-suggestions"
                  value={animeName}
                  onChange={(e) => setAnimeName(e.target.value)}
                  placeholder="e.g. Solo Leveling, Naruto, Jujutsu Kaisen"
                  className="px-3.5 py-2.5 bg-[#1a1d2d] border border-white/10 rounded-xl text-white focus:outline-none focus:border-rose-500"
                  required
                />
                <datalist id="anime-suggestions">
                  {animeList.map((a) => (
                    <option key={a.id} value={a.title} />
                  ))}
                </datalist>
              </div>

              {/* Grid 2 Columns: Season & Episode */}
              <div className="grid grid-cols-2 gap-4">
                <div className="flex flex-col gap-1.5">
                  <label className="font-bold text-gray-300">Season Number *</label>
                  <input
                    type="number"
                    min={1}
                    value={season}
                    onChange={(e) => setSeason(e.target.value)}
                    className="px-3.5 py-2.5 bg-[#1a1d2d] border border-white/10 rounded-xl text-white focus:outline-none focus:border-rose-500"
                    required
                  />
                </div>

                <div className="flex flex-col gap-1.5">
                  <label className="font-bold text-gray-300">Episode Number *</label>
                  <input
                    type="number"
                    min={1}
                    value={episodeNumber}
                    onChange={(e) => setEpisodeNumber(e.target.value)}
                    className="px-3.5 py-2.5 bg-[#1a1d2d] border border-white/10 rounded-xl text-white focus:outline-none focus:border-rose-500"
                    required
                  />
                </div>
              </div>

              {/* Grid 2 Columns: Language & Priority */}
              <div className="grid grid-cols-2 gap-4">
                <div className="flex flex-col gap-1.5">
                  <label className="font-bold text-gray-300">Language</label>
                  <select
                    value={language}
                    onChange={(e) => setLanguage(e.target.value)}
                    className="px-3.5 py-2.5 bg-[#1a1d2d] border border-white/10 rounded-xl text-white focus:outline-none focus:border-rose-500"
                  >
                    <option value="Sub">Subtitled (Sub)</option>
                    <option value="Dub">Dubbed (Dub)</option>
                    <option value="Bangla Sub">Bangla Sub</option>
                    <option value="Bangla Dub">Bangla Dub</option>
                    <option value="Dual Audio">Dual Audio</option>
                    <option value="Multi Audio">Multi Audio</option>
                    <option value="Raw">Raw</option>
                  </select>
                </div>

                <div className="flex flex-col gap-1.5">
                  <label className="font-bold text-gray-300">Priority</label>
                  <select
                    value={priority}
                    onChange={(e) => setPriority(e.target.value as any)}
                    className="px-3.5 py-2.5 bg-[#1a1d2d] border border-white/10 rounded-xl text-white focus:outline-none focus:border-rose-500"
                  >
                    <option value="High">High Priority</option>
                    <option value="Medium">Medium Priority</option>
                    <option value="Low">Low Priority</option>
                  </select>
                </div>
              </div>

              {/* Grid 2 Columns: Reason & Status */}
              <div className="grid grid-cols-2 gap-4">
                <div className="flex flex-col gap-1.5">
                  <label className="font-bold text-gray-300">Reason</label>
                  <select
                    value={reason}
                    onChange={(e) => setReason(e.target.value as any)}
                    className="px-3.5 py-2.5 bg-[#1a1d2d] border border-white/10 rounded-xl text-white focus:outline-none focus:border-rose-500"
                  >
                    {REASONS_LIST.map((r) => (
                      <option key={r} value={r}>{r}</option>
                    ))}
                  </select>
                </div>

                <div className="flex flex-col gap-1.5">
                  <label className="font-bold text-gray-300">Status</label>
                  <select
                    value={status}
                    onChange={(e) => setStatus(e.target.value as any)}
                    className="px-3.5 py-2.5 bg-[#1a1d2d] border border-white/10 rounded-xl text-white focus:outline-none focus:border-rose-500"
                  >
                    {STATUSES_LIST.map((s) => (
                      <option key={s} value={s}>{s}</option>
                    ))}
                  </select>
                </div>
              </div>

              {/* Notes */}
              <div className="flex flex-col gap-1.5">
                <label className="font-bold text-gray-300">Notes / Instructions (Optional)</label>
                <textarea
                  rows={3}
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                  placeholder="e.g. Waiting for 1080p Web-DL release..."
                  className="px-3.5 py-2.5 bg-[#1a1d2d] border border-white/10 rounded-xl text-white focus:outline-none focus:border-rose-500 leading-relaxed"
                />
              </div>

              <div className="flex justify-end gap-3 pt-3 border-t border-white/10">
                <button
                  type="button"
                  onClick={() => setIsModalOpen(false)}
                  className="px-5 py-2.5 rounded-xl bg-white/10 hover:bg-white/15 text-gray-300 font-bold cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="px-6 py-2.5 rounded-xl bg-rose-500 hover:bg-rose-400 text-white font-extrabold cursor-pointer shadow-lg shadow-rose-500/20"
                >
                  Save Alert
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
