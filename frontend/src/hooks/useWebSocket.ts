import { useEffect, useRef, useCallback, useState } from 'react';
import { Client, Message, StompSubscription } from '@stomp/stompjs';
import { v4 as uuidv4 } from 'uuid';

// --- Import DTOs ---
// We define the interfaces here or import them. 
// I am updating them here to match your Java Backend exactly.

import {
    ConnectionMessage,
    CrudOperation,
    EntityUpdateMessage,
    ItemCreatedMessage,
    ItemDeletedMessage,
    LocationCreatedMessage,
    LocationDeletedMessage,
    // PositionUpdate -> Redefined below to match Java
} from '../websocket-types/websocket-types';

import { ItemInput, LocationInput } from '../api-client/api';

// --- Updated Types matching Java Backend ---

// Matches Java: record PositionUpdate(String itemId, String edgeId, long timestamp, PositionStatus status)
export interface PositionUpdate {
    itemId: string;
    edgeId: string | null; // Renamed from locationId
    timestamp: number;
    status: 'UPDATED' | 'LOST';
}

export interface SimulationStatusUpdate {
    status: string;
}

export interface SimulationSpeedUpdate {
    speed: number;
}

type Handler = (message: any) => void;

interface SubscriptionManager {
    subscription: StompSubscription;
    handlers: Map<string, Handler>;
}

