import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { BrowserRouter, Routes, Route, Navigate, Outlet, useLocation, Link } from 'react-router-dom';
import toast, { Toaster } from 'react-hot-toast';

// --- COMPONENTS ---
import { DisplayGraph } from './components/graph/DisplayGraph';
import { PlaybackControls } from './components/PlaybackControls';
import LiveAnalysisPanel from './components/SettingsPanel';
import { LoginPage } from './components/LoginPage';
import { UserManagement } from './components/admin/UserManagement';
import { AppHeader } from './components/AppHeader';

// --- HOOKS & UTILS ---
import { useGraph } from './hooks/useGraph';
import { useSimulationClock } from './components/graph/hooks/useSimulationClock';
import { useWebSocketConnection } from './hooks/websocket/useWebSocketConnection';
import { useWebSocketEvents } from './hooks/websocket/useWebSocketEvents';
import { useApi } from './hooks/useApi';
import { DisplayRuleColorResult, SimulationStateResponseStatusEnum } from "./api-client/api";

// --- CONTEXTS ---
import { GraphThemeProvider } from './context/theme.context';
import { SimulationProvider, useSimulationContext } from './context/simulation.context';
import { AuthProvider, useAuth } from './context/auth.context';

// --- STYLES ---
import './index.css';
import { GraphImportExport } from './components/graph/GraphImportExport';

// ============================================================================
// 1. AUTH GUARD
// ============================================================================
const RequireAuth = () => {
  const { isAuthenticated } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }
  return <Outlet />;
};

// ============================================================================
// 2. LIVE WORKSPACE
// ============================================================================
function LiveWorkspace() {
   const { activeSimulation, setActiveSimulation } = useSimulationContext();

   // --- Simulation State ---
   const [selectedDate, setSelectedDate] = useState(new Date());
   const [isRestoring, setIsRestoring] = useState(false);
   const [playbackSpeed, setPlaybackSpeed] = useState(1.0);
   const [isSelectingDate, setIsSelectingDate] = useState(false);
   const [showLiveAnalysis, setShowLiveAnalysis] = useState(false);
  const [colorOverrides, setColorOverrides] = useState<DisplayRuleColorResult | null>(null);

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

  const updateColors = useCallback((result: DisplayRuleColorResult) => {
      setColorOverrides(result);
  }, []);
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

  // --- Effects ---
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
        { style: { borderRadius: '10px', background: '#333', color: '#fff' } }
    );
  };

  if (graphError) {
    return (
      <div className="flex flex-col items-center justify-center h-screen bg-gray-50 dark:bg-gray-900 text-gray-900 dark:text-gray-100">
        <div className="p-10 border border-gray-200 dark:border-gray-700 rounded-xl bg-white dark:bg-gray-800 text-center shadow-xl max-w-md">
            <h2 className="text-red-600 text-2xl font-bold mb-2">Connection Failed</h2>
            <p className="mb-4 text-gray-700 dark:text-gray-300">Could not connect to the Backend API.</p>
            <button onClick={handleRetry} className="px-6 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg font-bold transition-colors">Retry Connection ↻</button>
        </div>
      </div>
    );
  }

  // --- HEADER CONTENT CONFIGURATION ---

  // 1. Center Content (Playback or Time Travel)
  const centerContent = (
    <div className="flex items-center gap-4">
        {activeSimulation && !isRestoring ? (
            <PlaybackControls
                simulation={activeSimulation}
                simTime={simTime}
                currentSpeed={playbackSpeed}
                onTogglePlay={handleTogglePlayback}
                onSetSpeed={handleSetSpeed}
            />
        ) : (
            !isSelectingDate && (
                <button onClick={handleStartSimulationClick} className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg font-bold transition-colors shadow-sm flex items-center gap-2">
                    <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                    Time Travel
                </button>
            )
        )}

        {isSelectingDate && (
            <div className="flex items-center gap-2 bg-gray-50 dark:bg-gray-900 p-1 rounded-lg border border-gray-200 dark:border-gray-700 animate-pop-in">
                <input type="datetime-local" value={dateTimeLocal} onChange={handleDateChange} className="p-1.5 border rounded text-sm bg-white dark:bg-gray-800 border-gray-300 dark:border-gray-600 text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500 dark:[color-scheme:dark]" />
                <button onClick={handleConfirmRestore} disabled={isLoading} className="px-3 py-1.5 bg-green-600 hover:bg-green-700 text-white rounded font-bold text-sm transition-colors">Start</button>
                <button onClick={() => setIsSelectingDate(false)} className="px-3 py-1.5 text-gray-600 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-gray-700 rounded font-medium text-sm transition-colors">Cancel</button>
            </div>
        )}
    </div>
  );

   // 2. left Actions (Exit Sim, Live Analysis Toggle)
   const leftActions = (
      <>
         {activeSimulation && (
            <button onClick={handleReturnToLive} className="px-3 py-1.5 border border-red-500 rounded text-xs font-bold text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors">
               Exit Sim
            </button>
         )}

      <GraphImportExport onImportSuccess={() => refetchGraphData(null)} />

      
      <Link to="/admin" className="text-sm font-medium text-gray-500 hover:text-blue-600 dark:text-gray-400 dark:hover:text-blue-400 transition-colors">
        Admin
      </Link>
      </>
   );

  return (
    <div className="flex flex-col h-screen bg-[#f0f2f5] dark:bg-[#1a1a1a] text-gray-800 dark:text-white transition-colors duration-300">
      
      {/* UNIFIED HEADER */}
      <AppHeader centerContent={centerContent} leftActions={leftActions} />

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
            colorOverrides={colorOverrides}
          />
        )}
      </main>
      
       <LiveAnalysisPanel onColorsUpdated={updateColors} />
    </div>
  );
}

