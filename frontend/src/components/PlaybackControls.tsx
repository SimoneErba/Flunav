import React from 'react';
import { SimulationStateResponse, SimulationStateResponseStatusEnum } from '../api-client/api';

interface PlaybackControlsProps {
  simulation: (SimulationStateResponse & {
    nextFastTick?: string | null;
    nextMinuteTick?: string | null;
    nextBaselineTick?: string | null;
  }) | null;
  simTime: number;
  onTogglePlay?: () => void;
  onSetSpeed?: (speed: number) => void;
  currentSpeed?: number;
}

export const PlaybackControls = ({
  simulation,
  simTime,
  onTogglePlay,
  onSetSpeed,
  currentSpeed = 1,
}: PlaybackControlsProps) => {
  
  const isLive = !simulation;
  const isPlaying = simulation?.status === SimulationStateResponseStatusEnum.Playing;

  // Time Formatting
  const date = new Date(simTime);
  const timeStr = date.toLocaleTimeString('en-GB', { hour12: false });
  const msStr = date.getMilliseconds().toString().padStart(3, '0');
  const dateStr = date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });

  return (
    <div className="flex items-center gap-4 px-4 py-1.5 rounded-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 transition-colors duration-300 shadow-sm">
      
      {/* --- THE CLOCK --- */}
      <div className="flex flex-col items-end leading-none">
        <div className="font-mono text-base font-bold text-gray-900 dark:text-gray-100 min-w-[110px] text-right">
          {timeStr}<span className="text-xs opacity-60">.{msStr}</span>
        </div>
        <div className="text-[10px] text-gray-900 dark:text-gray-100 opacity-60 uppercase mt-0.5">
            {dateStr}
        </div>
      </div>

      {/* Separator */}
      <div className="h-6 w-px bg-gray-200 dark:bg-gray-700"></div>

      {/* --- MODE INDICATOR / CONTROLS --- */}
      {isLive ? (
        <div className="flex items-center gap-2">
            <div className="w-2.5 h-2.5 rounded-full bg-red-600 shadow-[0_0_0_2px_rgba(220,53,69,0.2)] animate-pulse"></div>
            <span className="font-bold text-red-600 text-sm tracking-wide">
                LIVE
            </span>
        </div>
      ) : (
        <>
          <button
            onClick={onTogglePlay}
            className={`
              flex items-center justify-center gap-1.5 min-w-[80px] px-3 py-1.5 
              border rounded text-sm font-bold cursor-pointer transition-colors
              bg-gray-50 dark:bg-gray-700 hover:bg-gray-100 dark:hover:bg-gray-600
              ${isPlaying 
                ? 'border-amber-500 text-amber-500' 
                : 'border-green-600 text-green-600'
              }
            `}
          >
            <span>{isPlaying ? '⏸' : '▶'}</span>
            <span>{isPlaying ? 'Pause' : 'Play'}</span>
          </button>

          <select
            value={currentSpeed}
            onChange={(e) => onSetSpeed && onSetSpeed(parseFloat(e.target.value))}
            className="
              p-1.5 border rounded text-sm font-bold cursor-pointer
              border-gray-200 dark:border-gray-700
              bg-gray-50 dark:bg-gray-700 
              text-gray-900 dark:text-gray-100
              focus:outline-none focus:ring-2 focus:ring-blue-500
            "
          >
            <option value={0.5}>0.5x</option>
            <option value={1}>1x</option>
            <option value={2}>2x</option>
            <option value={5}>5x</option>
            <option value={10}>10x</option>
            <option value={50}>50x</option>
          </select>
          <div className="hidden xl:flex flex-col border-l border-gray-200 pl-3 text-[10px] leading-4 text-gray-500 dark:border-gray-700 dark:text-gray-400" title="Next scheduled virtual detector boundaries">
            <span>10s {simulation.nextFastTick ? new Date(simulation.nextFastTick).toLocaleTimeString() : '—'}</span>
            <span>1m {simulation.nextMinuteTick ? new Date(simulation.nextMinuteTick).toLocaleTimeString() : '—'}</span>
            <span>5m {simulation.nextBaselineTick ? new Date(simulation.nextBaselineTick).toLocaleTimeString() : '—'}</span>
          </div>
        </>
      )}
    </div>
  );
};
