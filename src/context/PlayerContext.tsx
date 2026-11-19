import React, { createContext, useContext, useState } from "react";
import { Episode, Anime } from "../types";

export type PlayerMode = "full" | "mini" | "hidden";

interface PlayerContextType {
  activeAnime: Anime | null;
  activeEpisode: Episode | null;
  playerMode: PlayerMode;
  currentTime: number;
  duration: number;
  isPlaying: boolean;
  startEpisode: (anime: Anime, episode: Episode, initialPosition?: number) => void;
  minimizePlayer: () => void;
  expandPlayer: () => void;
  closePlayer: () => void;
  setIsPlaying: (playing: boolean) => void;
  setCurrentTime: (time: number) => void;
  setDuration: (dur: number) => void;
  setActiveAnime: (anime: Anime | null) => void;
  setActiveEpisode: (episode: Episode | null) => void;
  setPlayerMode: (mode: PlayerMode) => void;
}

const PlayerContext = createContext<PlayerContextType | undefined>(undefined);

export const PlayerProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [activeAnime, setActiveAnime] = useState<Anime | null>(null);
  const [activeEpisode, setActiveEpisode] = useState<Episode | null>(null);
  const [playerMode, setPlayerMode] = useState<PlayerMode>("hidden");
  const [currentTime, setCurrentTime] = useState<number>(0);
  const [duration, setDuration] = useState<number>(0);
  const [isPlaying, setIsPlaying] = useState<boolean>(false);

  const startEpisode = (anime: Anime, episode: Episode, initialPosition: number = 0) => {
    setActiveAnime(anime);
    setActiveEpisode(episode);
    setCurrentTime(initialPosition);
    setPlayerMode("full");
    setIsPlaying(true);
  };

  const minimizePlayer = () => {
    if (activeEpisode) {
      setPlayerMode("mini");
    }
  };

  const expandPlayer = () => {
    if (activeEpisode) {
      setPlayerMode("full");
    }
  };

  const closePlayer = () => {
    setPlayerMode("hidden");
    setActiveAnime(null);
    setActiveEpisode(null);
    setIsPlaying(false);
    setCurrentTime(0);
  };

  return (
    <PlayerContext.Provider
      value={{
        activeAnime,
        activeEpisode,
        playerMode,
        currentTime,
        duration,
        isPlaying,
        startEpisode,
        minimizePlayer,
        expandPlayer,
        closePlayer,
        setIsPlaying,
        setCurrentTime,
        setDuration,
        setActiveAnime,
        setActiveEpisode,
        setPlayerMode,
      }}
    >
      {children}
    </PlayerContext.Provider>
  );
};

export const usePlayer = () => {
  const context = useContext(PlayerContext);
  if (!context) {
    throw new Error("usePlayer must be used within a PlayerProvider");
  }
  return context;
};
