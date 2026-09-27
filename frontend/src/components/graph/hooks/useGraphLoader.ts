import { useEffect, useLayoutEffect } from "react";
import { useLoadGraph, useSigma } from "@react-sigma/core";
import { MultiDirectedGraph } from "graphology";
import { GraphData, ConveyorResponse, ItemResponse, DisplayRuleColorResult } from "../../../api-client/api";
import { hashToNumber } from "../utils/graphUtils";
import { isHighPriorityItem } from "../utils/itemPriority";

export const useGraphLoader = (
    initialGraphData: GraphData,
    activeItemsRef: React.MutableRefObject<Map<string, ItemResponse>>,
    onHighPriorityCountChange?: (count: number) => void,
    colorOverrides?: DisplayRuleColorResult | null
) => {
    const loadGraph = useLoadGraph();
    const sigma = useSigma();

    /**
     * Rebuilds the Sigma graph from an authoritative GraphData snapshot.
     * This path is intentionally full-reload because topology, item hot state, and
     * chute occupancy must start from one consistent backend timestamp.
     */
    useLayoutEffect(() => {
        const graph = new MultiDirectedGraph();

        // 1. Locations — add `itemsInChute: []` to all nodes upfront
        initialGraphData?.locations?.forEach((loc) => {
            const hasAdvisory = (((loc as typeof loc & { activeAlarms?: unknown[] }).activeAlarms?.length) ?? 0) > 0;
            graph.addNode(loc.id, {
                x: loc.latitude ?? hashToNumber(loc.id!),
                y: loc.longitude ?? hashToNumber(loc.id + "random"),
                label: hasAdvisory ? `⚠ ${loc.name}` : loc.name,
                advisoryOriginalLabel: loc.name,
                advisory: hasAdvisory,
                size: 10,
                color: loc.customColor || "#69b3a2",
                type: "circle",
                id: loc.id,
                capacity: loc.capacity,
                timeToProcessMs: loc.timeToProcessMs,
                locationType: loc.type,
                properties: loc.properties,
                customColor: loc.customColor,
                itemsInChute: [] as ItemResponse[],
            });
        });

        // 2. Conveyors
        const conveyorLookup = new Map<string, ConveyorResponse>();
        initialGraphData?.conveyors?.forEach((conv) => {
            conveyorLookup.set(conv.id!, conv);
            if (graph.hasNode(conv.sourceId) && graph.hasNode(conv.targetId)) {
                let size = 3; if (conv.mainPath) size = 6;
                const isActive = conv.active ?? true;
                const color = conv.customColor || '#808080';
                const speed = conv.speed ?? 1.0;
                const length = conv.length ?? 1.0;
                const hasAdvisory = (conv.activeAlarms?.length ?? 0) > 0;
                graph.addEdgeWithKey(conv.id, conv.sourceId, conv.targetId, {
                    id: conv.id, type: 'arrow', size, label: hasAdvisory ? `⚠ ${conv.name ?? conv.id}` : conv.name,
                    advisoryOriginalLabel: conv.name,
                    advisory: hasAdvisory,
                    speed: isActive ? speed : 0, length, mainPath: conv.mainPath,
                    conveyorType: conv.type,
                    flowStopped: conv.flowStopped ?? false,
                    minDistance: conv.minDistance ?? (conv.type === "STAGING" ? 0.1 : 0),
                    properties: conv.properties,
                    color: isActive ? color : '#FF0000',
                    customColor: conv.customColor,
                    originalColor: color,
                    originalSpeed: speed
                });
            }
        });

        initialGraphData?.sensorMappings?.forEach((sensor) => {
            const conveyor = sensor.conveyorId ? conveyorLookup.get(sensor.conveyorId) : undefined;
            if (!conveyor || !graph.hasNode(conveyor.sourceId) || !graph.hasNode(conveyor.targetId)) return;

            const source = graph.getNodeAttributes(conveyor.sourceId);
            const target = graph.getNodeAttributes(conveyor.targetId);
            const progress = Math.min(100, Math.max(0, sensor.progress ?? 0)) / 100;
            graph.addNode(`sensor:${sensor.sensorName}`, {
                x: source.x + (target.x - source.x) * progress,
                y: source.y + (target.y - source.y) * progress,
                label: sensor.sensorName,
                size: 5,
                color: "#f59e0b",
                type: "triangle",
                angle: Math.atan2(target.y - source.y, target.x - source.x),
                isSensor: true,
                sensorName: sensor.sensorName,
                conveyorId: sensor.conveyorId,
                progress: sensor.progress,
            });
        });

        // Chute items are represented on the chute node instead of as separate
        // item nodes so the graph stays readable while preserving occupancy counts.
        activeItemsRef.current.clear();
        initialGraphData?.items?.forEach((item) => {
            if (!item.locationId && !item.currentEdgeId) return;
            activeItemsRef.current.set(item.id!, item);

            const isInChute =
                item.locationId &&
                graph.hasNode(item.locationId) &&
                graph.getNodeAttribute(item.locationId, "locationType") === "CHUTE";

            if (isInChute) {
                const existing: ItemResponse[] = graph.getNodeAttribute(item.locationId!, "itemsInChute") ?? [];
                const updated = [...existing, item];
                const capacity = graph.getNodeAttribute(item.locationId!, "capacity");
                const baseName = graph.getNodeAttribute(item.locationId!, "label")?.split(" (")[0];

                graph.setNodeAttribute(item.locationId!, "itemsInChute", updated);

                if (capacity) {
                    if (updated.length <= capacity) {
                        graph.setNodeAttribute(item.locationId!, "label", `${baseName} (${updated.length}/${capacity})`);
                    } else {
                        graph.setNodeAttribute(item.locationId!, "color", "red");
                        graph.setNodeAttribute(item.locationId!, "label", `${baseName} (${updated.length}/${capacity})`);
                    }
                } else {
                    graph.setNodeAttribute(item.locationId!, "label", `${baseName} (${updated.length})`);
                }

                return;
            }

            // Normal item placement (on edge or non-chute location)
            let startX = 0, startY = 0;
            if (item.locationId && graph.hasNode(item.locationId)) {
                const locAttrs = graph.getNodeAttributes(item.locationId);
                startX = locAttrs.x; startY = locAttrs.y;
            } else if (item.currentEdgeId) {
                const conveyor = conveyorLookup.get(item.currentEdgeId);
                if (conveyor && graph.hasNode(conveyor.sourceId) && graph.hasNode(conveyor.targetId)) {
                    const sourceNode = graph.getNodeAttributes(conveyor.sourceId);
                    const targetNode = graph.getNodeAttributes(conveyor.targetId);
                    const p = item.progress ?? 0;
                    startX = sourceNode.x + (targetNode.x - sourceNode.x) * p;
                    startY = sourceNode.y + (targetNode.y - sourceNode.y) * p;
                }
            }

            graph.addNode(item.id, {
                x: startX, y: startY, label: item.name, size: 6,
                color: item.customColor || "#FF0000",
                type: "borderedSquare", id: item.id, isItem: true,
                path: item.path, properties: item.properties,
                destinations: item.destinations, selectedExitId: item.selectedExitId,
                routingStatus: item.routingStatus,
                routingStatusUpdatedAt: item.routingStatusUpdatedAt,
                isActive: item.active, priority: item.priority, effectivePriority: item.effectivePriority,
                rushActive: item.rushActive, customColor: item.customColor,
                borderColor: item.customBorderColor || item.customColor || "#FF0000",
                borderSize: item.customBorderWidth ?? 0,
            });
        });

        let highPriorityCount = 0;
        activeItemsRef.current.forEach((item) => {
            if (isHighPriorityItem(item)) highPriorityCount++;
        });
        onHighPriorityCountChange?.(highPriorityCount);

        loadGraph(graph);
    }, [activeItemsRef, initialGraphData, loadGraph, onHighPriorityCountChange]);

    /**
     * Applies display-rule color changes without reloading topology.
     * Avoiding a full reload here preserves animated item positions and the current
     * user viewport while still reflecting rule changes immediately.
     */
    useEffect(() => {
        if (!colorOverrides) return;
        const graph = sigma.getGraph();

        // Patch location node colors
        graph.forEachNode((nodeId, attrs) => {
            if (attrs.isItem) {
                // Item node — patch color + update ref
                const style = colorOverrides.itemStyles?.[nodeId];
                const color = style?.fillColor ?? attrs.customColor ?? "#FF0000";
                graph.setNodeAttribute(nodeId, "color", color);
                graph.setNodeAttribute(nodeId, "borderColor", style?.borderColor ?? color);
                graph.setNodeAttribute(nodeId, "borderSize", style?.borderWidth ?? 0);

                // Keep the ref in sync so the animation loop sees the new color
                const item = activeItemsRef.current.get(nodeId);
                if (item) {
                    item.customColor = style?.fillColor ?? null;
                    item.customBorderColor = style?.borderColor ?? null;
                    item.customBorderWidth = style?.borderWidth ?? null;
                }
            } else {
                // Location node
                const newColor = colorOverrides.locationStyles?.[nodeId]?.fillColor;
                const color = newColor ?? attrs.customColor ?? "#69b3a2";
                graph.setNodeAttribute(nodeId, "color", color);
            }
        });

        // Patch conveyor edge colors
        graph.forEachEdge((edgeId, attrs) => {
            const newColor = colorOverrides.conveyorStyles?.[edgeId]?.fillColor;
            if (newColor !== undefined) {
                graph.setEdgeAttribute(edgeId, "color", newColor);
                graph.setEdgeAttribute(edgeId, "originalColor", newColor);
            } else if (attrs.customColor) {
                graph.setEdgeAttribute(edgeId, "color", attrs.customColor);
            }
        });

        sigma.refresh();
    }, [colorOverrides, sigma, activeItemsRef]);
};
