import React, { useEffect, useState, useRef, useCallback } from "react";
import {
  SigmaContainer,
  useSigma,
  useLoadGraph,
  ControlsContainer,
  ZoomControl,
  FullScreenControl,
  EdgeReducer,
  useRegisterEvents
} from "@react-sigma/core";
import { MultiDirectedGraph } from "graphology";
import { NodeSquareProgram } from "@sigma/node-square";
import "@react-sigma/core/lib/react-sigma.min.css";

// --- API CLIENT IMPORTS ---
// (Assuming your generated client is in a folder named 'api' in the same directory)
import {
    LocationControllerApi,
    LocationConnectionControllerApi,
    LocationInput,
    ConnectionInput,
    UpdateModel,
} from "../api-client/api";


const hashToNumber = (s: string) => {
  let hash = 0;
  for (let i = 0; i < s.length; i++) {
    const char = s.charCodeAt(i);
    hash = (hash << 5) - hash + char;
    hash = hash & hash;
  }
  return Math.abs(hash);
};

export interface GraphData {
  locations: Array<{
    id: string;
    name: string;
    longitude?: number;
    latitude?: number;
    speed: number;
    length: number;
    items?: Array<{ id: string; name: string }>;
  }>;
  connections: Array<{
    sourceId: string;
    targetId: string;
  }>;
}

interface NodeEditorData {
  nodeId: string;
  name: string;
}

interface NodeEditorProps {
  data: NodeEditorData;
  onClose: () => void;
  onSubmit: (updatedData: { name: string }) => void;
  onDelete: (nodeId: string) => void;
}

const NodeEditor = ({ data, onClose, onSubmit, onDelete }: NodeEditorProps) => {
  const [name, setName] = useState(data.name);

  const handleSubmit = () => {
    if (name.trim()) {
      onSubmit({ name: name.trim() });
      onClose();
    }
  };

  const handleDelete = () => {
    if (window.confirm(`Are you sure you want to delete "${data.name}"?`)) {
      onDelete(data.nodeId);
      onClose();
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      handleSubmit();
    } else if (e.key === 'Escape') {
      onClose();
    }
  };

  return (
    <div style={editorStyle}>
      <h4>Edit Node</h4>
      
      <label>Name:</label>
      <input 
        type="text" 
        value={name} 
        onChange={e => setName(e.target.value)}
        onKeyPress={handleKeyPress}
        autoFocus
      />

      <div style={iconGroupStyle}>
        <button onClick={handleDelete} title="Delete">🗑️</button>
        <button onClick={onClose} title="Close">❌</button>
        <button onClick={handleSubmit} title="Submit">✔️</button>
      </div>
    </div>
  );
};

const sigmaStyle: React.CSSProperties = {
  width: '100%',
  height: '100%',
  backgroundColor: '#fff',
};

// --- Edge Editor Component ---
interface EdgeEditorData {
  edgeId: string;
  sourceId: string;
  targetId: string;
  speed: number;
  length: number;
}

interface EdgeEditorProps {
  data: EdgeEditorData;
  onClose: () => void;
  onSubmit: (updatedData: { speed: number; length: number; isReversed: boolean }) => void;
  onDelete: (edgeId: string, sourceId: string, targetId: string) => void;
}

