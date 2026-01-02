import { useRef } from "react";
import { ControlsContainer, ZoomControl, FullScreenControl } from "@react-sigma/core";
import { GraphData, ItemResponse } from "../../api-client/api";
import { EdgeEditor } from "./../edge.editor";
import { NodeEditor } from "./../node.editor";

// Hooks
import { useGraphLoader } from "./hooks/useGraphLoader";
import { useGraphAnimation } from "./hooks/useGraphAnimation";
import { useGraphLiveEvents } from "./hooks/useGraphLiveEvents";
import { useGraphInteractions } from "./hooks/useGraphInteractions";
import { HoverOverlay } from "./HoverOverlay";
import { ItemEditor } from "../item.editor";
import { HoverTarget } from "./DisplayGraph";

interface GraphEventsProps {
  initialGraphData: GraphData;
  simulationId?: string;
  simTime: number;
  hoverTarget: HoverTarget | null;
  setHoverTarget: (t: HoverTarget | null) => void;
  selectedItemData: any | null;
  setSelectedItemData: (d: any | null) => void;
}

export const GraphEvents = ({ 
    initialGraphData, simulationId, simTime,
    hoverTarget, setHoverTarget, selectedItemData, setSelectedItemData 
}: GraphEventsProps) => {
  const activeItemsRef = useRef<Map<string, ItemResponse>>(new Map());
  // 1. Load Data
  useGraphLoader(initialGraphData, activeItemsRef);

  // 2. Handle WebSockets & Speed Adjustments
  const { adjustItemsForSpeedChange } = useGraphLiveEvents(activeItemsRef, simulationId, simTime);

  // 3. Handle Interactions (Drag, Drop, Edit)
  const { 
      selectedEdgeData, setSelectedEdgeData, handleEdgeSubmit, handleEdgeDelete,
      selectedNodeData, setSelectedNodeData, handleNodeSubmit, handleNodeDelete,
      lineCoordinates, draggedNodeRef, setIsDetailsOpen
  } = useGraphInteractions(adjustItemsForSpeedChange, { hoverTarget, setHoverTarget, selectedItemData, setSelectedItemData }, simulationId);

  // 4. Handle Physics (Animation Loop)
  useGraphAnimation(activeItemsRef, simTime, draggedNodeRef);

  return (
    <>
      {/* SVG Line for Edge Creation */}
      <svg style={{ position: 'absolute', top: 0, left: 0, width: '100%', height: '100%', pointerEvents: 'none', zIndex: 100 }}>
        {lineCoordinates && (
          <line x1={lineCoordinates.x1} y1={lineCoordinates.y1} x2={lineCoordinates.x2} y2={lineCoordinates.y2} stroke="#ff5500" strokeWidth="2" />
        )}
      </svg>
    
      {hoverTarget && !selectedItemData && (
        <HoverOverlay 
            position={{ x: hoverTarget.x, y: hoverTarget.y }}
            onCancel={() => setHoverTarget(null)}
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
            data={selectedItemData} 
            onClose={() => setSelectedItemData(null)} 
        />
      )}
      {selectedEdgeData && (
        <EdgeEditor data={selectedEdgeData} onSubmit={handleEdgeSubmit} onClose={() => setSelectedEdgeData(null)} onDelete={(id, src, tgt) => handleEdgeDelete(id, src, tgt)} />
      )}
      {selectedNodeData && (
        <NodeEditor data={selectedNodeData} onSubmit={handleNodeSubmit} onClose={() => setSelectedNodeData(null)} onDelete={handleNodeDelete} />
      )}
      
      <ControlsContainer position={"bottom-right"}>
        <ZoomControl />
        <FullScreenControl />
      </ControlsContainer>
    </>
  );
};