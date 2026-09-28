import { useState, useRef, useEffect } from "react";
import { useRegisterEvents, useSigma } from "@react-sigma/core";
import { LocationTypeEnum } from "../../../api-client";
import toast from "react-hot-toast";


import type { ItemEditorData } from "../../editors/item.editor";
import type { MutableRefObject } from "react";
import type { GraphInteractionSnapshot, InteractionState } from "./useGraphInteractions";

/** Sigma pointer state stays in refs because drag events run outside React. */
export const useGraphPointerInteractions = (
    stateRef: MutableRefObject<GraphInteractionSnapshot>,
    { setHoverTarget, setSelectedEdgeData, setSelectedNodeData, setSelectedItemData, setIsDetailsOpen }: InteractionState,
    notifyReadOnly: () => void,
) => {
    const sigma = useSigma();
    const registerEvents = useRegisterEvents();
    const [lineCoordinates, setLineCoordinates] = useState<{x1:number, y1:number, x2:number, y2:number} | null>(null);

    // Interaction Refs
    const draggedNodeRef = useRef<string | null>(null);
    const isDraggingRef = useRef<boolean>(false);
    const isAddingEdgeRef = useRef<boolean>(false);
    const edgeSourceNodeRef = useRef<string | null>(null);
    const didMoveRef = useRef<boolean>(false);

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
                        x: "clientX" in event.original ? event.original.clientX : event.original.touches[0]?.clientX ?? event.x,
                        y: "clientY" in event.original ? event.original.clientY : event.original.touches[0]?.clientY ?? event.y,
                        attributes: { ...attrs, id: node } as ItemEditorData 
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

                if (sigma.getGraph().getNodeAttribute(node, "isSensor")) {
                    return;
                }

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
                        event.preventSigmaDefault();
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
                            .then(() => window.dispatchEvent(new Event("scenario-mutated")))
                            .catch(() => toast.error("Failed to save node position"));
                    }
                    isDraggingRef.current = false;
                    draggedNodeRef.current = null;
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
                        graph.addEdge(source, target, { id, type: 'arrow', conveyorType: 'BELT', size: 3, speed: 1, length: 10 });
                        
                        try {
                            await conveyorsApi.createConveyor({ sourceId: source, targetId: target, name: "New", speed: 1, length: 10, isActive: true, mainPath: false });
                            toast.success("Connection created");
                            window.dispatchEvent(new Event("scenario-mutated"));
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

                setSelectedEdgeData({ edgeId: edge, sourceId: graph.source(edge), targetId: graph.target(edge), speed: attrs.speed, length: attrs.length, mainPath: attrs.mainPath, conveyorType: attrs.conveyorType, properties: attrs.properties });
            },
            clickNode: ({ node }) => {
                if (didMoveRef.current) return;
                
                const attrs = sigma.getGraph().getNodeAttributes(node);
                if (attrs.isSensor) return;
                
                if (attrs.isItem) {
                    // It's an item: Lock the hover details immediately
                    setIsDetailsOpen(true);
                    // Ensure we have the data selected
                    setSelectedItemData({ ...attrs, id: node } as ItemEditorData);
                } else {
                    // It's a location: Open editor
                    const { isReadOnly } = stateRef.current;
                    if (isReadOnly) { notifyReadOnly(); return; }
                    setSelectedNodeData({ nodeId: node, name: attrs.label, capacity: attrs.capacity, timeToProcessMs: attrs.timeToProcessMs, properties: attrs.properties, locationType: attrs.locationType, itemsInChute: attrs.itemsInChute });
                }
            }
        });
    }, [sigma, registerEvents, notifyReadOnly, setHoverTarget, setSelectedItemData, setSelectedEdgeData, setSelectedNodeData, setIsDetailsOpen, stateRef]);

    return { lineCoordinates, draggedNodeRef };
};
