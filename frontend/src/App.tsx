import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { BrowserRouter, Routes, Route, Navigate, Outlet, useLocation, useSearchParams } from 'react-router-dom';
import toast, { Toaster } from 'react-hot-toast';

// --- COMPONENTS ---
import { DisplayGraph } from './components/graph/DisplayGraph';
import { PlaybackControls } from './components/PlaybackControls';
import LiveAnalysisPanel from './components/SettingsPanel';
import { LoginPage } from './components/LoginPage';
import { UserManagement } from './components/admin/UserManagement';
import { DestinationMappingManagement } from './components/admin/DestinationMappingManagement';
import { SensorMappingManagement } from './components/admin/SensorMappingManagement';
import { BiEntityEvents } from './components/admin/BiEntityEvents';
import { AppHeader } from './components/AppHeader';
import { AppNavigation, type OperationalMode } from './components/AppNavigation';

// --- HOOKS & UTILS ---
import { useGraph } from './hooks/useGraph';
import { useSimulationClock } from './components/graph/hooks/useSimulationClock';
import { WebSocketProvider, useWebSocketConnection } from './hooks/websocket/useWebSocketConnection';
import { useWebSocketEvents } from './hooks/websocket/useWebSocketEvents';
import { useApi } from './hooks/useApi';
import { DisplayRuleColorResult, SimulationStateResponseStatusEnum } from "./api-client/api";
import type { SimulationStatusUpdate } from './types/WebsocketTypes';

// --- CONTEXTS ---
import { GraphThemeProvider } from './context/theme.context';
import { SimulationProvider, useSimulationContext } from './context/simulation.context';
import { AuthProvider, useAuth } from './context/auth.context';

// --- STYLES ---
import './index.css';
import { GraphImportExport } from './components/graph/GraphImportExport';

const AssistantPage = React.lazy(() => import('./components/assistant/AssistantPage')
  .then(module => ({ default: module.AssistantPage })));
const MultiSimulationsPage = React.lazy(() => import('./components/multisimulation/MultiSimulationsPage')
  .then(module => ({ default: module.MultiSimulationsPage })));

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
const alignToMinute = (date: Date) => {
  const aligned = new Date(date);
  aligned.setSeconds(0, 0);
  return aligned;
};

const persistentNotification = (message: string, icon: string, id: string) => {
  toast((notification) => (
    <div className="flex max-w-lg items-center gap-3">
      <span aria-hidden="true">{icon}</span>
      <span className="flex-1 text-sm font-medium">{message}</span>
      <button
        type="button"
        onClick={() => toast.dismiss(notification.id)}
        className="rounded px-2 py-1 text-xs font-semibold text-gray-500 hover:bg-gray-100 hover:text-gray-900"
      >
        Dismiss
      </button>
    </div>
  ), { id, duration: Infinity });
};

