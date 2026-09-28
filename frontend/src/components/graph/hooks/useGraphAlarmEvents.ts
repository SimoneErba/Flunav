import { useEffect } from "react";
import { useWebSocketEvents } from "../../../hooks/websocket/useWebSocketEvents";

import type { GraphLiveEventContext } from "./graphLiveEventContext";

/** Applies alarm visuals from timestamp-aware WebSocket subscriptions. */
export const useGraphAlarmEvents = ({ sigma, edgeKeysRef, simulationId, designMode }: GraphLiveEventContext) => {
    const { connected, subscribeToAnomalies } = useWebSocketEvents();
    useEffect(() => {
        if (!connected || designMode) return;
        const graph = sigma.getGraph();
        const unsubscribers: (() => void)[] = [];
        unsubscribers.push(subscribeToAnomalies((notification) => {
            const finding = notification.finding;
            if (finding?.detector === 'UNSCORABLE_CAPACITY') return;
            const componentId = finding?.componentId ?? notification.componentId;
            if (!componentId) return;
            const enabled = notification.kind !== 'ALARM_CLEARED';
            if (finding?.componentType === 'LOCATION' || (!finding && graph.hasNode(componentId))) {
                if (!graph.hasNode(componentId)) return;
                const original = graph.getNodeAttribute(componentId, 'advisoryOriginalLabel')
                    ?? graph.getNodeAttribute(componentId, 'label');
                graph.setNodeAttribute(componentId, 'advisoryOriginalLabel', original);
                graph.setNodeAttribute(componentId, 'advisory', enabled);
                graph.setNodeAttribute(componentId, 'label', enabled ? `⚠ ${original}` : original);
            } else {
                const edge = graph.hasEdge(componentId)
                    ? componentId
                    : edgeKeysRef.current.get(componentId);
                if (!edge) return;
                const original = graph.getEdgeAttribute(edge, 'advisoryOriginalLabel')
                    ?? graph.getEdgeAttribute(edge, 'label');
                graph.setEdgeAttribute(edge, 'advisoryOriginalLabel', original);
                graph.setEdgeAttribute(edge, 'advisory', enabled);
                graph.setEdgeAttribute(edge, 'label', enabled ? `⚠ ${original ?? componentId}` : original);
            }
            sigma.refresh();
        }, simulationId));
        return () => unsubscribers.forEach(unsubscribe => unsubscribe());
    }, [sigma, edgeKeysRef, simulationId, designMode, connected, subscribeToAnomalies]);
};
