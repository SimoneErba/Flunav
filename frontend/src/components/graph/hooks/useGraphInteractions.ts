import { useState, useRef, useEffect, useCallback } from "react";
import { useRegisterEvents, useSigma } from "@react-sigma/core";
import { useApi } from "../../../hooks/useApi";
import { EdgeEditorData } from "../../editors/edge.editor";
import { NodeEditorData } from "../../editors/node.editor";
import { ItemEditorData } from "../../editors/item.editor";
import { LocationTypeEnum } from "../../../api-client";
import toast from "react-hot-toast";
import { toastWarning } from "../utils/toastUtils";

export interface InteractionState {
    setHoverTarget: (t: { nodeId: string; x: number; y: number; attributes: ItemEditorData } | null) => void;
    selectedItemData: ItemEditorData | null;
    setSelectedItemData: (d: ItemEditorData | null) => void;
}

export const useGraphInteractions = (
    adjustItemsForSpeedChange: (edgeId: string, newSpeed: number) => void,
    { setHoverTarget, selectedItemData, setSelectedItemData }: InteractionState, 
    simulationId?: string | null 
) => {
    const sigma = useSigma();
    const registerEvents = useRegisterEvents();
    const { locationApi, conveyorsApi, itemApi } = useApi();

    const isDemoMode = import.meta.env.VITE_DEMO_MODE === 'true';
    const isReadOnly = isDemoMode && !simulationId;

    // --- State ---
    const [selectedEdgeData, setSelectedEdgeData] = useState<EdgeEditorData | null>(null);
    const [selectedNodeData, setSelectedNodeData] = useState<NodeEditorData | null>(null);
    const [lineCoordinates, setLineCoordinates] = useState<{x1:number, y1:number, x2:number, y2:number} | null>(null);

    // --- PARADOX HOVER STATE ---
    const [isDetailsOpen, setIsDetailsOpen] = useState(false);

    // Interaction Refs
    const draggedNodeRef = useRef<string | null>(null);
    const isDraggingRef = useRef<boolean>(false);
    const isAddingEdgeRef = useRef<boolean>(false);
    const edgeSourceNodeRef = useRef<string | null>(null);
    const didMoveRef = useRef<boolean>(false);

    // Read-only feedback is centralized so demo/live restrictions stay consistent
    // across node, edge, and item mutation paths.
    const notifyReadOnly = useCallback(() => {
        toastWarning("Modification disabled in Demo, start a simulation", { id: 'readonly-toast' });
    }, []);

    // Sigma event handlers are registered once and can outlive React render state.
    // The ref keeps API clients and selection state fresh without rebinding every
    // pointer handler on each render.
    const stateRef = useRef({
        isReadOnly,
        locationApi,
        itemApi,
        conveyorsApi,
        adjustItemsForSpeedChange,
        // Selection State
        selectedEdgeData,
        selectedNodeData,
        selectedItemData,
        // Hover State
        isDetailsOpen
    });

    // Update Refs on every render
    useEffect(() => {
        stateRef.current = {
            isReadOnly,
            locationApi,
            conveyorsApi,
            itemApi,
            adjustItemsForSpeedChange,
            selectedEdgeData,
            selectedNodeData,
            selectedItemData,
            isDetailsOpen
        };
    }, [
        isReadOnly, locationApi, conveyorsApi, itemApi, adjustItemsForSpeedChange,
        selectedEdgeData, selectedNodeData, selectedItemData, isDetailsOpen
    ]);

    /**
     * Persists conveyor edits after patching local graph state.
     * Item timestamps are reanchored before the speed attribute changes so visible
     * conveyor progress does not jump while the backend update is in flight.
     */
    const handleEdgeSubmit = useCallback(async ({
        speed,
        length,
        mainPath,
        properties
    }: {
        speed: number;
        length: number;
        mainPath: boolean;
        properties: Record<string, unknown>;
    }) => {
        const { isReadOnly, conveyorsApi, adjustItemsForSpeedChange } = stateRef.current;
        if (!selectedEdgeData || isReadOnly) return;
        
        const graph = sigma.getGraph();
        const { edgeId } = selectedEdgeData;
        const conveyorId = graph.getEdgeAttribute(edgeId, 'id');
        
        try {
            adjustItemsForSpeedChange(edgeId, Number(speed));
            graph.setEdgeAttribute(edgeId, 'speed', Number(speed));
            graph.setEdgeAttribute(edgeId, 'length', Number(length));
            graph.setEdgeAttribute(edgeId, 'mainPath', mainPath);
            graph.setEdgeAttribute(edgeId, 'size', mainPath ? 6 : 3);
            graph.setEdgeAttribute(edgeId, 'properties', properties);

            if (conveyorId) {
                await conveyorsApi.updateConveyor(conveyorId, { speed: Number(speed), length: Number(length), mainPath, properties });
                toast.success("Conveyor updated");
            }
            sigma.refresh();
            setSelectedEdgeData(null);
        } catch (error) {
            console.error(error);
            toast.error("Failed to update conveyor");
        }
    }, [sigma, selectedEdgeData]);

    /**
     * Updates location attributes in the graph and backend together.
     * The local patch keeps the editor responsive while the API call remains the
     * durable source of truth.
     */
    const handleNodeSubmit = useCallback(async ({
        name,
        capacity,
        locationType,
        properties
    }: {
        name: string;
        capacity: number;
        locationType: string;
        properties: Record<string, unknown>;
    }) => {
        const { isReadOnly, locationApi } = stateRef.current;
        if (!selectedNodeData || isReadOnly) return;
        
        const graph = sigma.getGraph();
        const { nodeId } = selectedNodeData;
        
        try {
            graph.setNodeAttribute(nodeId, 'label', name);
            graph.setNodeAttribute(nodeId, 'capacity', capacity);
            graph.setNodeAttribute(nodeId, 'locationType', locationType);
            graph.setNodeAttribute(nodeId, 'properties', properties);
            sigma.refresh();
            await locationApi.updateLocation(nodeId, { name, capacity, type: locationType, properties });
            toast.success("Location updated");
        } catch (error) { 
            console.error(error);
            toast.error("Failed to update location");
        }
        setSelectedNodeData(null);
    }, [sigma, selectedNodeData]);

    /**
     * Applies item metadata edits to the selected graph node and backend.
     * Movement state is left untouched because position updates are driven by
     * websocket events and animation state.
     */
    const handleItemSubmit = useCallback(async ({
        name,
        priority,
        properties
    }: {
        name: string;
        priority: number;
        properties: Record<string, unknown>;
    }) => {
        const { isReadOnly, itemApi } = stateRef.current;
        if (!selectedItemData || isReadOnly) return;
        
        const graph = sigma.getGraph();
        const { id } = selectedItemData;
        
        try {
            graph.setNodeAttribute(id, 'label', name);
            graph.setNodeAttribute(id, 'priority', priority);
            graph.setNodeAttribute(id, 'properties', properties);
            sigma.refresh();
            await itemApi.updateItem(id, { name, priority, properties });
            toast.success("Item updated");
        } catch (error) { 
            console.error(error);
            toast.error("Failed to update item");
        }
        setSelectedItemData(null);
    }, [setSelectedItemData, sigma, selectedItemData]);

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
            } catch {
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
            } catch {
                toast.error("Failed to delete location");
            }
        }
        setSelectedNodeData(null);
    }, [sigma]);

    const handleItemDelete = useCallback(async (itemId: string) => {
        const { isReadOnly, itemApi } = stateRef.current;
        if (isReadOnly) return;

        const graph = sigma.getGraph();
        if (graph.hasNode(itemId)) {
            try {
                graph.dropNode(itemId);
                sigma.refresh();
                await itemApi.deleteItem(itemId);
                toast.success("Item deleted");
            } catch {
                toast.error("Failed to delete item");
            }
        }
        setSelectedItemData(null);
    }, [setSelectedItemData, sigma]);

    /**
     * Registers graph pointer interactions once with Sigma.
     * Mutable refs hold drag/edge-creation state because these events fire outside
     * React's normal controlled input flow.
     */
    useEffect(() => {
        registerEvents({
            // --- PARADOX HOVER EVENTS ---
            enterNode: ({ node, event }) => {
                // Access state via REF to avoid stale closures
                const { selectedItemData, isDetailsOpen } = stateRef.current;
                
                if (isDraggingRef.current || isAddingEdgeRef.current || selectedItemData || isDetailsOpen) return;

                const attrs = sigma.getGraph().getNodeAttributes(node);
                if (attrs.isItem) {
                    setHoverTarget({
                        nodeId: node,
                        x: event.original.clientX,
                        y: event.original.clientY,
                        attributes: attrs 
                    });
                }
            },
            leaveNode: () => {
                // Access state via REF
                const { isDetailsOpen } = stateRef.current;
                if (!isDetailsOpen) {
                    setHoverTarget(null);
                }
            },
            wheel: () => {
                const { isDetailsOpen } = stateRef.current;
                if (!isDetailsOpen) setHoverTarget(null);
            },

            // --- STANDARD EVENTS ---
            downStage: () => { didMoveRef.current = false; },

            clickStage: ({ event }) => {
                if (didMoveRef.current) return;

                const suppressUntil = (window as Window & { __suppressNextGraphStageClickUntil?: number }).__suppressNextGraphStageClickUntil;
                if (suppressUntil && suppressUntil > Date.now()) {
                    return;
                }

                if (document.body.dataset.liveInteractionsOpen === "true") {
                    return;
                }

                // READ FROM REF TO GET FRESH VALUES
                const { 
                    selectedEdgeData: currentEdge, 
                    selectedNodeData: currentNode, 
                    selectedItemData: currentItem,
                    isReadOnly, 
                    locationApi 
                } = stateRef.current;

                // Check if any panel is currently open using the REF values
                const isPanelOpen = currentEdge !== null || currentNode !== null || currentItem !== null;
                
                // Clear selections (State updates)
                setSelectedEdgeData(null);
                setSelectedNodeData(null);
                setSelectedItemData(null);
                setHoverTarget(null);
                setIsDetailsOpen(false);

                // If a panel was open, return early. 
                // The user's intention was just to close the panel, not create a node.
                if (isPanelOpen) return;

                // --- Node Creation Logic ---
                if (isReadOnly) {
                    notifyReadOnly(); 
                    return; 
                }

                if (!isDraggingRef.current && !isAddingEdgeRef.current) {
                    const pos = sigma.viewportToGraph(event);
                    const newNodeId = crypto.randomUUID();
                    
                    sigma.getGraph().addNode(newNodeId, { 
                        x: pos.x, 
                        y: pos.y, 
                        label: "New", 
                        size: 10, 
                        color: "#69b3a2", 
                        type: "circle" 
                    });
                    
                    locationApi.createLocation({ 
                        id: newNodeId, 
                        name: "New", 
                        longitude: pos.y, 
                        latitude: pos.x, 
                        active: true, 
                        capacity: 10, 
                        type: LocationTypeEnum.Generic 
                    }).catch(() => {
                        toast.error("Failed to create location");
                        sigma.getGraph().dropNode(newNodeId);
                    });
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

                    if (didMoveRef.current && !isReadOnly && !attrs.isItem) {
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
                            await conveyorsApi.createConveyor({ sourceId: source, targetId: target, name: "New", speed: 1, length: 10, active: true, mainPath: false });
                            toast.success("Connection created");
                        } catch {
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

                setSelectedEdgeData({ edgeId: edge, sourceId: graph.source(edge), targetId: graph.target(edge), speed: attrs.speed, length: attrs.length, mainPath: attrs.mainPath, properties: attrs.properties });
            },
            clickNode: ({ node }) => {
                if (didMoveRef.current) return;
                
                const attrs = sigma.getGraph().getNodeAttributes(node);
                
                if (attrs.isItem) {
                    // It's an item: Lock the hover details immediately
                    setIsDetailsOpen(true);
                    // Ensure we have the data selected
                    setSelectedItemData(attrs);
                } else {
                    // It's a location: Open editor
                    const { isReadOnly } = stateRef.current;
                    if (isReadOnly) { notifyReadOnly(); return; }
                    setSelectedNodeData({ nodeId: node, name: attrs.label, capacity: attrs.capacity, properties: attrs.properties, locationType: attrs.locationType, itemsInChute: attrs.itemsInChute });
                }
            }
        });
    }, [sigma, registerEvents, notifyReadOnly, setHoverTarget, setSelectedItemData]); // Dependency array is now clean and stable!

    return { 
        selectedEdgeData, setSelectedEdgeData, handleEdgeSubmit, handleEdgeDelete,
        selectedNodeData, setSelectedNodeData, handleNodeSubmit, handleNodeDelete,
        lineCoordinates, draggedNodeRef,
        isDetailsOpen, setIsDetailsOpen, handleItemSubmit,handleItemDelete
    };
};
