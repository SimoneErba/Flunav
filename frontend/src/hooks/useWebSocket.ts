import { useEffect, useRef, useCallback } from 'react';
import { Client, Message, StompSubscription } from '@stomp/stompjs';
export interface PositionUpdate {
    itemId: string;
    locationId: string | null;
}

export interface NodeUpdate {
    id: string;
    properties: Record<string, unknown>;
}

export const useWebSocket = () => {
    const client = useRef<Client | null>(null);
    const subscriptions = useRef<Map<string, StompSubscription>>(new Map());

    const connect = useCallback(() => {
        client.current = new Client({
            brokerURL: 'ws://localhost:8080/ws/websocket',
            debug: (str: string) => {
                console.log('%cSTOMP Debug:', 'color: blue; font-weight: bold;', str);
            },
            reconnectDelay: 5000,
            heartbeatIncoming: 4000,
            heartbeatOutgoing: 4000,
        });
    
        client.current.onConnect = (frame) => {
            console.log('%cSTOMP Connected:', 'color: green; font-weight: bold;', frame);
        };
    
        client.current.onStompError = (frame) => {
            console.error('%cSTOMP Protocol Error:', 'color: red; font-weight: bold;', frame);
        };
    
        client.current.onWebSocketClose = (event) => {
            console.warn('%cWebSocket Closed:', 'color: orange; font-weight: bold;', event);
        };
    
        client.current.onWebSocketError = (event) => {
            console.error('%cWebSocket Error:', 'color: red; font-weight: bold;', event);
        };
    
        client.current.activate();
    }, []);

    const subscribeToPositionUpdates = useCallback((handler: (update: PositionUpdate) => void) => {
        if (!client.current?.connected) {
            console.warn('WebSocket not connected');
            return () => {};
        }

        const subscription = client.current.subscribe('/topic/positions', (message: Message) => {
            const update: PositionUpdate = JSON.parse(message.body);
            handler(update);
        });

        subscriptions.current.set('positions', subscription);

        return () => {
            subscription.unsubscribe();
            subscriptions.current.delete('positions');
        };
    }, []);

    const subscribeToNodeUpdates = useCallback((handler: (update: NodeUpdate) => void) => {
        if (!client.current?.connected) {
            console.warn('WebSocket not connected');
            return () => {};
        }

        const subscription = client.current.subscribe('/topic/nodes/*', (message: Message) => {
            const update: NodeUpdate = JSON.parse(message.body);
            handler(update);
        });

        subscriptions.current.set('nodes', subscription);

        return () => {
            subscription.unsubscribe();
            subscriptions.current.delete('nodes');
        };
    }, []);

    const subscribeToSimulationStatus = useCallback((
        simulationId: string,
        handler: (update: any) => void
    ) => {
        if (!client.current?.connected) {
            console.warn('WebSocket not connected, cannot subscribe to simulation status.');
            return () => {};
        }


        const topic = `/topic/simulation-status/${simulationId}`;
        console.log(`Subscribing to user-specific topic: ${topic}`);

        const subscription = client.current.subscribe(topic, (message: Message) => {
            try {
                const update = JSON.parse(message.body);
                handler(update);
            } catch (e) {
                console.error("Failed to parse simulation status update", e);
            }
        });

        const subKey = `simulation-${simulationId}`;
        subscriptions.current.set(subKey, subscription);

        // Funzione di cleanup per annullare la sottoscrizione
        return () => {
            console.log(`Unsubscribing from ${topic}`);
            subscription.unsubscribe();
            subscriptions.current.delete(subKey);
        };
    }, []);

    useEffect(() => {
        connect();
        return () => {
            subscriptions.current.forEach(subscription => subscription.unsubscribe());
            subscriptions.current.clear();
            client.current?.deactivate();
        };
    }, [connect]);

    return {
        connected: client.current?.connected ?? false,
        subscribeToPositionUpdates,
        subscribeToNodeUpdates,
        subscribeToSimulationStatus
    };
}; 