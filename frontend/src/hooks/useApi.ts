import { useMemo } from 'react';
import {
    GraphApi,
    ItemControllerApi,
    LocationControllerApi,
    PositionsApi,
    SimulationsApi,
    ConveyorsApi,
    Configuration,
    DisplayRulesControllerApi,
    AuthControllerApi,
    UserControllerApi,
    AnalyticsControllerApi
} from '../api-client';
import { useSimulationContext } from '../context/simulation.context';
import { baseURL, CLIENT_ID } from '../api/config';
import { useAuth } from '../context/auth.context';
import { axiosInstance } from '../api/axiosInstance'; 

export const useApi = () => {
    const { activeSimulation } = useSimulationContext();

    const apiConfig = useMemo(() => {
        const headers: Record<string, string> = {
            'X-Sender-ID': CLIENT_ID
        };

        if (activeSimulation?.id) {
            headers['X-Simulation-ID'] = activeSimulation.id;
        }

        return new Configuration({
            basePath: baseURL,
            baseOptions: { headers },
        });
    }, [activeSimulation?.id]);
    
    const graphApi = useMemo(() => new GraphApi(apiConfig, undefined, axiosInstance), [apiConfig]);
    const itemApi = useMemo(() => new ItemControllerApi(apiConfig, undefined, axiosInstance), [apiConfig]);
    const locationApi = useMemo(() => new LocationControllerApi(apiConfig, undefined, axiosInstance), [apiConfig]);
    const positionsApi = useMemo(() => new PositionsApi(apiConfig, undefined, axiosInstance), [apiConfig]);
    const simulationApi = useMemo(() => new SimulationsApi(apiConfig, undefined, axiosInstance), [apiConfig]);
    const conveyorsApi = useMemo(() => new ConveyorsApi(apiConfig, undefined, axiosInstance), [apiConfig]);
    const displayRuleApi = useMemo(() => new DisplayRulesControllerApi(apiConfig, undefined, axiosInstance), [apiConfig]);
    const authApi = useMemo(() => new AuthControllerApi(apiConfig, undefined), [apiConfig]);
    const userApi = useMemo(() => new UserControllerApi(apiConfig, undefined, axiosInstance), [apiConfig]);
    const analyticsApi = useMemo(() => new AnalyticsControllerApi(apiConfig, undefined, axiosInstance), [apiConfig]);

    return {
        graphApi,
        itemApi,
        locationApi,
        positionsApi,
        simulationApi,
        conveyorsApi,
        displayRuleApi,
        authApi,
        userApi,
        analyticsApi,
        clientId: CLIENT_ID,
    };
};