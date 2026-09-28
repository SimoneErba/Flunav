import { useEffect } from "react";
import { ItemPositionTypeEnum } from "../../../api-client/api";
import { useWebSocketEvents } from "../../../hooks/websocket/useWebSocketEvents";
import { dischargeItemToChute } from "../utils/chuteUtils";

import type { GraphLiveEventContext } from "./graphLiveEventContext";

/** Applies item motion and metadata from timestamp-aware WebSocket subscriptions. */
export const useGraphItemEvents = ({ sigma, activeItemsRef, edgeKeysRef, simulationId, refreshHighPriorityCount, onItemUpdated, designMode }: GraphLiveEventContext) => {
    const { connected, subscribeToPositionUpdates, subscribeToItemCreated, subscribeToItemDeleted, subscribeToAllItemUpdates, subscribeToChuteEmptied } = useWebSocketEvents();
    useEffect(() => {
        if (!connected || designMode) return;
        const graph = sigma.getGraph();
        const unsubscribers: (() => void)[] = [];
        unsubscribers.push(subscribeToPositionUpdates((update) => {
            // Chute items may exist only in runtime state, without a graph node.
            if (update.status === 'LOST') {
                if (graph.hasNode(update.itemId)) graph.dropNode(update.itemId);
                activeItemsRef.current.delete(update.itemId);
                refreshHighPriorityCount();
                return;
            }

            if (!graph.hasNode(update.itemId)) return;
            const currentItem = activeItemsRef.current.get(update.itemId);
            const isConveyor = update.type === ItemPositionTypeEnum.Conveyor;
            const positionId = update.positionId ?? update.edgeId;
            // Position events use percentages; ItemResponse snapshots use fractions.
            const progress = Math.min(1, Math.max(0, (update.progress ?? 0) / 100));

            if (
                !isConveyor &&
                positionId &&
                graph.hasNode(positionId) &&
                graph.getNodeAttribute(positionId, "locationType") === "CHUTE"
            ) {
                if (currentItem) {
                    dischargeItemToChute(graph, activeItemsRef.current, update.itemId, positionId, {
                        ...currentItem,
                        currentEdgeId: null,
                        locationId: positionId,
                        entryTimestamp: new Date(update.timestamp).toISOString(),
                        progress,
                    });
                } else {
                    dischargeItemToChute(graph, activeItemsRef.current, update.itemId, positionId);
                }
                refreshHighPriorityCount();
                sigma.refresh();
                return;
            }

            if (currentItem) {
                const entryTimestamp = new Date(update.timestamp).toISOString();
                const updatedItem = {
                    ...currentItem,
                    currentEdgeId: isConveyor ? positionId : null,
                    locationId: isConveyor ? null : positionId,
                    entryTimestamp: entryTimestamp,
                    progress
                };
                
                activeItemsRef.current.set(update.itemId, updatedItem);
                refreshHighPriorityCount();

                // Update graph node logical state for highlighting/interactions
                graph.setNodeAttribute(update.itemId, "currentEdgeId", updatedItem.currentEdgeId);
                graph.setNodeAttribute(update.itemId, "locationId", updatedItem.locationId);

                // If stationary, immediately update visual position
                if (!isConveyor && updatedItem.locationId && graph.hasNode(updatedItem.locationId)) {
                    const locAttrs = graph.getNodeAttributes(updatedItem.locationId);
                    graph.setNodeAttribute(update.itemId, "x", locAttrs.x);
                    graph.setNodeAttribute(update.itemId, "y", locAttrs.y);
                }
            }
        }, simulationId));

        unsubscribers.push(subscribeToItemCreated((item, timestamp) => {
            if (!item.id || graph.hasNode(item.id)) return;

            if (
                item.locationId &&
                graph.hasNode(item.locationId) &&
                graph.getNodeAttribute(item.locationId, "locationType") === "CHUTE" &&
                !item.currentEdgeId
            ) {
                dischargeItemToChute(graph, activeItemsRef.current, item.id, item.locationId, item);
                refreshHighPriorityCount();
                sigma.refresh();
                return;
            }

            let startX = 0;
            let startY = 0;
            let isHidden = true; // Default to invisible
            const resolveEdgeKey = (edgeId?: string | null) => edgeId ? edgeKeysRef.current.get(edgeId) : undefined;
            const edgeKey = resolveEdgeKey(item.currentEdgeId) ?? (
                item.locationId && !graph.hasNode(item.locationId) ? resolveEdgeKey(item.locationId) : undefined
            );
            const edgeAttributes = edgeKey ? graph.getEdgeAttributes(edgeKey) : undefined;
            const currentEdgeId = item.currentEdgeId ?? edgeAttributes?.id;

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
                else if (edgeKey) {
                    const sourceId = graph.source(edgeKey);
                    const targetId = graph.target(edgeKey);
                    
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
            } else if (edgeKey) {
                const sourceId = graph.source(edgeKey);
                const targetId = graph.target(edgeKey);
                if (graph.hasNode(sourceId) && graph.hasNode(targetId)) {
                    const sourceNode = graph.getNodeAttributes(sourceId);
                    const targetNode = graph.getNodeAttributes(targetId);
                    const progress = item.progress || 0.0;
                    startX = sourceNode.x + (targetNode.x - sourceNode.x) * progress;
                    startY = sourceNode.y + (targetNode.y - sourceNode.y) * progress;
                    isHidden = false;
                }
            }

            // Add to Graph
            graph.addNode(item.id, { 
                x: startX, 
                y: startY, 
                label: item.name, 
                size: 6, 
                color: item.customColor || "#FF0000", 
                type: "borderedSquare",
                id: item.id, 
                isItem: true,
                hidden: isHidden, // Start invisible if location unknown
                properties: item.properties,
                isActive: item.active,
                customColor: item.customColor,
                customBorderColor: item.customBorderColor,
                customBorderWidth: item.customBorderWidth,
                priority: item.priority,
                effectivePriority: item.effectivePriority,
                rushActive: item.rushActive,
                destinations: item.destinations,
                selectedExitId: item.selectedExitId,
                currentEdgeId,
                locationId: currentEdgeId ? null : item.locationId,
                routingStatus: item.routingStatus,
                routingStatusUpdatedAt: item.routingStatusUpdatedAt,
                path: item.path,
                borderColor: item.customBorderColor || item.customColor || "#FF0000",
                borderSize: item.customBorderWidth ?? 0,
            });

            const isConveyor = Boolean(currentEdgeId);
            const checkpointTime = item.entryTimestamp ? new Date(item.entryTimestamp).getTime() : timestamp;
            let entryTimestamp = new Date(checkpointTime).toISOString();
            if (isConveyor && edgeKey) {
                const edgeAttrs = graph.getEdgeAttributes(edgeKey);
                const speed = Number(edgeAttrs.speed);
                const length = Number(edgeAttrs.length);
                if (speed > 0 && length > 0) {
                    const totalDuration = (length / speed) * 1000;
                    const progress = item.progress || 0;
                    entryTimestamp = new Date(checkpointTime - progress * totalDuration).toISOString();
                }
            }
            
            // Update Logic State
            const liveItem = {
                id: item.id, 
                name: item.name, 
                active: item.active,
                locationId: isConveyor ? null : item.locationId, 
                currentEdgeId,
                entryTimestamp, 
                progress: item.progress || 0,
                customColor: item.customColor,
                customBorderColor: item.customBorderColor,
                customBorderWidth: item.customBorderWidth,
                priority: item.priority,
                effectivePriority: item.effectivePriority,
                rushActive: item.rushActive,
                destinations: item.destinations,
                selectedExitId: item.selectedExitId,
                routingStatus: item.routingStatus,
                routingStatusUpdatedAt: item.routingStatusUpdatedAt,
                path: item.path,
                properties: item.properties
            };
            activeItemsRef.current.set(item.id!, liveItem);
            refreshHighPriorityCount();
        }, simulationId));

        unsubscribers.push(subscribeToItemDeleted((itemId) => {
            if (graph.hasNode(itemId)) {
                graph.dropNode(itemId);
                activeItemsRef.current.delete(itemId);
                refreshHighPriorityCount();
            }
        }, simulationId));

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
                    const updatedItem = { ...currentItem, ...update.properties };
                    activeItemsRef.current.set(update.id, updatedItem);
                    onItemUpdated?.(update.id, updatedItem);
                    graph.setNodeAttribute(update.id, "borderColor",
                        updatedItem.customBorderColor || updatedItem.customColor || "#FF0000");
                    graph.setNodeAttribute(update.id, "borderSize", updatedItem.customBorderWidth ?? 0);
                    graph.setNodeAttribute(update.id, "type", "borderedSquare");
                    refreshHighPriorityCount();
                }
            }
        }, simulationId));

        unsubscribers.push(subscribeToChuteEmptied((chuteId: string) => {
            if (graph.hasNode(chuteId)) {
                const capacity = graph.getNodeAttribute(chuteId, "capacity");
                const baseName = graph.getNodeAttribute(chuteId, "label")?.split(" (")[0];

                // Chute emptying removes any item still logically parked on the chute,
                // including items represented as graph nodes instead of node occupancy.
                activeItemsRef.current.forEach((item, itemId) => {
                    if (item.locationId !== chuteId || item.currentEdgeId) return;
                    activeItemsRef.current.delete(itemId);
                    if (graph.hasNode(itemId)) {
                        graph.dropNode(itemId);
                    }
                });

                graph.setNodeAttribute(chuteId, "itemsInChute", []);
                graph.setNodeAttribute(chuteId, "label", capacity ? `${baseName} (0/${capacity})` : `${baseName} (0)`);
                refreshHighPriorityCount();

                // Visual flash
                const originalColor = graph.getNodeAttribute(chuteId, "customColor") || "#69b3a2";
                graph.setNodeAttribute(chuteId, "color", "#FFFF00");
                setTimeout(() => {
                    if (graph.hasNode(chuteId)) {
                        graph.setNodeAttribute(chuteId, "color", originalColor);
                    }
                }, 500);
            }
        }, simulationId));
        return () => unsubscribers.forEach(unsubscribe => unsubscribe());
    }, [sigma, activeItemsRef, edgeKeysRef, simulationId, refreshHighPriorityCount, onItemUpdated, designMode, connected, subscribeToPositionUpdates, subscribeToItemCreated, subscribeToItemDeleted, subscribeToAllItemUpdates, subscribeToChuteEmptied]);
};
