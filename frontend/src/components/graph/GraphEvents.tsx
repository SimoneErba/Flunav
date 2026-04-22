import { useCallback, useRef } from "react";
import { ControlsContainer, ZoomControl, FullScreenControl } from "@react-sigma/core";
import { DisplayRuleColorResult, GraphData, ItemResponse } from "../../api-client/api";
import { EdgeEditor } from "../editors/edge.editor";
import { NodeEditor } from "../editors/node.editor";

// Hooks
import { useGraphLoader } from "./hooks/useGraphLoader";
import { useGraphAnimation } from "./hooks/useGraphAnimation";
import { useGraphLiveEvents } from "./hooks/useGraphLiveEvents";
import { useGraphInteractions } from "./hooks/useGraphInteractions";
import { HoverOverlay } from "./HoverOverlay";
import { ItemEditor, ItemEditorData } from "../editors/item.editor";
import { HoverTarget } from "./DisplayGraph";

interface GraphEventsProps {
  initialGraphData: GraphData;
  simulationId?: string;
  simTime: number;
  hoverTarget: HoverTarget | null;
  setHoverTarget: (t: HoverTarget | null) => void;
  selectedItemData: ItemEditorData | null;
  setSelectedItemData: (d: ItemEditorData | null) => void;
  colorOverrides?: DisplayRuleColorResult | null;
}

export const GraphEvents = ({ 
    initialGraphData, simulationId, simTime,
    hoverTarget, setHoverTarget, selectedItemData, setSelectedItemData, colorOverrides
}: GraphEventsProps) => {
  const activeItemsRef = useRef<Map<string, ItemResponse>>(new Map());
  
  // 1. Load Data
  useGraphLoader(initialGraphData, activeItemsRef, colorOverrides);

  // 2. Handle WebSockets & Speed Adjustments
  const { adjustItemsForSpeedChange } = useGraphLiveEvents(activeItemsRef, simulationId, simTime);

  // 3. Handle Interactions (Drag, Drop, Edit)
  const { 
      selectedEdgeData, setSelectedEdgeData, handleEdgeSubmit, handleEdgeDelete,
      selectedNodeData, setSelectedNodeData, handleNodeSubmit, handleNodeDelete,
      lineCoordinates, draggedNodeRef, setIsDetailsOpen, handleItemSubmit, handleItemDelete
  } = useGraphInteractions(adjustItemsForSpeedChange, { hoverTarget, setHoverTarget, selectedItemData, setSelectedItemData }, simulationId);

  // 4. Handle Physics (Animation Loop)
  useGraphAnimation(activeItemsRef, simTime, draggedNodeRef);


  const handleNodeClose = useCallback(() => setSelectedNodeData(null), [setSelectedNodeData]);
  const handleEdgeClose = useCallback(() => { setSelectedEdgeData(null); setHoverTarget(null); setIsDetailsOpen(false); }, [setHoverTarget, setIsDetailsOpen, setSelectedEdgeData]);
  const handleItemClose = useCallback(() => { setSelectedItemData(null); setHoverTarget(null); setIsDetailsOpen(false); }, [setHoverTarget, setIsDetailsOpen, setSelectedItemData]);

  return (
    <>
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
