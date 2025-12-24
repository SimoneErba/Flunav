import './App.css'
import React, { useState, useEffect, useMemo, useRef } from 'react';
import { DisplayGraph } from './components/graph/DisplayGraph';
import { useGraph } from './hooks/useGraph';
import { SimulationsApi, SimulationStateResponse, SimulationStateResponseStatusEnum } from "./api-client/api";
import { useWebSocket } from './hooks/useWebSocket';
import { PlaybackControls } from './components/PlaybackControls';
import { useSimulationClock } from './components/graph/hooks/useSimulationClock';

// Import Theme Stuff
import { GraphThemeProvider, useTheme, THEMES } from './context/theme.context';
import { useWebSocketConnection } from './hooks/websocket/useWebSocketConnection';
import { useWebSocketEvents } from './hooks/websocket/useWebSocketEvents';
import { SimulationProvider, useSimulationContext } from './context/simulation.context';
import { useApi } from './hooks/useApi';

// --- INNER COMPONENT (Can use useTheme) ---
function AppContent() {
  const { activeSimulation, setActiveSimulation } = useSimulationContext();


  // 1. Get Theme
  const { mode } = useTheme();
  const theme = THEMES[mode];

  // --- State ---
  const [selectedDate, setSelectedDate] = useState(new Date());
  const [isRestoring, setIsRestoring] = useState(false);
  const [playbackSpeed, setPlaybackSpeed] = useState(1.0);
  const [isSelectingDate, setIsSelectingDate] = useState(false);

  // --- Hooks ---
  const { graphData, loading: graphLoading, refetchGraphData } = useGraph();
  const { connected } = useWebSocketConnection();
  const { subscribeToSimulationStatus } = useWebSocketEvents();
  const { simulationApi } = useApi();
  const activeSimulationIdRef = useRef<string | null>(null);
  activeSimulationIdRef.current = activeSimulation?.id || null;

  const isPaused = activeSimulation 
    ? activeSimulation.status !== SimulationStateResponseStatusEnum.Playing
    : false;

  const simTime = useSimulationClock(
      activeSimulation?.timestamp, 
      playbackSpeed, 
      isPaused
  );

  const dateTimeLocal = useMemo(() => {
    const offset = selectedDate.getTimezoneOffset() * 60000;
    return (new Date(selectedDate.getTime() - offset)).toISOString().slice(0, 16);
  }, [selectedDate]);

  // --- Handlers (Same as before) ---
  const handleDateChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!e.target.value) return;
    setSelectedDate(new Date(e.target.value));
  };

  const handleStartSimulationClick = () => {
      setIsSelectingDate(true);
      setSelectedDate(new Date());
  };

  const handleConfirmRestore = async () => {
    setIsSelectingDate(false);
    if (isRestoring) return;
    if (!selectedDate) { alert('Invalid date'); return; }

    setIsRestoring(true);
    setPlaybackSpeed(1.0);

    if (activeSimulation) {
        try { await simulationApi.destroySimulation(activeSimulation.id); } 
        catch (e) { console.warn(e); }
    }

    try {
      const result = await simulationApi.createSimulation({timestamp: selectedDate.toISOString()});
      setActiveSimulation(result.data);
    } catch (error) {
      alert(`Error: ${error}`);
      setIsRestoring(false);
      setActiveSimulation(null);
    }
  };

  const handleReturnToLive = async () => {
    if (activeSimulation) {
        try { await simulationApi.destroySimulation(activeSimulation.id); } 
        catch (e) { console.error(e); }
    }
    setActiveSimulation(null);
    setIsRestoring(false);
    setPlaybackSpeed(1.0);
    refetchGraphData(null);
  };

  const handleTogglePlayback = async () => {
    if (!activeSimulation) return;
    const isPlaying = activeSimulation.status === SimulationStateResponseStatusEnum.Playing;
    try {
      if (isPlaying) {
        await simulationApi.pausePlayback(activeSimulation.id);
        setActiveSimulation(prev => prev ? { ...prev, status: SimulationStateResponseStatusEnum.Paused } : null);
      } else {
        await simulationApi.startPlayback(activeSimulation.id, { 
            speedFactor: playbackSpeed,
            startTimestamp: new Date(simTime).toISOString() 
        });
        setActiveSimulation(prev => prev ? { ...prev, status: SimulationStateResponseStatusEnum.Playing } : null);
      }
    } catch (error) { console.error(error); }
  };

  const handleSetSpeed = async (speed: number) => {
    setPlaybackSpeed(speed);
    if (activeSimulation?.status === SimulationStateResponseStatusEnum.Playing) {
        try {
            await simulationApi.startPlayback(activeSimulation.id, { 
                speedFactor: speed,
                startTimestamp: new Date(simTime).toISOString()
            });
        } catch (e) { console.error(e); }
    }
  };

  useEffect(() => {
    const simId = activeSimulation?.id;
    if (!connected || !simId) return;
    
    const handleStatusUpdate = (update: any) => {
        setActiveSimulation(prev => {
            if (prev?.id !== simId) return prev;
            return { ...prev, status: update.status };
        });
        if (update.status === SimulationStateResponseStatusEnum.Ready) {
            refetchGraphData(simId);
            setIsRestoring(false);
        } else if (update.status === SimulationStateResponseStatusEnum.Failed) {
            alert(`Simulation failed: ${update.message}`);
            setIsRestoring(false);
        }
    };
    const unsubscribe = subscribeToSimulationStatus(simId, handleStatusUpdate);
    simulationApi.getSimulationStatus(simId).then(response => {
        if (activeSimulationIdRef.current === simId) {
            const status = response.data.status;
            setActiveSimulation(prev => prev ? { ...prev, status } : null);
            if (status === SimulationStateResponseStatusEnum.Ready) {
                refetchGraphData(simId);
                setIsRestoring(false);
            }
        }
    }).catch(console.warn);
    return () => { unsubscribe(); };
  }, [connected, activeSimulation?.id, subscribeToSimulationStatus, refetchGraphData]); 

  useEffect(() => {
    if (!activeSimulation?.id) return;
    const simId = activeSimulation.id;
    const intervalId = setInterval(() => {
      simulationApi.sendHeartbeat(simId).catch(console.warn);
    }, 30_000);
    return () => clearInterval(intervalId);
  }, [activeSimulation?.id]); 

  const isLoading = graphLoading || isRestoring;

  // --- RENDER WITH THEME ---
  return (
    <div style={{ 
        display: 'flex', 
        flexDirection: 'column', 
        height: '100vh', 
        // Apply Theme Background to whole app
        backgroundColor: theme.background, 
        color: theme.headerText,
        transition: 'background-color 0.3s ease'
    }}>
      
      <header style={{ 
        padding: '12px 24px', 
        // Apply Theme Header Colors
        backgroundColor: theme.headerBackground, 
        borderBottom: `1px solid ${theme.headerBorder}`,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexShrink: 0,
        boxShadow: '0 2px 4px rgba(0,0,0,0.05)'
      }}>
        
        <div style={{ display: 'flex', alignItems: 'center', gap: '20px' }}>
            <img src="/logo.svg" alt="Logo" style={{ width: 180 }} />
        </div>
        
        <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
            {activeSimulation && !isRestoring && (
                <PlaybackControls
                    simulation={activeSimulation}
                    simTime={simTime}
                    currentSpeed={playbackSpeed}
                    onTogglePlay={handleTogglePlayback}
                    onSetSpeed={handleSetSpeed}
                />
            )}

            {!activeSimulation && !isSelectingDate && (
                <button 
                    onClick={handleStartSimulationClick}
                    style={{ padding: '8px 16px', background: '#007bff', color: 'white', border: 'none', borderRadius: '6px', cursor: 'pointer', fontWeight: 'bold' }}
                >
                    ⏱ Time Travel
                </button>
            )}

            {isSelectingDate && (
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', background: theme.uiBackground, padding: '4px', borderRadius: '6px', border: `1px solid ${theme.uiBorder}` }}>
                    <input
                        type="datetime-local"
                        value={dateTimeLocal}
                        onChange={handleDateChange}
                        style={{ 
                            padding: '6px', 
                            border: `1px solid ${theme.uiBorder}`, 
                            borderRadius: '4px',
                            backgroundColor: theme.inputBackground,
                            color: theme.inputColor,
                            colorScheme: mode
                        }}
                    />
                    <button 
                        onClick={handleConfirmRestore}
                        disabled={isLoading}
                        style={{ padding: '6px 12px', background: '#28a745', color: 'white', border: 'none', borderRadius: '4px', cursor: 'pointer' }}
                    >
                        Start
                    </button>
                    <button 
                        onClick={() => setIsSelectingDate(false)}
                        style={{ padding: '6px 12px', background: 'transparent', color: theme.uiText, border: 'none', cursor: 'pointer' }}
                    >
                        Cancel
                    </button>
                </div>
            )}
        </div>

        <div style={{ width: '180px', display: 'flex', justifyContent: 'flex-end' }}>
            {activeSimulation && (
                <button
                    onClick={handleReturnToLive}
                    style={{ 
                        padding: '8px 16px', border: '1px solid #dc3545', borderRadius: '6px', 
                        background: 'transparent', color: '#dc3545', cursor: 'pointer', fontWeight: 'bold'
                    }}
                >
                    Exit Simulation
                </button>
            )}
        </div>
      </header>

      <main style={{ flex: 1, position: 'relative' }}>
        {isLoading ? (
          <div style={{ position: 'absolute', top: '50%', left: '50%', transform: 'translate(-50%, -50%)', textAlign: 'center', color: theme.uiText }}>
            <h3>{isRestoring ? 'Reconstructing Historical State...' : 'Loading Graph...'}</h3>
          </div>
        ) : (
          <DisplayGraph 
            initialGraphData={graphData} 
            simulationId={activeSimulation?.id}
            simTime={simTime}
          />
        )}
      </main>
    </div>
  );
}

// --- MAIN WRAPPER (Provides Theme) ---
function App() {
  return (
    <GraphThemeProvider>
      <SimulationProvider>
        <AppContent />
      </SimulationProvider>
    </GraphThemeProvider>
  );
}

export default App;