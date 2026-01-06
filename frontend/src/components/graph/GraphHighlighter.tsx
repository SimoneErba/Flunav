import { useEffect } from "react";
import { useSigma } from "@react-sigma/core";
import { GraphData } from "../../api-client";

interface GraphHighlighterProps {
    initialGraphData: GraphData;
    highlightedItem: any | null;
}

const STYLES = {
    path: { color: "#2563eb", size: 4 }, 
    item: { color: "#dc2626", size: 10 }, 
    nodeOnPath: { color: "#2563eb", size: 6 }, 
    dimmed: { color: "#d1d5db", size: 1, labelColor: "transparent" } 
};

export const GraphHighlighter = ({ highlightedItem }: GraphHighlighterProps) => {
    const sigma = useSigma();
    const graph = sigma.getGraph();

    useEffect(() => {
        if (!graph) return;

        const pathEdgeSet = new Set<string>();
        const pathNodeSet = new Set<string>();

        if (highlightedItem) {
            // --- SCENARIO A: Item has an explicit path ---
            if (highlightedItem.path && highlightedItem.path.length > 0) {
                const fullPath = highlightedItem.path;
                let startIndex = 0;

                // 1. Determine where we are in the path list
                if (highlightedItem.locationId) {
                    // If stationary, start from the current node
                    startIndex = fullPath.indexOf(highlightedItem.locationId);
                } 
                else if (highlightedItem.currentEdgeId && graph.hasEdge(highlightedItem.currentEdgeId)) {
                    // If moving, start from the SOURCE of the current edge
                    // This ensures the edge the item is currently on gets highlighted
                    const source = graph.source(highlightedItem.currentEdgeId);
                    startIndex = fullPath.indexOf(source);
                }

                // Safety: If not found (-1), default to 0 (show full path) 
                // or you could choose to show nothing if it's off-path.
                if (startIndex === -1) startIndex = 0;

                // 2. Slice the path to get only future nodes (including current)
                const futurePath = fullPath.slice(startIndex);

                // 3. Add to sets
                futurePath.forEach((id: string) => pathNodeSet.add(id));

                for (let i = 0; i < futurePath.length - 1; i++) {
                    const u = futurePath[i];
                    const v = futurePath[i + 1];
                    pathEdgeSet.add(`${u}|${v}`);
                    pathEdgeSet.add(`${v}|${u}`);
                }
            }
            // --- SCENARIO B: No path, follow "Main Path" ---
            else {
                let currentNode: string | null = null;

                // 1. Determine Start Node
                if (highlightedItem.locationId) {
                    currentNode = highlightedItem.locationId;
                } else if (highlightedItem.currentEdgeId) {
                    if (graph.hasEdge(highlightedItem.currentEdgeId)) {
                        const target = graph.target(highlightedItem.currentEdgeId);
                        const source = graph.source(highlightedItem.currentEdgeId);
                        
                        // Highlight the current edge the item is sitting on
                        pathEdgeSet.add(`${source}|${target}`);
                        pathEdgeSet.add(`${target}|${source}`);
                        
                        currentNode = target;
                    } else {
                        console.warn("Highlighter: Item is on unknown edge:", highlightedItem.currentEdgeId);
                    }
                }

                // 2. Walk the graph following 'isMainPath'
                if (currentNode && graph.hasNode(currentNode)) {
                    pathNodeSet.add(currentNode);
                    
                    let steps = 0;
                    const MAX_STEPS = 50; 

                    while (steps < MAX_STEPS) {
                        const outEdges = graph.outEdges(currentNode);
                        let mainEdge: string | null = null;

                        console.log(`Highlighter [Step ${steps}]: Checking outgoing edges from ${currentNode}`, outEdges);

                        // Find the outgoing edge marked as main path
                        for (const edge of outEdges) {
                            const isMain = graph.getEdgeAttribute(edge, 'isMainPath');
                            console.log(`   -> Edge ${edge}: isMainPath =`, isMain);
                            
                            if (isMain === true || isMain === "true") { // Check for string "true" just in case
                                mainEdge = edge;
                                break;
                            }
                        }

                        if (mainEdge) {
                            const target = graph.target(mainEdge);
                            console.log(`   -> FOUND Main Path: ${mainEdge} pointing to ${target}`);
                            
                            pathEdgeSet.add(`${currentNode}|${target}`);
                            pathEdgeSet.add(`${target}|${currentNode}`);
                            pathNodeSet.add(target);

                            currentNode = target;
                            steps++;
                        } else {
                            console.log(`   -> STOP. No main path edge found from ${currentNode}`);
                            break;
                        }
                    }
                } else {
                 console.log("Highlighter: Could not determine valid start node.", currentNode);
                }
            }
        }

        // 3. Edge Reducer
        sigma.setSetting("edgeReducer", (edge, data) => {
            const res = { ...data };
            if (!highlightedItem) return res;

            const source = graph.source(edge);
            const target = graph.target(edge);
            const key = `${source}|${target}`;

            if (pathEdgeSet.has(key)) {
                res.color = STYLES.path.color;
                res.size = STYLES.path.size;
                res.zIndex = 10;
                res.type = "arrow"; 
            } else {
                res.color = STYLES.dimmed.color;
                res.size = STYLES.dimmed.size;
                res.zIndex = 0;
                res.hidden = false; 
                res.label = ""; 
            }
            return res;
        });

        // 4. Node Reducer
        sigma.setSetting("nodeReducer", (node, data) => {
            const res = { ...data };
            if (!highlightedItem) return res;

            if (node === highlightedItem.id) {
                res.color = data.customColor || STYLES.item.color;
                res.size = STYLES.item.size;
                res.zIndex = 20;
                res.highlighted = true;
            } else if (pathNodeSet.has(node)) {
                res.color = STYLES.nodeOnPath.color;
                res.size = STYLES.nodeOnPath.size;
                res.zIndex = 10;
                res.label = data.label; 
            } else {
                res.color = STYLES.dimmed.color;
                res.size = data.size || 3;
                res.zIndex = 0;
                res.label = ""; 
            }
            return res;
        });

        sigma.refresh();

        return () => {
            try {
                sigma.setSetting("edgeReducer", null);
                sigma.setSetting("nodeReducer", null);

                const container = sigma.getContainer();
                if (container && container.clientWidth > 0) {
                    sigma.refresh();
                }
            } catch (e) {
            }
        };

    }, [sigma, graph, highlightedItem]);

    return null;
};