// ============================================================================
// 3. ADMIN WORKSPACE
// ============================================================================
const AdminWorkspace = () => {
    const { user } = useAuth();
    
    // 1. Center: Title
    const centerContent = (
        <div className="flex items-center gap-2 text-gray-500 dark:text-gray-400">
            <span className="font-semibold text-gray-900 dark:text-white">Admin Portal</span>
            <span>/</span>
            <span>System Configuration</span>
        </div>
    );

    // 2. left: Back Button
    const leftActions = (
        <Link 
            to="/live" 
            className="text-sm font-medium text-gray-500 hover:text-blue-600 dark:text-gray-400 dark:hover:text-blue-400 transition-colors"
        >
            Live Twin
        </Link>
    );

    return (
        <div className="flex flex-col h-screen bg-gray-50 dark:bg-[#121212] text-gray-900 dark:text-white transition-colors duration-300">
            
            {/* UNIFIED HEADER */}
            <AppHeader centerContent={centerContent} leftActions={leftActions} />

            <div className="flex-1 overflow-y-auto p-8">
                <div className="max-w-6xl mx-auto space-y-8">
                    
                    {/* User Management Section */}
                    {user?.role === 'SUPERADMIN' ? (
                        <UserManagement />
                    ) : (
                        <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 p-4 rounded text-yellow-800 dark:text-yellow-200">
                            ⚠️ You do not have permission to manage users.
                        </div>
                    )}

                    {/* System Health Section */}
                    <div className="bg-white dark:bg-gray-800 p-6 rounded-lg shadow border border-gray-200 dark:border-gray-700">
                        <h2 className="text-lg font-bold mb-2">System Health</h2>
                        <p className="text-gray-500">Dashboards coming soon...</p>
                    </div>
                </div>
            </div>
        </div>
    );
};

// ============================================================================
// 4. ROOT APP
// ============================================================================
function App() {
  return (
    <GraphThemeProvider>
      <AuthProvider>
        <SimulationProvider>
          <Toaster position="bottom-center" reverseOrder={false} />
          
          <BrowserRouter>
            <Routes>
              {/* Public Route */}
              <Route path="/login" element={<LoginPage />} />

              {/* Protected Routes */}
              <Route element={<RequireAuth />}>
                {/* Redirect root to live */}
                <Route path="/" element={<Navigate to="/live" replace />} />
                
                {/* Operational View (Graph) */}
                <Route path="/live" element={<LiveWorkspace />} />
                
                {/* Admin View (Tables/Forms) */}
                <Route path="/admin/*" element={<AdminWorkspace />} />
              </Route>

              {/* Catch All */}
              <Route path="*" element={<Navigate to="/live" replace />} />
            </Routes>
          </BrowserRouter>

        </SimulationProvider>
      </AuthProvider>
    </GraphThemeProvider>
  );
}

export default App;