/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, useState, ReactNode } from 'react';
import { SimulationStateResponse } from '../api-client';

interface SimulationContextType {
    activeSimulation: SimulationStateResponse | null;
    setActiveSimulation: (sim: SimulationStateResponse | null) => void;
}

const SimulationContext = createContext<SimulationContextType>({
    activeSimulation: null,
    setActiveSimulation: () => {},
});

export const useSimulationContext = () => useContext(SimulationContext);

/**
 * Stores the currently selected simulation for components outside the graph tree.
 * Graph rendering still receives the simulation id explicitly so websocket and API
 * calls can choose live or simulation topics without reading hidden globals.
 */
export const SimulationProvider = ({ children }: { children: ReactNode }) => {
    const [activeSimulation, setActiveSimulation] = useState<SimulationStateResponse | null>(null);

    return (
        <SimulationContext.Provider value={{ activeSimulation, setActiveSimulation }}>
            {children}
        </SimulationContext.Provider>
    );
};
