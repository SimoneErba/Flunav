import { useCallback } from 'react';
import { useWebSocketConnection } from './useWebSocketConnection';
import { 
    CrudOperation, 
    EntityMessage, 
    PositionUpdate, 
    ConnectionMessage, 
    EntityUpdateMessage,
    SimulationStatusUpdate,
    SimulationSpeedUpdate
} from '../../types/WebsocketTypes';
import { ItemInput, LocationInput } from '../../api-client/api';

export const useWebSocketEvents = () => {
    const { connected, subscribe } = useWebSocketConnection();

    const buildTopic = (base: string, simId?: string | null) => 
        simId ? `/topic/simulations/${simId}/${base}` : `/topic/${base}`;

    // 1. Simulation Control
    const subscribeToSimulationStatus = useCallback((simId: string, handler: (update: SimulationStatusUpdate & { timestamp: number }) => void) => {
        return subscribe(`/topic/simulations/${simId}/status`, handler);
    }, [subscribe]);

    const subscribeToSimulationSpeed = useCallback((simId: string, handler: (update: SimulationSpeedUpdate & { timestamp: number }) => void) => {
        return subscribe(`/topic/simulations/${simId}/speed`, handler);
    }, [subscribe]);

    // 2. Physics (Positions)
    const subscribeToPositionUpdates = useCallback((
        handler: (update: PositionUpdate & { timestamp: number }) => void,
        simId?: string | null
    ) => {
        return subscribe(buildTopic('positions', simId), handler);
    }, [subscribe]);

    // 3. Items
    const subscribeToItemCreated = useCallback((handler: (item: ItemInput, timestamp: number) => void, simId?: string | null) => {
        return subscribe(buildTopic('items', simId), (msg: EntityMessage<ItemInput> & { timestamp: number }) => {
            if (msg.operation === CrudOperation.CREATED) handler(msg.data, msg.timestamp);
        });
    }, [subscribe]);

    const subscribeToItemDeleted = useCallback((handler: (itemId: string, timestamp: number) => void, simId?: string | null) => {
        return subscribe(buildTopic('items', simId), (msg: EntityMessage<string> & { timestamp: number }) => {
            if (msg.operation === CrudOperation.DELETED) handler(msg.data, msg.timestamp);
        });
    }, [subscribe]);

    // RENAMED to match usage
    const subscribeToAllItemUpdates = useCallback((handler: (update: EntityUpdateMessage & { timestamp: number }) => void, simId?: string | null) => {
        return subscribe(buildTopic('items/updates', simId), handler);
    }, [subscribe]);

    // 4. Locations
    const subscribeToLocationCreated = useCallback((handler: (location: LocationInput, timestamp: number) => void, simId?: string | null) => {
        return subscribe(buildTopic('locations', simId), (msg: EntityMessage<LocationInput> & { timestamp: number }) => {
            if (msg.operation === CrudOperation.CREATED) handler(msg.data, msg.timestamp);
        });
    }, [subscribe]);

    const subscribeToLocationDeleted = useCallback((handler: (locationId: string, timestamp: number) => void, simId?: string | null) => {
        return subscribe(buildTopic('locations', simId), (msg: EntityMessage<string> & { timestamp: number }) => {
            if (msg.operation === CrudOperation.DELETED) handler(msg.data, msg.timestamp);
        });
    }, [subscribe]);

    // RENAMED to match usage
    const subscribeToAllLocationUpdates = useCallback((handler: (update: EntityUpdateMessage & { timestamp: number }) => void, simId?: string | null) => {
        return subscribe(buildTopic('locations/updates', simId), handler);
    }, [subscribe]);

    // 5. Connections
    const subscribeToConnectionCreated = useCallback((handler: (message: ConnectionMessage & { timestamp: number }) => void, simId?: string | null) => {
        return subscribe(buildTopic('connections', simId), (msg: ConnectionMessage & { timestamp: number }) => {
            if (msg.operation === CrudOperation.CREATED) handler(msg);
        });
    }, [subscribe]);

    const subscribeToConnectionDeleted = useCallback((handler: (message: ConnectionMessage & { timestamp: number }) => void, simId?: string | null) => {
        return subscribe(buildTopic('connections', simId), (msg: ConnectionMessage & { timestamp: number }) => {
            if (msg.operation === CrudOperation.DELETED) handler(msg);
        });
    }, [subscribe]);

    // RENAMED to match usage (Updated vs Updates)
    const subscribeToConnectionUpdated = useCallback((handler: (update: EntityUpdateMessage & { timestamp: number }) => void, simId?: string | null) => {
        return subscribe(buildTopic('connections/updates', simId), handler);
    }, [subscribe]);

    return {
        connected,
        subscribeToSimulationStatus,
        subscribeToSimulationSpeed,
        subscribeToPositionUpdates,
        subscribeToItemCreated,
        subscribeToItemDeleted,
        subscribeToAllItemUpdates,
        subscribeToLocationCreated,
        subscribeToLocationDeleted,
        subscribeToAllLocationUpdates,
        subscribeToConnectionCreated,
        subscribeToConnectionDeleted,
        subscribeToConnectionUpdated
    };
};