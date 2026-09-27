import { useState, useCallback, useEffect, useRef } from 'react';
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

    /**
     * Fetches graph data.
     * @param simulationIdOverride
     *  - undefined: Use current Context (Standard)
     *  - null: Force Live (No header)
     *  - string: Force specific Simulation ID
     */
    const refetchGraphData = useCallback(async (simulationIdOverride?: string | null) => {
        const version = ++requestVersion.current;
        setLoading(true);
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
    }, [graphApi, clientId, designMode]);

    const snapshotReady = !activeSimulation || !['QUEUED', 'BUILDING', 'FAILED'].includes(activeSimulation.status ?? '');

    // A mode transition or READY status establishes which snapshot is authoritative.
    // Keeping this trigger here prevents status, URL, and reconnect handlers from
    // each issuing a competing graph request.
    useEffect(() => {
        if (!snapshotReady) return;
        void refetchGraphData(activeSimulation?.id ?? null).catch((): void => undefined);
    }, [activeSimulation?.id, snapshotReady, designMode, refetchGraphData]);

    useEffect(() => {
        const refresh = () => { void refetchGraphData(activeSimulation?.id ?? null).catch(console.warn); };
        window.addEventListener('scenario-mutated', refresh);
        return () => window.removeEventListener('scenario-mutated', refresh);
    }, [activeSimulation?.id, refetchGraphData]);

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
        void refetchGraphData(activeSimulation?.id ?? null).catch((): void => undefined);
    }, [activeSimulation?.id, connected, refetchGraphData, snapshotReady]);

    return { graphData, loading, error, refetchGraphData };
};
