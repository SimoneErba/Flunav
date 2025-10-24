import './App.css'
import { DisplayGraph } from './components/graph'
import { useGraph } from './hooks/useGraph';
import React, { useState, useEffect } from 'react';
import {
  SimulationsApi, SimulationStateResponse, SimulationStateResponseStatusEnum
} from "./api-client/api";
import { useWebSocket } from './hooks/useWebSocket';
import { PlaybackControls } from './components/PlaybackControls';

function App() {
  const [activeSimulation, setActiveSimulation] = useState<SimulationStateResponse | null>(null);
  const { graphData, loading, refetchGraphData } = useGraph();
  const client = new SimulationsApi();
  const [selectedDate, setSelectedDate] = useState(new Date());
  const { connected, subscribeToSimulationStatus } = useWebSocket();
  const [isRestoring, setIsRestoring] = useState(false);
  const [playbackSpeed, setPlaybackSpeed] = useState(1.0);

  const toDateTimeLocal = (date: Date) => {
    const offset = date.getTimezoneOffset() * 60000;
    const localISOTime = (new Date(date.getTime() - offset)).toISOString().slice(0, 16);
    return localISOTime;
  };

  const handleRestore = async () => {
    if (!selectedDate) {
      alert('Please select a valid date and time.');
      return;
    }

    if (activeSimulation) {
        try {
            await client.destroySimulation(activeSimulation.id);
        } catch (e) {
            console.warn("Failed to destroy previous simulation", e);
        }
    }

    console.log(`Restoring system to: ${selectedDate.toISOString()}`);
    setIsRestoring(true);
    setPlaybackSpeed(1.0);

    try {
      const result = await client.createSimulation({timestamp: selectedDate.toISOString()});
      setActiveSimulation(result.data);
    } catch (error) {
      alert(`Error while initiating the restore: ${error}`);
      setIsRestoring(false);
      setActiveSimulation(null);
    }
  };

  const handleReturnToLive = async () => {
    if (activeSimulation) {
        try {
            await client.destroySimulation(activeSimulation.id);
        } catch (error) {
            console.error("Error destroying simulation on backend:", error);
        }
    }

    setActiveSimulation(null);
    setIsRestoring(false);
    refetchGraphData();
  };

  const handleTogglePlayback = async () => {
    if (!activeSimulation) return;

    const isPlaying = activeSimulation.status === SimulationStateResponseStatusEnum.Playing;

    try {
      if (isPlaying) {
        await client.cancelPlayback(activeSimulation.id);
      } else {
        await client.startPlayback(activeSimulation.id, { speedFactor: playbackSpeed });
      }
    } catch (error) {
      console.error(`Failed to ${isPlaying ? 'cancel' : 'start'} playback`, error);
      alert(`Error: Could not update playback state.`);
    }
  };

  const handleSetSpeed = async (speed: number) => {
    if (!activeSimulation) return;

    setPlaybackSpeed(speed);

    try {
      await client.startPlayback(activeSimulation.id, { speedFactor: speed });
    } catch (error) {
      console.error("Failed to set simulation speed", error);
      alert("Error: Could not update the simulation speed.");
    }
  };

useEffect(() => {
      if (!connected || !activeSimulation || 
          activeSimulation.status === SimulationStateResponseStatusEnum.Ready || 
          activeSimulation.status === SimulationStateResponseStatusEnum.Playing ||
          activeSimulation.status === SimulationStateResponseStatusEnum.Failed) {
        return;
    }

    console.log(`Subscribing to status updates for simulation: ${activeSimulation.id}`);
    const unsubscribe = subscribeToSimulationStatus(activeSimulation.id, (update) => {
        console.log('Received simulation status update:', update);
        
          setActiveSimulation(prev => prev ? ({ ...prev, status: update.status }) : null);

          if (update.status === SimulationStateResponseStatusEnum.Ready) {
              refetchGraphData(activeSimulation.id)
            setIsRestoring(false);
              unsubscribe();
        } else if (update.status === SimulationStateResponseStatusEnum.Failed) {
            alert(`Simulation build failed: ${update.message || 'Unknown error'}`);
            setIsRestoring(false);
            setActiveSimulation(null);
              unsubscribe();
        }
    });

      client.getSimulationStatus(activeSimulation.id)
      .then(response => {
        const status = response.data.status;
        console.log("Polled simulation status:", status);

        if (status === SimulationStateResponseStatusEnum.Ready) {
          refetchGraphData(activeSimulation.id);
          setIsRestoring(false);
        unsubscribe();
        } else if (status === SimulationStateResponseStatusEnum.Failed) {
          alert(`Simulation build failed: ${response.data.message || 'Unknown error'}`);
          setIsRestoring(false);
          setActiveSimulation(null);
          unsubscribe();
        }
      })
      .catch(error => {
        console.warn("Error polling simulation status", error);
      });

      return () => unsubscribe();

  }, [connected, activeSimulation, subscribeToSimulationStatus]);

  useEffect(() => {
    if (!activeSimulation) return;

    const intervalId = setInterval(() => {
      console.log(`Sending heartbeat for simulation: ${activeSimulation.id}`);
      client.sendHeartbeat(activeSimulation.id)
        .catch(err => console.warn("Failed to send heartbeat:", err));
    }, 30_000);

    return () => clearInterval(intervalId);
  }, [activeSimulation]);

  const canShowPlaybackControls = activeSimulation && !isRestoring && (
    activeSimulation.status === SimulationStateResponseStatusEnum.Ready ||
    activeSimulation.status === SimulationStateResponseStatusEnum.Playing
  );

return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', background: '#f0f2f5' }}>
      
      {/* This top-level div is your header. It's a flex container that will space out its two children. */}
      <header style={{ 
        padding: '16px', 
        background: 'white', 
        borderBottom: '1px solid #ddd',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between', // This will push the left and right divs apart
        flexShrink: 0
      }}>
        
        {/* --- CONTAINER 1: Left-Side Controls --- */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
            <img src="/logo.svg" alt="Logo" style={{ width: 250 }} />
            <h2 style={{ margin: 0, color: '#333' }}>
                {activeSimulation ? 'Historical View' : 'Live System'}
            </h2>
            
            <input
              type="datetime-local"
              value={toDateTimeLocal(selectedDate)}
              onChange={(e) => setSelectedDate(new Date(e.target.value))}
              disabled={loading || isRestoring}
              style={{ padding: '8px', border: '1px solid #ccc', borderRadius: '4px' }}
            />
            
            <button
              onClick={handleRestore}
              disabled={loading || isRestoring}
              style={{ 
                  padding: '8px 16px', 
                  border: 'none', 
                  borderRadius: '4px', 
                  background: '#007bff', 
                  color: 'white',
                  cursor: loading || isRestoring ? 'not-allowed' : 'pointer',
                  opacity: loading || isRestoring ? 0.7 : 1
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
        
        {/* --- CONTAINER 2: Right-Side Controls --- */}
        {/* This is a new div that is a direct child of the header */}
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
                        padding: '8px 16px', 
                        border: '1px solid #dc3545', 
                        borderRadius: '4px', 
                        background: 'white', 
                        color: '#dc3545',
                        cursor: 'pointer',
                        fontWeight: 'bold'
                    }}
                >
                    Return to Live
                </button>
            )}
        </div>
      </header> {/* I've renamed the div to header for semantic clarity */}

      {/* This main content area will now correctly fill the remaining space */}
      <main style={{ flex: 1, position: 'relative' }}>
        {loading || isRestoring ? (
          <div style={{ 
              position: 'absolute', top: '50%', left: '50%', transform: 'translate(-50%, -50%)',
              textAlign: 'center', color: '#666' 
          }}>
            <h3>{isRestoring ? 'Reconstructing Historical State...' : 'Loading Graph...'}</h3>
          </div>
        ) : (
          <DisplayGraph initialGraphData={graphData} />
        )}
      </main>
    </div>
  );
}

export default App;