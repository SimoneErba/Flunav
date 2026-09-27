import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import toast from 'react-hot-toast';
import { DisplayGraph } from '../components/graph/DisplayGraph';
import { PlaybackControls } from '../components/PlaybackControls';
import LiveAnalysisPanel from '../components/SettingsPanel';
import { AppHeader } from '../components/AppHeader';
import { AppNavigation, type OperationalMode } from '../components/AppNavigation';
import { useGraph } from '../hooks/useGraph';
import { useSimulationClock } from '../components/graph/hooks/useSimulationClock';
import { useWebSocketConnection } from '../hooks/websocket/useWebSocketConnection';
import { useWebSocketEvents } from '../hooks/websocket/useWebSocketEvents';
import { useApi } from '../hooks/useApi';
import { DisplayRuleColorResult, SimulationStateResponseStatusEnum } from '../api-client/api';
import type { SimulationStatusUpdate } from '../types/WebsocketTypes';
import { useSimulationContext } from '../context/simulation.context';
import { GraphImportExport } from '../components/graph/GraphImportExport';
import { useWorkspaceModeTransitions } from './useWorkspaceModeTransitions';

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

export default function LiveWorkspace() {
  useEffect(() => {
    if (import.meta.env.VITE_GRAPH_TEST_API === 'true') {
      window.__workspaceRenderCount = (window.__workspaceRenderCount ?? 0) + 1;
    }
  });
   const { activeSimulation, setActiveSimulation, designMode, isBranching, isExitingWhatIf } = useSimulationContext();
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
      setSelectedDate(alignToMinute(new Date(simTime())));
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
  };

  const { selectLive: handleSelectLive, selectReplay: handleSelectReplay,
    selectWhatIf: handleSelectWhatIf, selectDesign } = useWorkspaceModeTransitions(
      isWhatIf, setIsSelectingDate, handleStartSimulationClick, handleReturnToLive);

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
                setIsRestoring(false);
            }
        }
    }).catch(console.warn);
    return () => { unsubscribe(); };
  }, [connected, activeSimulation?.id, setActiveSimulation, simulationApi, subscribeToSimulationStatus]); 

  useEffect(() => {
    if (!connected || designMode) return;
    return subscribeToAnomalies((notification) => {
      const finding = notification.finding;
      if (finding?.detector === 'UNSCORABLE_CAPACITY') return;
      const mode = finding?.temporalMode?.replace(/_/g, ' ') ?? (activeSimulation ? 'SIMULATION' : 'LIVE');
      if (notification.kind === 'FINDING_DETECTED' && finding) {
        persistentNotification(
          `${mode}: ${finding.detector.replace(/_/g, ' ')} on ${finding.componentId}`,
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
                    now={simTime}
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
        onDesignSystem={selectDesign}
      />
   );

   const rightActions = (
      <GraphImportExport onImportSuccess={() => window.dispatchEvent(new Event('scenario-mutated'))} />
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
            now={simTime}
            colorOverrides={colorOverrides}
          />
        </div>}
      </main>
      
       {!designMode && <LiveAnalysisPanel onColorsUpdated={updateColors} />}
    </div>
  );
}