const EdgeEditor = ({ data, onClose, onSubmit, onDelete }: EdgeEditorProps) => {
  const [speed, setSpeed] = useState(data.speed);
  const [length, setLength] = useState(data.length);
  const [isReversed, setIsReversed] = useState(false);

  const handleSubmit = () => {
    onSubmit({ speed: Number(speed), length: Number(length), isReversed });
    onClose();
  };

  const handleDelete = () => {
    if (window.confirm(`Are you sure you want to delete the path from ${data.sourceId} to ${data.targetId}?`)) {
      onDelete(data.edgeId, data.sourceId, data.targetId);
      onClose();
    }
  };

  const currentSourceLabel = isReversed ? data.targetId : data.sourceId;
  const currentTargetLabel = isReversed ? data.sourceId : data.targetId;

  return (
    <div style={editorStyle}>
      <h4>Edit Path</h4>
      <p><b>From:</b> {currentSourceLabel}</p>
      <p><b>To:</b> {currentTargetLabel}</p>
      
      <label>Speed:</label>
      <input type="number" value={speed} min="0" onChange={e => setSpeed(parseFloat(e.target.value))} />
      
      <label>Length:</label>
      <input type="number" value={length} min="0" onChange={e => setLength(parseFloat(e.target.value))} />
      
      <div style={buttonGroupStyle}>
        <button onClick={() => setIsReversed(!isReversed)}>
          {isReversed ? 'Direction Reversed' : 'Reverse Direction'}
        </button>
      </div>

      <div style={iconGroupStyle}>
        <button onClick={handleDelete} title="Delete">🗑️</button>
        <button onClick={onClose} title="Close">❌</button>
        <button onClick={handleSubmit} title="Submit">✔️</button>
      </div>
    </div>
  );
};

const editorStyle: React.CSSProperties = {
  position: 'absolute', top: '10px', right: '10px', background: 'white',
  padding: '10px', border: '1px solid #ccc', borderRadius: '8px',
  boxShadow: '0 2px 10px rgba(0,0,0,0.1)', zIndex: 110,
  display: 'flex', flexDirection: 'column', gap: '8px'
};
const buttonGroupStyle: React.CSSProperties = { display: 'flex', justifyContent: 'center', marginTop: '10px' };
const iconGroupStyle: React.CSSProperties = { display: 'flex', justifyContent: 'space-between', marginTop: '10px' };

// --- Graph Logic Component ---
interface AnimationState {
  sourceId: string;
  targetId: string;
  startTime: number;
  duration: number;
}

