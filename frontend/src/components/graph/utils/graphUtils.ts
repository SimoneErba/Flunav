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

/**
 * Finds the next logical edge from a node.
 * Priority: 
 * 1. Specific Path (Edge leading to nextNodeId)
 * 2. Main Path (Edge marked mainPath)
 * 3. Single Option
 * 4. Null (Stop if ambiguous)
 */
export const findNextEdge = (
  nodeId: string, 
  graph: MultiDirectedGraph, 
  nextNodeId?: string | null
) => {
  const outEdges = graph.outEdges(nodeId);
  if (outEdges.length === 0) return null;
  
  // 1. Path Priority: Find edge connecting to nextNodeId
  if (nextNodeId) {
      const edgeToTarget = outEdges.find(edge => graph.target(edge) === nextNodeId);
      if (edgeToTarget) return edgeToTarget;
  }
  
  // 2. Main Path Priority
  const mainPathEdge = outEdges.find(edge => graph.getEdgeAttribute(edge, 'mainPath'));
  if (mainPathEdge) return mainPathEdge;
  
  // 3. Ambiguity Check (Stop if multiple choices and no instruction)
  if (outEdges.length > 1) return null;
  
  // 4. Single Option Fallback
  return outEdges[0];
};