import { useState, useEffect, useCallback } from 'react';
import { useApi } from './useApi';
import { GraphData } from "../api-client/api";
import { useWebSocketConnection } from './websocket/useWebSocketConnection';

const emptyGraphData: GraphData = {
    locations: [],
    conveyors: []
};

export const useGraph = () => {
    const { simulationApi, graphApi } = useApi();
    const { connected } = useWebSocketConnection();
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [graphData, setGraphData] = useState<GraphData>(emptyGraphData);

    const refetchGraphData = useCallback(async (simulationId: string | undefined = undefined) => {
        try {
            console.log("Refetching graph data...");
            setLoading(true);
            let response;
            if (simulationId) {
                response = await simulationApi.getSimulationGraphData(simulationId);
            } else {
                response = await graphApi.getGraphData();
            }
            if (response?.data) {
                setGraphData(response.data);
                setError(null);
            } else {
                throw new Error('Invalid response data');
            }
        } catch (err) {
            setError('Failed to fetch graph data');
            console.error('Error fetching graph data:', err);
            setGraphData(emptyGraphData);
        } finally {
            setLoading(false);
        }
    }, [graphApi]);

    useEffect(() => {
        refetchGraphData();
    }, [refetchGraphData]); 

    return {
        graphData,
        loading,
        error,
        connected,
        refetchGraphData
    };
}; 