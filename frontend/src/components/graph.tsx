import React, { useEffect, useState, useRef, useCallback } from "react";
import {
  SigmaContainer,
  useSigma,
  useLoadGraph,
  ControlsContainer,
  ZoomControl,
  FullScreenControl,
  useRegisterEvents
} from "@react-sigma/core";
import { MultiDirectedGraph } from "graphology";
import { NodeSquareProgram } from "@sigma/node-square";
import "@react-sigma/core/lib/react-sigma.min.css";
import { useWebSocket } from './../hooks/useWebSocket';
// --- API CLIENT IMPORTS ---
import {
    LocationInput,
    LocationResponse,
    CreateConveyorInput, // New Input type
    ConveyorResponse, // New Response type
    ItemInput,
    ItemResponse,
    CoordinatesUpdateRequest,
    GraphData
} from "../api-client/api";
import { ConnectionMessage, EntityUpdateMessage } from "../websocket-types/websocket-types";
import { useApi } from "../hooks/useApi";
import { EdgeEditor, EdgeEditorData } from "./edge.editor";
import { NodeEditor, NodeEditorData } from "./node.editor";
import { sigmaStyle } from "../styles/styles";
import { GraphThemeProvider, GraphThemeController, ThemedBackground} from "../context/theme.context";
import { ThemeToggle } from "./theme.toggle";

const hashToNumber = (s: string) => {
  let hash = 0;
  for (let i = 0; i < s.length; i++) {
    const char = s.charCodeAt(i);
    hash = (hash << 5) - hash + char;
    hash = hash & hash;
  }
  return Math.abs(hash);
};

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
  simulationId?: string;
}

// Updated to calculate animation based on Edge (Conveyor) properties
const calculateNextAnimation = (
  graph: MultiDirectedGraph,
  currentLocationId: string
): AnimationState | null => {
  if (!graph.hasNode(currentLocationId)) return null;

  // Get outgoing edges
  const outEdges = graph.outEdges(currentLocationId);
  if (outEdges.length === 0) return null;

  // For simplicity, take the first path. In a real scenario, logic might determine the path.
  const edgeId = outEdges[0];
  const targetId = graph.target(edgeId);
  
  const edgeAttrs = graph.getEdgeAttributes(edgeId);

  // Only animate if speed > 0
  if (edgeAttrs && edgeAttrs.speed > 0) {
    const duration = (edgeAttrs.length / edgeAttrs.speed) * 1000;
    return { 
      sourceId: currentLocationId, 
      targetId, 
      startTime: Date.now(), 
      duration 
    };
  }
  return null;
};

