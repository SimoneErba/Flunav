import React from 'react';
import { SimulationStateResponse, SimulationStateResponseStatusEnum } from '../api-client/api';
import { useTheme, THEMES } from '../context/theme.context'; // <--- Import Context

interface PlaybackControlsProps {
  simulation: SimulationStateResponse | null;
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
  // 1. Get Theme Data
  const { mode } = useTheme();
  const theme = THEMES[mode];

  const isLive = !simulation;
  const isPlaying = simulation?.status === SimulationStateResponseStatusEnum.Playing;

  const date = new Date(simTime);
  const timeStr = date.toLocaleTimeString('en-GB', { hour12: false });
  const msStr = date.getMilliseconds().toString().padStart(3, '0');
  const dateStr = date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });

  return (
    <div style={{ 
      display: 'flex', 
      alignItems: 'center', 
      gap: '16px', 
      // 2. Use Theme Colors for Container
      background: theme.uiBackground, 
      padding: '6px 16px', 
      borderRadius: '8px', 
      border: `1px solid ${theme.uiBorder}`,
      transition: 'background-color 0.3s ease, border-color 0.3s ease'
    }}>
      
      {/* --- THE CLOCK --- */}
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', lineHeight: '1.1' }}>
        <div style={{ 
            fontFamily: 'monospace', 
            fontSize: '16px', 
            fontWeight: 'bold', 
            color: theme.uiText, // Dynamic Text Color
            minWidth: '110px',
            textAlign: 'right'
        }}>
          {timeStr}<span style={{ fontSize: '0.8em', opacity: 0.6 }}>.{msStr}</span>
        </div>
        <div style={{ fontSize: '10px', color: theme.uiText, opacity: 0.6, textTransform: 'uppercase' }}>
            {dateStr}
        </div>
      </div>

      {/* Separator */}
      <div style={{ height: '24px', width: '1px', background: theme.uiBorder }}></div>

      {/* --- MODE INDICATOR / CONTROLS --- */}
      {isLive ? (
        <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <div style={{
                width: '10px', height: '10px', borderRadius: '50%', 
                background: '#dc3545', 
                boxShadow: '0 0 0 2px rgba(220, 53, 69, 0.2)'
            }}></div>
            <span style={{ fontWeight: 'bold', color: '#dc3545', fontSize: '14px', letterSpacing: '0.5px' }}>
                LIVE
            </span>
        </div>
      ) : (
        <>
          <button
            onClick={onTogglePlay}
            style={{
              padding: '6px 12px',
              border: `1px solid ${isPlaying ? '#ffc107' : '#28a745'}`,
              borderRadius: '4px',
              // 3. Use Theme Colors for Button Background
              background: theme.inputBackground,
              color: isPlaying ? '#ffc107' : '#28a745',
              cursor: 'pointer',
              fontWeight: 'bold',
              minWidth: '80px',
              display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '6px'
            }}
          >
            <span>{isPlaying ? '⏸' : '▶'}</span>
            <span>{isPlaying ? 'Pause' : 'Play'}</span>
          </button>

          <select
            value={currentSpeed}
            onChange={(e) => onSetSpeed && onSetSpeed(parseFloat(e.target.value))}
            style={{ 
                padding: '6px', 
                border: `1px solid ${theme.uiBorder}`, 
                borderRadius: '4px', 
                cursor: 'pointer',
                fontWeight: 'bold',
                // 4. Use Theme Colors for Select Input
                backgroundColor: theme.inputBackground,
                color: theme.uiText,
                colorScheme: mode // Forces browser native dropdown to match theme
            }}
          >
            <option value={0.5}>0.5x</option>
            <option value={1}>1x</option>
            <option value={2}>2x</option>
            <option value={5}>5x</option>
            <option value={10}>10x</option>
            <option value={50}>50x</option>
          </select>
        </>
      )}
    </div>
  );
};