function LiveWorkspace() {
   const [searchParams, setSearchParams] = useSearchParams();
   const { activeSimulation, setActiveSimulation, designMode, setDesignMode, isBranching, isExitingWhatIf,
       enterWhatIf, exitWhatIf } = useSimulationContext();
   const isWhatIf = activeSimulation?.kind === 'WHAT_IF_LIVE' || activeSimulation?.kind === 'WHAT_IF_SIMULATION';

   // --- Simulation State ---
   const [selectedDate, setSelectedDate] = useState(new Date());
   const [isRestoring, setIsRestoring] = useState(false);
   const [playbackSpeed, setPlaybackSpeed] = useState(1.0);
   const [isSelectingDate, setIsSelectingDate] = useState(false);
  const [colorOverrides, setColorOverrides] = useState<DisplayRuleColorResult | null>(null);

  // --- Hooks ---
  const { graphData, loading: graphLoading, refetchGraphData, error: graphError } = useGraph();
  const { connected } = useWebSocketConnection();
  const { subscribeToSimulationStatus, subscribeToAnomalies } = useWebSocketEvents();
  const { simulationApi } = useApi();
  const activeSimulationIdRef = useRef<string | null>(null);
  activeSimulationIdRef.current = activeSimulation?.id || null;

  const isPaused = activeSimulation 
    ? activeSimulation.status !== SimulationStateResponseStatusEnum.Playing
    : false;

  const simTime = useSimulationClock(
      activeSimulation?.lastProcessedTimestamp ?? activeSimulation?.timestamp, 
      playbackSpeed, 
      isPaused,
      activeSimulation?.id
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
    setSelectedDate(alignToMinute(new Date(e.target.value)));
  };

  const handleStartSimulationClick = () => {
      setIsSelectingDate(true);
      setSelectedDate(alignToMinute(new Date()));
  };

  const handleChangeSimulationTimeClick = () => {
      setIsSelectingDate(true);
      setSelectedDate(alignToMinute(new Date(simTime)));
  };

  const handleConfirmRestore = async () => {
    setIsSelectingDate(false);
    if (isRestoring) return;
    if (!selectedDate) { toast.error('Please select a valid date'); return; }

    setIsRestoring(true);
    setPlaybackSpeed(1.0);

    const simulationToDestroy = activeSimulation;
    if (simulationToDestroy) {
        activeSimulationIdRef.current = null;
        setActiveSimulation(null);
        try { await simulationApi.destroySimulation(simulationToDestroy.id); } 
        catch (e) { console.warn(e); }
    }

    try {
      const restoreTimestamp = alignToMinute(selectedDate);
      const result = await simulationApi.createSimulation({timestamp: restoreTimestamp.toISOString()});
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

  const handleSelectLive = () => {
    setIsSelectingDate(false);
    setDesignMode(false);
    if (isWhatIf) {
      void exitWhatIf().catch(() => toast.error('Could not exit What If'));
    } else if (activeSimulation) {
      void handleReturnToLive();
    }
  };

  const handleSelectReplay = () => {
    setDesignMode(false);
    handleStartSimulationClick();
  };

  const handleSelectWhatIf = () => {
    setIsSelectingDate(false);
    setDesignMode(false);
    if (!isWhatIf) void enterWhatIf().catch(() => toast.error('Could not start What If'));
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
            speedFactor: playbackSpeed
        });
        setActiveSimulation(prev => prev ? { ...prev, status: SimulationStateResponseStatusEnum.Playing } : null);
      }
    } catch (error) { console.error(error); }
  };

  const handleSetSpeed = async (speed: number) => {
    setPlaybackSpeed(speed);
    if (activeSimulation?.status === SimulationStateResponseStatusEnum.Playing) {
        try {
            await simulationApi.updateSpeed(activeSimulation.id, speed);
        } catch (e) { console.error(e); }
    }
  };

  // --- Effects ---
  useEffect(() => {
    const simId = activeSimulation?.id;
    if (!connected || !simId) return;
    
    const handleStatusUpdate = (update: SimulationStatusUpdate & { timestamp: number }) => {
        if (activeSimulationIdRef.current !== simId) return;
        setActiveSimulation(prev => {
            if (prev?.id !== simId) return prev;
            return {
              ...prev,
              status: update.status,
              buildProgress: update.buildProgress ?? prev.buildProgress,
              liveInputState: update.liveInputState ?? prev.liveInputState,
              lastProcessedTimestamp: new Date(update.timestamp).toISOString()
            };
        });
        if (update.status === SimulationStateResponseStatusEnum.Ready) {
            refetchGraphData(simId);
            setIsRestoring(false);
        } else if (update.status === SimulationStateResponseStatusEnum.Failed) {
            toast.error(update.message ?? 'Simulation could not continue');
            setIsRestoring(false);
        }
    };
    const unsubscribe = subscribeToSimulationStatus(simId, handleStatusUpdate);
    simulationApi.getSimulationStatus(simId).then(response => {
        if (activeSimulationIdRef.current === simId) {
            const status = response.data.status;
            setActiveSimulation(prev => prev ? { ...prev, ...response.data, status } : response.data);
            if (status === SimulationStateResponseStatusEnum.Ready) {
                refetchGraphData(simId);
                setIsRestoring(false);
            }
        }
    }).catch(console.warn);
    return () => { unsubscribe(); };
  }, [connected, activeSimulation?.id, refetchGraphData, setActiveSimulation, simulationApi, subscribeToSimulationStatus]); 

  useEffect(() => {
    if (!connected || designMode) return;
    return subscribeToAnomalies((notification) => {
      const finding = notification.finding;
      if (finding?.detector === 'UNSCORABLE_CAPACITY') return;
      const mode = finding?.temporalMode?.replaceAll('_', ' ') ?? (activeSimulation ? 'SIMULATION' : 'LIVE');
      if (notification.kind === 'FINDING_DETECTED' && finding) {
        persistentNotification(
          `${mode}: ${finding.detector.replaceAll('_', ' ')} on ${finding.componentId}`,
          '⚠️',
          `finding-${mode}-${finding.detector}-${finding.componentId}`,
        );
      } else if (notification.kind === 'INCIDENT_UPDATED' && notification.incident) {
        persistentNotification(
          `Probable root ${notification.incident.probableRootComponentId} · ${notification.incident.confidence.toLowerCase()} confidence`,
          '🔎',
          `incident-${notification.incident.incidentId}`,
        );
      } else if (notification.kind === 'ALARM_CLEARED') {
        toast.success(`Advisory cleared on ${notification.componentId}`);
      }
    }, activeSimulation?.id);
  }, [activeSimulation, connected, subscribeToAnomalies, designMode]);

  useEffect(() => {
    if (!activeSimulation?.id) return;
    const simId = activeSimulation.id;
    const intervalId = setInterval(() => {
      simulationApi.sendHeartbeat(simId).catch(console.warn);
    }, 30_000);
    return () => clearInterval(intervalId);
  }, [activeSimulation?.id, simulationApi]); 

  useEffect(() => {
    const requestedMode = searchParams.get('mode');
    if (!requestedMode) return;
    setSearchParams({}, { replace: true });
    setIsSelectingDate(false);
    setDesignMode(false);
    if (requestedMode === 'live') {
      if (isWhatIf) {
        void exitWhatIf().catch(() => toast.error('Could not exit What If'));
      } else if (activeSimulation) {
        void simulationApi.destroySimulation(activeSimulation.id)
          .catch(console.error)
          .finally(() => {
            setActiveSimulation(null);
            setIsRestoring(false);
            setPlaybackSpeed(1.0);
            void refetchGraphData(null).catch(console.warn);
          });
      }
    } else if (requestedMode === 'replay') {
      setSelectedDate(alignToMinute(new Date()));
      setIsSelectingDate(true);
    } else if (requestedMode === 'what-if' && !isWhatIf) {
      void enterWhatIf().catch(() => toast.error('Could not start What If'));
    } else if (requestedMode === 'design' && !activeSimulation) {
      setDesignMode(true);
    }
  }, [activeSimulation, enterWhatIf, exitWhatIf, isWhatIf, refetchGraphData, searchParams, setActiveSimulation, setDesignMode, setSearchParams, simulationApi]);

  useEffect(() => {
    const refresh = () => { void refetchGraphData(activeSimulationIdRef.current).catch(console.warn); };
    window.addEventListener('scenario-mutated', refresh);
    return () => window.removeEventListener('scenario-mutated', refresh);
  }, [refetchGraphData]);

  const graphReady = !activeSimulation || !['QUEUED', 'BUILDING', 'FAILED'].includes(activeSimulation.status!);
  useEffect(() => {
    if (graphReady) {
      void refetchGraphData(activeSimulation?.id ?? null).catch(console.warn);
    }
  }, [activeSimulation?.id, graphReady, designMode, refetchGraphData]);

  const isLoading = graphLoading || isRestoring || isBranching || isExitingWhatIf;
  const restoreProgress = Math.min(100, Math.max(0, activeSimulation?.buildProgress ?? 0));

  const handleRetry = () => {
    toast.promise(
        refetchGraphData(activeSimulation?.id || null),
        {
            loading: 'Attempting to reconnect...',
            success: 'Connected successfully!',
            error: 'Graph data is still unavailable.',
        },
        { style: { borderRadius: '10px', background: '#333', color: '#fff' } }
    );
  };

  if (graphError) {
    return (
      <div className="flex flex-col items-center justify-center h-screen bg-gray-50 dark:bg-gray-900 text-gray-900 dark:text-gray-100">
        <div className="p-10 border border-gray-200 dark:border-gray-700 rounded-xl bg-white dark:bg-gray-800 text-center shadow-xl max-w-md">
            <h2 className="text-red-600 text-2xl font-bold mb-2">Graph Unavailable</h2>
            <p className="mb-4 text-gray-700 dark:text-gray-300">Could not load graph data.</p>
            <button onClick={handleRetry} className="px-6 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg font-bold transition-colors">Retry Graph Load ↻</button>
        </div>
      </div>
    );
  }

  // --- HEADER CONTENT CONFIGURATION ---

  const restoreConfirmLabel = activeSimulation ? 'Change' : 'Start';

  // Playback controls appear only after a mode has established its simulation context.
  const centerContent = (
    <div className="flex items-center gap-2">
        {isSelectingDate ? (
            <div className="flex items-center gap-2 bg-gray-50 dark:bg-gray-900 p-1 rounded-lg border border-gray-200 dark:border-gray-700 animate-pop-in">
                <input type="datetime-local" value={dateTimeLocal} onChange={handleDateChange} className="p-1.5 border rounded text-sm bg-white dark:bg-gray-800 border-gray-300 dark:border-gray-600 text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500 dark:[color-scheme:dark]" />
                <button onClick={handleConfirmRestore} disabled={isLoading} className="px-3 py-1.5 bg-green-600 hover:bg-green-700 text-white rounded font-bold text-sm transition-colors">{restoreConfirmLabel}</button>
                <button onClick={() => setIsSelectingDate(false)} className="px-3 py-1.5 text-gray-600 dark:text-gray-300 hover:bg-gray-200 dark:hover:bg-gray-700 rounded font-medium text-sm transition-colors">Cancel</button>
            </div>
        ) : activeSimulation && !isRestoring ? (
            <>
                <PlaybackControls
                    simulation={activeSimulation}
                    simTime={simTime}
                    currentSpeed={playbackSpeed}
                    onTogglePlay={handleTogglePlayback}
                    onSetSpeed={handleSetSpeed}
                />
                {!isWhatIf && <button onClick={handleChangeSimulationTimeClick} className="px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded-lg font-bold transition-colors shadow-sm flex items-center gap-2">
                    <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                    Change Time
                </button>}
            </>
        ) : null}
    </div>
  );

   const activeMode: OperationalMode = isWhatIf ? 'what-if' : (activeSimulation || isSelectingDate || isRestoring) ? 'replay' : 'live';
   const leftActions = (
      <AppNavigation
        activeMode={activeMode}
        designMode={designMode}
        disabled={isLoading}
        onLive={handleSelectLive}
        onReplay={handleSelectReplay}
        onWhatIf={handleSelectWhatIf}
        onDesignSystem={() => {
          if (!activeSimulation) setDesignMode(!designMode);
        }}
      />
   );

   const rightActions = (
      <GraphImportExport onImportSuccess={() => refetchGraphData(activeSimulation?.id ?? null)} />
   );

  return (
    <div className="flex flex-col h-screen bg-[#f0f2f5] dark:bg-[#1a1a1a] text-gray-800 dark:text-white transition-colors duration-300">
      
      {/* UNIFIED HEADER */}
      <AppHeader centerContent={centerContent} leftActions={leftActions} rightActions={rightActions} />

      <main className="flex-1 relative overflow-hidden">
        {isLoading && (
          <div className="absolute top-1/2 left-1/2 w-full max-w-md -translate-x-1/2 -translate-y-1/2 px-6 text-center">
            <h3 className="text-xl font-semibold text-gray-600 dark:text-gray-300 animate-pulse">
              {isExitingWhatIf
                ? 'Exiting What If...'
                : isBranching
                  ? 'Going into What If...'
                  : isRestoring
                    ? 'Reconstructing Historical State...'
                    : 'Loading Graph...'}
            </h3>
            {isRestoring && (
              <div className="mt-5">
                <div className="mb-2 flex items-center justify-between text-sm font-medium text-gray-600 dark:text-gray-300">
                  <span>Simulation progress</span>
                  <span>{Math.round(restoreProgress)}%</span>
                </div>
                <div
                  className="h-3 overflow-hidden rounded-full bg-gray-200 shadow-inner dark:bg-gray-700"
                  role="progressbar"
                  aria-label="Simulation build progress"
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-valuenow={Math.round(restoreProgress)}
                >
                  <div
                    className="h-full rounded-full bg-blue-600 transition-[width] duration-300 ease-out"
                    style={{ width: `${restoreProgress}%` }}
                  />
                </div>
              </div>
            )}
          </div>
        )}
        {graphData && <div className={isLoading ? 'invisible absolute inset-0' : 'absolute inset-0'}>
          <DisplayGraph
            initialGraphData={graphData} 
            simulationId={activeSimulation?.id}
            simTime={simTime}
            colorOverrides={colorOverrides}
          />
        </div>}
      </main>
      
       {!designMode && <LiveAnalysisPanel onColorsUpdated={updateColors} />}
    </div>
  );
}

