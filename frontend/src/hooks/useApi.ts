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
    UserControllerApi
} from '../api-client';
import { useSimulationContext } from '../context/simulation.context';
import { baseURL, CLIENT_ID } from '../api/config';
import { useAuth } from '../context/auth.context';

export const useApi = () => {
    const { activeSimulation } = useSimulationContext();
    const { token } = useAuth();
    
    const apiConfig = useMemo(() => {
        const headers: Record<string, string> = {
            'X-Sender-ID': CLIENT_ID
        };

        if (activeSimulation?.id) {
            headers['X-Simulation-ID'] = activeSimulation.id;
        }

        if (token) {
            headers['Authorization'] = `Bearer ${token}`;
        }

        return new Configuration({
            basePath: baseURL,
            baseOptions: {
                headers,
            },
        });
    }, [activeSimulation?.id, token]);

    const graphApi = useMemo(() => new GraphApi(apiConfig), [apiConfig]);
    const itemApi = useMemo(() => new ItemControllerApi(apiConfig), [apiConfig]);
    const locationApi = useMemo(() => new LocationControllerApi(apiConfig), [apiConfig]);
    const positionsApi = useMemo(() => new PositionsApi(apiConfig), [apiConfig]);
    const simulationApi = useMemo(() => new SimulationsApi(apiConfig), [apiConfig]);
    const conveyorsApi = useMemo(() => new ConveyorsApi(apiConfig), [apiConfig]);
    const displayRuleApi = useMemo(() => new DisplayRulesControllerApi(apiConfig), [apiConfig]);
    const authApi = useMemo(() => new AuthControllerApi(apiConfig), [apiConfig]);
    const usersApi = useMemo(() => new UserControllerApi(apiConfig), [apiConfig]);

    return {
        graphApi,
        itemApi,
        locationApi,
        positionsApi,
        simulationApi,
        conveyorsApi,
        displayRuleApi,
        authApi,
        usersApi,
        clientId: CLIENT_ID,
    };
};
