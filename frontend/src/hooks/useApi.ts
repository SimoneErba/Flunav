import { useMemo } from 'react';
import {
    GraphApi,
    ItemControllerApi,
    LocationControllerApi,
    PositionsApi,
    SimulationsApi,
    ConveyorsApi,
    Configuration,
} from '../api-client';
import { useSimulationContext } from '../context/simulation.context';
import { axiosInstance, CLIENT_ID } from '../api/config';

export const useApi = () => {
    const { activeSimulation } = useSimulationContext();

    const apiConfig = useMemo(() => {
        // clone headers to avoid mutation bugs
        const headers: Record<string, string> = {
            ...axiosInstance.defaults.headers.common,
        };

        if (activeSimulation?.id) {
            headers['X-Simulation-ID'] = activeSimulation.id;
        }

        return new Configuration({
            basePath: axiosInstance.defaults.baseURL,
            baseOptions: {
                headers,
            },
        });
    }, [activeSimulation?.id]);

    const graphApi = useMemo(() => new GraphApi(apiConfig), [apiConfig]);
    const itemApi = useMemo(() => new ItemControllerApi(apiConfig), [apiConfig]);
    const locationApi = useMemo(() => new LocationControllerApi(apiConfig), [apiConfig]);
    const positionsApi = useMemo(() => new PositionsApi(apiConfig), [apiConfig]);
    const simulationApi = useMemo(() => new SimulationsApi(apiConfig), [apiConfig]);
    const conveyorsApi = useMemo(() => new ConveyorsApi(apiConfig), [apiConfig]);

    return {
        graphApi,
        itemApi,
        locationApi,
        positionsApi,
        simulationApi,
        conveyorsApi,
        clientId: CLIENT_ID,
    };
};
