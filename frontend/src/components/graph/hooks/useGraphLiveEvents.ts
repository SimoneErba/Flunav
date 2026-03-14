import { useEffect, useCallback, useRef } from "react";
import { useSigma } from "@react-sigma/core";
import { ItemResponse } from "../../../api-client/api";
import { useWebSocketEvents } from "../../../hooks/websocket/useWebSocketEvents";
import { hashToNumber } from "../utils/graphUtils";

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
        subscribeToAllItemUpdates,
        subscribeToLocationCreated,
        subscribeToLocationDeleted,
        subscribeToAllLocationUpdates,
        subscribeToConnectionCreated,
        subscribeToConnectionDeleted,
        subscribeToConnectionUpdated,
        subscribeToChuteEmptied
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
            console.log("Got item position chnaged");
            if (!graph.hasNode(update.itemId)) return;

            // FIX: Handle LOST status (Item removed from system)
            if (update.status === 'LOST') {
                graph.dropNode(update.itemId);
                activeItemsRef.current.delete(update.itemId);
                return;
            }

            const currentItem = activeItemsRef.current.get(update.itemId);
            console.log("curent item", currentItem)
            if (currentItem) {
                const isConveyor = update.type === 'CONVEYOR';
                const updatedItem = {
                    ...currentItem,
                    currentEdgeId: isConveyor ? update.edgeId : null,
                    locationId: isConveyor ? null : update.edgeId,
                    entryTimestamp: new Date(update.timestamp).toISOString(),
                    progress: update.progress || 0
                };
                
                activeItemsRef.current.set(update.itemId, updatedItem);
                console.log("curent item", currentItem)

                // Update graph node logical state
                graph.setNodeAttribute(update.itemId, "currentEdgeId", updatedItem.currentEdgeId);
                graph.setNodeAttribute(update.itemId, "locationId", updatedItem.locationId);
            }
        }, simulationId));

        // 2. Item CRUD
        unsubscribers.push(subscribeToItemCreated((item, timestamp) => {
            if (graph.hasNode(item.id)) return;

            let startX = 0;
            let startY = 0;
            let isHidden = true; // Default to invisible

            // Check if we have a valid location ID
            if (item.locationId) {
                
                // CASE A: Spawning on a Location (Node)
                if (graph.hasNode(item.locationId)) {
                    const attrs = graph.getNodeAttributes(item.locationId);
                    startX = attrs.x;
                    startY = attrs.y;
                    isHidden = false; // Found it, make visible
                } 
                
                // CASE B: Spawning on a Conveyor (Edge)
                else if (graph.hasEdge(item.locationId)) {
                    const edgeId = item.locationId;
                    const sourceId = graph.source(edgeId);
                    const targetId = graph.target(edgeId);
                    
                    // Ensure source/target exist (safety check)
                    if (graph.hasNode(sourceId) && graph.hasNode(targetId)) {
                        const sourceNode = graph.getNodeAttributes(sourceId);
                        const targetNode = graph.getNodeAttributes(targetId);
                        
                        // Use the progress from the event (default to 0 if missing)
                        const progress = item.progress || 0.0;

                        // Linear Interpolation based on progress %
                        startX = sourceNode.x + (targetNode.x - sourceNode.x) * progress;
                        startY = sourceNode.y + (targetNode.y - sourceNode.y) * progress;
                        
                        isHidden = false; // Found it, make visible
                    }
                }
            }

            // Add to Graph
            graph.addNode(item.id, { 
                x: startX, 
                y: startY, 
                label: item.name, 
                size: 6, 
                color: item.customColor || "#FF0000", 
                type: "square", 
                id: item.id, 
                isItem: true,
                hidden: isHidden, // Start invisible if location unknown
                properties: item.properties,
                isActive: item.active,
                customColor: item.customColor
            });

            const isConveyor = item.positionType === 'CONVEYOR' || (item.locationId && graph.hasEdge(item.locationId));
            
            // Update Logic State
            activeItemsRef.current.set(item.id!, {
                id: item.id, 
                name: item.name, 
                active: item.active,
                locationId: isConveyor ? null : item.locationId, 
                currentEdgeId: isConveyor ? item.locationId : undefined,
                entryTimestamp: new Date(timestamp).toISOString(), 
                progress: item.progress || 0,
                customColor: item.customColor
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
                    if (key === 'name') {
                        graph.setNodeAttribute(update.id, 'label', val);
                    }
                    if (key === 'customColor') {
                        graph.setNodeAttribute(update.id, 'color', val);
                    }

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
                x: loc.latitude ?? hashToNumber(loc.id!), y: loc.longitude ?? hashToNumber(loc.id + "random"), locationType: loc.type,
                label: loc.name, size: 10, color: loc.customColor || "#69b3a2", type: "circle", id: loc.id, capacity: loc.capacity, properties: loc.properties, customColor: loc.customColor
            });
        }, simulationId));

        unsubscribers.push(subscribeToLocationDeleted((id) => {
            if (graph.hasNode(id)) graph.dropNode(id);
        }, simulationId));

        unsubscribers.push(subscribeToAllLocationUpdates((update) => {
            if (update.id && graph.hasNode(update.id) && update.properties) {
                Object.keys(update.properties).forEach(key => {
                    const val = update.properties![key];
                    if (key === 'customColor') {
                        graph.setNodeAttribute(update.id, 'color', val);
                    }
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
                const mainPath = data?.mainPath ?? false;
                const label = data?.name ?? "";
                const id = data?.id;
                const customColor = data?.customColor;
                graph.addEdge(from, to, { id, type: 'arrow', size: mainPath ? 6 : 3, label, speed, length, mainPath, color: customColor, customColor });
            }
        }, simulationId));

        unsubscribers.push(subscribeToConnectionDeleted((conn) => {
            if (graph.hasEdge(conn.from, conn.to)) graph.dropEdge(conn.from, conn.to);
        }, simulationId));

        unsubscribers.push(subscribeToChuteEmptied((chuteId: string) => {
            if (graph.hasNode(chuteId)) {
                const capacity = graph.getNodeAttribute(chuteId, "capacity");
                const baseName = graph.getNodeAttribute(chuteId, "label")?.split(" (")[0];

                graph.setNodeAttribute(chuteId, "itemsInChute", []);
                graph.setNodeAttribute(chuteId, "label", capacity ? `${baseName} (0/${capacity})` : `${baseName} (0)`);

                // Visual flash
                const originalColor = graph.getNodeAttribute(chuteId, "customColor") || "#69b3a2";
                graph.setNodeAttribute(chuteId, "color", "#FFFF00");
                setTimeout(() => {
                    if (graph.hasNode(chuteId)) {
                        graph.setNodeAttribute(chuteId, "color", originalColor);
                    }
                }, 500);
            }
        }));

        unsubscribers.push(subscribeToConnectionUpdated((update) => {
            const edge = graph.findEdge((edge, attrs) => attrs.id === update.id);
            if (edge && update.properties) {
                if (update.properties.active === false) {
                    // Edge deactivated - set color to red and stop items
                    graph.setEdgeAttribute(edge, 'originalColor', graph.getEdgeAttribute(edge, 'color'));
                    graph.setEdgeAttribute(edge, 'color', '#FF0000');
                    graph.setEdgeAttribute(edge, 'originalSpeed', graph.getEdgeAttribute(edge, 'speed'));
                    graph.setEdgeAttribute(edge, 'speed', 0);

                    adjustItemsForSpeedChange(edge, 0);
                } else if (update.properties.active === true) {
                    // Edge activated - restore color and speed
                    const originalColor = graph.getEdgeAttribute(edge, 'originalColor') as string || '#808080';
                    const originalSpeed = graph.getEdgeAttribute(edge, 'originalSpeed') as number || 1.0;
                    graph.setEdgeAttribute(edge, 'color', originalColor);
                    graph.setEdgeAttribute(edge, 'speed', originalSpeed);
                    adjustItemsForSpeedChange(edge, originalSpeed);
                } else if (update.properties.speed !== undefined) {
                    adjustItemsForSpeedChange(edge, Number(update.properties.speed));
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

        return () => unsubscribers.forEach(u => u());
    }, [connected, sigma, simulationId, adjustItemsForSpeedChange]);

    return { adjustItemsForSpeedChange };
};