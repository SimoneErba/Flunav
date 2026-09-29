import { useCallback } from "react";
import { useSigma } from "@react-sigma/core";
import { EdgeEditorData } from "../../editors/edge.editor";
import toast from "react-hot-toast";


import type { MutableRefObject } from "react";
import type { GraphInteractionSnapshot } from "./useGraphInteractions";
import type { GraphSelectionState } from "./useGraphSelection";

// Keep the generated-client mismatch at this API boundary; do not alter payloads.
const apiProperties = (values: Record<string, unknown>): Record<string, object> =>
    values as Record<string, object>;

/** Persists editor commands through the scoped API clients and requests a snapshot on completion. */
export const useGraphEditorCommands = (
    stateRef: MutableRefObject<GraphInteractionSnapshot>,
    { selectedEdgeData, selectedNodeData, selectedItemData,
        setSelectedEdgeData, setSelectedNodeData, setSelectedItemData }: GraphSelectionState,
) => {
    const sigma = useSigma();
    /**
     * Persists conveyor edits after patching local graph state.
     * Item timestamps are reanchored before the speed attribute changes so visible
     * conveyor progress does not jump while the backend update is in flight.
     */
    const handleEdgeSubmit = useCallback(async ({
        speed,
        length,
        mainPath,
        minDistance,
        capacity,
        conveyorType,
        properties
    }: {
        speed: number;
        length: number;
        mainPath: boolean;
        minDistance: number;
        capacity: number | null;
        conveyorType: EdgeEditorData["conveyorType"];
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
            graph.setEdgeAttribute(edgeId, 'minDistance', minDistance);
            graph.setEdgeAttribute(edgeId, 'capacity', capacity);
            graph.setEdgeAttribute(edgeId, 'conveyorType', conveyorType);
            graph.setEdgeAttribute(edgeId, 'size', mainPath ? 6 : 3);
            graph.setEdgeAttribute(edgeId, 'properties', properties);

            if (conveyorId) {
                await conveyorsApi.updateConveyor(conveyorId, apiProperties({ speed: Number(speed), length: Number(length), minDistance, capacity, mainPath, properties }));
                if (conveyorType && conveyorType !== selectedEdgeData.conveyorType) {
                    await conveyorsApi.updateConveyorType(conveyorId, {
                        type: conveyorType,
                    });
                }
                toast.success("Conveyor updated");
                window.dispatchEvent(new Event("scenario-mutated"));
            }
            sigma.refresh();
            setSelectedEdgeData(null);
        } catch (error) {
            console.error(error);
            toast.error("Failed to update conveyor");
        }
    }, [sigma, selectedEdgeData, setSelectedEdgeData, stateRef]);

    /**
     * Updates location attributes in the graph and backend together.
     * The local patch keeps the editor responsive while the API call remains the
     * durable source of truth.
     */
    const handleNodeSubmit = useCallback(async ({
        name,
        capacity,
        locationType,
        properties,
        timeToProcessMs
    }: {
        name: string;
        capacity?: number;
        locationType: string;
        timeToProcessMs?: number;
        properties: Record<string, unknown>;
    }) => {
        const { isReadOnly, locationApi } = stateRef.current;
        if (!selectedNodeData || isReadOnly) return;
        
        const graph = sigma.getGraph();
        const { nodeId } = selectedNodeData;
        
        try {
            graph.setNodeAttribute(nodeId, 'label', name);
            if (capacity !== undefined) {
                graph.setNodeAttribute(nodeId, 'capacity', capacity);
            }
            graph.setNodeAttribute(nodeId, 'locationType', locationType);
            graph.setNodeAttribute(nodeId, 'timeToProcessMs', timeToProcessMs);
            graph.setNodeAttribute(nodeId, 'properties', properties);
            sigma.refresh();
            await locationApi.updateLocation(nodeId, apiProperties({
                name,
                ...(capacity !== undefined ? { capacity } : {}),
                type: locationType,
                timeToProcessMs,
                properties
            }));
            toast.success("Location updated");
                window.dispatchEvent(new Event("scenario-mutated"));
        } catch (error) { 
            console.error(error);
            toast.error("Failed to update location");
        }
        setSelectedNodeData(null);
    }, [sigma, selectedNodeData, setSelectedNodeData, stateRef]);

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
            await itemApi.updateItem(id, apiProperties({ name, priority, properties }));
            toast.success("Item updated");
                window.dispatchEvent(new Event("scenario-mutated"));
        } catch (error) { 
            console.error(error);
            toast.error("Failed to update item");
        }
        setSelectedItemData(null);
    }, [setSelectedItemData, sigma, selectedItemData, stateRef]);

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
                window.dispatchEvent(new Event("scenario-mutated"));
            } catch {
                toast.error("Failed to delete conveyor");
            }
        }
        setSelectedEdgeData(null);
    }, [sigma, setSelectedEdgeData, stateRef]);

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
                window.dispatchEvent(new Event("scenario-mutated"));
            } catch {
                toast.error("Failed to delete location");
            }
        }
        setSelectedNodeData(null);
    }, [sigma, setSelectedNodeData, stateRef]);

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
                window.dispatchEvent(new Event("scenario-mutated"));
            } catch {
                toast.error("Failed to delete item");
            }
        }
        setSelectedItemData(null);
    }, [setSelectedItemData, sigma, stateRef]);

    return { handleEdgeSubmit, handleEdgeDelete, handleNodeSubmit, handleNodeDelete,
        handleItemSubmit, handleItemDelete };
};
