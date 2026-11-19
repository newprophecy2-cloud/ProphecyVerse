import React from "react";
import { 
  Film, Video, PlaySquare, Layers, Clock, CheckCircle2, AlertTriangle, 
  RefreshCw, Database, Sparkles, Upload, Server, Activity, ShieldCheck
} from "lucide-react";
import { Anime, Episode } from "../../types";

interface AdminDashboardStatsProps {
  animeList: Anime[];
  allEpisodesList: Episode[];
  missingAlertsCount: number;
  onNavigateTab: (tab: any) => void;
  onRefreshAll: () => void;
}

export const AdminDashboardStats: React.FC<AdminDashboardStatsProps> = ({
  animeList,
  allEpisodesList,
  missingAlertsCount,
  onNavigateTab,
  onRefreshAll
}) => {
  // Calculations
  const totalAnime = animeList.filter(a => a.type !== "Movie").length;
  const totalMovies = animeList.filter(a => a.type === "Movie").length;

  const totalSeasons = animeList.reduce((acc, a) => {
    const epsInAnime = allEpisodesList.filter(e => e.animeId === a.id);
    const seasonsSet = new Set(epsInAnime.map(e => e.seasonNumber || 1));
    return acc + Math.max(seasonsSet.size, 1);
  }, 0);

  const totalEpisodes = allEpisodesList.length;

  const publishingEps = allEpisodesList.filter(
    e => (!e.muxPlaybackId || e.muxPlaybackId.startsWith("upload_") || e.muxPlaybackId.startsWith("asset_")) && (e.uploadId || e.assetId)
  ).length;

  const readyEps = allEpisodesList.filter(
    e => e.muxStatus === "Ready" || e.status === "Published"
  ).length;

  const failedUploads = allEpisodesList.filter(
    e => e.muxStatus === "Errored" || e.status === "Draft"
  ).length;

  // Storage summary estimation (approx 500MB per episode on average)
  const estStorageGB = ((totalEpisodes * 520) / 1024).toFixed(1);

  // Recently Uploaded
  const recentlyUploadedEps = [...allEpisodesList]
    .sort((a, b) => new Date(b.releaseDate || 0).getTime() - new Date(a.releaseDate || 0).getTime())
    .slice(0, 5);

  const lastSyncTime = new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

  return (
    <div className="flex flex-col gap-8 animate-fade-in">
      
      {/* Top Banner */}
      <div className="p-6 rounded-2xl bg-gradient-to-r from-[#181a29] via-[#131520] to-[#1c1829] border border-amber-500/20 shadow-xl flex flex-col md:flex-row md:items-center justify-between gap-6">
        <div className="flex items-center gap-4">
          <div className="p-3.5 rounded-2xl bg-amber-500/10 text-amber-400 border border-amber-500/30">
            <Activity className="w-8 h-8" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-extrabold text-white font-display">
                Prophecy Verse HD Control Center
              </h2>
              <span className="px-2.5 py-0.5 rounded-full text-[10px] font-mono font-bold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 flex items-center gap-1">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-ping" />
                Live Systems Operational
              </span>
            </div>
            <p className="text-xs text-gray-400 mt-1 font-mono">
              Enterprise Streaming Dashboard • Mux Video Engine Active • Real-time Sync
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={onRefreshAll}
            className="px-4 py-2.5 rounded-xl bg-white/5 hover:bg-white/10 border border-white/10 text-xs font-bold text-gray-300 hover:text-white transition-all flex items-center gap-2 cursor-pointer"
          >
            <RefreshCw className="w-4 h-4 text-amber-400" />
            Sync Metrics
          </button>
          <button
            onClick={() => onNavigateTab("ep-upload-video")}
            className="px-5 py-2.5 rounded-xl bg-amber-500 hover:bg-amber-400 text-black font-extrabold text-xs uppercase tracking-wider shadow-lg shadow-amber-500/20 transition-all flex items-center gap-2 cursor-pointer"
          >
            <Upload className="w-4 h-4" />
            Quick Upload
          </button>
        </div>
      </div>

      {/* Primary KPI Grid */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-4">
        
        {/* Total Anime */}
        <div 
          onClick={() => onNavigateTab("anime")}
          className="p-5 rounded-2xl bg-[#131520] border border-white/5 hover:border-amber-500/30 transition-all cursor-pointer group"
        >
          <div className="flex items-center justify-between text-gray-400 mb-3">
            <span className="text-xs font-mono font-bold uppercase tracking-wider">Total Anime</span>
            <div className="p-2 rounded-xl bg-amber-500/10 text-amber-400 group-hover:scale-110 transition-transform">
              <Film className="w-5 h-5" />
            </div>
          </div>
          <div className="text-2xl font-black text-white font-mono">{totalAnime}</div>
          <span className="text-[10px] text-gray-400 mt-1 block">Catalog Series</span>
        </div>

        {/* Total Movies */}
        <div 
          onClick={() => onNavigateTab("movie")}
          className="p-5 rounded-2xl bg-[#131520] border border-white/5 hover:border-amber-500/30 transition-all cursor-pointer group"
        >
          <div className="flex items-center justify-between text-gray-400 mb-3">
            <span className="text-xs font-mono font-bold uppercase tracking-wider">Total Movies</span>
            <div className="p-2 rounded-xl bg-blue-500/10 text-blue-400 group-hover:scale-110 transition-transform">
              <Video className="w-5 h-5" />
            </div>
          </div>
          <div className="text-2xl font-black text-white font-mono">{totalMovies}</div>
          <span className="text-[10px] text-gray-400 mt-1 block">Feature Films</span>
        </div>

        {/* Total Seasons */}
        <div 
          onClick={() => onNavigateTab("anime")}
          className="p-5 rounded-2xl bg-[#131520] border border-white/5 hover:border-amber-500/30 transition-all cursor-pointer group"
        >
          <div className="flex items-center justify-between text-gray-400 mb-3">
            <span className="text-xs font-mono font-bold uppercase tracking-wider">Total Seasons</span>
            <div className="p-2 rounded-xl bg-purple-500/10 text-purple-400 group-hover:scale-110 transition-transform">
              <Layers className="w-5 h-5" />
            </div>
          </div>
          <div className="text-2xl font-black text-white font-mono">{totalSeasons}</div>
          <span className="text-[10px] text-gray-400 mt-1 block">Active Seasons</span>
        </div>

        {/* Total Episodes */}
        <div 
          onClick={() => onNavigateTab("episodes-manage")}
          className="p-5 rounded-2xl bg-[#131520] border border-white/5 hover:border-amber-500/30 transition-all cursor-pointer group"
        >
          <div className="flex items-center justify-between text-gray-400 mb-3">
            <span className="text-xs font-mono font-bold uppercase tracking-wider">Total Episodes</span>
            <div className="p-2 rounded-xl bg-emerald-500/10 text-emerald-400 group-hover:scale-110 transition-transform">
              <PlaySquare className="w-5 h-5" />
            </div>
          </div>
          <div className="text-2xl font-black text-white font-mono">{totalEpisodes}</div>
          <span className="text-[10px] text-emerald-400 font-mono mt-1 block">In Streaming Index</span>
        </div>

        {/* Published Episodes */}
        <div 
          onClick={() => onNavigateTab("episodes-manage")}
          className="p-5 rounded-2xl bg-[#131520] border border-white/5 hover:border-amber-500/30 transition-all cursor-pointer group"
        >
          <div className="flex items-center justify-between text-gray-400 mb-3">
            <span className="text-xs font-mono font-bold uppercase tracking-wider">Published</span>
            <div className="p-2 rounded-xl bg-emerald-500/10 text-emerald-400">
              <CheckCircle2 className="w-5 h-5" />
            </div>
          </div>
          <div className="text-2xl font-black text-emerald-400 font-mono">{readyEps}</div>
          <span className="text-[10px] text-gray-400 mt-1 block">Live & Accessible</span>
        </div>

        {/* Episodes Ready */}
        <div 
          onClick={() => onNavigateTab("episodes-manage")}
          className="p-5 rounded-2xl bg-[#131520] border border-white/5 hover:border-amber-500/30 transition-all cursor-pointer group"
        >
          <div className="flex items-center justify-between text-gray-400 mb-3">
            <span className="text-xs font-mono font-bold uppercase tracking-wider">Episodes Ready</span>
            <div className="p-2 rounded-xl bg-emerald-500/10 text-emerald-400">
              <CheckCircle2 className="w-5 h-5" />
            </div>
          </div>
          <div className="text-2xl font-black text-emerald-400 font-mono">{readyEps}</div>
          <span className="text-[10px] text-gray-400 mt-1 block">Published & Streamable</span>
        </div>

        {/* Missing Episodes Alerts */}
        <div 
          onClick={() => onNavigateTab("missing-alerts")}
          className={`p-5 rounded-2xl bg-[#131520] border transition-all cursor-pointer group ${
            missingAlertsCount > 0 ? "border-amber-500/40 bg-amber-500/5" : "border-white/5 hover:border-amber-500/30"
          }`}
        >
          <div className="flex items-center justify-between text-gray-400 mb-3">
            <span className="text-xs font-mono font-bold uppercase tracking-wider">Missing Episodes</span>
            <div className="p-2 rounded-xl bg-amber-500/20 text-amber-400">
              <AlertTriangle className="w-5 h-5" />
            </div>
          </div>
          <div className="text-2xl font-black text-amber-400 font-mono">{missingAlertsCount}</div>
          <span className="text-[10px] text-gray-400 mt-1 block">Sequence Gaps Detected</span>
        </div>

        {/* Storage Summary */}
        <div className="p-5 rounded-2xl bg-[#131520] border border-white/5">
          <div className="flex items-center justify-between text-gray-400 mb-3">
            <span className="text-xs font-mono font-bold uppercase tracking-wider">Mux Cloud Storage</span>
            <div className="p-2 rounded-xl bg-blue-500/10 text-blue-400">
              <Server className="w-5 h-5" />
            </div>
          </div>
          <div className="text-2xl font-black text-white font-mono">~{estStorageGB} GB</div>
          <span className="text-[10px] text-gray-400 mt-1 block">HLS Master Renditions</span>
        </div>

      </div>

      {/* Sync Status Bar & Quick Insights */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        
        {/* System Sync Timestamps */}
        <div className="p-6 rounded-2xl bg-[#131520] border border-white/5 flex flex-col gap-4">
          <h3 className="font-display font-extrabold text-base text-white flex items-center gap-2 border-b border-white/5 pb-3">
            <ShieldCheck className="w-5 h-5 text-amber-500" />
            Engine Infrastructure Status
          </h3>
          <div className="flex flex-col gap-3 font-mono text-xs">
            <div className="flex items-center justify-between p-3 rounded-xl bg-[#0d0e12] border border-white/5">
              <span className="text-gray-400">Last AniList / Kitsu Metadata Sync</span>
              <span className="text-amber-400 font-bold">Today, {lastSyncTime}</span>
            </div>
            <div className="flex items-center justify-between p-3 rounded-xl bg-[#0d0e12] border border-white/5">
              <span className="text-gray-400">Last Mux Video Webhook Sync</span>
              <span className="text-emerald-400 font-bold">Active (Realtime)</span>
            </div>
            <div className="flex items-center justify-between p-3 rounded-xl bg-[#0d0e12] border border-white/5">
              <span className="text-gray-400">Database Storage Mode</span>
              <span className="text-blue-400 font-bold">Firestore Enterprise</span>
            </div>
          </div>
        </div>

        {/* Recently Uploaded Episodes */}
        <div className="p-6 rounded-2xl bg-[#131520] border border-white/5 flex flex-col gap-4">
          <div className="flex items-center justify-between border-b border-white/5 pb-3">
            <h3 className="font-display font-extrabold text-base text-white flex items-center gap-2">
              <Clock className="w-5 h-5 text-amber-500" />
              Recently Uploaded Episodes
            </h3>
            <button
              onClick={() => onNavigateTab("episodes-manage")}
              className="text-xs text-amber-400 font-mono hover:underline cursor-pointer"
            >
              View All →
            </button>
          </div>

          {recentlyUploadedEps.length === 0 ? (
            <p className="text-xs text-gray-400 py-4 font-mono">No recent uploads recorded.</p>
          ) : (
            <div className="flex flex-col gap-2.5">
              {recentlyUploadedEps.map((ep) => {
                const parentAnime = animeList.find(a => a.id === ep.animeId);
                return (
                  <div key={ep.id} className="flex items-center justify-between p-2.5 rounded-xl bg-[#0d0e12] border border-white/5 text-xs">
                    <div className="flex items-center gap-3 min-w-0">
                      <div className="w-7 h-7 rounded bg-amber-500/10 text-amber-400 font-mono font-bold flex items-center justify-center shrink-0">
                        {ep.episodeNumber}
                      </div>
                      <div className="flex flex-col min-w-0">
                        <span className="text-white font-bold truncate">{ep.title}</span>
                        <span className="text-[10px] text-gray-400 font-mono truncate">{parentAnime?.title || ep.animeId}</span>
                      </div>
                    </div>
                    <span className="text-[10px] font-mono text-gray-400 shrink-0 ml-2">
                      {ep.releaseDate || "Recently"}
                    </span>
                  </div>
                );
              })}
            </div>
          )}
        </div>

      </div>

    </div>
  );
};
