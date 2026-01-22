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

export const SimulationProvider = ({ children }: { children: ReactNode }) => {
    const [activeSimulation, setActiveSimulation] = useState<SimulationStateResponse | null>(null);

    return (
        <SimulationContext.Provider value={{ activeSimulation, setActiveSimulation }}>
            {children}
        </SimulationContext.Provider>
    );
};