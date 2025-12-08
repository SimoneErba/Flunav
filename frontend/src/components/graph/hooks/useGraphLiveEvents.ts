import { useEffect, useCallback, useRef } from "react";
import { useSigma } from "@react-sigma/core";
import { ItemResponse } from "../../../api-client/api";
import { useWebSocketEvents } from "../../../hooks/websocket/useWebSocketEvents";

export const useGraphLiveEvents = (
    activeItemsRef: React.MutableRefObject<Map<string, ItemResponse>>,
    simulationId: string | undefined,
    simTime: number
) => {
    const sigma = useSigma();
    const {
        connected,
        subscribeToPositionUpdates,
        subscribeToItemCreated,
        subscribeToItemDeleted,
        subscribeToAllItemUpdates, // This was unused before
        subscribeToLocationCreated,
        subscribeToLocationDeleted,
        subscribeToAllLocationUpdates,
        subscribeToConnectionCreated,
        subscribeToConnectionDeleted,
        subscribeToConnectionUpdated
    } = useWebSocketEvents();

    // Ref to access current simTime inside callbacks without re-subscribing
    const simTimeRef = useRef(simTime);
    simTimeRef.current = simTime;

    // Helper to adjust items when speed changes to prevent teleporting
    const adjustItemsForSpeedChange = useCallback((edgeId: string, newSpeed: number) => {
        const graph = sigma.getGraph();
        if (!graph.hasEdge(edgeId)) return;

        const attrs = graph.getEdgeAttributes(edgeId);
        const oldSpeed = attrs.speed;
        const length = attrs.length;

        if (oldSpeed === 0 || newSpeed === 0) return;

        activeItemsRef.current.forEach((item, itemId) => {
            if (item.currentEdgeId === attrs.id) {
                const oldDuration = (length / oldSpeed) * 1000;
                const currentEntryTime = new Date(item.entryTimestamp).getTime();
                const timeElapsed = simTimeRef.current - currentEntryTime;
                
                const progress = Math.min(1, Math.max(0, timeElapsed / oldDuration));

                const newDuration = (length / newSpeed) * 1000;
                const newTimeElapsed = progress * newDuration;
                const newEntryTimestamp = new Date(simTimeRef.current - newTimeElapsed).toISOString();
                
                activeItemsRef.current.set(itemId, { ...item, entryTimestamp: newEntryTimestamp });
            }
        });
    }, [sigma]);

    useEffect(() => {
        if (!connected || !sigma) return;
        const graph = sigma.getGraph();
        const unsubscribers: (() => void)[] = [];

        // 1. Position Update
        unsubscribers.push(subscribeToPositionUpdates((update) => {
            if (!graph.hasNode(update.itemId)) return;

            // FIX: Handle LOST status (Item removed from system)
            if (update.status === 'LOST') {
                graph.dropNode(update.itemId);
                activeItemsRef.current.delete(update.itemId);
                return;
            }

            const currentItem = activeItemsRef.current.get(update.itemId);
            if (currentItem) {
                const updatedItem = {
                    ...currentItem,
                    currentEdgeId: update.edgeId,
                    locationId: update.edgeId ? null : (update.locationId || null), // Use locationId if provided
                    entryTimestamp: new Date(update.timestamp).toISOString(),
                    progress: 0
                };
                
                // Logic to determine if it's on a Node or Edge based on graph existence
                // (Fallback if backend sends edgeId for a node)
                if (update.edgeId && graph.hasNode(update.edgeId)) {
                    updatedItem.locationId = update.edgeId;
                    updatedItem.currentEdgeId = null;
                } else {
                    updatedItem.currentEdgeId = update.edgeId;
                    updatedItem.locationId = null;
                }
                
                activeItemsRef.current.set(update.itemId, updatedItem);
            }
        }, simulationId));

        // 2. Item CRUD
        unsubscribers.push(subscribeToItemCreated((item, timestamp) => {
            if (graph.hasNode(item.id)) return;
            graph.addNode(item.id, { x: 0, y: 0, label: item.name, size: 6, color: "#FF0000", type: "square", id: item.id, isItem: true });
            
            activeItemsRef.current.set(item.id, {
                id: item.id, 
                name: item.name, 
                active: item.active,
                locationId: item.locationId, 
                currentEdgeId: null,
                entryTimestamp: new Date(timestamp).toISOString(), // Use event timestamp
                progress: 0
            });
        }, simulationId));

        unsubscribers.push(subscribeToItemDeleted((itemId) => {
            if (graph.hasNode(itemId)) {
                graph.dropNode(itemId);
                activeItemsRef.current.delete(itemId);
            }
        }, simulationId));

        // FIX: Added Item Properties Update Handler
        unsubscribers.push(subscribeToAllItemUpdates((update) => {
            if (update.id && graph.hasNode(update.id) && update.properties) {
                Object.keys(update.properties).forEach(key => {
                    const val = update.properties![key];
                    // Update visual label if name changes
                    if (key === 'name') graph.setNodeAttribute(update.id, 'label', val);
                    graph.setNodeAttribute(update.id, key, val);
                });

                // Update internal ref state
                const currentItem = activeItemsRef.current.get(update.id);
                if (currentItem) {
                    activeItemsRef.current.set(update.id, { ...currentItem, ...update.properties });
                }
            }
        }, simulationId));

        // 3. Location CRUD
        unsubscribers.push(subscribeToLocationCreated((loc) => {
            if (graph.hasNode(loc.id)) return;
            graph.addNode(loc.id, {
                x: loc.latitude ?? hashToNumber(loc.id), y: loc.longitude ?? hashToNumber(loc.id + "random"),
                label: loc.name, size: 10, color: "#69b3a2", type: "circle", id: loc.id, capacity: loc.capacity
            });
        }, simulationId));

        unsubscribers.push(subscribeToLocationDeleted((id) => {
            if (graph.hasNode(id)) graph.dropNode(id);
        }, simulationId));

        unsubscribers.push(subscribeToAllLocationUpdates((update) => {
            if (update.id && graph.hasNode(update.id) && update.properties) {
                Object.keys(update.properties).forEach(key => {
                    const val = update.properties![key];
                    graph.setNodeAttribute(update.id, key === 'name' ? 'label' : key, val);
                });
            }
        }, simulationId));

        // 4. Conveyor CRUD
        unsubscribers.push(subscribeToConnectionCreated((conn) => {
            const { from, to, data } = conn;
            if (graph.hasNode(from) && graph.hasNode(to) && !graph.hasEdge(from, to)) {
                const speed = data?.speed ?? 1.0;
                const length = data?.length ?? 10.0;
                const isMainPath = data?.isMainPath ?? false;
                const label = data?.name ?? "";
                const id = data?.id;
                graph.addEdge(from, to, { id, type: 'arrow', size: isMainPath ? 6 : 3, label, speed, length, isMainPath });
            }
        }, simulationId));

        unsubscribers.push(subscribeToConnectionDeleted((conn) => {
            if (graph.hasEdge(conn.from, conn.to)) graph.dropEdge(conn.from, conn.to);
        }, simulationId));

        unsubscribers.push(subscribeToConnectionUpdated((update) => {
            const edge = graph.findEdge((edge, attrs) => attrs.id === update.id);
            if (edge && update.properties) {
                if (update.properties.speed !== undefined) {
                    adjustItemsForSpeedChange(edge, Number(update.properties.speed));
                }
                Object.keys(update.properties).forEach(key => {
                    const val = update.properties![key];
                    graph.setEdgeAttribute(edge, key, val);
                });
            }
        }, simulationId));

        return () => unsubscribers.forEach(u => u());
    }, [connected, sigma, simulationId, adjustItemsForSpeedChange]);

    return { adjustItemsForSpeedChange };
};