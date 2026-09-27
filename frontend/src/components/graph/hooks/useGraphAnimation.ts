import { useEffect, useRef } from "react";
import { useSigma } from "@react-sigma/core";
import { ItemResponse } from "../../../api-client/api";
import type { Attributes } from "graphology-types";
import { useSimulationContext } from "../../../context/simulation.context";

export const useGraphAnimation = (
    activeItemsRef: React.MutableRefObject<Map<string, ItemResponse>>,
    simTime: number,
    draggedNodeRef: React.MutableRefObject<string | null>
) => {
    const { designMode } = useSimulationContext();
    const sigma = useSigma();
    const animationFrameId = useRef<number | null>(null);
    const simTimeRef = useRef(simTime);
    simTimeRef.current = simTime;

    /**
     * Interpolates items only within their backend-confirmed position.
     * Reaching a visual endpoint does not advance or remove an item; the next domain
     * event remains authoritative for conveyor transitions, chutes, and exits.
     */
    useEffect(() => {
        if (designMode) return;
        const animate = () => {
            const graph = sigma.getGraph();
            if (!graph) {
                animationFrameId.current = requestAnimationFrame(animate);
                return;
            }

            let needsRefresh = false;

            const stagingIndexes = new Map<string, number>();
            const stagedByEdge = new Map<string, Array<[string, ItemResponse]>>();
            activeItemsRef.current.forEach((item, itemId) => {
                if (!item.currentEdgeId) return;
                const edge = graph.hasEdge(item.currentEdgeId)
                    ? item.currentEdgeId
                    : graph.findEdge((_key, attrs) => attrs.id === item.currentEdgeId);
                if (!edge || graph.getEdgeAttribute(edge, "conveyorType") !== "STAGING") return;
                const staged = stagedByEdge.get(item.currentEdgeId) ?? [];
                staged.push([itemId, item]);
                stagedByEdge.set(item.currentEdgeId, staged);
            });
            stagedByEdge.forEach((staged) => {
                staged.sort(([leftId, left], [rightId, right]) => {
                    if (left.stagingOrder !== undefined && right.stagingOrder !== undefined) {
                        return left.stagingOrder - right.stagingOrder;
                    }
                    const timeDifference = new Date(left.entryTimestamp ?? 0).getTime()
                        - new Date(right.entryTimestamp ?? 0).getTime();
                    return timeDifference || (right.progress ?? 0) - (left.progress ?? 0)
                        || leftId.localeCompare(rightId);
                });
                staged.forEach(([itemId], index) => stagingIndexes.set(itemId, index));
            });

            activeItemsRef.current.forEach((item, itemId) => {
                if (itemId === draggedNodeRef.current) return;
                if (!graph.hasNode(itemId)) return;

                // Conveyor items are animated from the last authoritative checkpoint.
                if (item.currentEdgeId) {
                    let edgeKey: string | undefined;
                    let edgeAttrs: Attributes | undefined;
                    
                    graph.forEachEdge((edge, attrs) => { 
                        if (attrs.id === item.currentEdgeId) { edgeKey = edge; edgeAttrs = attrs; } 
                    });

                    if (edgeKey && edgeAttrs) {
                        const sourceId = graph.source(edgeKey);
                        const targetId = graph.target(edgeKey);
                        const sourceNode = graph.getNodeAttributes(sourceId);
                        const targetNode = graph.getNodeAttributes(targetId);

                        const length = Number(edgeAttrs.length);
                        const speed = Number(edgeAttrs.speed);
                        const checkpointProgress = Math.min(1, Math.max(0, item.progress ?? 0));
                        let progress = checkpointProgress;
                        const flowPaused = item.flowPaused;
                        if (item.active !== false && !flowPaused && !edgeAttrs.flowStopped
                            && speed > 0 && length > 0) {
                            const totalDuration = (length / speed) * 1000;
                            const entryTime = new Date(item.entryTimestamp).getTime();
                            const timeElapsed = Math.max(0, simTimeRef.current - entryTime);

                            // A confirmed item stays visible even if the render clock trails its event.
                            progress = Math.min(1, checkpointProgress + timeElapsed / totalDuration);
                        }
                        if (edgeAttrs.conveyorType === "STAGING") {
                            const spacing = Number(edgeAttrs.minDistance ?? 0.1);
                            const index = stagingIndexes.get(itemId) ?? 0;
                            progress = Math.min(progress, Math.max(0, 1 - index * spacing / length));
                        }
                        graph.setNodeAttribute(itemId, "hidden", false);
                        const x = sourceNode.x + (targetNode.x - sourceNode.x) * progress;
                        const y = sourceNode.y + (targetNode.y - sourceNode.y) * progress;

                        graph.setNodeAttribute(itemId, "x", x);
                        graph.setNodeAttribute(itemId, "y", y);
                        graph.setNodeAttribute(itemId, "currentEdgeId", item.currentEdgeId);
                        graph.setNodeAttribute(itemId, "locationId", null);
                        needsRefresh = true;
                    }
                } 
                // Location items remain stationary until the backend confirms a move.
                else if (item.locationId) {
                    if (!graph.hasNode(item.locationId)) return;

                    const locNode = graph.getNodeAttributes(item.locationId);
                    graph.setNodeAttribute(itemId, "x", locNode.x);
                    graph.setNodeAttribute(itemId, "y", locNode.y);
                    graph.setNodeAttribute(itemId, "hidden", false);
                    graph.setNodeAttribute(itemId, "currentEdgeId", null);
                    graph.setNodeAttribute(itemId, "locationId", item.locationId);
                    needsRefresh = true;
                }
            });

            if (needsRefresh) sigma.refresh();
            animationFrameId.current = requestAnimationFrame(animate);
        };

        animationFrameId.current = requestAnimationFrame(animate);
        return () => { if (animationFrameId.current) cancelAnimationFrame(animationFrameId.current); };
    }, [activeItemsRef, draggedNodeRef, sigma, designMode]);
};
