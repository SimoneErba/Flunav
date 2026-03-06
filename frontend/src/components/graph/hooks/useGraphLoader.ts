import { useEffect } from "react";
import { useLoadGraph } from "@react-sigma/core";
import { MultiDirectedGraph } from "graphology";
import { GraphData, ConveyorResponse, ItemResponse } from "../../../api-client/api";
import { hashToNumber } from "../utils/graphUtils";

export const useGraphLoader = (
    initialGraphData: GraphData,
    activeItemsRef: React.MutableRefObject<Map<string, ItemResponse>>
) => {
    const loadGraph = useLoadGraph();

    useEffect(() => {
        const graph = new MultiDirectedGraph();

        // 1. Locations — add `itemsInChute: []` to all nodes upfront
        initialGraphData?.locations?.forEach((loc) => {
            graph.addNode(loc.id, {
                x: loc.latitude ?? hashToNumber(loc.id!),
                y: loc.longitude ?? hashToNumber(loc.id + "random"),
                label: loc.name,
                size: 10,
                color: loc.customColor || "#69b3a2",
                type: "circle",
                id: loc.id,
                capacity: loc.capacity,
                locationType: loc.type,
                properties: loc.properties,
                customColor: loc.customColor,
                itemsInChute: [] as ItemResponse[],  // <-- chute storage
            });
        });

        // 2. Conveyors (unchanged)
        const conveyorLookup = new Map<string, ConveyorResponse>();
        initialGraphData?.conveyors?.forEach((conv) => {
            conveyorLookup.set(conv.id!, conv);
            if (graph.hasNode(conv.sourceId) && graph.hasNode(conv.targetId)) {
                let size = 3; if (conv.mainPath) size = 6;
                const isActive = conv.active ?? true;
                const color = conv.customColor || '#808080';
                const speed = conv.speed ?? 1.0;
                const length = conv.length ?? 1.0;
                graph.addEdgeWithKey(conv.id, conv.sourceId, conv.targetId, {
                    id: conv.id, type: 'arrow', size, label: conv.name,
                    speed: isActive ? conv.speed : 0, length, mainPath: conv.mainPath,
                    color: isActive ? color : '#FF0000',
                    customColor: conv.customColor,
                    originalColor: color,
                    originalSpeed: speed
                });
            }
        });

        // 3. Items — hide chute items, store them on the chute node
        activeItemsRef.current.clear();
        initialGraphData?.items?.forEach((item) => {
            if (!item.locationId && !item.currentEdgeId) return;
            activeItemsRef.current.set(item.id!, item);

            const isInChute =
                item.locationId &&
                graph.hasNode(item.locationId) &&
                graph.getNodeAttribute(item.locationId, "locationType") === "CHUTE"; // adjust to your type value

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
                type: "square", id: item.id, isItem: true,
                path: item.path, properties: item.properties,
                isActive: item.active, customColor: item.customColor,
            });
        });

        loadGraph(graph);
    }, [activeItemsRef, initialGraphData, loadGraph]);
};