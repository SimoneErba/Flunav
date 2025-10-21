import React from 'react';
import { SimulationStateResponse, SimulationStateResponseStatusEnum } from '../api-client/api';

interface PlaybackControlsProps {
  simulation: SimulationStateResponse;
  onTogglePlay: () => void;
  onSetSpeed: (speed: number) => void;
  currentSpeed: number;
}

export const PlaybackControls = ({
  simulation,
  onTogglePlay,
  onSetSpeed,
  currentSpeed,
}: PlaybackControlsProps) => {
  const isPlaying = simulation.status === SimulationStateResponseStatusEnum.Playing;

  const handleSpeedChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    onSetSpeed(parseFloat(e.target.value));
  };

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
      <button
        onClick={onTogglePlay}
        style={{
          padding: '8px 16px',
          border: `1px solid ${isPlaying ? '#ffc107' : '#28a745'}`,
          borderRadius: '4px',
          background: 'white',
          color: isPlaying ? '#ffc107' : '#28a745',
          cursor: 'pointer',
          fontWeight: 'bold',
          minWidth: '90px',
        }}
      >
        {isPlaying ? 'Pause' : 'Play'}
      </button>

      <select
        value={currentSpeed}
        onChange={handleSpeedChange}
        style={{ padding: '8px', border: '1px solid #ccc', borderRadius: '4px' }}
      >
        <option value={0.5}>0.5x</option>
        <option value={1}>1x (Normal)</option>
        <option value={2}>2x</option>
        <option value={4}>4x</option>
      </select>
    </div>
  );
};