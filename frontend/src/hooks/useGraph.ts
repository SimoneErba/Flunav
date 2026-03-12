import { useState, useCallback, useEffect } from 'react';
import { GraphData, GraphApi, Configuration, DisplayRuleColorResult } from '../api-client';
import { useApi } from './useApi';
import { baseURL } from '../api/config';
import { axiosInstance } from '../api/axiosInstance';

export const useGraph = () => {
    const { graphApi, clientId } = useApi(); // Default API from context
    const [graphData, setGraphData] = useState<GraphData | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<Error | null>(null);

    /**
     * Fetches graph data.
     * @param simulationIdOverride 
     *  - undefined: Use current Context (Standard)
     *  - null: Force Live (No header)
     *  - string: Force specific Simulation ID
     */
    const refetchGraphData = useCallback(async (simulationIdOverride?: string | null) => {
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

            const response = await api.getGraphData();
            // Ensure we set the timestamp if missing (fallback)
            const data = response.data;
            if (!data.timestamp) {
                data.timestamp = new Date().toISOString();
            }
            
            setGraphData(data);
            setError(null);
        } catch (err) {
            console.error("Failed to fetch graph data", err);
            setError(err as Error);
            throw err;
        } finally {
            setLoading(false);
        }
    }, [graphApi, clientId]);

    // Initial load (uses default context)
    useEffect(() => {
        refetchGraphData();
    }, [refetchGraphData]);

    return { graphData, loading, error, refetchGraphData };
};