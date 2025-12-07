import { useState, useRef, useEffect, useCallback } from "react";
import { useRegisterEvents, useSigma } from "@react-sigma/core";
import { useApi } from "../../../hooks/useApi";
import { EdgeEditorData } from "../../edge.editor";
import { NodeEditorData } from "../../node.editor";

export const useGraphInteractions = (
    adjustItemsForSpeedChange: (edgeId: string, newSpeed: number) => void
) => {
    const sigma = useSigma();
    const registerEvents = useRegisterEvents();
    const { locationApi, conveyorsApi } = useApi();

    // State
    const [selectedEdgeData, setSelectedEdgeData] = useState<EdgeEditorData | null>(null);
    const [selectedNodeData, setSelectedNodeData] = useState<NodeEditorData | null>(null);
    const [lineCoordinates, setLineCoordinates] = useState<{x1:number, y1:number, x2:number, y2:number} | null>(null);

    // Refs
    const draggedNodeRef = useRef<string | null>(null);
    const isDraggingRef = useRef<boolean>(false);
    const isAddingEdgeRef = useRef<boolean>(false);
    const edgeSourceNodeRef = useRef<string | null>(null);
    
    // FIX: Track if movement occurred to distinguish Click vs Drag
    const didMoveRef = useRef<boolean>(false);

    // --- API Handlers ---
    const debouncedUpdateNodePosition = useCallback((nodeId: string, x: number, y: number) => {
        locationApi.updateLocationCoordinates(nodeId, { latitude: x, longitude: y }).catch(console.error);
    }, [locationApi]);

    const handleEdgeSubmit = useCallback(async ({ speed, length, isMainPath }: any) => {
        if (!selectedEdgeData) return;
        const graph = sigma.getGraph();
        const { edgeId } = selectedEdgeData;
        const conveyorId = graph.getEdgeAttribute(edgeId, 'id');
        
        adjustItemsForSpeedChange(edgeId, Number(speed));

        graph.setEdgeAttribute(edgeId, 'speed', Number(speed));
        graph.setEdgeAttribute(edgeId, 'length', Number(length));
        graph.setEdgeAttribute(edgeId, 'isMainPath', isMainPath);
        graph.setEdgeAttribute(edgeId, 'size', isMainPath ? 6 : 3);

        if (conveyorId) {
            await conveyorsApi.updateConveyor(conveyorId, { speed: Number(speed), length: Number(length), isMainPath });
        }
        sigma.refresh();
        setSelectedEdgeData(null);
    }, [sigma, selectedEdgeData, conveyorsApi, adjustItemsForSpeedChange]);

    const handleNodeSubmit = useCallback(async ({ name, capacity }: any) => {
        if (!selectedNodeData) return;
        const graph = sigma.getGraph();
        const { nodeId } = selectedNodeData;
        graph.setNodeAttribute(nodeId, 'label', name);
        graph.setNodeAttribute(nodeId, 'capacity', capacity);
        sigma.refresh();
        try {
            await locationApi.updateLocation(nodeId, { name, capacity });
        } catch (error) { console.error(error); }
        setSelectedNodeData(null);
    }, [sigma, selectedNodeData, locationApi]);

    const handleEdgeDelete = useCallback(async (edgeId: string, sourceId: string, targetId: string) => {
        const graph = sigma.getGraph();
        if (graph.hasEdge(edgeId)) {
            graph.dropEdge(edgeId);
            sigma.refresh();
            await conveyorsApi.deleteConveyor(sourceId, targetId);
        }
        setSelectedEdgeData(null);
    }, [sigma, conveyorsApi]);

    const handleNodeDelete = useCallback(async (nodeId: string) => {
        const graph = sigma.getGraph();
        if (graph.hasNode(nodeId)) {
            graph.dropNode(nodeId);
            sigma.refresh();
            await locationApi.deleteLocation(nodeId);
        }
        setSelectedNodeData(null);
    }, [sigma, locationApi]);

    // --- Events ---
    useEffect(() => {
        registerEvents({
            // FIX: Reset didMove on stage down (for panning or clicking empty space)
            downStage: () => {
                didMoveRef.current = false;
            },

            clickStage: ({ event }) => {
                // FIX: If we moved (dragged stage OR finished creating edge), ignore click
                if (didMoveRef.current) return;

                if (selectedEdgeData || selectedNodeData) {
                    setSelectedEdgeData(null);
                    setSelectedNodeData(null);
                } else if (!isDraggingRef.current && !isAddingEdgeRef.current) {
                    const pos = sigma.viewportToGraph(event);
                    const newNodeId = crypto.randomUUID();
                    sigma.getGraph().addNode(newNodeId, { x: pos.x, y: pos.y, label: "New", size: 10, color: "#69b3a2", type: "circle" });
                    locationApi.createLocation({ id: newNodeId, name: "New", longitude: pos.y, latitude: pos.x, active: true, capacity: 10, type: 'standard' });
                }
            },
            downNode: ({ node, event }) => {
                // FIX: Reset didMove on node down
                didMoveRef.current = false;
                isDraggingRef.current = false;

                if (event.original.altKey) {
                    // --- START ADDING EDGE ---
                    isAddingEdgeRef.current = true;
                    edgeSourceNodeRef.current = node;
                    
                    // Use Viewport coordinates for the SVG line
                    setLineCoordinates({ 
                        x1: event.x, 
                        y1: event.y, 
                        x2: event.x, 
                        y2: event.y 
                    });
                } else {
                    // --- START DRAGGING NODE ---
                    setSelectedEdgeData(null);
                    if (sigma.getGraph().getNodeAttribute(node, "type") !== "square") {
                        isDraggingRef.current = true;
                        draggedNodeRef.current = node;
                        sigma.getSettings().mouseEnabled = false;
                    }
                }
            },
            mousemove: (event) => {
                // FIX: Mark that we moved
                if (isDraggingRef.current || isAddingEdgeRef.current) {
                    didMoveRef.current = true;
                }

                if (isDraggingRef.current && draggedNodeRef.current) {
                    event.preventSigmaDefault();
                    const pos = sigma.viewportToGraph(event);
                    sigma.getGraph().setNodeAttribute(draggedNodeRef.current, "x", pos.x);
                    sigma.getGraph().setNodeAttribute(draggedNodeRef.current, "y", pos.y);
                }
                if (isAddingEdgeRef.current) {
                    // Update line end to mouse position (Viewport coords)
                    setLineCoordinates(prev => prev ? { ...prev, x2: event.x, y2: event.y } : null);
                    event.preventSigmaDefault();
                }
            },
            mouseup: () => {
                if (isDraggingRef.current && draggedNodeRef.current) {
                    const node = draggedNodeRef.current;
                    const attrs = sigma.getGraph().getNodeAttributes(node);
                    if (!attrs.isItem) debouncedUpdateNodePosition(node, attrs.x, attrs.y);
                    
                    isDraggingRef.current = false;
                    draggedNodeRef.current = null;
                    sigma.getSettings().mouseEnabled = true;
                }
                if (isAddingEdgeRef.current) {
                    isAddingEdgeRef.current = false;
                    setLineCoordinates(null);
                }
            },
            upNode: async ({ node }) => {
                if (isAddingEdgeRef.current && edgeSourceNodeRef.current && edgeSourceNodeRef.current !== node) {
                    const source = edgeSourceNodeRef.current;
                    const target = node;
                    const graph = sigma.getGraph();
                    if (!graph.hasEdge(source, target)) {
                        const id = `temp_${source}_${target}`;
                        graph.addEdge(source, target, { id, type: 'arrow', size: 3, speed: 1, length: 10 });
                        await conveyorsApi.createConveyor({ sourceId: source, targetId: target, name: "New", speed: 1, length: 10, isActive: true, isMainPath: false });
                    }
                }
            },
            clickEdge: ({ edge }) => {
                const graph = sigma.getGraph();
                const attrs = graph.getEdgeAttributes(edge);
                setSelectedEdgeData({ edgeId: edge, sourceId: graph.source(edge), targetId: graph.target(edge), speed: attrs.speed, length: attrs.length, isMainPath: attrs.isMainPath });
            },
            clickNode: ({ node }) => {
                // FIX: If we dragged, do NOT open the editor
                if (didMoveRef.current) return;

                if (!isDraggingRef.current && !isAddingEdgeRef.current) {
                    const attrs = sigma.getGraph().getNodeAttributes(node);
                    if (!attrs.isItem) setSelectedNodeData({ nodeId: node, name: attrs.label, capacity: attrs.capacity });
                }
            }
        });
    }, [sigma, registerEvents]);

    return { 
        selectedEdgeData, setSelectedEdgeData, handleEdgeSubmit, handleEdgeDelete,
        selectedNodeData, setSelectedNodeData, handleNodeSubmit, handleNodeDelete,
        lineCoordinates, draggedNodeRef 
    };
};