import React, { useEffect, useState } from "react";
import { Notice } from "../types";
import { subscribeToNotices, seedNoticesIfEmpty } from "../services/noticeService";
import { detectIsBangladeshUser } from "../utils/geoUtils";
import { Bell, X, Calendar, Check, AlertCircle } from "lucide-react";

export const NoticePopupModal: React.FC = () => {
  const [notices, setNotices] = useState<Notice[]>([]);
  const [activeNotice, setActiveNotice] = useState<Notice | null>(null);
  const [isBangladesh, setIsBangladesh] = useState<boolean>(false);
  const [isOpen, setIsOpen] = useState<boolean>(false);
  const [isFullscreen, setIsFullscreen] = useState<boolean>(false);

  // Monitor Fullscreen state (don't overlay during full screen video play)
  useEffect(() => {
    const handleFullscreenChange = () => {
      const isFs = !!(
        document.fullscreenElement ||
        (document as any).webkitFullscreenElement ||
        (document as any).mozFullScreenElement
      );
      setIsFullscreen(isFs);
    };

    document.addEventListener("fullscreenchange", handleFullscreenChange);
    document.addEventListener("webkitfullscreenchange", handleFullscreenChange);
    return () => {
      document.removeEventListener("fullscreenchange", handleFullscreenChange);
      document.removeEventListener("webkitfullscreenchange", handleFullscreenChange);
    };
  }, []);

  // Detect Country & Subscribe to Notices
  useEffect(() => {
    seedNoticesIfEmpty();

    let isMounted = true;
    detectIsBangladeshUser().then((isBD) => {
      if (isMounted) {
        setIsBangladesh(isBD);
      }
    });

    const unsub = subscribeToNotices((noticeList) => {
      if (isMounted) {
        setNotices(noticeList);
      }
    });

    return () => {
      isMounted = false;
      unsub();
    };
  }, []);

  // Evaluate Active Notice
  useEffect(() => {
    if (isFullscreen || notices.length === 0) return;

    const todayStr = new Date().toISOString().split("T")[0];

    // Filter active popup notices matching schedule
    const eligibleNotices = notices.filter((n) => {
      if (n.status !== "active") return false;
      if (n.displayType !== "popup" && n.displayType !== "both") return false;

      // Schedule check if dates are defined
      if (n.scheduleStart && new Date(n.scheduleStart).getTime() > Date.now()) return false;
      if (n.scheduleEnd && new Date(n.scheduleEnd).getTime() < Date.now()) return false;

      // Frequency Checks
      if (n.frequency === "show_once") {
        if (localStorage.getItem(`animestream_notice_once_${n.id}`)) {
          return false;
        }
      } else if (n.frequency === "show_once_per_day") {
        if (localStorage.getItem(`animestream_notice_day_${n.id}_${todayStr}`)) {
          return false;
        }
      }

      return true;
    });

    if (eligibleNotices.length === 0) return;

    // Prioritize pinned notice, then highest priority
    const selected = eligibleNotices.find((n) => n.isPinned) || eligibleNotices[0];

    setActiveNotice(selected);
    setIsOpen(true);
  }, [notices, isFullscreen]);

  if (!isOpen || !activeNotice || isFullscreen) return null;

  const handleClose = () => {
    setIsOpen(false);
  };

  const handleDontShowToday = () => {
    if (activeNotice) {
      const todayStr = new Date().toISOString().split("T")[0];
      localStorage.setItem(`animestream_notice_day_${activeNotice.id}_${todayStr}`, "true");
      localStorage.setItem(`animestream_notice_once_${activeNotice.id}`, "true");
    }
    setIsOpen(false);
  };

  // Determine Title and Content strictly by country detection
  const title = isBangladesh ? (activeNotice.titleBn || activeNotice.titleEn) : (activeNotice.titleEn || activeNotice.titleBn);
  const content = isBangladesh ? (activeNotice.contentBn || activeNotice.contentEn) : (activeNotice.contentEn || activeNotice.contentBn);

  const dontShowLabel = isBangladesh ? "আজ আর দেখাবেন না" : "Don't Show Again Today";
  const closeLabel = isBangladesh ? "বন্ধ করুন" : "Close";

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6 bg-black/75 backdrop-blur-md transition-all animate-fade-in">
      <div className="relative w-full max-w-lg bg-[#12141d]/95 text-white border border-amber-500/30 rounded-3xl overflow-hidden shadow-2xl animate-scale-up">
        {/* Header Bar */}
        <div className="flex items-center justify-between px-6 py-4 bg-[#181a26] border-b border-white/10">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-xl bg-amber-500/20 text-amber-400 border border-amber-500/30">
              <Bell className="w-4 h-4 animate-bounce" />
            </div>
            <span className="text-sm font-extrabold text-white tracking-wide">
              {isBangladesh ? "অফিসিয়াল নোটিশ" : "Official Notice"}
            </span>
          </div>

          <button
            type="button"
            onClick={handleClose}
            className="p-1.5 rounded-full text-gray-400 hover:text-white hover:bg-white/10 transition-all cursor-pointer"
            title={closeLabel}
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Content Body */}
        <div className="p-6 sm:p-7 flex flex-col gap-4 text-left">
          <h3 className="text-lg sm:text-xl font-black text-amber-400 leading-snug flex items-center gap-2">
            <span>{title}</span>
          </h3>

          <div className="text-sm text-gray-200 leading-relaxed whitespace-pre-line bg-white/5 p-4 rounded-2xl border border-white/5 font-sans">
            {content}
          </div>

          {/* Action Buttons */}
          <div className="flex flex-col sm:flex-row items-center gap-3 mt-2">
            <button
              type="button"
              onClick={handleDontShowToday}
              className="w-full sm:flex-1 py-3 px-4 rounded-xl text-xs font-black bg-amber-500 hover:bg-amber-400 text-black transition-all cursor-pointer shadow-lg flex items-center justify-center gap-2 transform active:scale-95"
            >
              <Check className="w-4 h-4" />
              <span>{dontShowLabel}</span>
            </button>

            <button
              type="button"
              onClick={handleClose}
              className="w-full sm:w-auto px-5 py-3 rounded-xl text-xs font-bold text-gray-300 hover:text-white bg-white/10 hover:bg-white/15 transition-all cursor-pointer"
            >
              {closeLabel}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