interface LineCoordinates {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

interface GraphEventsProps {
  initialGraphData: GraphData;
  setHoveredEdge: (edge: string | null) => void;
}

const GraphEvents = ({ initialGraphData, setHoveredEdge }: GraphEventsProps) => {
  const sigma = useSigma();
  const registerEvents = useRegisterEvents();
  const loadGraph = useLoadGraph();
  
  // --- API Client Instances ---
  const locationApi = useRef(new LocationControllerApi()).current;
  const connectionApi = useRef(new LocationConnectionControllerApi()).current;

  const draggedNodeRef = useRef<string | null>(null);
  const isDraggingRef = useRef<boolean>(false);
  const [selectedEdgeData, setSelectedEdgeData] = useState<EdgeEditorData | null>(null);
  const [selectedNodeData, setSelectedNodeData] = useState<NodeEditorData | null>(null);
  const didMoveRef = useRef<boolean>(false);
  
  const [animatingItems, setAnimatingItems] = useState<Record<string, AnimationState>>({});
  const animatingItemsRef = useRef(animatingItems);
  animatingItemsRef.current = animatingItems;
  const animationFrameId = useRef<number | null>(null);
  
  const selectedEdgeRef = useRef(null);
  const selectedNodeRef = useRef(null);
  selectedEdgeRef.current = selectedEdgeData;
  selectedNodeRef.current = selectedNodeData;

  const [lineCoordinates, setLineCoordinates] = useState<LineCoordinates | null>(null);
  const isAddingEdgeRef = useRef<boolean>(false);
  const edgeSourceNodeRef = useRef<string | null>(null);
  const wasAddingEdgeRef = useRef<boolean>(false);

  useEffect(() => {
    const graph = new MultiDirectedGraph();
    console.log("Building graph with data:", initialGraphData); // Log the incoming data

    initialGraphData.locations.forEach(location => {
      graph.addNode(location.id, {
        x: location.longitude ?? hashToNumber(location.id),
        y: location.latitude ?? hashToNumber(location.id + "random"),
        label: location.name, size: 10, color: "#69b3a2",
        speed: location.speed, length: location.length,
        id: location.id
      });
    });
    initialGraphData.locations.forEach(location => {
      (location.items || []).forEach(item => {
        graph.addNode(item.id, {
          x: location.longitude ?? hashToNumber(location.id),
          y: location.latitude ?? hashToNumber(location.id + "random"),
          label: item.name, size: 8, color: "#FF0000", type: "square",
          initialLocationId: location.id,
          id: item.id
        });
      });
    });
    initialGraphData.connections.forEach(c => {
      if (graph.hasNode(c.sourceId) && graph.hasNode(c.targetId)) {
        graph.addEdge(c.sourceId, c.targetId, { type: 'arrow', size: 5 });
      }
    });
    loadGraph(graph);
    const initialAnimations: Record<string, AnimationState> = {};
    graph.forEachNode((node, attrs) => {
      if (attrs.type === "square") {
        const sourceLocAttrs = graph.getNodeAttributes(attrs.initialLocationId);
        const nextTargets = graph.outEdges(attrs.initialLocationId).map(edge => graph.target(edge));
        const uniqueTargets = [...new Set(nextTargets)];

        if (sourceLocAttrs && sourceLocAttrs.speed > 0 && uniqueTargets.length === 1) {
          const targetId = uniqueTargets[0];
          const duration = (sourceLocAttrs.length / sourceLocAttrs.speed) * 1000;
          initialAnimations[node] = { sourceId: attrs.initialLocationId, targetId, startTime: Date.now(), duration };
        }
      }
    });
    setAnimatingItems(initialAnimations);
  }, [initialGraphData, loadGraph]);

  useEffect(() => {
    const animate = () => {
      const graph = sigma.getGraph();
      if (!graph) {
        animationFrameId.current = requestAnimationFrame(animate);
        return;
      }
      const currentTime = Date.now();
      let needsRefresh = false;
      let itemsHaveChanged = false;
      const nextAnimatingItems = { ...animatingItemsRef.current };
      for (const itemId in animatingItemsRef.current) {
        if (itemId === draggedNodeRef.current) continue;
        const anim = animatingItemsRef.current[itemId];
        const sourceNode = graph.getNodeAttributes(anim.sourceId);
        const targetNode = graph.getNodeAttributes(anim.targetId);
        if (!sourceNode || !targetNode) continue;
        const progress = Math.min((currentTime - anim.startTime) / anim.duration, 1);
        graph.setNodeAttribute(itemId, "x", sourceNode.x + (targetNode.x - sourceNode.x) * progress);
        graph.setNodeAttribute(itemId, "y", sourceNode.y + (targetNode.y - sourceNode.y) * progress);
        needsRefresh = true;
        if (progress >= 1) {
          itemsHaveChanged = true;
          const newSourceId = anim.targetId;
          const newSourceLocationAttrs = graph.getNodeAttributes(newSourceId);
          const nextPossibleTargets = graph.outEdges(newSourceId).map(edge => graph.target(edge));
          const uniqueNextTargets = [...new Set(nextPossibleTargets)];
          if (uniqueNextTargets.length === 1 && newSourceLocationAttrs && newSourceLocationAttrs.speed > 0) {
            const newTargetId = uniqueNextTargets[0];
            const duration = (newSourceLocationAttrs.length / newSourceLocationAttrs.speed) * 1000;
            nextAnimatingItems[itemId] = { sourceId: newSourceId, targetId: newTargetId, startTime: Date.now(), duration };
          } else {
            delete nextAnimatingItems[itemId];
          }
        }
      }
      if (needsRefresh) sigma.refresh();
      if (itemsHaveChanged) {
        setAnimatingItems(nextAnimatingItems);
      }
      animationFrameId.current = requestAnimationFrame(animate);
    };
    animationFrameId.current = requestAnimationFrame(animate);
    return () => {
      if (animationFrameId.current) cancelAnimationFrame(animationFrameId.current);
    };
  }, [sigma]);

  const handleEdgeSubmit = useCallback(async ({ speed, length, isReversed }: { speed: number; length: number; isReversed: boolean }) => {
    if (!selectedEdgeData) return;
    const graph = sigma.getGraph();
    const { sourceId, targetId, edgeId } = selectedEdgeData;
    const sourceOfEdit = isReversed ? targetId : sourceId;
    
    const finalSpeed = parseFloat(String(speed));
    const finalLength = parseFloat(String(length));

    // Optimistic UI update
    graph.setNodeAttribute(sourceOfEdit, 'speed', finalSpeed);
    graph.setNodeAttribute(sourceOfEdit, 'length', finalLength);
    console.log(finalSpeed, finalLength)
    // API Call to update node properties
    try {
        const updatePayload: UpdateModel = { id: sourceOfEdit, properties: { "speed": finalSpeed, "length": finalLength } };
        await locationApi.updateLocation(updatePayload);
    } catch (error) {
        console.error("Failed to update edge properties (speed/length):", error);
        // TODO: Add UI feedback for failed save
    }

    if (isReversed) {
      if (graph.hasEdge(edgeId)) {
        // Optimistic UI update
        graph.dropEdge(edgeId);
        graph.addEdge(targetId, sourceId, { type: 'arrow', size: 5 });

        // TODO: API CALL TO REVERSE EDGE
        // This is complex because the current API client does not seem to support deleting a single edge.
        // A robust implementation would require:
        // 1. An API endpoint to DELETE a specific connection (e.g., DELETE /api/connections?from=A&to=B)
        // 2. An API call here to delete the old edge.
        // 3. An API call to create the new edge.
        console.warn("Edge reversal in UI only. API call for deletion is not implemented due to API limitations.");

        // We can still create the new edge in the backend
        try {
            const connectionPayload: ConnectionInput = { location1Id: targetId, location2Id: sourceId };
            await connectionApi.createConnection1(connectionPayload);
        } catch (error) {
            console.error("Failed to create reversed edge in backend:", error);
        }
      }
    }
    sigma.refresh();
  }, [sigma, selectedEdgeData, locationApi, connectionApi]);

  const handleNodeSubmit = useCallback(async ({ name }: { name: string }) => {
    if (!selectedNodeData) return;
    const graph = sigma.getGraph();
    const { nodeId } = selectedNodeData;
    
    // Optimistic UI update
    graph.setNodeAttribute(nodeId, 'label', name);
    sigma.refresh();

    // API Call
    try {
        const updatePayload: UpdateModel = { id: nodeId, properties: { name } };
        await locationApi.updateLocation(updatePayload);
    } catch (error) {
        console.error("Failed to update node name:", error);
        // TODO: Revert UI change and show error message
        graph.setNodeAttribute(nodeId, 'label', selectedNodeData.name); // Revert
        sigma.refresh();
    }
  }, [sigma, selectedNodeData, locationApi]);

  const handleEdgeDelete = useCallback(async (edgeId: string, sourceId: string, targetId: string) => {
    const graph = sigma.getGraph();
    if (graph.hasEdge(edgeId)) {
      // Optimistic UI update
      graph.dropEdge(edgeId);
      sigma.refresh();
      
      await connectionApi.deleteConnection(sourceId, targetId)
    }
  }, [sigma, connectionApi]);

  const handleNodeDelete = useCallback(async (nodeId: string) => {
    const graph = sigma.getGraph();
    if (graph.hasNode(nodeId)) {
      // Optimistic UI update
      const oldNodeAttributes = graph.getNodeAttributes(nodeId);
      graph.dropNode(nodeId);
      sigma.refresh();

      // API Call
      try {
          await locationApi.deleteLocation(nodeId);
      } catch (error) {
          console.error("Failed to delete node:", error);
          // TODO: Re-add node to graph and show error
          graph.addNode(nodeId, oldNodeAttributes);
          sigma.refresh();
      }
    }
  }, [sigma, locationApi]);

  const addNode = useCallback(async (x: number, y: number) => {
    const graph = sigma.getGraph();
    if (!graph) return;
    
    // Use a temporary ID for the optimistic UI update
    const newNodeId = crypto.randomUUID();
    const newNodeLabel = "New Location";
    
    // Optimistic UI update
    graph.addNode(newNodeId, { x, y, label: newNodeLabel, size: 10, color: "#69b3a2", speed: 10, length: 50 });
    sigma.refresh(); // Refresh to show the new node immediately

    // API Call to create the node in the backend
    try {
        const locationPayload: LocationInput = {
            id: newNodeId,
            name: newNodeLabel,
            longitude: x,
            latitude: y,
            speed: 10,
            length: 50,
            active: true
        };
        const response = await locationApi.createLocation(locationPayload);
        
        // Optional: If the backend returns a different ID, you might need to update the graph.
        // This can be complex, for now we assume the frontend ID is used.
        if (response.data.id !== newNodeId) {
            console.log("Backend assigned a new ID:", response.data.id);
            // Logic to replace node ID in graph if necessary
        }
    } catch (error) {
        console.error("Failed to create node:", error);
        // On failure, remove the optimistically added node
        graph.dropNode(newNodeId);
        sigma.refresh();
    }

  }, [sigma, locationApi]);

    // --- DEBOUNCED UPDATE FOR NODE DRAGGING ---
    const debounceTimeoutRef = useRef<NodeJS.Timeout | null>(null);
    const debouncedUpdateNodePosition = useCallback((nodeId: string, x: number, y: number) => {
        if (debounceTimeoutRef.current) {
            clearTimeout(debounceTimeoutRef.current);
        }
        debounceTimeoutRef.current = setTimeout(async () => {
            try {
                const updatePayload: UpdateModel = {
                    id: nodeId,
                    properties: { longitude: x, latitude: y }
                };
                await locationApi.updateLocation(updatePayload);
            } catch (error) {
                console.error("Failed to update node position:", error);
                // TODO: Add logic to revert the node's position in the UI
            }
        }, 500); // 500ms delay
    }, [locationApi]);


  useEffect(() => {
    registerEvents({
      enterEdge: ({ edge }) => setHoveredEdge(edge),
      leaveEdge: () => setHoveredEdge(null),
      clickEdge: ({ edge }) => {
        const graph = sigma.getGraph();
        if (!graph.hasEdge(edge)) return;
        const sourceId = graph.source(edge);
        const targetId = graph.target(edge);
        const sourceAttrs = graph.getNodeAttributes(sourceId);
        setSelectedEdgeData({
          edgeId: edge, sourceId, targetId,
          speed: sourceAttrs.speed || 0, length: sourceAttrs.length || 0,
        });
      },
      clickStage: ({ event }) => {
        if (selectedNodeRef.current){
          setSelectedNodeData(null);
          return;
        }
        if (wasAddingEdgeRef.current) {
          wasAddingEdgeRef.current = false;
          return;
        }
        if (selectedEdgeRef.current) {
          setSelectedEdgeData(null);
        } else if (!isDraggingRef.current) {
          const pos = sigma.viewportToGraph(event);
          addNode(pos.x, pos.y);
        }
      },
      downNode: ({ node, event }) => {
        didMoveRef.current = false;
        if (event.original.altKey) {
          wasAddingEdgeRef.current = true; 
          event.preventSigmaDefault();
          isAddingEdgeRef.current = true;
          edgeSourceNodeRef.current = node;
          const nodeDisplayData = sigma.getNodeDisplayData(node);
          if (nodeDisplayData) {
            setLineCoordinates({
              x1: event.x, y1: event.y,
              x2: event.x, y2: event.y,
            });
          }
        } else {
          setSelectedEdgeData(null);
          if (sigma.getGraph().getNodeAttribute(node, "type") !== "square") {
            isDraggingRef.current = true;
            draggedNodeRef.current = node;
            sigma.getSettings().mouseEnabled = false;
          }
        }
      },
      upNode: async ({ node }) => {
        if (isAddingEdgeRef.current && edgeSourceNodeRef.current && edgeSourceNodeRef.current !== node) {
          const graph = sigma.getGraph();
          const source = edgeSourceNodeRef.current;
          const target = node;
          if (!graph.hasEdge(source, target)) {
            // Optimistic UI update
            graph.addEdge(source, target, { type: 'arrow', size: 5 });
            
            // API Call
            try {
                const connectionPayload: ConnectionInput = { location1Id: source, location2Id: target };
                await connectionApi.createConnection1(connectionPayload);
            } catch (error) {
                console.error("Failed to create connection:", error);
                // On failure, remove the optimistically added edge
                graph.dropEdge(source, target);
            }
          }
        }
      },
      mouseup: () => {
        if (isAddingEdgeRef.current) {
          isAddingEdgeRef.current = false;
          edgeSourceNodeRef.current = null;
          setLineCoordinates(null);
        }
        if (isDraggingRef.current) {
            const draggedNodeId = draggedNodeRef.current;
            if (draggedNodeId) {
                const graph = sigma.getGraph();
                const attrs = graph.getNodeAttributes(draggedNodeId);
                // Trigger the debounced save on mouse up
                debouncedUpdateNodePosition(draggedNodeId, attrs.x, attrs.y);
            }
            isDraggingRef.current = false;
            draggedNodeRef.current = null;
            sigma.getSettings().mouseEnabled = true;
        }
      },
      mousemove: (event) => {
        if (isDraggingRef.current || isAddingEdgeRef.current) {
          didMoveRef.current = true;
        }

        if (isDraggingRef.current && draggedNodeRef.current) {
          event.preventSigmaDefault();
          const pos = sigma.viewportToGraph(event);
          sigma.getGraph().setNodeAttribute(draggedNodeRef.current, "x", pos.x);
          sigma.getGraph().setNodeAttribute(draggedNodeRef.current, "y", pos.y);
          return;
        }

        if (isAddingEdgeRef.current && edgeSourceNodeRef.current) {
          event.preventSigmaDefault();
          setLineCoordinates(coords => {
            if (!coords) return null;
            return {
              ...coords,
              x2: event.x,
              y2: event.y,
            };
          });
        }
      },
      clickNode: ({ node }) => {
        if (didMoveRef.current) {
          return;
        }
        if (!isAddingEdgeRef.current && !isDraggingRef.current){
          const graph = sigma.getGraph();
          const attr = graph.getNodeAttributes(node)
          setSelectedNodeData({nodeId: node, name: attr.label})
        }
      }
    });
  }, [sigma, registerEvents, addNode, setHoveredEdge, connectionApi, debouncedUpdateNodePosition]);

  return (
    <>
      <svg
        style={{
          position: 'absolute', top: 0, left: 0,
          width: '100%', height: '100%',
          pointerEvents: 'none',
          zIndex: 100,
        }}
      >
        {lineCoordinates && (
          <line
            x1={lineCoordinates.x1} y1={lineCoordinates.y1}
            x2={lineCoordinates.x2} y2={lineCoordinates.y2}
            stroke="#ff5500" strokeWidth="2"
          />
        )}
      </svg>
    
      {selectedEdgeData && (
        <EdgeEditor
          data={selectedEdgeData}
          onSubmit={handleEdgeSubmit}
          onClose={() => setSelectedEdgeData(null)}
          onDelete={(edgeId, sourceId, targetId) => handleEdgeDelete(edgeId, sourceId, targetId)}
        />
      )}
      {selectedNodeData && (
        <NodeEditor
          data={selectedNodeData}
          onSubmit={handleNodeSubmit}
          onClose={() => setSelectedNodeData(null)}
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

// --- Main Display Component ---
export const DisplayGraph = ({ initialGraphData }: { initialGraphData: GraphData }) => {
  const [hoveredEdge, setHoveredEdge] = useState<string | null>(null);

  const edgeReducer = useCallback<EdgeReducer>((edge, attrs) => {
    if (hoveredEdge === edge) {
      return { ...attrs, color: "#ff5500", size: 7 };
    }
    return attrs;
  }, [hoveredEdge]);

  return (
    <div style={{ width: '100%', height: '100%', position: 'relative' }}>
      <SigmaContainer
        style={{ ...sigmaStyle, cursor: hoveredEdge ? 'pointer' : 'default' }}
        settings={{
          nodeProgramClasses: { square: NodeSquareProgram },
          enableEdgeEvents: true,
          autoRescale: true
        }}
        edgeReducer={edgeReducer}
      >
        <GraphEvents initialGraphData={initialGraphData} setHoveredEdge={setHoveredEdge} />
      </SigmaContainer>
    </div>
  );
};

export default DisplayGraph;