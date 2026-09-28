import { useEffect } from "react";
import { useWebSocketEvents } from "../../../hooks/websocket/useWebSocketEvents";
import { hashToNumber } from "../utils/graphUtils";
import { EntityUpdateMessage } from "../../../websocket-types/websocket-types";

import type { GraphLiveEventContext } from "./graphLiveEventContext";

const asNumber = (value: unknown): number | null => {
    if (typeof value === "number" && Number.isFinite(value)) return value;
    if (typeof value === "string") {
        const parsed = Number(value);
        return Number.isFinite(parsed) ? parsed : null;
    }
    return null;
};

/** Applies topology changes from timestamp-aware WebSocket subscriptions. */
export const useGraphTopologyEvents = ({ sigma, edgeKeysRef, simulationId, adjustItemsForSpeedChange }: GraphLiveEventContext) => {
    const { connected, subscribeToLocationCreated, subscribeToLocationDeleted, subscribeToAllLocationUpdates, subscribeToConnectionCreated, subscribeToConnectionDeleted, subscribeToConnectionUpdated } = useWebSocketEvents();
    useEffect(() => {
        if (!connected) return;
        const graph = sigma.getGraph();
        const unsubscribers: (() => void)[] = [];
        unsubscribers.push(subscribeToLocationCreated((loc) => {
            if (graph.hasNode(loc.id)) return;
            const color = (loc as typeof loc & { customColor?: string }).customColor;
            graph.addNode(loc.id, {
                x: loc.latitude ?? hashToNumber(loc.id!), y: loc.longitude ?? hashToNumber(loc.id + "random"), locationType: loc.type,
                label: loc.name, size: 10, color: color || "#69b3a2", type: "circle", id: loc.id, capacity: loc.capacity, timeToProcessMs: loc.timeToProcessMs, properties: loc.properties, customColor: color
            });
        }, simulationId));

        unsubscribers.push(subscribeToLocationDeleted((id) => {
            if (graph.hasNode(id)) {
                graph.forEachEdge(id, (key, attributes) => {
                    if (attributes.id) edgeKeysRef.current.delete(attributes.id);
                });
                graph.dropNode(id);
            }
        }, simulationId));

        unsubscribers.push(subscribeToAllLocationUpdates((update: EntityUpdateMessage) => {
            if (update.id && graph.hasNode(update.id) && update.properties) {
                const nextX = asNumber(update.properties.latitude);
                const nextY = asNumber(update.properties.longitude);
                if (nextX !== null) {
                    graph.setNodeAttribute(update.id, "x", nextX);
                }
                if (nextY !== null) {
                    graph.setNodeAttribute(update.id, "y", nextY);
                }

                Object.keys(update.properties).forEach(key => {
                    const val = update.properties![key];
                    if (key === 'customColor') {
                        graph.setNodeAttribute(update.id, 'color', val);
                    }
                    graph.setNodeAttribute(update.id, key === 'name' ? 'label' : key, val);
                });

                sigma.refresh();
            }
        }, simulationId));

        unsubscribers.push(subscribeToConnectionCreated((conn) => {
            const { from, to, data } = conn;
            if (graph.hasNode(from) && graph.hasNode(to) && !graph.hasEdge(from, to)) {
                const speed = data?.speed ?? 1.0;
                const length = data?.length ?? 10.0;
                const mainPath = data?.mainPath ?? false;
                const label = data?.name ?? "";
                const id = data?.id;
                const customColor = data?.customColor;
                const edgeKey = graph.addEdge(from, to, { id, type: 'arrow', conveyorType: data?.type ?? 'BELT', minDistance: data?.minDistance ?? (data?.type === 'STAGING' ? 0.1 : 0), flowStopped: false, size: mainPath ? 6 : 3, label, speed, length, mainPath, color: customColor, customColor, properties: data?.properties });
                if (id) edgeKeysRef.current.set(id, edgeKey);
            }
        }, simulationId));

        unsubscribers.push(subscribeToConnectionDeleted((conn) => {
            if (graph.hasEdge(conn.from, conn.to)) {
                const key = graph.edge(conn.from, conn.to);
                if (key) {
                    const id = graph.getEdgeAttribute(key, "id");
                    if (id) edgeKeysRef.current.delete(id);
                    graph.dropEdge(key);
                }
            }
        }, simulationId));

        unsubscribers.push(subscribeToConnectionUpdated((update) => {
            const edge = edgeKeysRef.current.get(update.id);
            if (edge && update.properties) {
                if (update.properties.active === false) {
                    // Edge deactivated - set color to red and stop items
                    graph.setEdgeAttribute(edge, 'originalColor', graph.getEdgeAttribute(edge, 'color'));
                    graph.setEdgeAttribute(edge, 'color', '#FF0000');
                    graph.setEdgeAttribute(edge, 'originalSpeed', graph.getEdgeAttribute(edge, 'speed'));
                    adjustItemsForSpeedChange(edge, 0, update.timestamp);
                    graph.setEdgeAttribute(edge, 'speed', 0);
                } else if (update.properties.active === true) {
                    // Edge activated - restore color and speed
                    const originalColor = graph.getEdgeAttribute(edge, 'originalColor') as string || '#808080';
                    const originalSpeed = graph.getEdgeAttribute(edge, 'originalSpeed')
                        ?? graph.getEdgeAttribute(edge, 'speed') ?? 1.0;
                    graph.setEdgeAttribute(edge, 'color', originalColor);
                    adjustItemsForSpeedChange(edge, Number(originalSpeed), update.timestamp);
                    graph.setEdgeAttribute(edge, 'speed', originalSpeed);
                } else if (update.properties.speed !== undefined) {
                    adjustItemsForSpeedChange(edge, Number(update.properties.speed), update.timestamp);
                }
                if (update.properties.conveyorType !== undefined || update.properties.type !== undefined || update.properties.minDistance !== undefined) {
                    edgeKeysRef.current.touch();
                }
                Object.keys(update.properties).forEach(key => {
                    const val = update.properties![key];
                    if (key === 'customColor') {
                        graph.setEdgeAttribute(edge, 'color', val);
                    }
                    graph.setEdgeAttribute(edge, key, val);
                });
            }
        }, simulationId));
        return () => unsubscribers.forEach(unsubscribe => unsubscribe());
    }, [sigma, edgeKeysRef, simulationId, adjustItemsForSpeedChange, connected, subscribeToLocationCreated, subscribeToLocationDeleted, subscribeToAllLocationUpdates, subscribeToConnectionCreated, subscribeToConnectionDeleted, subscribeToConnectionUpdated]);
};
