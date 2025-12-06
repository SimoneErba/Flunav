// utils/graphUtils.ts

import { MultiDirectedGraph } from "graphology";

export const hashToNumber = (s: string) => {
  let hash = 0;
  for (let i = 0; i < s.length; i++) {
    const char = s.charCodeAt(i);
    hash = (hash << 5) - hash + char;
    hash = hash & hash;
  }
  return Math.abs(hash);
};

export const findNextEdge = (nodeId: string, graph: MultiDirectedGraph) => {
    const outEdges = graph.outEdges(nodeId);
    if (outEdges.length === 0) return null;
    
    // 1. Prioritize Main Path
    const mainPathEdge = outEdges.find(edge => graph.getEdgeAttribute(edge, 'isMainPath'));
    if (mainPathEdge) return mainPathEdge;
    
    // 2. Ambiguity Check
    if (outEdges.length > 1) return null;
    
    // 3. Single Option Fallback
    return outEdges[0];
};