import { useMemo } from 'react';
import { 
    GraphApi, ItemControllerApi, LocationControllerApi, 
    PositionsApi, SimulationsApi, ConveyorsApi, Configuration 
} from '../api-client';
import { CLIENT_ID } from '../api/config';
import { useSimulationContext } from '../context/simulation.context'; // <--- Import

export const useApi = () => {
    // 1. Get the active simulation ID
    const { activeSimulation } = useSimulationContext();

    // 2. Re-create config whenever simulationId changes
    const apiConfig = useMemo(() => {
        const headers: Record<string, string> = {
            'X-Sender-ID': CLIENT_ID
        };

        // Inject Simulation ID if active
        if (activeSimulation) {
            headers['X-Simulation-ID'] = activeSimulation.id as string;
        }

        return new Configuration({
            basePath: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
            baseOptions: { headers }
        });
    }, [activeSimulation?.id]); // Re-run when ID changes

    // 3. Instantiate APIs
    const graphApi = useMemo(() => new GraphApi(apiConfig), [apiConfig]);
    const itemApi = useMemo(() => new ItemControllerApi(apiConfig), [apiConfig]);
    const locationApi = useMemo(() => new LocationControllerApi(apiConfig), [apiConfig]);
    const positionsApi = useMemo(() => new PositionsApi(apiConfig), [apiConfig]);
    const simulationApi = useMemo(() => new SimulationsApi(apiConfig), [apiConfig]);
    const conveyorsApi = useMemo(() => new ConveyorsApi(apiConfig), [apiConfig]);

    return { 
        graphApi, itemApi, locationApi, positionsApi, simulationApi, conveyorsApi, clientId: CLIENT_ID 
    };
};