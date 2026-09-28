import type { MutableRefObject } from "react";
import type { useSigma } from "@react-sigma/core";
import type { ItemResponse } from "../../../api-client/api";
import type { ConveyorKeys } from "./useGraphRuntime";

export interface GraphLiveEventContext {
    sigma: ReturnType<typeof useSigma>;
    activeItemsRef: MutableRefObject<Map<string, ItemResponse>>;
    edgeKeysRef: MutableRefObject<ConveyorKeys>;
    simulationId: string | undefined;
    designMode: boolean;
    refreshHighPriorityCount: () => void;
    onItemUpdated?: (itemId: string, item: ItemResponse) => void;
    adjustItemsForSpeedChange: (edgeId: string, speed: number, timestamp?: number) => void;
}
