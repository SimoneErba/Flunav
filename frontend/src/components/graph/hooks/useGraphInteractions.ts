import { useRef, useEffect, useCallback } from "react";
import { useApi } from "../../../hooks/useApi";
import type { ItemEditorData } from "../../editors/item.editor";
import { toastWarning } from "../utils/toastUtils";
import { useSimulationContext } from "../../../context/simulation.context";
import type { GraphSelectionState } from "./useGraphSelection";
import { useGraphEditorCommands } from "./useGraphEditorCommands";
import { useGraphPointerInteractions } from "./useGraphPointerInteractions";

export interface InteractionState extends GraphSelectionState {
    setHoverTarget: (target: { nodeId: string; x: number; y: number; attributes: ItemEditorData } | null) => void;
}

export type GraphInteractionSnapshot = Pick<ReturnType<typeof useApi>, "locationApi" | "itemApi" | "conveyorsApi">
    & Pick<GraphSelectionState, "selectedEdgeData" | "selectedNodeData" | "selectedItemData" | "isDetailsOpen">
    & { isReadOnly: boolean; adjustItemsForSpeedChange: (edgeId: string, speed: number) => void };

export const useGraphInteractions = (
    adjustItemsForSpeedChange: (edgeId: string, newSpeed: number) => void,
    interaction: InteractionState,
    simulationId?: string | null,
) => {
    const { locationApi, conveyorsApi, itemApi } = useApi();
    const { designMode } = useSimulationContext();
    const isReadOnly = import.meta.env.VITE_DEMO_MODE === "true" && designMode && !simulationId;
    const { selectedEdgeData, selectedNodeData, selectedItemData, isDetailsOpen } = interaction;
    const notifyReadOnly = useCallback(() => {
        toastWarning("Modification disabled in Demo, start a simulation", { id: "readonly-toast" });
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

    const commands = useGraphEditorCommands(stateRef, interaction);
    const pointer = useGraphPointerInteractions(stateRef, interaction, notifyReadOnly);
    return { ...interaction, ...commands, ...pointer };
};
