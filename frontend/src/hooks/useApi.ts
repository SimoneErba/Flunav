import { GraphApi, ItemControllerApi, LocationControllerApi, PositionsApi, SimulationsApi } from '../api-client';
import { apiConfig } from '../api/config';
import { useMemo } from 'react';

export const useApi = () => {
    const graphApi = useMemo(() => new GraphApi(apiConfig), []);
    const itemApi = useMemo(() => new ItemControllerApi(apiConfig), []);
    const locationApi = useMemo(() => new LocationControllerApi(apiConfig), []);
    const positionsApi = useMemo(() => new PositionsApi(apiConfig), []);
    const simulationApi = useMemo(() => new SimulationsApi(apiConfig), []);

    return { 
        graphApi,
        itemApi,
        locationApi,
        positionsApi,
        simulationApi
    };
};
