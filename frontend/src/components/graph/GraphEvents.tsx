import { useCallback, useEffect } from "react";
import { ControlsContainer, ZoomControl, FullScreenControl, useSigma } from "@react-sigma/core";
import { DisplayRuleColorResult, GraphData, ItemResponse } from "../../api-client/api";
import { EdgeEditor } from "../editors/edge.editor";
import { NodeEditor } from "../editors/node.editor";

// Hooks
import { useGraphLoader } from "./hooks/useGraphLoader";
import type { ClockReader } from "./hooks/useSimulationClock";
import { useGraphRuntime } from "./hooks/useGraphRuntime";
import { useGraphAnimation } from "./hooks/useGraphAnimation";
import { useGraphLiveEvents } from "./hooks/useGraphLiveEvents";
import { useGraphInteractions } from "./hooks/useGraphInteractions";
import { HoverOverlay } from "./HoverOverlay";
import { ItemEditor } from "../editors/item.editor";
import { HoverTarget } from "./DisplayGraph";
import { LiveHud } from "./LiveHud";
import { useSimulationContext } from "../../context/simulation.context";

import type { GraphSelectionState } from "./hooks/useGraphSelection";

interface GraphEventsProps {
  initialGraphData: GraphData;
  simulationId?: string;
  now: ClockReader;
  hoverTarget: HoverTarget | null;
  setHoverTarget: (t: HoverTarget | null) => void;
  selection: GraphSelectionState;
  colorOverrides?: DisplayRuleColorResult | null;
}

const cloneAttributes = (value: unknown): Record<string, unknown> => {
  if (!value || typeof value !== "object") return {};
  return JSON.parse(JSON.stringify(value)) as Record<string, unknown>;
};

const GraphTestApiBridge = ({
  activeItemsRef,
  now,
}: {
  activeItemsRef: React.MutableRefObject<Map<string, ItemResponse>>;
  now: ClockReader;
}): null => {
  const sigma = useSigma();

  useEffect(() => {
    if (import.meta.env.VITE_GRAPH_TEST_API !== "true") return;

    const graph = sigma.getGraph();

    const getNode = (id: string) => {
      if (!graph.hasNode(id)) return null;
      return { id, attributes: cloneAttributes(graph.getNodeAttributes(id)) };
    };

    const getEdge = (keyOrId: string) => {
      let edgeKey: string | null = null;

      if (graph.hasEdge(keyOrId)) {
        edgeKey = keyOrId;
      } else {
        graph.forEachEdge((key, attributes) => {
          if (!edgeKey && attributes.id === keyOrId) edgeKey = key;
        });
      }

      if (!edgeKey || !graph.hasEdge(edgeKey)) return null;
      return {
        key: edgeKey,
        source: graph.source(edgeKey),
        target: graph.target(edgeKey),
        attributes: cloneAttributes(graph.getEdgeAttributes(edgeKey)),
      };
    };

    window.__graphTestApi = {
      version: 1,
      getSnapshot: () => {
        const nodes = graph.nodes().map((id) => ({
          id,
          attributes: cloneAttributes(graph.getNodeAttributes(id)),
        }));
        const edges = graph.edges().map((key) => ({
          key,
          source: graph.source(key),
          target: graph.target(key),
          attributes: cloneAttributes(graph.getEdgeAttributes(key)),
        }));

        return {
          nodeCount: graph.order,
          edgeCount: graph.size,
          nodes,
          edges,
          activeItems: Array.from(activeItemsRef.current.entries()).map(([id, item]) => [
            id,
            cloneAttributes(item),
          ]),
          simTime: now(),
        };
      },
      getNode,
      getEdge,
      getItem: (id: string) => ({
        graphNode: getNode(id),
        activeItem: activeItemsRef.current.has(id)
          ? cloneAttributes(activeItemsRef.current.get(id))
          : null,
      }),
      hasNode: (id: string) => graph.hasNode(id),
      hasEdge: (keyOrId: string) => getEdge(keyOrId) !== null,
    };

    return () => {
      if (window.__graphTestApi?.version === 1) {
        delete window.__graphTestApi;
      }
    };
  }, [activeItemsRef, sigma, now]);

  return null;
};

