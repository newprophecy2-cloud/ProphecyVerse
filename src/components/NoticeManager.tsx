import React, { useEffect, useState } from "react";
import { Notice } from "../types";
import { 
  subscribeToNotices, 
  createNotice, 
  updateNotice, 
  deleteNotice, 
  seedNoticesIfEmpty 
} from "../services/noticeService";
import { 
  Bell, 
  Plus, 
  Edit, 
  Trash2, 
  Pin, 
  Eye, 
  CheckCircle2, 
  X, 
  Calendar, 
  Globe, 
  AlertCircle,
  Archive,
  Sparkles
} from "lucide-react";

export const NoticeManager: React.FC = () => {
  const [notices, setNotices] = useState<Notice[]>([]);
  const [isModalOpen, setIsModalOpen] = useState<boolean>(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [previewNotice, setPreviewNotice] = useState<Notice | null>(null);
  const [toastMsg, setToastMsg] = useState<string | null>(null);

  // Form Fields
  const [titleBn, setTitleBn] = useState("");
  const [titleEn, setTitleEn] = useState("");
  const [contentBn, setContentBn] = useState("");
  const [contentEn, setContentEn] = useState("");
  const [language, setLanguage] = useState<"bn" | "en" | "all">("all");
  const [priority, setPriority] = useState<"high" | "normal" | "low">("high");
  const [status, setStatus] = useState<"active" | "draft" | "archived">("active");
  const [displayType, setDisplayType] = useState<"popup" | "banner" | "both">("popup");
  const [frequency, setFrequency] = useState<"show_once" | "show_every_visit" | "show_once_per_day">("show_once_per_day");
  const [isPinned, setIsPinned] = useState<boolean>(true);
  const [scheduleStart, setScheduleStart] = useState("");
  const [scheduleEnd, setScheduleEnd] = useState("");

  useEffect(() => {
    seedNoticesIfEmpty();
    const unsub = subscribeToNotices((list) => setNotices(list));
    return () => unsub();
  }, []);

  const showToast = (msg: string) => {
    setToastMsg(msg);
    setTimeout(() => setToastMsg(null), 3000);
  };

  const handleOpenCreateModal = () => {
    setEditingId(null);
    setTitleBn("📢 নোটিশ");
    setTitleEn("📢 Notice");
    setContentBn("আমাদের আপলোডাররা যত দ্রুত সম্ভব নতুন এপিসোড আপলোড করার চেষ্টা করছেন। তাই মাঝে মাঝে Episode Number বা Language-সংক্রান্ত কিছু ভুল হতে পারে।\n\nএ ধরনের কোনো সমস্যা চোখে পড়লে, অনুগ্রহ করে সংশ্লিষ্ট Anime-এর নিচে কমেন্ট করে আমাদের জানান। আপনার মন্তব্যের ভিত্তিতে আমরা দ্রুত সমস্যাটি সমাধান করার চেষ্টা করব।\n\nআপনার সহযোগিতার জন্য ধন্যবাদ! ❤️");
    setContentEn("Our uploaders work hard to upload new episodes as quickly as possible. Occasionally, there may be mistakes in the episode number or language.\n\nIf you notice any issue, please leave a comment under the corresponding anime. We will review it and fix it as soon as possible.\n\nThank you for your support!");
    setLanguage("all");
    setPriority("high");
    setStatus("active");
    setDisplayType("popup");
    setFrequency("show_once_per_day");
    setIsPinned(true);
    setScheduleStart("");
    setScheduleEnd("");
    setIsModalOpen(true);
  };

  const handleOpenEditModal = (n: Notice) => {
    setEditingId(n.id);
    setTitleBn(n.titleBn || "");
    setTitleEn(n.titleEn || "");
    setContentBn(n.contentBn || "");
    setContentEn(n.contentEn || "");
    setLanguage(n.language || "all");
    setPriority(n.priority || "high");
    setStatus(n.status || "active");
    setDisplayType(n.displayType || "popup");
    setFrequency(n.frequency || "show_once_per_day");
    setIsPinned(!!n.isPinned);
    setScheduleStart(n.scheduleStart || "");
    setScheduleEnd(n.scheduleEnd || "");
    setIsModalOpen(true);
  };

  const handleSaveNotice = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!titleBn.trim() && !titleEn.trim()) {
      alert("Please provide at least one title.");
      return;
    }

    if (editingId) {
      await updateNotice(editingId, {
        titleBn,
        titleEn,
        contentBn,
        contentEn,
        language,
        priority,
        status,
        displayType,
        frequency,
        isPinned,
        scheduleStart,
        scheduleEnd,
      });
      showToast("Notice Updated!");
    } else {
      await createNotice({
        titleBn,
        titleEn,
        contentBn,
        contentEn,
        language,
        priority,
        status,
        displayType,
        frequency,
        isPinned,
        scheduleStart,
        scheduleEnd,
      });
      showToast("Notice Created!");
    }
    setIsModalOpen(false);
  };

  const handleToggleStatus = async (n: Notice) => {
    const nextStatus = n.status === "active" ? "draft" : "active";
    await updateNotice(n.id, { status: nextStatus });
    showToast(`Notice ${nextStatus === "active" ? "Activated" : "Deactivated"}`);
  };

  const handleTogglePin = async (n: Notice) => {
    await updateNotice(n.id, { isPinned: !n.isPinned });
    showToast(n.isPinned ? "Unpinned Notice" : "Pinned Notice to Top!");
  };

  const handleDelete = async (id: string) => {
    if (confirm("Are you sure you want to delete this notice?")) {
      await deleteNotice(id);
      showToast("Notice Deleted");
    }
  };

  const handleArchive = async (n: Notice) => {
    await updateNotice(n.id, { status: "archived" });
    showToast("Notice Archived");
  };

  return (
    <div className="flex flex-col gap-6 text-white">
      {/* Toast Notification */}
      {toastMsg && (
        <div className="fixed top-20 right-6 z-50 bg-emerald-500 text-black font-extrabold px-4 py-2.5 rounded-xl shadow-2xl flex items-center gap-2 animate-bounce-in text-xs">
          <CheckCircle2 className="w-4 h-4 text-black shrink-0" />
          <span>{toastMsg}</span>
        </div>
      )}

      {/* Header Banner */}
      <div className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4 p-6 rounded-3xl bg-gradient-to-r from-[#181a26] via-[#1f2233] to-[#181a26] border border-amber-500/20 shadow-2xl">
        <div className="flex items-center gap-4">
          <div className="p-3.5 rounded-2xl bg-amber-500 text-black shadow-lg">
            <Bell className="w-7 h-7" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-black tracking-tight text-white">Notice Management System</h2>
              <span className="text-[10px] font-mono font-black uppercase px-2 py-0.5 rounded bg-amber-500/20 text-amber-300 border border-amber-500/30">
                Geo-Smart (BD/EN)
              </span>
            </div>
            <p className="text-xs text-gray-400 mt-1">
              Publish announcements that automatically serve Bangla notices to Bangladesh visitors and English notices globally.
            </p>
          </div>
        </div>

        <button
          type="button"
          onClick={handleOpenCreateModal}
          className="px-5 py-2.5 rounded-xl bg-gradient-to-r from-amber-500 to-orange-500 text-black font-extrabold text-xs flex items-center gap-2 transition-all cursor-pointer hover:brightness-110 shadow-lg shrink-0"
        >
          <Plus className="w-4 h-4" />
          <span>Create Notice</span>
        </button>
      </div>

      {/* Notices Table */}
      <div className="overflow-x-auto rounded-2xl border border-white/10 bg-[#131520]/60">
        <table className="w-full text-left text-xs">
          <thead className="bg-[#1a1d2d] text-gray-400 font-mono uppercase text-[11px] border-b border-white/10">
            <tr>
              <th className="p-4">Title (Bangla / English)</th>
              <th className="p-4">Type</th>
              <th className="p-4">Frequency</th>
              <th className="p-4">Priority</th>
              <th className="p-4">Status</th>
              <th className="p-4 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-white/5 text-gray-200">
            {notices.length === 0 ? (
              <tr>
                <td colSpan={6} className="p-8 text-center text-gray-400 font-mono">
                  No notices found. Click <span className="text-amber-400 font-bold">"Create Notice"</span> to add an announcement.
                </td>
              </tr>
            ) : (
              notices.map((n) => (
                <tr key={n.id} className="hover:bg-white/5 transition-colors">
                  <td className="p-4 font-bold">
                    <div className="flex flex-col gap-0.5">
                      <div className="flex items-center gap-2">
                        {n.isPinned && <Pin className="w-3.5 h-3.5 text-amber-400 fill-amber-400 shrink-0" />}
                        <span className="text-white font-extrabold">{n.titleBn || n.titleEn}</span>
                      </div>
                      <span className="text-[10px] text-gray-400 line-clamp-1">{n.contentBn || n.contentEn}</span>
                    </div>
                  </td>

                  <td className="p-4 font-mono font-bold uppercase text-[10px]">
                    <span className="px-2 py-0.5 rounded bg-amber-500/10 text-amber-400 border border-amber-500/20">
                      {n.displayType}
                    </span>
                  </td>

                  <td className="p-4 font-mono text-gray-300">
                    {n.frequency.replace(/_/g, " ")}
                  </td>

                  <td className="p-4 font-mono">
                    <span className={`px-2 py-0.5 rounded text-[10px] font-bold ${
                      n.priority === "high" ? "bg-red-500/20 text-red-400" : "bg-blue-500/20 text-blue-400"
                    }`}>
                      {n.priority}
                    </span>
                  </td>

                  <td className="p-4 font-mono">
                    <span
                      className={`px-2.5 py-0.5 rounded text-[10px] font-extrabold uppercase ${
                        n.status === "active"
                          ? "bg-emerald-500/20 text-emerald-400 border border-emerald-500/30"
                          : n.status === "archived"
                          ? "bg-gray-500/20 text-gray-400 border border-gray-500/30"
                          : "bg-amber-500/20 text-amber-400 border border-amber-500/30"
                      }`}
                    >
                      {n.status}
                    </span>
                  </td>

                  <td className="p-4 text-right">
                    <div className="flex items-center justify-end gap-1.5">
                      <button
                        type="button"
                        onClick={() => setPreviewNotice(n)}
                        className="p-2 rounded-lg text-gray-400 hover:text-white hover:bg-white/10 transition-colors"
                        title="Preview Notice"
                      >
                        <Eye className="w-4 h-4" />
                      </button>

                      <button
                        type="button"
                        onClick={() => handleTogglePin(n)}
                        className={`p-2 rounded-lg transition-colors ${
                          n.isPinned ? "text-amber-400 bg-amber-500/10" : "text-gray-400 hover:text-amber-400"
                        }`}
                        title={n.isPinned ? "Unpin Notice" : "Pin Notice"}
                      >
                        <Pin className="w-4 h-4" />
                      </button>

                      <button
                        type="button"
                        onClick={() => handleToggleStatus(n)}
                        className="p-2 rounded-lg text-gray-400 hover:text-emerald-400 hover:bg-emerald-500/10 transition-colors"
                        title={n.status === "active" ? "Deactivate" : "Activate"}
                      >
                        <CheckCircle2 className="w-4 h-4" />
                      </button>

                      <button
                        type="button"
                        onClick={() => handleOpenEditModal(n)}
                        className="p-2 rounded-lg text-gray-400 hover:text-amber-500 hover:bg-amber-500/10 transition-colors"
                        title="Edit Notice"
                      >
                        <Edit className="w-4 h-4" />
                      </button>

                      <button
                        type="button"
                        onClick={() => handleArchive(n)}
                        className="p-2 rounded-lg text-gray-400 hover:text-blue-400 hover:bg-blue-500/10 transition-colors"
                        title="Archive Notice"
                      >
                        <Archive className="w-4 h-4" />
                      </button>

                      <button
                        type="button"
                        onClick={() => handleDelete(n.id)}
                        className="p-2 rounded-lg text-gray-400 hover:text-red-500 hover:bg-red-500/10 transition-colors"
                        title="Delete Notice"
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
          <div className="w-full max-w-2xl bg-[#131520] border border-amber-500/30 rounded-3xl p-6 flex flex-col gap-5 max-h-[90vh] overflow-y-auto">
            <div className="flex items-center justify-between border-b border-white/10 pb-4">
              <h3 className="text-lg font-black text-white">
                {editingId ? "Edit Notice" : "Create New Notice"}
              </h3>
              <button type="button" onClick={() => setIsModalOpen(false)} className="text-gray-400 hover:text-white cursor-pointer">
                <X className="w-5 h-5" />
              </button>
            </div>

            <form onSubmit={handleSaveNotice} className="flex flex-col gap-4 text-xs">
              {/* Bangla Fields */}
              <div className="p-4 rounded-2xl bg-[#1a1d2d] border border-amber-500/20 flex flex-col gap-3">
                <div className="flex items-center gap-2 text-amber-400 font-bold">
                  <Globe className="w-4 h-4" />
                  <span>Bangla Version (Shows for Bangladesh Visitors)</span>
                </div>
                <div className="flex flex-col gap-1.5">
                  <label className="font-bold text-gray-300">Bangla Title</label>
                  <input
                    type="text"
                    value={titleBn}
                    onChange={(e) => setTitleBn(e.target.value)}
                    className="px-3.5 py-2.5 bg-[#0b0c10] border border-white/10 rounded-xl text-white font-sans"
                    placeholder="📢 নোটিশ"
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <label className="font-bold text-gray-300">Bangla Content</label>
                  <textarea
                    rows={4}
                    value={contentBn}
                    onChange={(e) => setContentBn(e.target.value)}
                    className="px-3.5 py-2.5 bg-[#0b0c10] border border-white/10 rounded-xl text-white font-sans leading-relaxed"
                    placeholder="আমাদের আপলোডাররা যত দ্রুত সম্ভব..."
                  />
                </div>
              </div>

              {/* English Fields */}
              <div className="p-4 rounded-2xl bg-[#1a1d2d] border border-blue-500/20 flex flex-col gap-3">
                <div className="flex items-center gap-2 text-blue-400 font-bold">
                  <Globe className="w-4 h-4" />
                  <span>English Version (Shows for International Visitors)</span>
                </div>
                <div className="flex flex-col gap-1.5">
                  <label className="font-bold text-gray-300">English Title</label>
                  <input
                    type="text"
                    value={titleEn}
                    onChange={(e) => setTitleEn(e.target.value)}
                    className="px-3.5 py-2.5 bg-[#0b0c10] border border-white/10 rounded-xl text-white"
                    placeholder="📢 Notice"
                  />
                </div>
                <div className="flex flex-col gap-1.5">
                  <label className="font-bold text-gray-300">English Content</label>
                  <textarea
                    rows={4}
                    value={contentEn}
                    onChange={(e) => setContentEn(e.target.value)}
                    className="px-3.5 py-2.5 bg-[#0b0c10] border border-white/10 rounded-xl text-white leading-relaxed"
                    placeholder="Our uploaders work hard..."
                  />
                </div>
              </div>

              {/* Config Grid */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div className="flex flex-col gap-1.5">
                  <label className="font-bold text-gray-300">Display Type</label>
                  <select
                    value={displayType}
                    onChange={(e) => setDisplayType(e.target.value as any)}
                    className="px-3.5 py-2.5 bg-[#1a1d2d] border border-white/10 rounded-xl text-white"
                  >
                    <option value="popup">Popup Modal</option>
                    <option value="banner">Top Banner</option>
                    <option value="both">Both</option>
                  </select>
                </div>

                <div className="flex flex-col gap-1.5">
                  <label className="font-bold text-gray-300">Display Frequency</label>
                  <select
                    value={frequency}
                    onChange={(e) => setFrequency(e.target.value as any)}
                    className="px-3.5 py-2.5 bg-[#1a1d2d] border border-white/10 rounded-xl text-white"
                  >
                    <option value="show_once_per_day">Show Once Per Day</option>
                    <option value="show_once">Show Only Once</option>
                    <option value="show_every_visit">Show Every Visit</option>
                  </select>
                </div>

                <div className="flex flex-col gap-1.5">
                  <label className="font-bold text-gray-300">Priority</label>
                  <select
                    value={priority}
                    onChange={(e) => setPriority(e.target.value as any)}
                    className="px-3.5 py-2.5 bg-[#1a1d2d] border border-white/10 rounded-xl text-white"
                  >
                    <option value="high">High Priority</option>
                    <option value="normal">Normal Priority</option>
                    <option value="low">Low Priority</option>
                  </select>
                </div>

                <div className="flex flex-col gap-1.5">
                  <label className="font-bold text-gray-300">Status</label>
                  <select
                    value={status}
                    onChange={(e) => setStatus(e.target.value as any)}
                    className="px-3.5 py-2.5 bg-[#1a1d2d] border border-white/10 rounded-xl text-white"
                  >
                    <option value="active">Active</option>
                    <option value="draft">Draft</option>
                    <option value="archived">Archived</option>
                  </select>
                </div>
              </div>

              <div className="flex items-center gap-3 p-3 bg-[#1a1d2d] rounded-xl border border-white/10">
                <input
                  type="checkbox"
                  id="isPinned"
                  checked={isPinned}
                  onChange={(e) => setIsPinned(e.target.checked)}
                  className="w-4 h-4 accent-amber-500 rounded cursor-pointer"
                />
                <label htmlFor="isPinned" className="font-bold text-white cursor-pointer">
                  Pin Notice (Feature prominently at top of notice queue)
                </label>
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
                  className="px-6 py-2.5 rounded-xl bg-amber-500 hover:bg-amber-400 text-black font-extrabold cursor-pointer shadow-lg"
                >
                  Save Notice
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* PREVIEW MODAL */}
      {previewNotice && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md">
          <div className="w-full max-w-lg bg-[#12141d] border border-amber-500/30 rounded-3xl p-6 flex flex-col gap-4 text-white">
            <div className="flex items-center justify-between border-b border-white/10 pb-3">
              <span className="text-xs font-mono text-amber-400 font-bold uppercase">Admin Notice Preview</span>
              <button type="button" onClick={() => setPreviewNotice(null)} className="text-gray-400 hover:text-white cursor-pointer">
                <X className="w-5 h-5" />
              </button>
            </div>

            <div className="flex flex-col gap-3">
              <div className="p-3 bg-amber-500/10 rounded-xl border border-amber-500/20">
                <h4 className="font-black text-amber-400">{previewNotice.titleBn}</h4>
                <p className="text-xs text-gray-200 whitespace-pre-line mt-2">{previewNotice.contentBn}</p>
              </div>

              <div className="p-3 bg-blue-500/10 rounded-xl border border-blue-500/20">
                <h4 className="font-black text-blue-400">{previewNotice.titleEn}</h4>
                <p className="text-xs text-gray-200 whitespace-pre-line mt-2">{previewNotice.contentEn}</p>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