export const useWebSocket = () => {
    const client = useRef<Client | null>(null);
    const subscriptions = useRef<Map<string, SubscriptionManager>>(new Map());
    const [connected, setConnected] = useState(false);

    // Helper to build topic path based on simulation ID
    const buildTopic = (baseTopic: string, simulationId?: string | null): string => {
        return simulationId
            ? `/topic/simulations/${simulationId}/${baseTopic}`
            : `/topic/${baseTopic}`;
    };

    const connect = useCallback(() => {
        client.current = new Client({
            brokerURL: 'ws://localhost:8080/ws/websocket',
            debug: (str: string) => console.log('%cSTOMP:', 'color: blue', str),
            reconnectDelay: 5000,
            heartbeatIncoming: 4000,
            heartbeatOutgoing: 4000,
        });

        client.current.onConnect = (frame) => {
            console.log('%cSTOMP Connected', 'color: green', frame);
            setConnected(true);
        };
        client.current.onStompError = (frame) => console.error('%cSTOMP Error', 'color: red', frame);
        client.current.onWebSocketClose = (event) => {
            console.warn('%cWebSocket Closed', 'color: orange', event);
            setConnected(false);
        };
        client.current.onWebSocketError = (event) => console.error('%cWebSocket Error', 'color: red', event);

        client.current.activate();
    }, []);

    const subscribe = useCallback((topic: string, handler: Handler): (() => void) => {
        if (!client.current?.connected) {
            console.warn(`WebSocket not connected, cannot subscribe to ${topic}.`);
            return () => {};
        }

        const handlerId = uuidv4();
        const existingManager = subscriptions.current.get(topic);

        if (existingManager) {
            existingManager.handlers.set(handlerId, handler);
        } else {
            const newManager: SubscriptionManager = {
                subscription: client.current.subscribe(topic, (message: Message) => {
                    const parsedBody = JSON.parse(message.body);
                    subscriptions.current.get(topic)?.handlers.forEach(h => h(parsedBody));
                }),
                handlers: new Map([[handlerId, handler]]),
            };
            subscriptions.current.set(topic, newManager);
        }

        return () => {
            const manager = subscriptions.current.get(topic);
            if (manager) {
                manager.handlers.delete(handlerId);
                if (manager.handlers.size === 0) {
                    console.log(`Unsubscribing from topic: ${topic}`);
                    manager.subscription.unsubscribe();
                    subscriptions.current.delete(topic);
                }
            }
        };
    }, []);

    // --- SUBSCRIPTION METHODS ---

    // 1. Simulation Status & Speed
    const subscribeToSimulationStatus = useCallback((
        simulationId: string,
        handler: (update: SimulationStatusUpdate) => void
    ) => {
        const topic = `/topic/simulation-status/${simulationId}`;
        return subscribe(topic, handler);
    }, [subscribe]);

    const subscribeToSimulationSpeed = useCallback((
        simulationId: string,
        handler: (update: SimulationSpeedUpdate) => void
    ) => {
        const topic = `/topic/simulation-speed/${simulationId}`;
        return subscribe(topic, handler);
    }, [subscribe]);

    // 2. Positions (Physics)
    const subscribeToPositionUpdates = useCallback((
        handler: (update: PositionUpdate) => void,
        simulationId?: string | null
    ) => {
        const topic = buildTopic('positions', simulationId);
        return subscribe(topic, handler);
    }, [subscribe]);

    // 3. Items
    const subscribeToItemCreated = useCallback((
        handler: (item: ItemInput) => void,
        simulationId?: string | null
    ) => {
        const topic = buildTopic('items', simulationId);
        const filteredHandler = (message: ItemCreatedMessage) => {
            if (message.operation === CrudOperation.CREATED) {
                handler(message.data);
            }
        };
        return subscribe(topic, filteredHandler);
    }, [subscribe]);

    const subscribeToItemDeleted = useCallback((
        handler: (itemId: string) => void,
        simulationId?: string | null
    ) => {
        const topic = buildTopic('items', simulationId);
        const filteredHandler = (message: ItemDeletedMessage) => {
            if (message.operation === CrudOperation.DELETED) {
                handler(message.data);
            }
        };
        return subscribe(topic, filteredHandler);
    }, [subscribe]);
    
    const subscribeToAllItemUpdates = useCallback((
        handler: (update: EntityUpdateMessage) => void,
        simulationId?: string | null
    ) => {
        const topic = buildTopic('items/updates', simulationId);
        return subscribe(topic, handler);
    }, [subscribe]);

    // 4. Locations (Nodes)
    const subscribeToLocationCreated = useCallback((
        handler: (location: LocationInput) => void,
        simulationId?: string | null
    ) => {
        const topic = buildTopic('locations', simulationId);
        const filteredHandler = (message: LocationCreatedMessage) => {
            if (message.operation === CrudOperation.CREATED) {
                handler(message.data);
            }
        };
        return subscribe(topic, filteredHandler);
    }, [subscribe]);

    const subscribeToLocationDeleted = useCallback((
        handler: (locationId: string) => void,
        simulationId?: string | null
    ) => {
        const topic = buildTopic('locations', simulationId);
        const filteredHandler = (message: LocationDeletedMessage) => {
            if (message.operation === CrudOperation.DELETED) {
                handler(message.data);
            }
        };
        return subscribe(topic, filteredHandler);
    }, [subscribe]);

    const subscribeToAllLocationUpdates = useCallback((
        handler: (update: EntityUpdateMessage) => void,
        simulationId?: string | null
    ) => {
        const topic = buildTopic('locations/updates', simulationId);
        return subscribe(topic, handler);
    }, [subscribe]);

    // 5. Connections (Conveyors)
    const subscribeToConnectionCreated = useCallback((
        handler: (message: ConnectionMessage) => void,
        simulationId?: string | null
    ) => {
        const topic = buildTopic('connections', simulationId);
        const filteredHandler = (message: ConnectionMessage) => {
            if (message.operation === CrudOperation.CREATED) {
                handler(message);
            }
        };
        return subscribe(topic, filteredHandler);
    }, [subscribe]);

    const subscribeToConnectionDeleted = useCallback((
        handler: (message: ConnectionMessage) => void,
        simulationId?: string | null
    ) => {
        const topic = buildTopic('connections', simulationId);
        const filteredHandler = (message: ConnectionMessage) => {
            if (message.operation === CrudOperation.DELETED) {
                handler(message);
            }
        };
        return subscribe(topic, filteredHandler);
    }, [subscribe]);

    // --- NEW: Added this to match backend 'connections/updates' ---
    const subscribeToConnectionUpdated = useCallback((
        handler: (update: EntityUpdateMessage) => void,
        simulationId?: string | null
    ) => {
        const topic = buildTopic('connections/updates', simulationId);
        return subscribe(topic, handler);
    }, [subscribe]);

    useEffect(() => {
        connect();
        return () => {
            console.log("Deactivating WebSocket client.");
            client.current?.deactivate();
        };
    }, [connect]);

    return {
        connected,
        subscribeToSimulationStatus,
        subscribeToSimulationSpeed, // Exported new function
        subscribeToPositionUpdates,
        subscribeToItemCreated,
        subscribeToItemDeleted,
        subscribeToAllItemUpdates,
        subscribeToLocationCreated,
        subscribeToLocationDeleted,
        subscribeToAllLocationUpdates,
        subscribeToConnectionCreated,
        subscribeToConnectionDeleted,
        subscribeToConnectionUpdated, // Exported new function
    };
};