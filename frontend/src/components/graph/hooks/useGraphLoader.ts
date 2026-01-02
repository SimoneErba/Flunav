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

        // 1. Locations
        initialGraphData?.locations?.forEach((loc) => {
            graph.addNode(loc.id, {
                x: loc.latitude ?? hashToNumber(loc.id!),
                y: loc.longitude ?? hashToNumber(loc.id + "random"),
                label: loc.name, 
                size: 10, 
                color: "#69b3a2", // Default color (will be overwritten by ThemeController)
                type: "circle",
                id: loc.id, 
                capacity: loc.capacity,
                locationType: loc.type
            });
        });

        // 2. Conveyors
        const conveyorLookup = new Map<string, ConveyorResponse>();
        initialGraphData?.conveyors?.forEach((conv) => {
            conveyorLookup.set(conv.id!, conv);
            if (graph.hasNode(conv.sourceId) && graph.hasNode(conv.targetId)) {
                let size = 3; if (conv.isMainPath) size = 6;
                graph.addEdgeWithKey(conv.id, conv.sourceId, conv.targetId, {
                    id: conv.id, type: 'arrow', size, label: conv.name,
                    speed: conv.speed, length: conv.length, isMainPath: conv.isMainPath
                });
                console.log("Adding", conv.id, conv.name, conv.sourceId, conv.targetId)
            }
        });

        // 3. Items
        activeItemsRef.current.clear();
        initialGraphData?.items?.forEach((item) => {
            if (!item.locationId && !item.currentEdgeId) return;
            activeItemsRef.current.set(item.id!, item);

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
                x: startX, y: startY, label: item.name, size: 6, color: "#FF0000",
                type: "square", id: item.id, isItem: true, path: item.path
            });
        });

        loadGraph(graph);
    }, [activeItemsRef, initialGraphData, loadGraph]);
};