const GraphEvents = ({ initialGraphData, setHoveredEdge, simulationId }: GraphEventsProps) => {
  const sigma = useSigma();
  const registerEvents = useRegisterEvents();
  const loadGraph = useLoadGraph();
  const {
      connected,
        subscribeToPositionUpdates,
        subscribeToItemCreated,
        subscribeToItemDeleted,
        subscribeToAllItemUpdates,
        subscribeToLocationCreated,
        subscribeToLocationDeleted,
        subscribeToAllLocationUpdates,
        subscribeToConnectionCreated, // Maps to Conveyor Created
        subscribeToConnectionDeleted, // Maps to Conveyor Deleted
        subscribeToConnectionUpdated  // Maps to Conveyor Updated
  } = useWebSocket();
  
  // --- API Client Instances ---
  // Assuming useApi now exposes conveyorApi
  const { locationApi, conveyorsApi, itemApi } = useApi();

  const animatingItemsRef = useRef<Record<string, AnimationState>>({}); 
  const animationFrameId = useRef<number | null>(null);
  
  // Interaction Refs
  const draggedNodeRef = useRef<string | null>(null);
  const isDraggingRef = useRef<boolean>(false);
  const didMoveRef = useRef<boolean>(false);
  const isAddingEdgeRef = useRef<boolean>(false);
  const edgeSourceNodeRef = useRef<string | null>(null);
  const wasAddingEdgeRef = useRef<boolean>(false);

  // Selection State
  const [selectedEdgeData, setSelectedEdgeData] = useState<EdgeEditorData | null>(null);
  const [selectedNodeData, setSelectedNodeData] = useState<NodeEditorData | null>(null);
  const [lineCoordinates, setLineCoordinates] = useState<LineCoordinates | null>(null);

  // Refs for callbacks
  const selectedEdgeRef = useRef(selectedEdgeData);
  const selectedNodeRef = useRef(selectedNodeData);
  selectedEdgeRef.current = selectedEdgeData;
  selectedNodeRef.current = selectedNodeData;

  useEffect(() => {
    const graph = new MultiDirectedGraph();
    console.log("Building graph with data:", initialGraphData);

    // 1. Add Locations (Nodes)
    initialGraphData?.locations?.forEach((loc: LocationResponse) => {
      graph.addNode(loc.id, {
        x: loc.latitude ?? hashToNumber(loc.id), // Swapped based on typical map projection, or keep as is
        y: loc.longitude ?? hashToNumber(loc.id + "random"),
        label: loc.name,
        size: 10,
        color: "#69b3a2",
        type: "circle", // Locations are points
        id: loc.id,
        capacity: loc.capacity // Store capacity for editing
      });
    });

    // 2. Add Conveyors (Edges)
    initialGraphData?.conveyors?.forEach((conv: ConveyorResponse) => {
      if (graph.hasNode(conv.sourceId) && graph.hasNode(conv.targetId)) {
        let size = 3;
        if (conv.isMainPath) size = 6;

        graph.addEdge(conv.sourceId, conv.targetId, {
          id: conv.id, // Important: Store the Conveyor ID on the edge
          type: 'arrow',
          size: size,
          label: conv.name,
          speed: conv.speed,
          length: conv.length,
          isMainPath: conv.isMainPath
        });
      }
    });

    // 3. Add Items (Nodes) - Top Level
    initialGraphData?.items?.forEach((item: ItemResponse) => {
      // Find where to place the item. 
      // If item.locationId corresponds to a Node, place it there.
      // If it corresponds to a Conveyor, we might need logic to find the source node.
      
      let startX = 0;
      let startY = 0;
      let initialLocId = item.locationId;

      if (graph.hasNode(item.locationId)) {
        const locAttrs = graph.getNodeAttributes(item.locationId);
        startX = locAttrs.x;
        startY = locAttrs.y;
      } else {
        // Fallback: If locationId is not a node, it might be a conveyor ID or null.
        // For visualization, if we can't find the node, we might skip or place at 0,0
        // Or try to find if it's an edge (though graph.hasNode checks nodes).
        // Here we assume items start at a valid Location Node.
        startX = 0; 
        startY = 0;
      }

      graph.addNode(item.id, {
        x: startX,
        y: startY,
        label: item.name,
        size: 6,
        color: "#FF0000",
        type: "square", // Items are squares now to distinguish from location points
        initialLocationId: initialLocId,
        id: item.id,
        isItem: true
      });
    });

    loadGraph(graph);

    // Initialize Animations
    const initialAnimations: Record<string, AnimationState> = {};
    graph.forEachNode((node, attrs) => {
      if (attrs.isItem && attrs.initialLocationId) {
        const anim = calculateNextAnimation(graph, attrs.initialLocationId);
        if (anim) initialAnimations[node] = anim;
      }
    });
    animatingItemsRef.current = initialAnimations;

  }, [initialGraphData, loadGraph]);

  // --- Animation Loop ---
  useEffect(() => {
    const animate = () => {
      const graph = sigma.getGraph();
      if (!graph) {
        animationFrameId.current = requestAnimationFrame(animate);
        return;
      }

      const currentTime = Date.now();
      let needsRefresh = false;
      
      Object.keys(animatingItemsRef.current).forEach(itemId => {
        if (itemId === draggedNodeRef.current) return;
        
        const anim = animatingItemsRef.current[itemId];
        
        // Safety check if nodes still exist
        if (!graph.hasNode(anim.sourceId) || !graph.hasNode(anim.targetId) || !graph.hasNode(itemId)) {
            delete animatingItemsRef.current[itemId];
            return;
        }

        const sourceNode = graph.getNodeAttributes(anim.sourceId);
        const targetNode = graph.getNodeAttributes(anim.targetId);
        
        const progress = Math.min((currentTime - anim.startTime) / anim.duration, 1);
        
        // Interpolate position
        graph.setNodeAttribute(itemId, "x", sourceNode.x + (targetNode.x - sourceNode.x) * progress);
        graph.setNodeAttribute(itemId, "y", sourceNode.y + (targetNode.y - sourceNode.y) * progress);
        needsRefresh = true;

        // Check if finished
        if (progress >= 1) {
          // Snap to target
          graph.setNodeAttribute(itemId, "x", targetNode.x);
          graph.setNodeAttribute(itemId, "y", targetNode.y);

          // Calculate next leg
          const nextAnim = calculateNextAnimation(graph, anim.targetId);
          if (nextAnim) {
            animatingItemsRef.current[itemId] = nextAnim;
          } else {
            delete animatingItemsRef.current[itemId];
          }
        }
      });

      if (needsRefresh) sigma.refresh();
      animationFrameId.current = requestAnimationFrame(animate);
    };

    animationFrameId.current = requestAnimationFrame(animate);
    return () => {
      if (animationFrameId.current) cancelAnimationFrame(animationFrameId.current);
    };
  }, [sigma]);

  // --- WebSocket Event Handling ---
  useEffect(() => {
      if (!connected || !sigma) return;
      const graph = sigma.getGraph();
      if (!graph) return;

      const unsubscribers: (() => void)[] = [];

      // 1. Position Updates
      const handlePositionUpdate = (update: any) => {
          if (!update.locationId || !graph.hasNode(update.itemId)) return;
          
          // If the location is a Node (Waypoint)
          if (graph.hasNode(update.locationId)) {
              const newLocationNode = graph.getNodeAttributes(update.locationId);
              graph.setNodeAttribute(update.itemId, "x", newLocationNode.x);
              graph.setNodeAttribute(update.itemId, "y", newLocationNode.y);

              const nextAnim = calculateNextAnimation(graph, update.locationId);
              if (nextAnim) {
                animatingItemsRef.current[update.itemId] = nextAnim;
              } else {
                delete animatingItemsRef.current[update.itemId];
              }
          }
          // Note: If locationId refers to a Conveyor ID, we might need logic to map Conveyor -> Source Node
          // For now, assuming locationId in updates refers to Nodes.
      };
      unsubscribers.push(subscribeToPositionUpdates(handlePositionUpdate, simulationId));

      // 2. Item CRUD
      const handleItemCreated = (item: ItemInput) => {
          if (graph.hasNode(item.id) || !item.locationId) return;
          
          // Find start coordinates
          let x = 0, y = 0;
          if (graph.hasNode(item.locationId)) {
              const attrs = graph.getNodeAttributes(item.locationId);
              x = attrs.x;
              y = attrs.y;
          }

          graph.addNode(item.id, {
              x, y,
              label: item.name,
              size: 6,
              color: "#FF0000",
              type: "square",
              id: item.id,
              isItem: true
          });
          
          const nextAnim = calculateNextAnimation(graph, item.locationId);
          if (nextAnim) animatingItemsRef.current[item.id] = nextAnim;
      };
      unsubscribers.push(subscribeToItemCreated(handleItemCreated, simulationId));

      const handleItemDeleted = (itemId: string) => {
          if (graph.hasNode(itemId)) {
              graph.dropNode(itemId);
              delete animatingItemsRef.current[itemId];
          }
      };
      unsubscribers.push(subscribeToItemDeleted(handleItemDeleted, simulationId));

      const handleItemUpdates = (update: EntityUpdateMessage) => {
          if (update.id && graph.hasNode(update.id) && update.properties) {
              Object.keys(update.properties).forEach(key => {
                  const value = update.properties![key];
                  graph.setNodeAttribute(update.id, key === 'name' ? 'label' : key, value);
              });
          }
      };
      unsubscribers.push(subscribeToAllItemUpdates(handleItemUpdates, simulationId));

      // 3. Location CRUD (Nodes)
      const handleLocationCreated = (location: LocationInput) => {
          if (graph.hasNode(location.id)) return;
          graph.addNode(location.id, {
              x: location.latitude ?? hashToNumber(location.id),
              y: location.longitude ?? hashToNumber(location.id + "random"),
              label: location.name,
              size: 10,
              color: "#69b3a2",
              type: "circle",
              id: location.id,
              capacity: location.capacity
          });
      };
      unsubscribers.push(subscribeToLocationCreated(handleLocationCreated, simulationId));

      const handleLocationDeleted = (locationId: string) => {
          if (graph.hasNode(locationId)) {
              graph.dropNode(locationId);
          }
      };
      unsubscribers.push(subscribeToLocationDeleted(handleLocationDeleted, simulationId));

      const handleLocationUpdates = (update: EntityUpdateMessage) => {
          if (!update.id || !graph.hasNode(update.id) || !update.properties) return;
          Object.keys(update.properties).forEach(key => {
              const val = update.properties![key];
              graph.setNodeAttribute(update.id, key === 'name' ? 'label' : key, val);
          });
      };
      unsubscribers.push(subscribeToAllLocationUpdates(handleLocationUpdates, simulationId));

      // 4. Conveyor CRUD (Edges)
      const handleConveyorCreated = (connection: ConnectionMessage) => {
          // connection.from = sourceId, connection.to = targetId
          const { from, to, data } = connection;
          if (graph.hasNode(from) && graph.hasNode(to)) {
              // Check if edge exists
              if (!graph.hasEdge(from, to)) {
                  const speed = data?.speed ?? 1.0;
                  const length = data?.length ?? 10.0;
                  const isMainPath = data?.isMainPath ?? false;
                  const label = data?.name ?? "";
                  const id = data?.id;

                  graph.addEdge(from, to, { 
                      id: id,
                      type: 'arrow', 
                      size: isMainPath ? 6 : 3,
                      label: label,
                      speed: speed,
                      length: length,
                      isMainPath: isMainPath
                  });
              }
          }
      };
      unsubscribers.push(subscribeToConnectionCreated(handleConveyorCreated, simulationId));

      const handleConveyorDeleted = (connection: ConnectionMessage) => {
          if (graph.hasEdge(connection.from, connection.to)) {
              graph.dropEdge(connection.from, connection.to);
          }
      };
      unsubscribers.push(subscribeToConnectionDeleted(handleConveyorDeleted, simulationId));

      // Handle Conveyor Property Updates (Speed, Length, etc.)
      // We need a subscription for this. Assuming subscribeToConnectionUpdated exists or we use a generic one.
      if (subscribeToConnectionUpdated) {
          const handleConveyorUpdate = (update: EntityUpdateMessage) => {
             // The update.id is the Conveyor ID.
             // Sigma edges are usually referenced by Source->Target or by generated ID.
             // If we stored the ID in the edge attributes, we can find it.
             const edge = graph.findEdge((edge, attrs) => attrs.id === update.id);
             if (edge && update.properties) {
                 Object.keys(update.properties).forEach(key => {
                     const val = update.properties![key];
                     graph.setEdgeAttribute(edge, key, val);
                 });

                 // If physics changed, we might need to adjust active animations on this edge
                 if (update.properties.speed !== undefined || update.properties.length !== undefined) {
                     const attrs = graph.getEdgeAttributes(edge);
                     const sourceId = graph.source(edge);
                     
                     Object.keys(animatingItemsRef.current).forEach(itemId => {
                         const anim = animatingItemsRef.current[itemId];
                         if (anim.sourceId === sourceId && anim.targetId === graph.target(edge)) {
                             // Recalculate duration
                             const currentTime = Date.now();
                             const elapsedTime = currentTime - anim.startTime;
                             const oldProgress = Math.min(elapsedTime / anim.duration, 1);
                             
                             const newSpeed = attrs.speed;
                             const newLength = attrs.length;

                             if (newSpeed <= 0) {
                                 delete animatingItemsRef.current[itemId];
                             } else {
                                 const newDuration = (newLength / newSpeed) * 1000;
                                 const newStartTime = currentTime - (oldProgress * newDuration);
                                 animatingItemsRef.current[itemId] = {
                                     ...anim,
                                     duration: newDuration,
                                     startTime: newStartTime
                                 };
                             }
                         }
                     });
                 }
             }
          };
          unsubscribers.push(subscribeToConnectionUpdated(handleConveyorUpdate, simulationId));
      }

      return () => {
          unsubscribers.forEach(unsubscribe => unsubscribe());
      };

  }, [
      connected,
      sigma,
      subscribeToPositionUpdates,
      subscribeToItemCreated,
      subscribeToItemDeleted,
      subscribeToAllItemUpdates,
      subscribeToLocationCreated,
      subscribeToLocationDeleted,
      subscribeToAllLocationUpdates,
      subscribeToConnectionCreated,
      subscribeToConnectionDeleted,
      subscribeToConnectionUpdated
  ]);

  // --- Handlers ---

  const handleEdgeSubmit = useCallback(async ({ speed, length, isMainPath }: { speed: number; length: number; isMainPath: boolean }) => {
    if (!selectedEdgeData) return;
    const graph = sigma.getGraph();
    const { edgeId, sourceId, targetId } = selectedEdgeData;
    
    // Get the Conveyor ID stored in the edge attributes
    const conveyorId = graph.getEdgeAttribute(edgeId, 'id');

    const finalSpeed = parseFloat(String(speed));
    const finalLength = parseFloat(String(length));

    // Optimistic UI update
    graph.setEdgeAttribute(edgeId, 'speed', finalSpeed);
    graph.setEdgeAttribute(edgeId, 'length', finalLength);
    graph.setEdgeAttribute(edgeId, 'isMainPath', isMainPath);
    graph.setEdgeAttribute(edgeId, 'size', isMainPath ? 6 : 3);

    try {
        // Call the new Conveyor Controller
        // We use the batch update endpoint
        const updatePayload = { 
            "speed": finalSpeed, 
            "length": finalLength,
            "isMainPath": isMainPath
        };
        
        // If we have a specific conveyor ID, use it. Otherwise, we might need to use source/target if the API supports it.
        // Assuming the API requires ID.
        if (conveyorId) {
            await conveyorsApi.updateConveyor(conveyorId, updatePayload);
        } else {
            console.warn("No Conveyor ID found on edge, cannot update via ID.");
        }
    } catch (error) {
        console.error("Failed to update conveyor properties:", error);
        // Revert UI?
    }
    sigma.refresh();
  }, [sigma, selectedEdgeData, conveyorsApi]);

  const handleNodeSubmit = useCallback(async ({ name, capacity }: { name: string, capacity: number }) => {
    if (!selectedNodeData) return;
    const graph = sigma.getGraph();
    const { nodeId } = selectedNodeData;
    
    // Optimistic UI update
    graph.setNodeAttribute(nodeId, 'label', name);
    graph.setNodeAttribute(nodeId, 'capacity', capacity);
    sigma.refresh();

    // API Call to Location Controller
    try {
        await locationApi.updateLocation(nodeId, { "name": name, "capacity": capacity });
    } catch (error) {
        console.error("Failed to update location:", error);
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
      
      // Call Conveyor API to delete
      await conveyorsApi.deleteConveyor(sourceId, targetId);
    }
  }, [sigma, conveyorsApi]);

  const handleNodeDelete = useCallback(async (nodeId: string) => {
    const graph = sigma.getGraph();
    if (graph.hasNode(nodeId)) {
      const oldNodeAttributes = graph.getNodeAttributes(nodeId);
      graph.dropNode(nodeId);
      sigma.refresh();

      try {
          await locationApi.deleteLocation(nodeId);
      } catch (error) {
          console.error("Failed to delete location:", error);
          graph.addNode(nodeId, oldNodeAttributes);
          sigma.refresh();
      }
    }
  }, [sigma, locationApi]);

  const addNode = useCallback(async (x: number, y: number) => {
    const graph = sigma.getGraph();
    if (!graph) return;
    
    const newNodeId = crypto.randomUUID();
    const newNodeLabel = "New Location";
    
    // Optimistic UI update
    graph.addNode(newNodeId, { 
        x, y, 
        label: newNodeLabel, 
        size: 10, 
        color: "#69b3a2", 
        type: "circle",
        capacity: 10 
    });
    sigma.refresh();

    try {
        const locationPayload: LocationInput = {
            id: newNodeId,
            name: newNodeLabel,
            longitude: y, // Note: Mapping might depend on backend expectation (lat/long vs x/y)
            latitude: x,
            active: true,
            capacity: 10,
            type: 'standard' // Default type
        };
        await locationApi.createLocation(locationPayload);
    } catch (error) {
        console.error("Failed to create location:", error);
        graph.dropNode(newNodeId);
        sigma.refresh();
    }

  }, [sigma, locationApi]);

  const debouncedUpdateNodePosition = useCallback((nodeId: string, x: number, y: number) => {
        // Simple debounce implementation or use lodash
        // Here we just fire it. In production, wrap with debounce.
        const updateRequest: CoordinatesUpdateRequest = {
            latitude: x, // Ensure mapping matches backend (x=lat or x=long?)
            longitude: y
        };
        locationApi.updateLocationCoordinates(nodeId, updateRequest).catch(e => console.error(e));
  }, [locationApi]);

  // --- Sigma Events ---
  useEffect(() => {
    registerEvents({
      enterEdge: ({ edge }) => setHoveredEdge(edge),
      leaveEdge: () => setHoveredEdge(null),
      clickEdge: ({ edge }) => {
        const graph = sigma.getGraph();
        if (!graph.hasEdge(edge)) return;
        const sourceId = graph.source(edge);
        const targetId = graph.target(edge);
        const attrs = graph.getEdgeAttributes(edge);
        
        setSelectedEdgeData({
          edgeId: edge, 
          sourceId, 
          targetId,
          speed: attrs.speed || 1, 
          length: attrs.length || 10,
          isMainPath: attrs.isMainPath || false
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
          // Only drag items or locations? Assuming both can be dragged.
          isDraggingRef.current = true;
          draggedNodeRef.current = node;
          sigma.getSettings().mouseEnabled = false;
        }
      },
      upNode: async ({ node }) => {
        if (isAddingEdgeRef.current && edgeSourceNodeRef.current && edgeSourceNodeRef.current !== node) {
          const graph = sigma.getGraph();
          const source = edgeSourceNodeRef.current;
          const target = node;
          if (!graph.hasEdge(source, target)) {
            // Optimistic UI update
            const tempId = `temp_${source}_${target}`;
            graph.addEdge(source, target, { 
                id: tempId,
                type: 'arrow', 
                size: 3,
                speed: 1.0,
                length: 10.0
            });
            
            try {
                const conveyorPayload: CreateConveyorInput = { 
                    sourceId: source, 
                    targetId: target,
                    name: `Conveyor ${source}->${target}`,
                    speed: 1.0,
                    length: 10.0,
                    isActive: true,
                    isMainPath: false
                };
                await conveyorsApi.createConveyor(conveyorPayload);
            } catch (error) {
                console.error("Failed to create conveyor:", error);
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
                // Only update coordinates for Locations, not Items (Items move by physics)
                if (!attrs.isItem) {
                    debouncedUpdateNodePosition(draggedNodeId, attrs.x, attrs.y);
                }
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
        if (didMoveRef.current) return;
        
        if (!isAddingEdgeRef.current && !isDraggingRef.current){
          const graph = sigma.getGraph();
          const attr = graph.getNodeAttributes(node);
          // Only open editor for Locations, not Items
          if (!attr.isItem) {
              setSelectedNodeData({
                  nodeId: node, 
                  name: attr.label,
                  capacity: attr.capacity || 0
              });
          }
        }
      }
    });
  }, [sigma, registerEvents, addNode, setHoveredEdge, conveyorsApi, debouncedUpdateNodePosition]);

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

export const DisplayGraph = ({ initialGraphData, simulationId }: { initialGraphData: GraphData, simulationId?: string }) => {
  const [hoveredEdge, setHoveredEdge] = useState<string | null>(null);

  return (
    <GraphThemeProvider>
      <ThemedBackground>
      <div style={{ width: '100%', height: '100%', position: 'relative' }}>
        <ThemeToggle />
        <SigmaContainer
          style={{ ...sigmaStyle, cursor: hoveredEdge ? 'pointer' : 'default' }}
          settings={{
            nodeProgramClasses: { square: NodeSquareProgram },
            enableEdgeEvents: true,
            autoRescale: true
          }}
        >
          <GraphThemeController />
          <GraphEvents initialGraphData={initialGraphData} setHoveredEdge={setHoveredEdge} simulationId={simulationId} />
        </SigmaContainer>
      </div>
      </ThemedBackground>
    </GraphThemeProvider>
  );
};

export default DisplayGraph;