// ============================================================================
// 3. ADMIN WORKSPACE
// ============================================================================
const AdminWorkspace = () => {
    const { user } = useAuth();
    const location = useLocation();
    const canAccessUsers = user?.role === 'SUPERADMIN';
    const canAccessDestinationMappings = user?.role === 'ADMIN' || user?.role === 'SUPERADMIN';
    const canAccessSensors = user?.role === 'ADMIN' || user?.role === 'SUPERADMIN';
    const canAccessBi = user?.role === 'ADMIN' || user?.role === 'SUPERADMIN';
    const isDestinationMappings = location.pathname === '/admin/destination-mappings';
    const isBi = location.pathname === '/admin/bi';
    const isSensors = location.pathname === '/admin/sensors';
    const title = isDestinationMappings ? 'Destination Mappings' : isSensors ? 'Sensors' : isBi ? 'BI' : 'User Management';
    
    // 1. Center: Title
    const centerContent = (
        <div className="flex items-center gap-2 text-gray-500 dark:text-gray-400">
            <span className="font-semibold text-gray-900 dark:text-white">Admin Portal</span>
            <span>/</span>
            <span>{title}</span>
        </div>
    );

    const leftActions = <AppNavigation />;

    const content = isBi ? (
        canAccessBi ? (
            <BiEntityEvents />
        ) : (
            <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 p-4 rounded text-yellow-800 dark:text-yellow-200">
                You do not have permission to access BI.
            </div>
        )
    ) : isSensors ? (
        canAccessSensors ? (
            <SensorMappingManagement />
        ) : (
            <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 p-4 rounded text-yellow-800 dark:text-yellow-200">
                You do not have permission to manage sensors.
            </div>
        )
    ) : isDestinationMappings ? (
        canAccessDestinationMappings ? (
            <DestinationMappingManagement />
        ) : (
            <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 p-4 rounded text-yellow-800 dark:text-yellow-200">
                You do not have permission to manage destination mappings.
            </div>
        )
    ) : canAccessUsers ? (
        <UserManagement />
    ) : (
        <div className="bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-800 p-4 rounded text-yellow-800 dark:text-yellow-200">
            You do not have permission to manage users.
        </div>
    );

    return (
        <div className="flex flex-col h-screen bg-gray-50 dark:bg-[#121212] text-gray-900 dark:text-white transition-colors duration-300">
            
            {/* UNIFIED HEADER */}
            <AppHeader centerContent={centerContent} leftActions={leftActions} />

            <div className="flex-1 overflow-y-auto p-8">
                <div className={`${isDestinationMappings || isSensors || isBi ? 'max-w-[1600px]' : 'max-w-6xl'} mx-auto space-y-8`}>
                    {content}
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
          <WebSocketProvider>
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
                  <Route path="/multi-simulations" element={(
                    <React.Suspense fallback={<div className="p-6">Loading multi-simulations…</div>}>
                      <MultiSimulationsPage />
                    </React.Suspense>
                  )} />
                  
                  {/* Admin View (Tables/Forms) */}
                  <Route path="/admin" element={<AdminWorkspace />} />
                  <Route path="/admin/destination-mappings" element={<AdminWorkspace />} />
                  <Route path="/admin/sensors" element={<AdminWorkspace />} />
                  <Route path="/admin/bi" element={<AdminWorkspace />} />
                  <Route path="/assistant" element={(
                    <React.Suspense fallback={<div className="p-6">Loading assistant…</div>}>
                      <AssistantPage />
                    </React.Suspense>
                  )} />
                </Route>

                {/* Catch All */}
                <Route path="*" element={<Navigate to="/live" replace />} />
              </Routes>
            </BrowserRouter>
          </WebSocketProvider>

        </SimulationProvider>
      </AuthProvider>
    </GraphThemeProvider>
  );
}

export default App;
