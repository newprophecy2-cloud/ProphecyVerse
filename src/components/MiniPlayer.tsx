import React from "react";
import { usePlayer } from "../context/PlayerContext";
import { VideoPlayer } from "./VideoPlayer";
import { Maximize2, X, Film, Play, Pause } from "lucide-react";
import { getDisplayTitle } from "../utils/animeUtils";

interface MiniPlayerProps {
  setView: (view: string, detailsId?: string) => void;
}

export const MiniPlayer: React.FC<MiniPlayerProps> = ({ setView }) => {
  const { 
    activeAnime, 
    activeEpisode, 
    playerMode, 
    currentTime, 
    setCurrentTime, 
    setDuration, 
    expandPlayer, 
    closePlayer 
  } = usePlayer();

  if (playerMode !== "mini" || !activeEpisode || !activeAnime) {
    return null;
  }

  const handleExpand = () => {
    expandPlayer();
    setView("watch", activeAnime.id);
  };

  const animeTitle = getDisplayTitle(activeAnime);

  return (
    <div className="fixed bottom-20 right-4 sm:right-6 md:bottom-6 md:right-8 z-50 w-[300px] sm:w-[340px] md:w-[380px] bg-[#0c0d12]/95 border border-white/20 rounded-2xl shadow-2xl shadow-black/80 overflow-hidden backdrop-blur-xl group transition-all duration-300 animate-in fade-in slide-in-from-bottom-5 select-none">
      
      {/* Mini Player Header Bar */}
      <div className="flex items-center justify-between px-3 py-2 bg-gradient-to-r from-neutral-900/90 via-black/80 to-neutral-900/90 border-b border-white/10">
        <div 
          onClick={handleExpand}
          className="flex items-center gap-2 cursor-pointer truncate max-w-[70%]"
        >
          <div className="w-5 h-5 rounded bg-brand-primary/20 border border-brand-primary/30 flex items-center justify-center shrink-0">
            <Film className="w-3 h-3 text-brand-primary" />
          </div>
          <div className="flex flex-col truncate">
            <span className="text-xs font-bold text-white truncate hover:text-brand-primary transition-colors">
              {animeTitle}
            </span>
            <span className="text-[10px] font-mono text-gray-400">
              EP {activeEpisode.episodeNumber}: {activeEpisode.title}
            </span>
          </div>
        </div>

        <div className="flex items-center gap-1">
          <button
            onClick={handleExpand}
            className="p-1.5 rounded-lg text-gray-300 hover:text-amber-400 hover:bg-white/10 transition-all cursor-pointer"
            title="Expand to Watch Page"
          >
            <Maximize2 className="w-4 h-4" />
          </button>
          <button
            onClick={closePlayer}
            className="p-1.5 rounded-lg text-gray-400 hover:text-red-400 hover:bg-white/10 transition-all cursor-pointer"
            title="Close Mini Player"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Mini Player Video Stream Container */}
      <div className="relative aspect-video w-full bg-black">
        <VideoPlayer
          episode={activeEpisode}
          initialPosition={currentTime}
          isMini={true}
          onExpand={handleExpand}
          onClose={closePlayer}
          onProgressUpdate={(secs, dur) => {
            setCurrentTime(secs);
            setDuration(dur);
          }}
        />
      </div>

      {/* Footer Quick Return Action */}
      <div 
        onClick={handleExpand}
        className="px-3 py-1.5 bg-white/5 hover:bg-white/10 border-t border-white/5 flex items-center justify-between cursor-pointer transition-colors"
      >
        <span className="text-[10px] font-mono font-bold text-brand-primary uppercase tracking-wider">
          Currently Playing • Tap to Expand
        </span>
        <Maximize2 className="w-3 h-3 text-brand-primary" />
      </div>

    </div>
  );
};
