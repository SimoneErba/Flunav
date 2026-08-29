import { useEffect, useRef } from "react";
import { useSigma } from "@react-sigma/core";
import { ItemResponse } from "../../../api-client/api";
import type { Attributes } from "graphology-types";

export const useGraphAnimation = (
    activeItemsRef: React.MutableRefObject<Map<string, ItemResponse>>,
    simTime: number,
    draggedNodeRef: React.MutableRefObject<string | null>
) => {
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
        const animate = () => {
            const graph = sigma.getGraph();
            if (!graph) {
                animationFrameId.current = requestAnimationFrame(animate);
                return;
            }

            let needsRefresh = false;

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
                        if (edgeAttrs.speed === 0) {
                            needsRefresh = true;
                            return;
                        }
                        const sourceId = graph.source(edgeKey);
                        const targetId = graph.target(edgeKey);
                        const sourceNode = graph.getNodeAttributes(sourceId);
                        const targetNode = graph.getNodeAttributes(targetId);

                        const totalDuration = (edgeAttrs.length / edgeAttrs.speed) * 1000;
                        const entryTime = new Date(item.entryTimestamp).getTime();
                        const timeElapsed = simTimeRef.current - entryTime;

                        if (timeElapsed < 0) {
                            graph.setNodeAttribute(itemId, "hidden", true);
                        } else {
                            const progress = Math.min(1, timeElapsed / totalDuration);
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
    }, [activeItemsRef, draggedNodeRef, sigma]);
};
