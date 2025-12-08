import { useEffect, useRef, useCallback, useState } from 'react';
import { Client, Message, StompSubscription } from '@stomp/stompjs';
import { v4 as uuidv4 } from 'uuid';
import { useApi } from '../useApi';
import { SocketEnvelope } from '../../types/WebsocketTypes';

type GenericHandler = (data: any) => void;

interface SubscriptionManager {
    subscription: StompSubscription;
    handlers: Map<string, GenericHandler>;
}

export const useWebSocketConnection = () => {
    const client = useRef<Client | null>(null);
    const subscriptions = useRef<Map<string, SubscriptionManager>>(new Map());
    const [connected, setConnected] = useState(false);
    const { clientId } = useApi();

    const connect = useCallback(() => {
        if (client.current?.active) return;

        client.current = new Client({
            brokerURL: 'ws://localhost:8080/ws/websocket',
            reconnectDelay: 5000,
            heartbeatIncoming: 4000,
            heartbeatOutgoing: 4000,
        });

        client.current.onConnect = () => setConnected(true);
        client.current.onWebSocketClose = () => setConnected(false);
        client.current.activate();
    }, []);

    const subscribe = useCallback((topic: string, handler: GenericHandler) => {
        if (!client.current?.connected) return () => {};

        const handlerId = uuidv4();
        
        // If topic not subscribed yet, create subscription
        if (!subscriptions.current.has(topic)) {
            const subscription = client.current.subscribe(topic, (message: Message) => {
                try {
                    const envelope: SocketEnvelope<any> = JSON.parse(message.body);

                    // 1. Filter Echoes
                    if (envelope.senderId === clientId) return;

                    // 2. Inject Timestamp into the payload
                    // If payload is an object, merge it. If primitive, wrap it? 
                    // Usually payload is an object (EntityMessage, PositionUpdate, etc)
                    const mergedData = { 
                        ...envelope.payload, 
                        timestamp: envelope.timestamp 
                    };

                    subscriptions.current.get(topic)?.handlers.forEach(h => h(mergedData));
                } catch (e) {
                    console.error("WS Parse Error", e);
                }
            });

            subscriptions.current.set(topic, {
                subscription,
                handlers: new Map()
            });
        }

        // Add handler
        subscriptions.current.get(topic)?.handlers.set(handlerId, handler);

        // Unsubscribe function
        return () => {
            const manager = subscriptions.current.get(topic);
            if (manager) {
                manager.handlers.delete(handlerId);
                if (manager.handlers.size === 0) {
                    manager.subscription.unsubscribe();
                    subscriptions.current.delete(topic);
                }
            }
        };
    }, [clientId]);

    useEffect(() => {
        connect();
        return () => { client.current?.deactivate(); };
    }, [connect]);

    return { connected, subscribe };
};