export const GraphEvents = ({ 
    initialGraphData, simulationId, now,
    hoverTarget, setHoverTarget, selection, colorOverrides
}: GraphEventsProps) => {
  const { selectedItemData, setSelectedItemData, clearSelection } = selection;
  const { designMode } = useSimulationContext();
  useEffect(() => {
    setHoverTarget(null);
    clearSelection();
  }, [simulationId, designMode, setHoverTarget, clearSelection]);
  const { activeItemsRef, edgeKeysRef } = useGraphRuntime();
  const updateSelectedItem = useCallback((itemId: string, item: ItemResponse) => {
    setSelectedItemData(current => current?.id === itemId
      ? { ...current, ...item, label: item.name ?? current.label }
      : current);
  }, [setSelectedItemData]);
  
  // 1. Load Data
  useGraphLoader(initialGraphData, activeItemsRef, edgeKeysRef, undefined, colorOverrides);

  // 2. Handle WebSockets & Speed Adjustments
  const { adjustItemsForSpeedChange } = useGraphLiveEvents(
    activeItemsRef,
    edgeKeysRef,
    simulationId,
    now,
    undefined,
    updateSelectedItem,
  );

  // 3. Handle Interactions (Drag, Drop, Edit)
  const { 
      selectedEdgeData, setSelectedEdgeData, handleEdgeSubmit, handleEdgeDelete,
      selectedNodeData, setSelectedNodeData, handleNodeSubmit, handleNodeDelete,
      lineCoordinates, draggedNodeRef, setIsDetailsOpen, handleItemSubmit, handleItemDelete
  } = useGraphInteractions(adjustItemsForSpeedChange, { setHoverTarget, ...selection }, simulationId);

  // 4. Handle Physics (Animation Loop)
  useGraphAnimation(activeItemsRef, edgeKeysRef, now, draggedNodeRef);


  const handleNodeClose = useCallback(() => setSelectedNodeData(null), [setSelectedNodeData]);
  const handleEdgeClose = useCallback(() => { setSelectedEdgeData(null); setHoverTarget(null); setIsDetailsOpen(false); }, [setHoverTarget, setIsDetailsOpen, setSelectedEdgeData]);
  const handleItemClose = useCallback(() => { setSelectedItemData(null); setHoverTarget(null); setIsDetailsOpen(false); }, [setHoverTarget, setIsDetailsOpen, setSelectedItemData]);

  return (
    <>
      {import.meta.env.VITE_GRAPH_TEST_API === "true" && (
        <GraphTestApiBridge activeItemsRef={activeItemsRef} now={now} />
      )}

      {!designMode && <LiveHud activeItemsRef={activeItemsRef} simulationId={simulationId} />}

      {/* SVG Line for Edge Creation */}
      {/* Converted inline styles to Tailwind classes */}
      <svg className="absolute top-0 left-0 w-full h-full pointer-events-none z-[100]">
        {lineCoordinates && (
          <line 
            x1={lineCoordinates.x1} 
            y1={lineCoordinates.y1} 
            x2={lineCoordinates.x2} 
            y2={lineCoordinates.y2} 
            className="stroke-[#ff5500] stroke-2" 
          />
        )}
      </svg>
    
      {/* Paradox Hover Overlay */}
      {hoverTarget && !selectedItemData && (
        <HoverOverlay 
            position={{ x: hoverTarget.x, y: hoverTarget.y }}
            onLock={() => {
                setSelectedItemData(hoverTarget.attributes);
                setHoverTarget(null);
                setIsDetailsOpen(true);
            }}
        />
      )}

      {/* Editors */}
      {selectedItemData && (
        <ItemEditor
            onSubmit={handleItemSubmit}
            onDelete={handleItemDelete}
            data={selectedItemData} 
            onClose={handleItemClose} 
        />
      )}
      {selectedEdgeData && (
        <EdgeEditor 
            data={selectedEdgeData} 
            onSubmit={handleEdgeSubmit} 
            onClose={handleEdgeClose} 
            onDelete={(id, src, tgt) => handleEdgeDelete(id, src, tgt)} 
        />
      )}
      {selectedNodeData && (
        <NodeEditor 
            data={selectedNodeData} 
            onSubmit={handleNodeSubmit} 
            onClose={handleNodeClose} 
            onDelete={handleNodeDelete} 
        />
      )}
      
      <ControlsContainer position={"bottom-right"}>
        <ZoomControl />
        <FullScreenControl />
      </ControlsContainer>
    </>
  );
};
