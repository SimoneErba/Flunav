import { useEffect, useRef } from "react";
import { useSigma } from "@react-sigma/core";
import { ItemResponse } from "../../../api-client/api";
import { findNextEdge } from "../utils/graphUtils";

export const useGraphAnimation = (
    activeItemsRef: React.MutableRefObject<Map<string, ItemResponse>>,
    simTime: number,
    draggedNodeRef: React.MutableRefObject<string | null>
) => {
    const sigma = useSigma();
    const animationFrameId = useRef<number | null>(null);

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

                // CASE 1: Item on Conveyor
                if (item.currentEdgeId) {
                    let edgeKey: string | undefined;
                    let edgeAttrs: any;
                    
                    // O(E) lookup - optimize with map in prod if needed
                    graph.forEachEdge((edge, attrs) => { 
                        if (attrs.id === item.currentEdgeId) { edgeKey = edge; edgeAttrs = attrs; } 
                    });

                    if (edgeKey && edgeAttrs) {
                        const sourceId = graph.source(edgeKey);
                        const targetId = graph.target(edgeKey);
                        const sourceNode = graph.getNodeAttributes(sourceId);
                        const targetNode = graph.getNodeAttributes(targetId);

                        const totalDuration = (edgeAttrs.length / edgeAttrs.speed) * 1000;
                        const entryTime = new Date(item.entryTimestamp).getTime();
                        const timeElapsed = simTime - entryTime;

                        if (timeElapsed < 0) {
                            graph.setNodeAttribute(itemId, "hidden", true);
                        } else if (timeElapsed >= totalDuration) {
                            // --- PREDICTION LOGIC ---
                            const overflow = timeElapsed - totalDuration;
                            const nextEdgeKey = findNextEdge(targetId, graph);

                            if (nextEdgeKey) {
                                const nextEdgeAttrs = graph.getEdgeAttributes(nextEdgeKey);
                                const newItem = { 
                                    ...item, 
                                    currentEdgeId: nextEdgeAttrs.id, 
                                    locationId: null, 
                                    entryTimestamp: new Date(simTime - overflow).toISOString() 
                                };
                                activeItemsRef.current.set(itemId, newItem);
                                graph.setNodeAttribute(itemId, "x", targetNode.x);
                                graph.setNodeAttribute(itemId, "y", targetNode.y);
                                needsRefresh = true;
                            } else {
                                // Stop at end
                                graph.setNodeAttribute(itemId, "x", targetNode.x);
                                graph.setNodeAttribute(itemId, "y", targetNode.y);
                                needsRefresh = true;
                            }
                        } else {
                            // Normal Movement
                            graph.setNodeAttribute(itemId, "hidden", false);
                            const progress = timeElapsed / totalDuration;
                            const x = sourceNode.x + (targetNode.x - sourceNode.x) * progress;
                            const y = sourceNode.y + (targetNode.y - sourceNode.y) * progress;
                            graph.setNodeAttribute(itemId, "x", x);
                            graph.setNodeAttribute(itemId, "y", y);
                            needsRefresh = true;
                        }
                    }
                } 
                // CASE 2: Item on Location
                else if (item.locationId) {
                    const nextEdgeKey = findNextEdge(item.locationId, graph);
                    if (nextEdgeKey) {
                         // Auto-start on next edge
                         const nextEdgeAttrs = graph.getEdgeAttributes(nextEdgeKey);
                         const newItem = { 
                             ...item, 
                             currentEdgeId: nextEdgeAttrs.id, 
                             locationId: null, 
                             entryTimestamp: new Date(simTime).toISOString() 
                         };
                         activeItemsRef.current.set(itemId, newItem);
                         needsRefresh = true;
                    } else if (graph.hasNode(item.locationId)) {
                        const locNode = graph.getNodeAttributes(item.locationId);
                        graph.setNodeAttribute(itemId, "x", locNode.x);
                        graph.setNodeAttribute(itemId, "y", locNode.y);
                        graph.setNodeAttribute(itemId, "hidden", false);
                        needsRefresh = true;
                    }
                }
            });

            if (needsRefresh) sigma.refresh();
            animationFrameId.current = requestAnimationFrame(animate);
        };

        animationFrameId.current = requestAnimationFrame(animate);
        return () => { if (animationFrameId.current) cancelAnimationFrame(animationFrameId.current); };
    }, [sigma, simTime]);
};