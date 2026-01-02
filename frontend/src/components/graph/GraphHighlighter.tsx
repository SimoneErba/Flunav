import { useEffect } from "react";
import { useSigma } from "@react-sigma/core";
import { GraphData } from "../../../api-client/api";

interface GraphHighlighterProps {
    initialGraphData: GraphData;
    highlightedItem: any | null;
}

// --- REFINED PALETTE ---
const STYLES = {
    // Active Path (Blue)
    path: { color: "#2563eb", size: 4 }, 
    
    // The Item (Red) - Reduced size
    item: { color: "#dc2626", size: 10 }, 
    
    // Nodes on the path (Blue)
    nodeOnPath: { color: "#2563eb", size: 6 }, 
    
    // Background/Inactive (Visible Gray) - Darker than before
    dimmed: { color: "#d1d5db", size: 1, labelColor: "transparent" } 
};

export const GraphHighlighter = ({ highlightedItem }: GraphHighlighterProps) => {
    const sigma = useSigma();
    const graph = sigma.getGraph();

    useEffect(() => {
        if (!graph) return;

        // 1. Build the Path Sets
        const pathEdgeSet = new Set<string>();
        const pathNodeSet = new Set<string>();

        if (highlightedItem && highlightedItem.path) {
            const path = highlightedItem.path;
            
            path.forEach(id => pathNodeSet.add(id));

            for (let i = 0; i < path.length - 1; i++) {
                const u = path[i];
                const v = path[i + 1];
                // Add both directions to ensure we catch the edge regardless of definition
                pathEdgeSet.add(`${u}|${v}`);
                pathEdgeSet.add(`${v}|${u}`);
            }
        }

        // 2. Edge Reducer
        sigma.setSetting("edgeReducer", (edge, data) => {
            const res = { ...data };

            if (!highlightedItem) return res;

            const source = graph.source(edge);
            const target = graph.target(edge);
            const key = `${source}|${target}`;

            if (pathEdgeSet.has(key)) {
                // ACTIVE PATH
                res.color = STYLES.path.color;
                res.size = STYLES.path.size;
                res.zIndex = 10;
                res.type = "arrow"; 
            } else {
                // DIMMED BACKGROUND
                res.color = STYLES.dimmed.color; // Now visible gray
                res.size = STYLES.dimmed.size;   // Thinner
                res.zIndex = 0;
                res.hidden = false; 
                res.label = ""; 
            }
            return res;
        });

        // 3. Node Reducer
        sigma.setSetting("nodeReducer", (node, data) => {
            const res = { ...data };

            if (!highlightedItem) return res;

            if (node === highlightedItem.id) {
                // THE ITEM ITSELF
                res.color = STYLES.item.color;
                res.size = STYLES.item.size; // Smaller now
                res.zIndex = 20;
                res.highlighted = true;
            } else if (pathNodeSet.has(node)) {
                // NODE ON PATH
                res.color = STYLES.nodeOnPath.color;
                res.size = STYLES.nodeOnPath.size;
                res.zIndex = 10;
                res.label = data.label; 
            } else {
                // DIMMED NODE
                res.color = STYLES.dimmed.color; // Now visible gray
                res.size = data.size || 3; // Keep original size or default
                res.zIndex = 0;
                res.label = ""; 
            }
            return res;
        });

        sigma.refresh();

        return () => {
            sigma.setSetting("edgeReducer", null);
            sigma.setSetting("nodeReducer", null);
            sigma.refresh();
        };

    }, [sigma, graph, highlightedItem]);

    return null;
};