import React, { useEffect, useState } from "react";
import { Sparkles, Film, Shield, Zap } from "lucide-react";

interface ThreeDLoadingScreenProps {
  onComplete?: () => void;
  message?: string;
  minDurationMs?: number;
}

export const ThreeDLoadingScreen: React.FC<ThreeDLoadingScreenProps> = ({
  onComplete,
  message = "Initializing Prophecy Verse Engine...",
  minDurationMs = 2000,
}) => {
  const [progress, setProgress] = useState(0);
  const [statusText, setStatusText] = useState("Booting 3D Stream Engine...");
  const [isFadingOut, setIsFadingOut] = useState(false);

  useEffect(() => {
    const statuses = [
      "Booting 3D Stream Engine...",
      "Connecting to Cloudflare R2 High-Speed CDN...",
      "Syncing Prophecy Media Repositories...",
      "Securing High-Definition Audio Tracks...",
      "Prophecy Verse Ready!"
    ];

    const startTime = Date.now();
    const interval = setInterval(() => {
      const elapsed = Date.now() - startTime;
      const pct = Math.min(100, Math.floor((elapsed / minDurationMs) * 100));
      setProgress(pct);

      if (pct < 25) setStatusText(statuses[0]);
      else if (pct < 50) setStatusText(statuses[1]);
      else if (pct < 75) setStatusText(statuses[2]);
      else if (pct < 95) setStatusText(statuses[3]);
      else setStatusText(statuses[4]);

      if (pct >= 100) {
        clearInterval(interval);
        setIsFadingOut(true);
        setTimeout(() => {
          if (onComplete) onComplete();
        }, 400);
      }
    }, 40);

    return () => clearInterval(interval);
  }, [minDurationMs, onComplete]);

  return (
    <div
      className={`fixed inset-0 z-[9999] flex flex-col items-center justify-center bg-[#07080c] overflow-hidden transition-opacity duration-500 ${
        isFadingOut ? "opacity-0 pointer-events-none" : "opacity-100"
      }`}
    >
      {/* Background Starfield / Particle Matrix Grid */}
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_center,rgba(244,63,94,0.12)_0%,transparent_70%)] pointer-events-none" />
      <div 
        className="absolute inset-0 opacity-20 pointer-events-none"
        style={{
          backgroundImage: `radial-gradient(rgba(255, 255, 255, 0.15) 1px, transparent 1px)`,
          backgroundSize: "32px 32px"
        }}
      />

      {/* Ambient Floating Glow Circles */}
      <div className="absolute top-1/4 left-1/3 w-72 h-72 bg-rose-500/20 rounded-full blur-[120px] animate-pulse" />
      <div className="absolute bottom-1/4 right-1/3 w-72 h-72 bg-amber-500/20 rounded-full blur-[120px] animate-pulse delay-700" />

      <div className="relative z-10 flex flex-col items-center gap-8 max-w-md px-6 text-center">
        
        {/* 3D CUBE & RINGS CONTAINER */}
        <div className="relative w-44 h-44 flex items-center justify-center [perspective:1000px]">
          
          {/* Outer Glowing Rotating Ring 1 */}
          <div 
            className="absolute inset-0 rounded-full border-2 border-dashed border-rose-500/40 animate-[spin_10s_linear_infinite]"
            style={{ transformStyle: "preserve-3d" }}
          />
          
          {/* Outer Glowing Rotating Ring 2 (Opposite direction) */}
          <div 
            className="absolute inset-2 rounded-full border-2 border-amber-500/40 animate-[spin_7s_linear_infinite_reverse]"
            style={{ transformStyle: "preserve-3d" }}
          />

          {/* 3D ROTATING GLASS CUBE */}
          <div className="relative w-20 h-20 [transform-style:preserve-3d] animate-[spin3d_6s_linear_infinite]">
            
            {/* Front */}
            <div className="absolute inset-0 bg-rose-500/10 border border-rose-500/60 backdrop-blur-md flex items-center justify-center rounded-xl [transform:translateZ(40px)] shadow-[0_0_15px_rgba(244,63,94,0.3)]">
              <Film className="w-8 h-8 text-rose-400" />
            </div>
            
            {/* Back */}
            <div className="absolute inset-0 bg-amber-500/10 border border-amber-500/60 backdrop-blur-md flex items-center justify-center rounded-xl [transform:rotateY(180deg)_translateZ(40px)] shadow-[0_0_15px_rgba(245,158,11,0.3)]">
              <Sparkles className="w-8 h-8 text-amber-400" />
            </div>

            {/* Right */}
            <div className="absolute inset-0 bg-rose-500/10 border border-rose-500/60 backdrop-blur-md flex items-center justify-center rounded-xl [transform:rotateY(90deg)_translateZ(40px)]">
              <Zap className="w-8 h-8 text-rose-300" />
            </div>

            {/* Left */}
            <div className="absolute inset-0 bg-amber-500/10 border border-amber-500/60 backdrop-blur-md flex items-center justify-center rounded-xl [transform:rotateY(-90deg)_translateZ(40px)]">
              <Shield className="w-8 h-8 text-amber-300" />
            </div>

            {/* Top */}
            <div className="absolute inset-0 bg-rose-600/20 border border-rose-400 flex items-center justify-center rounded-xl [transform:rotateX(90deg)_translateZ(40px)]" />

            {/* Bottom */}
            <div className="absolute inset-0 bg-amber-600/20 border border-amber-400 flex items-center justify-center rounded-xl [transform:rotateX(-90deg)_translateZ(40px)]" />

          </div>

          {/* Core Pulsing Glow Sphere */}
          <div className="absolute w-12 h-12 rounded-full bg-gradient-to-tr from-rose-500 to-amber-400 blur-md animate-ping opacity-75" />
        </div>

        {/* LOGO & TITLE */}
        <div className="flex flex-col items-center gap-1">
          <h1 className="font-display font-black text-3xl md:text-4xl tracking-wider text-transparent bg-clip-text bg-gradient-to-r from-white via-rose-300 to-amber-300 drop-shadow-[0_0_20px_rgba(244,63,94,0.4)]">
            PROPHECY VERSE
          </h1>
          <p className="text-xs font-mono font-bold tracking-[0.3em] uppercase text-rose-400/90">
            {message}
          </p>
        </div>

        {/* 3D PROGRESS BAR CONTAINER */}
        <div className="w-full flex flex-col gap-2">
          <div className="w-full h-3 bg-[#131520] border border-white/10 rounded-full overflow-hidden p-0.5 shadow-inner relative">
            {/* Shimmer Bar */}
            <div
              className="h-full bg-gradient-to-r from-rose-500 via-amber-400 to-rose-400 rounded-full transition-all duration-100 ease-out shadow-[0_0_12px_rgba(244,63,94,0.8)] relative"
              style={{ width: `${progress}%` }}
            >
              <div className="absolute inset-0 bg-[linear-gradient(90deg,transparent,rgba(255,255,255,0.4),transparent)] animate-shimmer" />
            </div>
          </div>

          <div className="flex items-center justify-between text-[11px] font-mono text-gray-400 font-medium px-1">
            <span className="flex items-center gap-1.5 text-amber-400">
              <span className="w-1.5 h-1.5 rounded-full bg-amber-400 animate-ping" />
              {statusText}
            </span>
            <span className="font-black text-white font-mono">{progress}%</span>
          </div>
        </div>

      </div>

      {/* CUSTOM 3D KEYFRAME STYLES */}
      <style>{`
        @keyframes spin3d {
          0% {
            transform: rotateX(0deg) rotateY(0deg) rotateZ(0deg);
          }
          50% {
            transform: rotateX(180deg) rotateY(180deg) rotateZ(90deg);
          }
          100% {
            transform: rotateX(360deg) rotateY(360deg) rotateZ(180deg);
          }
        }
      `}</style>
    </div>
  );
};
