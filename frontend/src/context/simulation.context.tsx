/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, useState, useRef, useCallback, useEffect, ReactNode, Dispatch, SetStateAction } from 'react';
import toast from 'react-hot-toast';
import { Configuration, SimulationsApi, SimulationStateResponse } from '../api-client';
import { axiosInstance } from '../api/axiosInstance';
import { baseURL, CLIENT_ID } from '../api/config';

interface SimulationContextType {
    activeSimulation: SimulationStateResponse | null;
    setActiveSimulation: Dispatch<SetStateAction<SimulationStateResponse | null>>;
    designMode: boolean;
    setDesignMode: (enabled: boolean) => void;
    isBranching: boolean;
    isExitingWhatIf: boolean;
    enterWhatIf: () => Promise<SimulationStateResponse>;
    exitWhatIf: () => Promise<void>;
}

const SimulationContext = createContext<SimulationContextType | null>(null);
const simulationApi = new SimulationsApi(new Configuration({ basePath: baseURL }), undefined, axiosInstance);

export const useSimulationContext = () => {
    const context = useContext(SimulationContext);
    if (!context) throw new Error('SimulationProvider is required');
    return context;
};

const isWhatIf = (simulation: SimulationStateResponse | null) =>
    simulation?.kind === 'WHAT_IF_LIVE' || simulation?.kind === 'WHAT_IF_SIMULATION';

const isOperationalCommand = (url?: string) =>
    /\/api\/conveyors\/[^/?]+\/(activate|deactivate|release)(?:\?|$)/.test(url ?? '') ||
    /\/api\/locations\/[^/?]+\/empty(?:\?|$)/.test(url ?? '');

const isGraphMutation = (method?: string, url?: string) =>
    !['get', 'head', 'options'].includes((method ?? 'get').toLowerCase()) &&
    !isOperationalCommand(url) &&
    /\/api\/(items|locations|conveyors|positions|graph\/import)(\/|$|\?)/.test(url ?? '');

/** One shared transition guards every graph mutation, including edits from controls outside the graph. */
export const SimulationProvider = ({ children }: { children: ReactNode }) => {
    const [activeSimulation, updateActiveSimulation] = useState<SimulationStateResponse | null>(null);
    const [designMode, updateDesignMode] = useState(false);
    const [isBranching, setIsBranching] = useState(false);
    const [isExitingWhatIf, setIsExitingWhatIf] = useState(false);
    const activeRef = useRef(activeSimulation);
    const designRef = useRef(false);
    const pendingFork = useRef<Promise<SimulationStateResponse> | null>(null);

    const setActiveSimulation: Dispatch<SetStateAction<SimulationStateResponse | null>> = useCallback(value => {
        const next = typeof value === 'function' ? value(activeRef.current) : value;
        activeRef.current = next;
        updateActiveSimulation(next);
    }, []);

    const setDesignMode = useCallback((enabled: boolean) => {
        if (enabled && activeRef.current) return;
        designRef.current = enabled;
        updateDesignMode(enabled);
    }, []);

    const enterWhatIf = useCallback(async () => {
        if (isWhatIf(activeRef.current)) return activeRef.current!;
        if (pendingFork.current) return pendingFork.current;
        setIsBranching(true);
        const notification = toast.loading('Going into What If...');
        pendingFork.current = (async () => {
            const response = await simulationApi.createWhatIf({
                sourceSimulationId: activeRef.current?.id,
            });
            let branch = response.data;
            while (branch.status === 'BUILDING' || branch.status === 'QUEUED') {
                await new Promise(resolve => setTimeout(resolve, 150));
                branch = (await simulationApi.getSimulationStatus(branch.id!)).data;
            }
            if (branch.status !== 'PAUSED' && branch.status !== 'READY') {
                throw new Error('What-if creation failed');
            }
            setDesignMode(false);
            setActiveSimulation(branch);
            return branch;
        })();
        try {
            return await pendingFork.current;
        } catch (error) {
            toast.error('Could not create What If. Your edit was not applied.');
            throw error;
        } finally {
            pendingFork.current = null;
            setIsBranching(false);
            toast.dismiss(notification);
        }
    }, [setActiveSimulation, setDesignMode]);

    const exitWhatIf = useCallback(async () => {
        const branch = activeRef.current;
        if (!isWhatIf(branch)) return;
        setIsExitingWhatIf(true);
        try {
            await simulationApi.destroySimulation(branch!.id!);
            const source = branch!.sourceSimulationId
                ? (await simulationApi.getSimulationStatus(branch!.sourceSimulationId)).data : null;
            setActiveSimulation(source);
        } finally {
            setIsExitingWhatIf(false);
        }
    }, [setActiveSimulation]);

    useEffect(() => {
        const request = axiosInstance.interceptors.request.use(async config => {
            if (!isGraphMutation(config.method, config.url)) return config;
            config.headers.set('X-Sender-ID', CLIENT_ID);
            if (designRef.current && /\/api\/(locations|conveyors)(\/|$|\?)/.test(config.url ?? '')
                    && !/\/empty(?:\?|$)/.test(config.url ?? '')) {
                config.headers.delete('X-Simulation-ID');
                return config;
            }
            const branch = await enterWhatIf();
            config.headers.set('X-Simulation-ID', branch.id!);
            return config;
        });
        const response = axiosInstance.interceptors.response.use(result => {
            if (isGraphMutation(result.config.method, result.config.url)) {
                window.dispatchEvent(new Event('scenario-mutated'));
            }
            return result;
        });
        return () => {
            axiosInstance.interceptors.request.eject(request);
            axiosInstance.interceptors.response.eject(response);
        };
    }, [enterWhatIf]);

    return (
        <SimulationContext.Provider value={{ activeSimulation, setActiveSimulation, designMode, setDesignMode,
            isBranching, isExitingWhatIf, enterWhatIf, exitWhatIf }}>
            {children}
        </SimulationContext.Provider>
    );
};
