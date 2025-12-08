import { useMemo } from 'react';
import { 
    GraphApi, 
    ItemControllerApi, 
    LocationControllerApi, 
    PositionsApi, 
    SimulationsApi, 
    ConveyorsApi 
} from '../api-client';
import { apiConfig, CLIENT_ID } from '../api/config'; 

export const useApi = () => {
    const graphApi = useMemo(() => new GraphApi(apiConfig), []);
    const itemApi = useMemo(() => new ItemControllerApi(apiConfig), []);
    const locationApi = useMemo(() => new LocationControllerApi(apiConfig), []);
    const positionsApi = useMemo(() => new PositionsApi(apiConfig), []);
    const simulationApi = useMemo(() => new SimulationsApi(apiConfig), []);
    const conveyorsApi = useMemo(() => new ConveyorsApi(apiConfig), []);

    return { 
        graphApi,
        itemApi,
        locationApi,
        positionsApi,
        simulationApi,
        conveyorsApi,
        clientId: CLIENT_ID
    };
};