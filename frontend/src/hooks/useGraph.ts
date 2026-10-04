import { useState, useCallback, useEffect, useLayoutEffect, useRef } from 'react';
import { GraphData, GraphApi, Configuration } from '../api-client';
import { useApi } from './useApi';
import { baseURL } from '../api/config';
import { axiosInstance } from '../api/axiosInstance';
import { useSimulationContext } from '../context/simulation.context';
import { useWebSocketConnection } from './websocket/useWebSocketConnection';

export const useGraph = () => {
    const { designMode, activeSimulation } = useSimulationContext();
    const { connected } = useWebSocketConnection();
    const { graphApi, clientId } = useApi(); // Default API from context
    const [graphData, setGraphData] = useState<GraphData | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<Error | null>(null);
    const wasConnected = useRef(connected);
    const hasConnected = useRef(connected);
    const requestVersion = useRef(0);
    const loadedScope = useRef<string | null>(null);
    const pendingRefresh = useRef<number | null>(null);
    const snapshotReady = !activeSimulation || !['QUEUED', 'BUILDING', 'FAILED'].includes(activeSimulation.status ?? '');

    // Invalidate in-flight requests even when the next simulation is still building.
    useLayoutEffect(() => {
        requestVersion.current++;
        if (pendingRefresh.current !== null) {
            window.clearTimeout(pendingRefresh.current);
            pendingRefresh.current = null;
        }
    }, [activeSimulation?.id, designMode, snapshotReady]);

    /**
     * Fetches graph data.
     * @param simulationIdOverride
     *  - undefined: Use current Context (Standard)
     *  - null: Force Live (No header)
     *  - string: Force specific Simulation ID
     */
    const refetchGraphData = useCallback(async (simulationIdOverride?: string | null) => {
        const version = ++requestVersion.current;
        const simulationId = simulationIdOverride === undefined ? activeSimulation?.id : simulationIdOverride;
        const scope = JSON.stringify([simulationId ?? null, designMode]);
        // Keep the current renderer visible while reconciling edits or reconnects.
        // A different live/design/simulation scope still requires foreground loading.
        setLoading(loadedScope.current !== scope);
        try {
            let api = graphApi;

            // If an override is explicitly passed (null or string), create a temp config
            if (simulationIdOverride !== undefined) {
                const config = new Configuration({
                    basePath: baseURL,
                    baseOptions: {
                        headers: {
                            'X-Sender-ID': clientId,
                            // Only add header if ID is a string (not null)
                            ...(simulationIdOverride ? { 'X-Simulation-ID': simulationIdOverride } : {})
                        }
                    }
                });
                api = new GraphApi(config, undefined, axiosInstance);
            }

            const response = await api.getGraphData(designMode);
            // Ensure we set the timestamp if missing (fallback)
            const data = response.data;
            if (!data.timestamp) {
                data.timestamp = new Date().toISOString();
            }

            if (version === requestVersion.current) {
                loadedScope.current = scope;
                setGraphData(data);
                setError(null);
            }
        } catch (err) {
            if (version !== requestVersion.current) return;
            console.error("Failed to fetch graph data", err);
            setError(err as Error);
            throw err;
        } finally {
            if (version === requestVersion.current) setLoading(false);
        }
    }, [graphApi, clientId, designMode, activeSimulation?.id]);

    // Coalesce READY, reconnect, and mutation triggers in the same browser turn.
    const requestRefresh = useCallback(() => {
        if (!snapshotReady || pendingRefresh.current !== null) return;
        pendingRefresh.current = window.setTimeout(() => {
            pendingRefresh.current = null;
            void refetchGraphData(activeSimulation?.id ?? null).catch((): void => undefined);
        }, 0);
    }, [activeSimulation?.id, snapshotReady, refetchGraphData]);

    useEffect(() => {
        const version = requestVersion;
        const pending = pendingRefresh;
        return () => {
            version.current++;
            if (pending.current !== null) window.clearTimeout(pending.current);
        };
    }, []);

    // A mode transition or READY status establishes which snapshot is authoritative.
    // Keeping this trigger here prevents status, URL, and reconnect handlers from
    // each issuing a competing graph request.
    useEffect(() => {
        if (!snapshotReady) return;
        requestRefresh();
    }, [activeSimulation?.id, snapshotReady, designMode, requestRefresh]);

    useEffect(() => {
        const refresh = () => requestRefresh();
        window.addEventListener('scenario-mutated', refresh);
        return () => window.removeEventListener('scenario-mutated', refresh);
    }, [requestRefresh]);

    useEffect(() => {
        const reconnected = connected && !wasConnected.current;
        wasConnected.current = connected;
        if (!reconnected) return;
        if (!hasConnected.current) {
            hasConnected.current = true;
            return;
        }
        if (!snapshotReady) return;
        // A socket reconnect can leave a short interval of missed entity events;
        // reconcile from the authoritative graph snapshot before live updates resume.
        requestRefresh();
    }, [connected, requestRefresh, snapshotReady]);

    return { graphData, loading, error, refetchGraphData };
};
