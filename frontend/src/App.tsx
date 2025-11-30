import './App.css'
import { DisplayGraph } from './components/graph'
import { useGraph } from './hooks/useGraph';
import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import {
  SimulationsApi, SimulationStateResponse, SimulationStateResponseStatusEnum
} from "./api-client/api";
import { useWebSocket } from './hooks/useWebSocket';
import { PlaybackControls } from './components/PlaybackControls';

// 1. Move API client outside or useMemo. 
// If it doesn't hold state, outside is fine.
const simulationClient = new SimulationsApi();

function App() {
  // State
  const [activeSimulation, setActiveSimulation] = useState<SimulationStateResponse | null>(null);
  const [selectedDate, setSelectedDate] = useState(new Date());
  const [isRestoring, setIsRestoring] = useState(false);
  const [playbackSpeed, setPlaybackSpeed] = useState(1.0);
  
  // Hooks
  const { graphData, loading: graphLoading, refetchGraphData } = useGraph();
  const { connected, subscribeToSimulationStatus } = useWebSocket();

  // Refs (to avoid effect re-runs)
  const activeSimulationIdRef = useRef<string | null>(null);
  activeSimulationIdRef.current = activeSimulation?.id || null;

  // --- Helpers ---

  // Memoize date conversion to prevent recalculation on every render
  const dateTimeLocal = useMemo(() => {
    const offset = selectedDate.getTimezoneOffset() * 60000;
    return (new Date(selectedDate.getTime() - offset)).toISOString().slice(0, 16);
  }, [selectedDate]);

  const handleDateChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!e.target.value) return;
    setSelectedDate(new Date(e.target.value));
  };

  // --- Handlers ---

  const handleRestore = async () => {
    if (isRestoring) return; // Prevent double clicks
    if (!selectedDate) {
      alert('Please select a valid date and time.');
      return;
    }

    // Optimistic / UI updates first
    setIsRestoring(true);
    setPlaybackSpeed(1.0);

    // Cleanup previous if exists
    if (activeSimulation) {
        try {
            await simulationClient.destroySimulation(activeSimulation.id);
        } catch (e) {
            console.warn("Failed to destroy previous simulation", e);
        }
    }

    console.log(`Restoring system to: ${selectedDate.toISOString()}`);

    try {
      const result = await simulationClient.createSimulation({timestamp: selectedDate.toISOString()});
      setActiveSimulation(result.data);
    } catch (error) {
      console.error(error);
      alert(`Error while initiating the restore: ${error}`);
      setIsRestoring(false);
      setActiveSimulation(null);
    }
  };

  const handleReturnToLive = async () => {
    if (activeSimulation) {
        try {
            await simulationClient.destroySimulation(activeSimulation.id);
        } catch (error) {
            console.error("Error destroying simulation:", error);
        }
    }
    setActiveSimulation(null);
    setIsRestoring(false);
    refetchGraphData(); // Fetch live data
  };

  const handleTogglePlayback = async () => {
    if (!activeSimulation) return;
    const isPlaying = activeSimulation.status === SimulationStateResponseStatusEnum.Playing;

    try {
      if (isPlaying) {
        await simulationClient.cancelPlayback(activeSimulation.id);
        // Optimistic update (optional, but makes UI snappy)
        setActiveSimulation(prev => prev ? { ...prev, status: SimulationStateResponseStatusEnum.Ready } : null);
      } else {
        await simulationClient.startPlayback(activeSimulation.id, { speedFactor: playbackSpeed });
        setActiveSimulation(prev => prev ? { ...prev, status: SimulationStateResponseStatusEnum.Playing } : null);
      }
    } catch (error) {
      console.error(`Failed to toggle playback`, error);
      alert(`Error: Could not update playback state.`);
    }
  };

  const handleSetSpeed = async (speed: number) => {
    if (!activeSimulation) return;
    setPlaybackSpeed(speed); // Update UI immediately
    try {
      await simulationClient.startPlayback(activeSimulation.id, { speedFactor: speed });
    } catch (error) {
      console.error("Failed to set simulation speed", error);
    }
  };

  // --- Effects ---

  // 2. Optimized Status Subscription
  // Only re-run if the Simulation ID changes, or connection status changes.
  // NOT when the simulation status changes (we handle that inside).
  useEffect(() => {
    const simId = activeSimulation?.id;
    
    // Don't subscribe if we are already in a terminal state or no simulation
    if (!connected || !simId) return;
    
    // If we are already Ready/Failed, we might not need to subscribe, 
    // BUT if we want to catch "Playing" -> "Ready" transitions, we should stay subscribed.
    // Let's only skip if we are null.

    console.log(`Subscribing to status updates for simulation: ${simId}`);

    const handleStatusUpdate = (update: any) => {
        console.log('Received status update:', update);
        
        setActiveSimulation(prev => {
            // Guard: ensure we are updating the correct simulation
            if (prev?.id !== simId) return prev;
            return { ...prev, status: update.status };
        });

        if (update.status === SimulationStateResponseStatusEnum.Ready) {
            refetchGraphData(simId);
            setIsRestoring(false);
        } else if (update.status === SimulationStateResponseStatusEnum.Failed) {
            alert(`Simulation failed: ${update.message}`);
            setIsRestoring(false);
            // Optional: setActiveSimulation(null);
        }
    };

    const unsubscribe = subscribeToSimulationStatus(simId, handleStatusUpdate);

    // Initial Poll to ensure we didn't miss a socket event between creation and subscription
    simulationClient.getSimulationStatus(simId)
      .then(response => {
        // Only update if we are still looking at the same simulation
        if (activeSimulationIdRef.current === simId) {
            const status = response.data.status;
            setActiveSimulation(prev => prev ? { ...prev, status } : null);
            
            if (status === SimulationStateResponseStatusEnum.Ready) {
                refetchGraphData(simId);
                setIsRestoring(false);
            }
        }
      })
      .catch(console.warn);

    return () => {
        unsubscribe();
    };
  }, [connected, activeSimulation?.id, subscribeToSimulationStatus, refetchGraphData]); 
  // ^ Key change: depend on .id, not the whole object

  // 3. Optimized Heartbeat
  useEffect(() => {
    if (!activeSimulation?.id) return;
    const simId = activeSimulation.id;

    const intervalId = setInterval(() => {
      // Use the captured simId variable, so we don't need activeSimulation in dependency
      console.log(`Sending heartbeat for: ${simId}`);
      simulationClient.sendHeartbeat(simId).catch(console.warn);
    }, 30_000);

    return () => clearInterval(intervalId);
  }, [activeSimulation?.id]); 

  // --- Render Logic ---

  const canShowPlaybackControls = activeSimulation && !isRestoring && (
    activeSimulation.status === SimulationStateResponseStatusEnum.Ready ||
    activeSimulation.status === SimulationStateResponseStatusEnum.Playing
  );

  const isLoading = graphLoading || isRestoring;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', background: '#f0f2f5' }}>
      
      <header style={{ 
        padding: '16px', background: 'white', borderBottom: '1px solid #ddd',
        display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexShrink: 0
      }}>
        
        <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
            <img src="/logo.svg" alt="Logo" style={{ width: 250 }} />
            <h2 style={{ margin: 0, color: '#333' }}>
                {activeSimulation ? 'Historical View' : 'Live System'}
            </h2>
            
            <input
              type="datetime-local"
              value={dateTimeLocal}
              onChange={handleDateChange}
              disabled={isLoading}
              style={{ padding: '8px', border: '1px solid #ccc', borderRadius: '4px' }}
            />
            
            <button
              onClick={handleRestore}
              disabled={isLoading}
              style={{ 
                  padding: '8px 16px', border: 'none', borderRadius: '4px', 
                  background: '#007bff', color: 'white',
                  cursor: isLoading ? 'not-allowed' : 'pointer',
                  opacity: isLoading ? 0.7 : 1
              }}
            >
              {isRestoring ? 'Building...' : 'Restore to this Time'}
            </button>

            {isRestoring && activeSimulation && (
                <span style={{ color: '#666', fontStyle: 'italic' }}>
                    Status: {activeSimulation.status}...
                </span>
            )}
        </div>
        
        <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
            {canShowPlaybackControls && (
                <PlaybackControls
                    simulation={activeSimulation}
                    currentSpeed={playbackSpeed}
                    onTogglePlay={handleTogglePlayback}
                    onSetSpeed={handleSetSpeed}
                />
            )}

            {activeSimulation && !isRestoring && (
                <button
                    onClick={handleReturnToLive}
                    style={{ 
                        padding: '8px 16px', border: '1px solid #dc3545', borderRadius: '4px', 
                        background: 'white', color: '#dc3545', cursor: 'pointer', fontWeight: 'bold'
                    }}
                >
                    Return to Live
                </button>
            )}
        </div>
      </header>

      <main style={{ flex: 1, position: 'relative' }}>
        {isLoading ? (
          <div style={{ 
              position: 'absolute', top: '50%', left: '50%', transform: 'translate(-50%, -50%)',
              textAlign: 'center', color: '#666' 
          }}>
            <h3>{isRestoring ? 'Reconstructing Historical State...' : 'Loading Graph...'}</h3>
          </div>
        ) : (
          <DisplayGraph initialGraphData={graphData} simulationId={activeSimulation?.id} />
        )}
      </main>
    </div>
  );
}

export default App;