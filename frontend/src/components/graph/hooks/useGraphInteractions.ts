import { useState, useRef, useEffect, useCallback } from "react";
import { useRegisterEvents, useSigma } from "@react-sigma/core";
import { useApi } from "../../../hooks/useApi";
import { EdgeEditorData } from "../../edge.editor";
import { NodeEditorData } from "../../node.editor";
import { LocationTypeEnum } from "../../../api-client";
import toast from "react-hot-toast";
import { toastWarning } from "../utils/toastUtils";

export const useGraphInteractions = (
    adjustItemsForSpeedChange: (edgeId: string, newSpeed: number) => void,
    simulationId?: string | null 
) => {
    const sigma = useSigma();
    const registerEvents = useRegisterEvents();
    const { locationApi, conveyorsApi } = useApi();

    const isDemoMode = import.meta.env.VITE_DEMO_MODE === 'true';
    const isReadOnly = isDemoMode && !simulationId;

    // --- 1. STABILIZATION REFS ---
    // We store the "unstable" things in Refs.
    // This allows us to access the *latest* version inside events
    // WITHOUT adding them to the useEffect dependency array.
    const stateRef = useRef({
        isReadOnly,
        locationApi,
        conveyorsApi,
        adjustItemsForSpeedChange
    });

    // Update Refs on every render (cheap)
    useEffect(() => {
        stateRef.current = {
            isReadOnly,
            locationApi,
            conveyorsApi,
            adjustItemsForSpeedChange
        };
    }, [isReadOnly, locationApi, conveyorsApi, adjustItemsForSpeedChange]);

    // --- State ---
    const [selectedEdgeData, setSelectedEdgeData] = useState<EdgeEditorData | null>(null);
    const [selectedNodeData, setSelectedNodeData] = useState<NodeEditorData | null>(null);
    const [lineCoordinates, setLineCoordinates] = useState<{x1:number, y1:number, x2:number, y2:number} | null>(null);

    // Interaction Refs
    const draggedNodeRef = useRef<string | null>(null);
    const isDraggingRef = useRef<boolean>(false);
    const isAddingEdgeRef = useRef<boolean>(false);
    const edgeSourceNodeRef = useRef<string | null>(null);
    const didMoveRef = useRef<boolean>(false);

    // Helper
    const notifyReadOnly = useCallback(() => {
        toastWarning("Modification disabled in Demo, start a simulation", { id: 'readonly-toast' });
    }, []);

    // --- 2. STABLE HANDLERS ---
    // These use stateRef.current, so they don't need to be recreated when API/ReadOnly changes.

    const handleEdgeSubmit = useCallback(async ({ speed, length, isMainPath }: any) => {
        const { isReadOnly, conveyorsApi, adjustItemsForSpeedChange } = stateRef.current;
        if (!selectedEdgeData || isReadOnly) return;
        
        const graph = sigma.getGraph();
        const { edgeId } = selectedEdgeData;
        const conveyorId = graph.getEdgeAttribute(edgeId, 'id');
        
        try {
            adjustItemsForSpeedChange(edgeId, Number(speed));
            graph.setEdgeAttribute(edgeId, 'speed', Number(speed));
            graph.setEdgeAttribute(edgeId, 'length', Number(length));
            graph.setEdgeAttribute(edgeId, 'isMainPath', isMainPath);
            graph.setEdgeAttribute(edgeId, 'size', isMainPath ? 6 : 3);

            if (conveyorId) {
                await conveyorsApi.updateConveyor(conveyorId, { speed: Number(speed), length: Number(length), isMainPath });
                toast.success("Conveyor updated");
            }
            sigma.refresh();
            setSelectedEdgeData(null);
        } catch (error) {
            console.error(error);
            toast.error("Failed to update conveyor");
        }
    }, [sigma, selectedEdgeData]);

    const handleNodeSubmit = useCallback(async ({ name, capacity }: any) => {
        const { isReadOnly, locationApi } = stateRef.current;
        if (!selectedNodeData || isReadOnly) return;
        
        const graph = sigma.getGraph();
        const { nodeId } = selectedNodeData;
        
        try {
            graph.setNodeAttribute(nodeId, 'label', name);
            graph.setNodeAttribute(nodeId, 'capacity', capacity);
            sigma.refresh();
            await locationApi.updateLocation(nodeId, { name, capacity });
            toast.success("Location updated");
        } catch (error) { 
            console.error(error);
            toast.error("Failed to update location");
        }
        setSelectedNodeData(null);
    }, [sigma, selectedNodeData]);

    const handleEdgeDelete = useCallback(async (edgeId: string, sourceId: string, targetId: string) => {
        const { isReadOnly, conveyorsApi } = stateRef.current;
        if (isReadOnly) return;
        
        const graph = sigma.getGraph();
        if (graph.hasEdge(edgeId)) {
            try {
                graph.dropEdge(edgeId);
                sigma.refresh();
                await conveyorsApi.deleteConveyor(sourceId, targetId);
                toast.success("Conveyor deleted");
            } catch (error) {
                toast.error("Failed to delete conveyor");
            }
        }
        setSelectedEdgeData(null);
    }, [sigma]);

    const handleNodeDelete = useCallback(async (nodeId: string) => {
        const { isReadOnly, locationApi } = stateRef.current;
        if (isReadOnly) return;

        const graph = sigma.getGraph();
        if (graph.hasNode(nodeId)) {
            try {
                graph.dropNode(nodeId);
                sigma.refresh();
                await locationApi.deleteLocation(nodeId);
                toast.success("Location deleted");
            } catch (error) {
                toast.error("Failed to delete location");
            }
        }
        setSelectedNodeData(null);
    }, [sigma]);

    // --- 3. THE MAIN EVENT LOOP (NOW STABLE) ---
    // Notice the dependency array: It ONLY depends on [sigma, registerEvents].
    // It does NOT depend on simTime, APIs, or ReadOnly state.
    useEffect(() => {
        registerEvents({
            downStage: () => { didMoveRef.current = false; },

            clickStage: ({ event }) => {
                if (didMoveRef.current) return;
                
                setSelectedEdgeData(null);
                setSelectedNodeData(null);

                // Access latest state via Ref
                const { isReadOnly, locationApi } = stateRef.current;
                if (isReadOnly) {
                    notifyReadOnly(); 
                    return; 
                }
                if (!isDraggingRef.current && !isAddingEdgeRef.current && !isReadOnly) {
                    const pos = sigma.viewportToGraph(event);
                    const newNodeId = crypto.randomUUID();
                    sigma.getGraph().addNode(newNodeId, { x: pos.x, y: pos.y, label: "New", size: 10, color: "#69b3a2", type: "circle" });
                    
                    locationApi.createLocation({ id: newNodeId, name: "New", longitude: pos.y, latitude: pos.x, active: true, capacity: 10, type: LocationTypeEnum.Generic })
                        .catch(() => toast.error("Failed to create location"));
                }
            },
            downNode: ({ node, event }) => {
                didMoveRef.current = false;
                const { isReadOnly } = stateRef.current;

                if (isReadOnly) {
                    notifyReadOnly(); 
                    return; 
                }

                isDraggingRef.current = false;
                if (event.original.altKey) {
                    isAddingEdgeRef.current = true;
                    edgeSourceNodeRef.current = node;
                    setLineCoordinates({ x1: event.x, y1: event.y, x2: event.x, y2: event.y });
                } else {
                    setSelectedEdgeData(null);
                    if (sigma.getGraph().getNodeAttribute(node, "type") !== "square") {
                        isDraggingRef.current = true;
                        draggedNodeRef.current = node;
                        sigma.getSettings().mouseEnabled = false;
                    }
                }
            },
            mousemove: (event) => {
                const { isReadOnly } = stateRef.current;
                if (isReadOnly) return;

                if (isDraggingRef.current || isAddingEdgeRef.current) didMoveRef.current = true;

                if (isDraggingRef.current && draggedNodeRef.current) {
                    event.preventSigmaDefault();
                    const pos = sigma.viewportToGraph(event);
                    sigma.getGraph().setNodeAttribute(draggedNodeRef.current, "x", pos.x);
                    sigma.getGraph().setNodeAttribute(draggedNodeRef.current, "y", pos.y);
                }
                if (isAddingEdgeRef.current) {
                    setLineCoordinates(prev => prev ? { ...prev, x2: event.x, y2: event.y } : null);
                    event.preventSigmaDefault();
                }
            },
            mouseup: () => {
                if (isDraggingRef.current && draggedNodeRef.current) {
                    const node = draggedNodeRef.current;
                    const attrs = sigma.getGraph().getNodeAttributes(node);
                    const { isReadOnly, locationApi } = stateRef.current;

                    if (!isReadOnly && !attrs.isItem) {
                        locationApi.updateLocationCoordinates(node, { latitude: attrs.x, longitude: attrs.y })
                            .catch(() => toast.error("Failed to save node position"));
                    }
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
                const { isReadOnly, conveyorsApi } = stateRef.current;
                if (isReadOnly) return;

                if (isAddingEdgeRef.current && edgeSourceNodeRef.current && edgeSourceNodeRef.current !== node) {
                    const source = edgeSourceNodeRef.current;
                    const target = node;
                    const graph = sigma.getGraph();
                    if (!graph.hasEdge(source, target)) {
                        const id = `temp_${source}_${target}`;
                        graph.addEdge(source, target, { id, type: 'arrow', size: 3, speed: 1, length: 10 });
                        
                        try {
                            await conveyorsApi.createConveyor({ sourceId: source, targetId: target, name: "New", speed: 1, length: 10, isActive: true, isMainPath: false });
                            toast.success("Connection created");
                        } catch (e) {
                            graph.dropEdge(source, target);
                            toast.error("Failed to create connection");
                        }
                    }
                }
            },
            clickEdge: ({ edge }) => {
                const { isReadOnly } = stateRef.current;
                if (isReadOnly) { notifyReadOnly(); return; }
                
                const graph = sigma.getGraph();
                const attrs = graph.getEdgeAttributes(edge);
                setSelectedEdgeData({ edgeId: edge, sourceId: graph.source(edge), targetId: graph.target(edge), speed: attrs.speed, length: attrs.length, isMainPath: attrs.isMainPath });
            },
            clickNode: ({ node }) => {
                if (didMoveRef.current) return;
                const { isReadOnly } = stateRef.current;
                if (isReadOnly) { notifyReadOnly(); return; }

                if (!isDraggingRef.current && !isAddingEdgeRef.current) {
                    const attrs = sigma.getGraph().getNodeAttributes(node);
                    if (!attrs.isItem) setSelectedNodeData({ nodeId: node, name: attrs.label, capacity: attrs.capacity });
                }
            }
        });
    }, [sigma, registerEvents, notifyReadOnly]); // <--- STABLE DEPENDENCIES!

    return { 
        selectedEdgeData, setSelectedEdgeData, handleEdgeSubmit, handleEdgeDelete,
        selectedNodeData, setSelectedNodeData, handleNodeSubmit, handleNodeDelete,
        lineCoordinates, draggedNodeRef 
    };
};