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

                // Helper to find next target from full path
                const getNextFromPath = (currentNodeId: string) => {
                    if (!item.path) return null;
                    const idx = item.path.indexOf(currentNodeId);
                    if (idx !== -1 && idx < item.path.length - 1) {
                        return item.path[idx + 1];
                    }
                    return null;
                };

                // CASE 1: Item on Conveyor (Moving)
                if (item.currentEdgeId) {
                    let edgeKey: string | undefined;
                    let edgeAttrs: any;
                    
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
                        let timeElapsed = simTime - entryTime;

                        // FIX: Clamp small negative values (sync jitter)
                        if (timeElapsed < 0 && timeElapsed > -500) {
                             timeElapsed = 0;
                        }

                        // DEBUG LOG (Enable if needed)
                        // if (activeItemsRef.current.keys().next().value === itemId) {
                        //     console.log(`[Anim] Item ${itemId}: Sim=${simTime}, Entry=${entryTime}, Delta=${timeElapsed}`);
                        // }

                        if (timeElapsed < 0) {
                            graph.setNodeAttribute(itemId, "hidden", true);
                        } else if (timeElapsed >= totalDuration) {
                            // --- ARRIVAL LOGIC ---
                            
                            // 1. CHECK FOR CHUTE (Discharge)
                            if (targetNode.locationType === "CHUTE") {
                                graph.dropNode(itemId);
                                activeItemsRef.current.delete(itemId);
                                needsRefresh = true;
                                return;
                            }

                            // 2. PREDICTION LOGIC
                            const overflow = timeElapsed - totalDuration;
                            const nextTargetNodeId = getNextFromPath(targetId);
                            const nextEdgeKey = findNextEdge(targetId, graph, nextTargetNodeId);

                            if (nextEdgeKey) {
                                const nextEdgeAttrs = graph.getEdgeAttributes(nextEdgeKey);
                                
                                // Create new item state
                                const newItem = { 
                                    ...item, 
                                    currentEdgeId: nextEdgeAttrs.id, 
                                    locationId: null, 
                                    entryTimestamp: new Date(simTime - overflow).toISOString() 
                                };

                                activeItemsRef.current.set(itemId, newItem);
                                
                                // --- SYNC GRAPH ATTRIBUTES (Transition) ---
                                // Update the graph so the Highlighter knows we switched edges
                                graph.setNodeAttribute(itemId, "currentEdgeId", newItem.currentEdgeId);
                                graph.setNodeAttribute(itemId, "locationId", null);
                                // ------------------------------------------

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

                            // --- SYNC GRAPH ATTRIBUTES (Continuous) ---
                            // Ensure the graph has the current logical state for the Highlighter
                            graph.setNodeAttribute(itemId, "currentEdgeId", item.currentEdgeId);
                            graph.setNodeAttribute(itemId, "locationId", null);

                            needsRefresh = true;
                        }
                    }
                } 
                // CASE 2: Item on Location (Stationary)
                else if (item.locationId) {
                    const nextTargetNodeId = getNextFromPath(item.locationId);
                    const nextEdgeKey = findNextEdge(item.locationId, graph, nextTargetNodeId);
                    
                    if (nextEdgeKey) {
                         const nextEdgeAttrs = graph.getEdgeAttributes(nextEdgeKey);
                         
                         const newItem = { 
                             ...item, 
                             currentEdgeId: nextEdgeAttrs.id, 
                             locationId: null, 
                             entryTimestamp: new Date(simTime).toISOString() 
                         };

                         activeItemsRef.current.set(itemId, newItem);

                         graph.setNodeAttribute(itemId, "currentEdgeId", newItem.currentEdgeId);
                         graph.setNodeAttribute(itemId, "locationId", null);

                         needsRefresh = true;
                    } else if (graph.hasNode(item.locationId)) {
                        const locNode = graph.getNodeAttributes(item.locationId);
                        graph.setNodeAttribute(itemId, "x", locNode.x);
                        graph.setNodeAttribute(itemId, "y", locNode.y);
                        graph.setNodeAttribute(itemId, "hidden", false);

                        graph.setNodeAttribute(itemId, "currentEdgeId", null);
                        graph.setNodeAttribute(itemId, "locationId", item.locationId);

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