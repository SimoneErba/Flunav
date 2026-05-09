/// <reference types="vite/client" />

type GraphTestNode = {
  id: string;
  attributes: Record<string, unknown>;
};

type GraphTestEdge = {
  key: string;
  source: string;
  target: string;
  attributes: Record<string, unknown>;
};

type GraphTestSnapshot = {
  nodeCount: number;
  edgeCount: number;
  nodes: GraphTestNode[];
  edges: GraphTestEdge[];
  activeItems: Array<[string, Record<string, unknown>]>;
  simTime: number;
};

type GraphTestApi = {
  version: 1;
  getSnapshot(): GraphTestSnapshot;
  getNode(id: string): GraphTestNode | null;
  getEdge(keyOrId: string): GraphTestEdge | null;
  getItem(id: string): { graphNode: GraphTestNode | null; activeItem: Record<string, unknown> | null };
  hasNode(id: string): boolean;
  hasEdge(keyOrId: string): boolean;
};

interface Window {
  __graphTestApi?: GraphTestApi;
}
