import { useCallback } from 'react';
import { useWebSocketConnection } from './useWebSocketConnection';
import { 
    CrudOperation, 
    EntityMessage, 
    PositionUpdate, 
    ConnectionMessage, 
    EntityUpdateMessage,
    SimulationStatusUpdate,
    SimulationSpeedUpdate,
    AnomalyNotification
} from '../../types/WebsocketTypes';
import { ItemResponse, LocationInput, ThroughputMetric } from '../../api-client/api';
import type { MultiSimulationResponse } from '../../api/multiSimulation';

const isRecord = (value: unknown): value is Record<string, unknown> =>
    typeof value === 'object' && value !== null && !Array.isArray(value);
const isEntityData = (value: unknown) => isRecord(value) && typeof value.id === 'string';
const isEntityUpdate = (message: Record<string, unknown>) =>
    typeof message.id === 'string' && isRecord(message.properties);
const isConnection = (message: Record<string, unknown>) =>
    typeof message.from === 'string' && typeof message.to === 'string';

export const useWebSocketEvents = () => {
    const { connected, subscribe: subscribeUnknown } = useWebSocketConnection();

    // Topic contracts are narrowed at the transport boundary before typed graph handlers run.
    const subscribe = useCallback(<T,>(topic: string, handler: (message: T) => void,
        valid: (message: Record<string, unknown>) => boolean) => subscribeUnknown(topic, value => {
        if (typeof value !== 'object' || value === null) return;
        const message = value as Record<string, unknown>;
        if (typeof message.timestamp !== 'number' || !Number.isFinite(message.timestamp) || !valid(message)) return;
        handler(message as T);
    }), [subscribeUnknown]);

    /**
     * Builds live or simulation topic names from the same event surface.
     * Graph hooks pass a simulation id when viewing replay state so websocket
     * updates stay isolated from live-mode topics.
     */
    const buildTopic = (base: string, simId?: string | null) => 
        simId ? `/topic/simulations/${simId}/${base}` : `/topic/${base}`;

    // 1. Simulation Control
    const subscribeToSimulationStatus = useCallback((simId: string, handler: (update: SimulationStatusUpdate & { timestamp: number }) => void) => {
        return subscribe(`/topic/simulations/${simId}/status`, handler, msg => typeof msg.status === 'string');
    }, [subscribe]);

    const subscribeToSimulationSpeed = useCallback((simId: string, handler: (update: SimulationSpeedUpdate & { timestamp: number }) => void) => {
        return subscribe(`/topic/simulations/${simId}/speed`, handler, msg => typeof msg.speed === 'number' && Number.isFinite(msg.speed));
    }, [subscribe]);

    const subscribeToMultiSimulationStatus = useCallback((
        simulationId: string,
        handler: (update: MultiSimulationResponse & { timestamp: number }) => void
    ) => {
        return subscribe(`/topic/simulations/${simulationId}/multi-simulation`, handler, msg => typeof msg.id === 'string' && typeof msg.status === 'string');
    }, [subscribe]);

    // 2. Physics (Positions)
    const subscribeToPositionUpdates = useCallback((
        handler: (update: PositionUpdate & { timestamp: number }) => void,
        simId?: string | null
    ) => {
        return subscribe(buildTopic('positions', simId), handler, msg => typeof msg.itemId === 'string' && (msg.status === 'UPDATED' || msg.status === 'LOST'));
    }, [subscribe]);

    // 3. Items
    const subscribeToItemCreated = useCallback((handler: (item: ItemResponse, timestamp: number) => void, simId?: string | null) => {
        return subscribe(buildTopic('items', simId), (msg: EntityMessage<ItemResponse> & { timestamp: number }) => {
            if (msg.operation === CrudOperation.CREATED) handler(msg.data, msg.timestamp);
        }, msg => msg.operation === CrudOperation.CREATED && isEntityData(msg.data));
    }, [subscribe]);

    const subscribeToItemDeleted = useCallback((handler: (itemId: string, timestamp: number) => void, simId?: string | null) => {
        return subscribe(buildTopic('items', simId), (msg: EntityMessage<string> & { timestamp: number }) => {
            if (msg.operation === CrudOperation.DELETED) handler(msg.data, msg.timestamp);
        }, msg => msg.operation === CrudOperation.DELETED && typeof msg.data === 'string');
    }, [subscribe]);

    const subscribeToAllItemUpdates = useCallback((handler: (update: EntityUpdateMessage & { timestamp: number }) => void, simId?: string | null) => {
        return subscribe(buildTopic('items/updates', simId), handler, isEntityUpdate);
    }, [subscribe]);

    // 4. Locations
    const subscribeToLocationCreated = useCallback((handler: (location: LocationInput, timestamp: number) => void, simId?: string | null) => {
        return subscribe(buildTopic('locations', simId), (msg: EntityMessage<LocationInput> & { timestamp: number }) => {
            if (msg.operation === CrudOperation.CREATED) handler(msg.data, msg.timestamp);
        }, msg => msg.operation === CrudOperation.CREATED && isEntityData(msg.data));
    }, [subscribe]);

    const subscribeToLocationDeleted = useCallback((handler: (locationId: string, timestamp: number) => void, simId?: string | null) => {
        return subscribe(buildTopic('locations', simId), (msg: EntityMessage<string> & { timestamp: number }) => {
            if (msg.operation === CrudOperation.DELETED) handler(msg.data, msg.timestamp);
        }, msg => msg.operation === CrudOperation.DELETED && typeof msg.data === 'string');
    }, [subscribe]);

    const subscribeToAllLocationUpdates = useCallback((handler: (update: EntityUpdateMessage) => void, simId?: string | null) => {
        return subscribe(buildTopic('locations/updates', simId), (msg: EntityUpdateMessage) => {
            handler(msg);
        }, isEntityUpdate);
    }, [subscribe]);

    const subscribeToChuteEmptied = useCallback((handler: (chuteId: string, timestamp: number) => void, simId?: string | null) => {
        return subscribe(buildTopic('locations/emptied', simId), (msg: { chuteId: string, timestamp: number }) => {
            handler(msg.chuteId, msg.timestamp);
        }, msg => typeof msg.chuteId === 'string');
    }, [subscribe]);

    // 5. Connections
    const subscribeToConnectionCreated = useCallback((handler: (message: ConnectionMessage & { timestamp: number }) => void, simId?: string | null) => {
        return subscribe(buildTopic('connections', simId), (msg: ConnectionMessage & { timestamp: number }) => {
            if (msg.operation === CrudOperation.CREATED) handler(msg);
        }, msg => msg.operation === CrudOperation.CREATED && isConnection(msg));
    }, [subscribe]);

    const subscribeToConnectionDeleted = useCallback((handler: (message: ConnectionMessage & { timestamp: number }) => void, simId?: string | null) => {
        return subscribe(buildTopic('connections', simId), (msg: ConnectionMessage & { timestamp: number }) => {
            if (msg.operation === CrudOperation.DELETED) handler(msg);
        }, msg => msg.operation === CrudOperation.DELETED && isConnection(msg));
    }, [subscribe]);

    const subscribeToConnectionUpdated = useCallback((handler: (update: EntityUpdateMessage & { timestamp: number }) => void, simId?: string | null) => {
        return subscribe(buildTopic('connections/updates', simId), handler, isEntityUpdate);
    }, [subscribe]);

    // --- 6. ANALYTICS  ---
    const subscribeToThroughputUpdates = useCallback((handler: (metric: ThroughputMetric) => void, simId?: string | null) => {
        return subscribeUnknown(buildTopic('analytics/throughput', simId), value => {
            // Throughput owns an ISO bucket timestamp, rather than an envelope timestamp.
            if (!isRecord(value) || typeof value.timestamp !== 'string') return;
            if (typeof value.itemsEntered !== 'number' || typeof value.itemsExited !== 'number') return;
            handler(value as ThroughputMetric);
        });
    }, [subscribeUnknown]);

    const subscribeToAnomalies = useCallback((handler: (notification: AnomalyNotification & { timestamp: number }) => void,
        simId?: string | null) => {
        return subscribe(buildTopic('analytics/anomalies', simId), handler, msg => typeof msg.kind === 'string' && typeof msg.virtualTimestamp === 'string');
    }, [subscribe]);

    return {
        connected,
        subscribeToSimulationStatus,
        subscribeToSimulationSpeed,
        subscribeToMultiSimulationStatus,
        subscribeToPositionUpdates,
        subscribeToItemCreated,
        subscribeToItemDeleted,
        subscribeToAllItemUpdates,
        subscribeToLocationCreated,
        subscribeToLocationDeleted,
        subscribeToAllLocationUpdates,
        subscribeToConnectionCreated,
        subscribeToConnectionDeleted,
        subscribeToConnectionUpdated,
        subscribeToThroughputUpdates,
        subscribeToAnomalies,
        subscribeToChuteEmptied
    };
};
