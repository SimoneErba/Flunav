import { useEffect, useRef, useCallback } from 'react';
import { Client, Message, StompSubscription } from '@stomp/stompjs';
import { v4 as uuidv4 } from 'uuid';

export enum CrudOperation {
    CREATED = 'CREATED',
    DELETED = 'DELETED',
}

export enum PositionStatus {
    UPDATED = 'UPDATED',
    LOST = 'LOST',
}

export interface PositionUpdate {
    itemId: string;
    locationId: string | null;
    status: PositionStatus;
}

export interface SimulationStatusUpdate {
    status: string;
}

export interface EntityMessage<T> {
    operation: CrudOperation;
    data: T;
}

export interface EntityUpdate {
    [key: string]: any;
}

export interface ConnectionMessage {
    from: string;
    to: string;
    operation: CrudOperation;
}

export interface ItemData {
    id: string;
    [key: string]: any;
}

export interface LocationData {
    id: string;
    [key: string]: any;
}


type Handler = (message: any) => void;

interface SubscriptionManager {
    subscription: StompSubscription;
    handlers: Map<string, Handler>;
}


export const useWebSocket = () => {
    const client = useRef<Client | null>(null);
    const subscriptions = useRef<Map<string, SubscriptionManager>>(new Map());

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

        client.current.onConnect = (frame) => console.log('%cSTOMP Connected', 'color: green', frame);
        client.current.onStompError = (frame) => console.error('%cSTOMP Error', 'color: red', frame);
        client.current.onWebSocketClose = (event) => console.warn('%cWebSocket Closed', 'color: orange', event);
        client.current.onWebSocketError = (event) => console.error('%cWebSocket Error', 'color: red', event);

        client.current.activate();
    }, []);

    /**
     * A generic, underlying subscribe method that manages shared subscriptions.
     */
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
                    subscriptions.current.get(topic)?.handlers.forEach(h => {
                        try {
                            h(JSON.parse(message.body));
                        } catch (e) {
                            console.error(`Failed to parse message on topic ${topic}`, e);
                        }
                    });
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

    const subscribeToSimulationStatus = useCallback((
        simulationId: string,
        handler: (update: SimulationStatusUpdate) => void
    ) => {
        const topic = `/topic/simulation-status/${simulationId}`;
        return subscribe(topic, handler);
    }, [subscribe]);

    const subscribeToPositionUpdates = useCallback((
        handler: (update: PositionUpdate) => void,
        simulationId?: string | null
    ) => {
        const topic = buildTopic('positions', simulationId);
        return subscribe(topic, handler);
    }, [subscribe]);

    const subscribeToItemCreated = useCallback((
        handler: (item: ItemData) => void,
        simulationId?: string | null
    ) => {
        const topic = buildTopic('items', simulationId);
        const filteredHandler = (message: EntityMessage<ItemData>) => {
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
        const filteredHandler = (message: EntityMessage<string>) => {
            if (message.operation === CrudOperation.DELETED) {
                handler(message.data);
            }
        };
        return subscribe(topic, filteredHandler);
    }, [subscribe]);

    const subscribeToItemUpdates = useCallback((
        itemId: string,
        handler: (update: EntityUpdate) => void,
        simulationId?: string | null
    ) => {
        const topic = buildTopic(`items/${itemId}`, simulationId);
        return subscribe(topic, handler);
    }, [subscribe]);

    const subscribeToLocationCreated = useCallback((
        handler: (location: LocationData) => void,
        simulationId?: string | null
    ) => {
        const topic = buildTopic('locations', simulationId);
        const filteredHandler = (message: EntityMessage<LocationData>) => {
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
        const filteredHandler = (message: EntityMessage<string>) => {
            if (message.operation === CrudOperation.DELETED) {
                handler(message.data);
            }
        };
        return subscribe(topic, filteredHandler);
    }, [subscribe]);

    const subscribeToLocationUpdates = useCallback((
        locationId: string,
        handler: (update: EntityUpdate) => void,
        simulationId?: string | null
    ) => {
        const topic = buildTopic(`locations/${locationId}`, simulationId);
        return subscribe(topic, handler);
    }, [subscribe]);

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

    useEffect(() => {
        connect();
        return () => {
            console.log("Deactivating WebSocket client and clearing all subscriptions.");
            subscriptions.current.forEach(manager => manager.subscription.unsubscribe());
            subscriptions.current.clear();
            client.current?.deactivate();
        };
    }, [connect]);

    return {
        connected: client.current?.connected ?? false,
        subscribeToSimulationStatus,
        subscribeToPositionUpdates,
        subscribeToItemCreated,
        subscribeToItemDeleted,
        subscribeToItemUpdates,
        subscribeToLocationCreated,
        subscribeToLocationDeleted,
        subscribeToLocationUpdates,
        subscribeToConnectionCreated,
        subscribeToConnectionDeleted,
    };
};