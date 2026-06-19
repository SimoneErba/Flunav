import { createContext, createElement, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { Client, Message, StompSubscription } from '@stomp/stompjs';
import { v4 as uuidv4 } from 'uuid';
import { SocketEnvelope } from '../../types/WebsocketTypes';
import { CLIENT_ID } from '../../api/config';

type GenericHandler = (data: unknown) => void;

/**
 * Resolves the websocket endpoint for local development and deployed builds.
 * Relative production URLs keep the browser connected through the same reverse
 * proxy path that served the frontend.
 */
const getWebSocketUrl = () => {
    const envUrl = import.meta.env.VITE_API_BASE_URL;

    // 1. Dev Mode (Explicit URL in .env, e.g., http://localhost:8080)
    // We replace http/https with ws/wss and append the endpoint
    if (envUrl && envUrl.startsWith('http')) {
        return envUrl.replace(/^http/, 'ws') + '/ws/websocket';
    }

    // 2. Production / Docker (Relative path or Nginx Proxy)
    // We connect to the same host/port as the browser, Nginx handles the rest.
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${protocol}//${window.location.host}/ws/websocket`;
};

interface SubscriptionManager {
    subscription: StompSubscription;
    handlers: Map<string, GenericHandler>;
}

interface WebSocketContextValue {
    connected: boolean;
    subscribe: (topic: string, handler: GenericHandler) => () => void;
}

const WebSocketContext = createContext<WebSocketContextValue | null>(null);

const useWebSocketConnectionState = (): WebSocketContextValue => {
    const client = useRef<Client | null>(null);
    const subscriptions = useRef<Map<string, SubscriptionManager>>(new Map());
    const [connected, setConnected] = useState(false);

    const connect = useCallback(() => {
        if (client.current?.active) return;
        const brokerURL = getWebSocketUrl();
        client.current = new Client({
            brokerURL: brokerURL,
            reconnectDelay: 5000,
            heartbeatIncoming: 4000,
            heartbeatOutgoing: 4000,
        });

        client.current.onConnect = () => setConnected(true);
        client.current.onWebSocketClose = () => setConnected(false);
        client.current.activate();
    }, []);

    /**
     * Multiplexes handlers for the same STOMP topic through one subscription.
     * Echo filtering and timestamp injection happen here so graph hooks consume
     * sender-filtered domain payloads instead of transport envelopes.
     */
    const subscribe = useCallback((topic: string, handler: GenericHandler) => {
        if (!client.current?.connected) return () => {};

        const handlerId = uuidv4();
        
        // If topic not subscribed yet, create subscription
        if (!subscriptions.current.has(topic)) {
            const subscription = client.current.subscribe(topic, (message: Message) => {
                try {
                    const envelope: SocketEnvelope<unknown> = JSON.parse(message.body);

                    // 1. Filter Echoes
                    if (envelope.senderId === CLIENT_ID) return;

                    const payload = envelope.payload;
                    const hasPayloadTimestamp =
                        typeof payload === 'object' &&
                        payload !== null &&
                        Object.prototype.hasOwnProperty.call(payload, 'timestamp');
                    const mergedData =
                        typeof payload === 'object' && payload !== null
                            ? {
                                ...payload,
                                timestamp: hasPayloadTimestamp
                                    ? (payload as { timestamp: unknown }).timestamp
                                    : envelope.timestamp,
                            }
                            : { value: payload, timestamp: envelope.timestamp };

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
    }, []);

    useEffect(() => {
        connect();
        const activeSubscriptions = subscriptions.current;
        return () => {
            activeSubscriptions.forEach(({ subscription }) => subscription.unsubscribe());
            activeSubscriptions.clear();
            client.current?.deactivate();
        };
    }, [connect]);

    return { connected, subscribe };
};

export const WebSocketProvider = ({ children }: { children: ReactNode }) => {
    const value = useWebSocketConnectionState();
    return createElement(WebSocketContext.Provider, { value }, children);
};

export const useWebSocketConnection = () => {
    const context = useContext(WebSocketContext);
    if (!context) {
        throw new Error('useWebSocketConnection must be used within a WebSocketProvider');
    }
    return context;
};
