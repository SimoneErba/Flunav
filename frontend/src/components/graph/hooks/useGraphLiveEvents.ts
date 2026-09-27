import { useEffect, useCallback, useRef } from "react";
import { useSigma } from "@react-sigma/core";
import { ItemPositionTypeEnum, ItemResponse } from "../../../api-client/api";
import { useWebSocketEvents } from "../../../hooks/websocket/useWebSocketEvents";
import { hashToNumber } from "../utils/graphUtils";
import { dischargeItemToChute } from "../utils/chuteUtils";
import { EntityUpdateMessage } from "../../../websocket-types/websocket-types";
import { isHighPriorityItem } from "../utils/itemPriority";
import { useSimulationContext } from "../../../context/simulation.context";

/**
 * Narrows websocket property values before applying them to graph coordinates.
 * Backend update payloads can carry numbers as strings, so graph mutation stays
 * defensive at the integration boundary.
 */
const asNumber = (value: unknown): number | null => {
    if (typeof value === "number" && Number.isFinite(value)) return value;
    if (typeof value === "string") {
        const parsed = Number(value);
        return Number.isFinite(parsed) ? parsed : null;
    }
    return null;
};

export const useGraphLiveEvents = (
    activeItemsRef: React.MutableRefObject<Map<string, ItemResponse>>,
    simulationId: string | undefined,
    simTime: number,
    onHighPriorityCountChange?: (count: number) => void,
    onItemUpdated?: (itemId: string, item: ItemResponse) => void,
) => {
    const { designMode } = useSimulationContext();
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
        subscribeToChuteEmptied,
        subscribeToAnomalies
    } = useWebSocketEvents();

    // WebSocket handlers are registered once per subscription set, so this ref
    // gives them current simulation time without constantly tearing down topics.
    const simTimeRef = useRef(simTime);
    simTimeRef.current = simTime;

    /**
     * Recounts high-priority items from the source-of-truth item ref.
     * Graph nodes can be hidden or dropped for chute display, so counting the ref
     * avoids mixing visual representation with live item state.
     */
    const refreshHighPriorityCount = useCallback(() => {
        let highPriorityCount = 0;
        activeItemsRef.current.forEach((item) => {
            if (isHighPriorityItem(item)) highPriorityCount++;
        });
        onHighPriorityCountChange?.(highPriorityCount);
    }, [activeItemsRef, onHighPriorityCountChange]);

    /**
     * Reanchors conveyor item entry timestamps after a speed change.
     * The visible progress at the effective event time is preserved, then the entry
     * timestamp is recalculated so the animation loop continues smoothly.
     */
    const adjustItemsForSpeedChange = useCallback((edgeId: string, newSpeed: number, effectiveTime?: number) => {
        const graph = sigma.getGraph();
        if (!graph.hasEdge(edgeId)) return;

        const attrs = graph.getEdgeAttributes(edgeId);
        const oldSpeed = Number(attrs.speed);
        const length = Number(attrs.length);
        const anchorTime = effectiveTime ?? simTimeRef.current;

        if (!Number.isFinite(length) || length <= 0) return;

        activeItemsRef.current.forEach((item, itemId) => {
            if (item.currentEdgeId === attrs.id) {
                const progress = oldSpeed > 0
                    ? Math.min(1, Math.max(0,
                        (item.progress ?? 0) + Math.max(0, anchorTime - new Date(item.entryTimestamp).getTime())
                        / ((length / oldSpeed) * 1000)))
                    : Math.min(1, Math.max(0, item.progress ?? 0));

                if (newSpeed <= 0) {
                    activeItemsRef.current.set(itemId, { ...item, progress,
                        entryTimestamp: new Date(anchorTime).toISOString() });
                    return;
                }

                const newEntryTimestamp = new Date(anchorTime).toISOString();
                activeItemsRef.current.set(itemId, { ...item, progress, entryTimestamp: newEntryTimestamp });
            }
        });
    }, [activeItemsRef, sigma]);

    useEffect(() => {
        if (!connected || !sigma) return;
        const graph = sigma.getGraph();
        const unsubscribers: (() => void)[] = [];

        // Position updates carry event timestamps, not browser arrival time, so the
        // handler converts progress back into the entry timestamp used by animation.
        if (!designMode) {
        unsubscribers.push(subscribeToPositionUpdates((update) => {
            if (!graph.hasNode(update.itemId)) return;

            // FIX: Handle LOST status (Item removed from system)
            if (update.status === 'LOST') {
                graph.dropNode(update.itemId);
                activeItemsRef.current.delete(update.itemId);
                refreshHighPriorityCount();
                return;
            }

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

        // Item creation may arrive for a location or conveyor id. The handler
        // resolves both forms so graph state matches the backend position model.
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
            const resolveEdgeKey = (edgeId?: string | null) => {
                if (!edgeId) return undefined;
                return graph.hasEdge(edgeId)
                    ? edgeId
                    : graph.findEdge((_edge, attrs) => attrs.id === edgeId);
            };
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

        // Item updates patch graph attributes and the active item ref together so
        // rendering, highlighting, and the editor all see the same properties.
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

        // 3. Location CRUD
        }
        unsubscribers.push(subscribeToLocationCreated((loc) => {
            if (graph.hasNode(loc.id)) return;
            graph.addNode(loc.id, {
                x: loc.latitude ?? hashToNumber(loc.id!), y: loc.longitude ?? hashToNumber(loc.id + "random"), locationType: loc.type,
                label: loc.name, size: 10, color: loc.customColor || "#69b3a2", type: "circle", id: loc.id, capacity: loc.capacity, timeToProcessMs: loc.timeToProcessMs, properties: loc.properties, customColor: loc.customColor
            });
        }, simulationId));

        unsubscribers.push(subscribeToLocationDeleted((id) => {
            if (graph.hasNode(id)) graph.dropNode(id);
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

        // Conveyor updates can affect both topology visuals and item physics, so
        // speed/active changes are applied before generic attribute patching.
        unsubscribers.push(subscribeToConnectionCreated((conn) => {
            const { from, to, data } = conn;
            if (graph.hasNode(from) && graph.hasNode(to) && !graph.hasEdge(from, to)) {
                const speed = data?.speed ?? 1.0;
                const length = data?.length ?? 10.0;
                const mainPath = data?.mainPath ?? false;
                const label = data?.name ?? "";
                const id = data?.id;
                const customColor = data?.customColor;
                graph.addEdge(from, to, { id, type: 'arrow', conveyorType: data?.type ?? 'BELT', minDistance: data?.minDistance ?? (data?.type === 'STAGING' ? 0.1 : 0), flowStopped: false, size: mainPath ? 6 : 3, label, speed, length, mainPath, color: customColor, customColor, properties: data?.properties });
            }
        }, simulationId));

        unsubscribers.push(subscribeToConnectionDeleted((conn) => {
            if (graph.hasEdge(conn.from, conn.to)) graph.dropEdge(conn.from, conn.to);
        }, simulationId));

        if (!designMode) unsubscribers.push(subscribeToChuteEmptied((chuteId: string) => {
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
        }));

        unsubscribers.push(subscribeToConnectionUpdated((update) => {
            const edge = graph.findEdge((edge, attrs) => attrs.id === update.id);
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
                Object.keys(update.properties).forEach(key => {
                    const val = update.properties![key];
                    if (key === 'customColor') {
                        graph.setEdgeAttribute(edge, 'color', val);
                    }
                    graph.setEdgeAttribute(edge, key, val);
                });
            }
        }, simulationId));

        if (!designMode) unsubscribers.push(subscribeToAnomalies((notification) => {
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
                    : graph.findEdge((_key, attrs) => attrs.id === componentId);
                if (!edge) return;
                const original = graph.getEdgeAttribute(edge, 'advisoryOriginalLabel')
                    ?? graph.getEdgeAttribute(edge, 'label');
                graph.setEdgeAttribute(edge, 'advisoryOriginalLabel', original);
                graph.setEdgeAttribute(edge, 'advisory', enabled);
                graph.setEdgeAttribute(edge, 'label', enabled ? `⚠ ${original ?? componentId}` : original);
            }
            sigma.refresh();
        }, simulationId));

        return () => unsubscribers.forEach(u => u());
    }, [
        activeItemsRef,
        adjustItemsForSpeedChange,
        connected,
        refreshHighPriorityCount,
        sigma,
        simulationId,
        subscribeToAllItemUpdates,
        subscribeToAllLocationUpdates,
        subscribeToChuteEmptied,
        subscribeToConnectionCreated,
        subscribeToConnectionDeleted,
        subscribeToConnectionUpdated,
        subscribeToItemCreated,
        subscribeToItemDeleted,
        subscribeToLocationCreated,
        subscribeToLocationDeleted,
        subscribeToPositionUpdates,
        subscribeToAnomalies,
        designMode,
        onItemUpdated
    ]);

    return { adjustItemsForSpeedChange };
};
