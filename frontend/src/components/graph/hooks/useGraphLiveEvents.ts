import type { ConveyorKeys } from "./useGraphRuntime";
import { useCallback } from "react";
import { useSigma } from "@react-sigma/core";
import { ItemResponse } from "../../../api-client/api";
import { isHighPriorityItem } from "../utils/itemPriority";
import type { ClockReader } from "./useSimulationClock";
import { useSimulationContext } from "../../../context/simulation.context";

import { useGraphItemEvents } from "./useGraphItemEvents";
import { useGraphTopologyEvents } from "./useGraphTopologyEvents";
import { useGraphAlarmEvents } from "./useGraphAlarmEvents";

export const useGraphLiveEvents = (
    activeItemsRef: React.MutableRefObject<Map<string, ItemResponse>>,
    edgeKeysRef: React.MutableRefObject<ConveyorKeys>,
    simulationId: string | undefined,
    now: ClockReader,
    onHighPriorityCountChange?: (count: number) => void,
    onItemUpdated?: (itemId: string, item: ItemResponse) => void,
) => {
    const { designMode } = useSimulationContext();
    const sigma = useSigma();
    // WebSocket handlers are registered once per subscription set, so this ref
    // gives them current simulation time without constantly tearing down topics.


    /**
     * Recounts high-priority items from the source-of-truth item ref.
     * Graph nodes can be hidden or dropped for chute display, so counting the ref
     * avoids mixing visual representation with live item state.
     */
    const refreshHighPriorityCount = useCallback(() => {
        let highPriorityCount = 0;
        activeItemsRef.current.forEach((item) => {
            if (isHighPriorityItem(item)) highPriorityCount++;
        });
        onHighPriorityCountChange?.(highPriorityCount);
    }, [activeItemsRef, onHighPriorityCountChange]);

    /**
     * Reanchors conveyor item entry timestamps after a speed change.
     * The visible progress at the effective event time is preserved, then the entry
     * timestamp is recalculated so the animation loop continues smoothly.
     */
    const adjustItemsForSpeedChange = useCallback((edgeId: string, newSpeed: number, effectiveTime?: number) => {
        const graph = sigma.getGraph();
        if (!graph.hasEdge(edgeId)) return;

        const attrs = graph.getEdgeAttributes(edgeId);
        const oldSpeed = Number(attrs.speed);
        const length = Number(attrs.length);
        const anchorTime = effectiveTime ?? now();

        if (!Number.isFinite(length) || length <= 0) return;

        activeItemsRef.current.forEach((item, itemId) => {
            if (item.currentEdgeId === attrs.id) {
                const progress = oldSpeed > 0
                    ? Math.min(1, Math.max(0,
                        (item.progress ?? 0) + Math.max(0, anchorTime - new Date(item.entryTimestamp).getTime())
                        / ((length / oldSpeed) * 1000)))
                    : Math.min(1, Math.max(0, item.progress ?? 0));

                if (newSpeed <= 0) {
                    activeItemsRef.current.set(itemId, { ...item, progress,
                        entryTimestamp: new Date(anchorTime).toISOString() });
                    return;
                }

                const newEntryTimestamp = new Date(anchorTime).toISOString();
                activeItemsRef.current.set(itemId, { ...item, progress, entryTimestamp: newEntryTimestamp });
            }
        });
    }, [activeItemsRef, sigma, now]);

    const context = { sigma, activeItemsRef, edgeKeysRef, simulationId, designMode,
        refreshHighPriorityCount, onItemUpdated, adjustItemsForSpeedChange };
    useGraphItemEvents(context);
    useGraphTopologyEvents(context);
    useGraphAlarmEvents(context);

    return { adjustItemsForSpeedChange };
};
