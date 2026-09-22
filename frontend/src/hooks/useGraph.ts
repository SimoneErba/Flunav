import { useState, useCallback, useEffect, useRef } from 'react';
import { GraphData, GraphApi, Configuration } from '../api-client';
import { useApi } from './useApi';
import { baseURL } from '../api/config';
import { axiosInstance } from '../api/axiosInstance';
import { useSimulationContext } from '../context/simulation.context';
import { useWebSocketConnection } from './websocket/useWebSocketConnection';

export const useGraph = () => {
    const { designMode } = useSimulationContext();
    const { connected } = useWebSocketConnection();
    const { graphApi, clientId } = useApi(); // Default API from context
    const [graphData, setGraphData] = useState<GraphData | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<Error | null>(null);
    const hasLoadedInitialGraph = useRef(false);
    const wasConnected = useRef(connected);
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

    // Simulation graph loads are requested explicitly after the build reaches READY.
    // A context change while it is still building must not be treated as an API outage.
    useEffect(() => {
        if (hasLoadedInitialGraph.current) return;
        hasLoadedInitialGraph.current = true;
        void refetchGraphData().catch(() => undefined);
    }, [refetchGraphData]);

    useEffect(() => {
        const reconnected = connected && !wasConnected.current;
        wasConnected.current = connected;
        if (!reconnected) return;
        // A socket reconnect can leave a short interval of missed entity events;
        // reconcile from the authoritative graph snapshot before live updates resume.
        void refetchGraphData().catch(() => undefined);
    }, [connected, refetchGraphData]);

    return { graphData, loading, error, refetchGraphData };
};
