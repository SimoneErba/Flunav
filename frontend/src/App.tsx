import React, { useState, useEffect, useMemo, useRef } from 'react';
import { DisplayGraph } from './components/graph/DisplayGraph';
import { useGraph } from './hooks/useGraph';
import { SimulationStateResponseStatusEnum } from "./api-client/api";
import { PlaybackControls } from './components/PlaybackControls';
import { useSimulationClock } from './components/graph/hooks/useSimulationClock';
import { ThemeToggle } from './components/theme.toggle';

// Import Theme Stuff
import { GraphThemeProvider } from './context/theme.context';
import { useWebSocketConnection } from './hooks/websocket/useWebSocketConnection';
import { useWebSocketEvents } from './hooks/websocket/useWebSocketEvents';
import { SimulationProvider, useSimulationContext } from './context/simulation.context';
import { useApi } from './hooks/useApi';
import toast, { Toaster } from 'react-hot-toast';
import './index.css'
// --- INNER COMPONENT ---
function AppContent() {
  const { activeSimulation, setActiveSimulation } = useSimulationContext();

  // --- State ---
  const [selectedDate, setSelectedDate] = useState(new Date());
  const [isRestoring, setIsRestoring] = useState(false);
  const [playbackSpeed, setPlaybackSpeed] = useState(1.0);
  const [isSelectingDate, setIsSelectingDate] = useState(false);

  // --- Hooks ---
  const { graphData, loading: graphLoading, refetchGraphData, error: graphError } = useGraph();
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

  // --- Handlers ---
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
    if (!selectedDate) { toast.error('Please select a valid date'); return; }

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
      toast.error(`Error: ${error}`);
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
          toast.error(`Simulation failed: ${update.message}`);
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

  const handleRetry = () => {
    toast.promise(
        refetchGraphData(activeSimulation?.id || null),
        {
            loading: 'Attempting to reconnect...',
            success: 'Connected successfully!',
            error: 'Connection failed. Backend is still down.',
        },
        {
            style: {
                borderRadius: '10px',
                background: '#333',
                color: '#fff',
            },
        }
    );
  };

  if (graphError) {
    return (
      <div className="flex flex-col items-center justify-center h-screen bg-gray-50 dark:bg-gray-900 text-gray-900 dark:text-gray-100">
        <div className="p-10 border border-gray-200 dark:border-gray-700 rounded-xl bg-white dark:bg-gray-800 text-center shadow-xl max-w-md">
            <h2 className="text-red-600 text-2xl font-bold mb-2">Connection Failed</h2>
            <p className="mb-4 text-gray-700 dark:text-gray-300">
                Could not connect to the Backend API.
            </p>
            <p className="text-xs text-gray-500 mb-6 font-mono bg-gray-100 dark:bg-gray-900 p-2 rounded">
                {graphError.message || "Network Error"}
            </p>
            <button 
                onClick={handleRetry}
                className="px-6 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg font-bold transition-colors"
            >
              Retry Connection ↻
            </button>
        </div>
      </div>
    );
  }

  if (!isLoading && !graphData) {
      return null;
  }

  return (
    <div className="flex flex-col h-screen bg-[#f0f2f5] dark:bg-[#1a1a1a] text-gray-800 dark:text-white transition-colors duration-300">
      
      {/* --- HEADER --- */}
      <header className="
        px-6 py-3 
        bg-white dark:bg-gray-800 
        border-b border-gray-200 dark:border-gray-700
        flex items-center justify-between shrink-0 shadow-sm z-50
      ">
        
        {/* Logo Area */}
        <div className="flex items-center gap-5">
            <img src="/logo.svg" alt="Logo" className="w-44" />
        </div>
        
        {/* Center Controls */}
        <div className="flex items-center gap-4">
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
                    className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg font-bold transition-colors shadow-sm"
                >
                    ⏱ Time Travel
                </button>
            )}

            {isSelectingDate && (
                <div className="flex items-center gap-2 bg-gray-50 dark:bg-gray-900 p-1 rounded-lg border border-gray-200 dark:border-gray-700 animate-pop-in">
                    <input
                        type="datetime-local"
                        value={dateTimeLocal}
                        onChange={handleDateChange}
                        className="
                          p-1.5 border rounded text-sm 
                          bg-white dark:bg-gray-800 
                          border-gray-300 dark:border-gray-600 
                          text-gray-900 dark:text-white 
                          focus:outline-none focus:ring-2 focus:ring-blue-500 
                          dark:[color-scheme:dark]
                        "
                    />
                    <button 
                        onClick={handleConfirmRestore}
                        disabled={isLoading}
                        className="px-3 py-1.5 bg-green-600 hover:bg-green-700 text-white rounded font-bold text-sm transition-colors"
                    >
                        Start
                    </button>
                    <button 
                        onClick={() => setIsSelectingDate(false)}
                        className="px-3 py-1.5 text-gray-600 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-gray-700 rounded font-medium text-sm transition-colors"
                    >
                        Cancel
                    </button>
                </div>
            )}
        </div>

        {/* Right Controls */}
        <div className="w-44 flex justify-end items-center gap-3">
            {activeSimulation && (
                <button
                    onClick={handleReturnToLive}
                    className="
                      px-4 py-2 border border-red-500 rounded-lg text-sm font-bold 
                      text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20 
                      transition-colors
                    "
                >
                    Exit Simulation
                </button>
            )}
            
            <ThemeToggle />
        </div>
      </header>

      {/* --- MAIN CONTENT --- */}
      <main className="flex-1 relative overflow-hidden">
        {isLoading ? (
          <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-center">
            <h3 className="text-xl font-semibold text-gray-600 dark:text-gray-300 animate-pulse">
              {isRestoring ? 'Reconstructing Historical State...' : 'Loading Graph...'}
            </h3>
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

function App() {
  return (
    <GraphThemeProvider>
      <SimulationProvider>
        <Toaster position="bottom-center" reverseOrder={false} />
        <AppContent />
      </SimulationProvider>
    </GraphThemeProvider>
  );
}

export default App;