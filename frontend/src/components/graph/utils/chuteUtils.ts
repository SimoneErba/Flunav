import type { AbstractGraph } from "graphology-types";
import type { ItemResponse } from "../../../api-client/api";

/**
 * Moves an item from animated graph state into chute occupancy state.
 * Chute contents are stored on the location node because completed chute items
 * should count toward capacity without cluttering the graph with stationary nodes.
 */
export const dischargeItemToChute = (
    graph: AbstractGraph,
    activeItems: Map<string, ItemResponse>,
    itemId: string,
    chuteId: string,
    itemData?: ItemResponse
) => {
    const item = itemData ?? activeItems.get(itemId);

    if (graph.hasNode(itemId)) {
        graph.dropNode(itemId);
    }
    activeItems.delete(itemId);

    if (!item || !graph.hasNode(chuteId)) return;

    const itemsInChute: ItemResponse[] = graph.getNodeAttribute(chuteId, "itemsInChute") ?? [];
    const capacity = graph.getNodeAttribute(chuteId, "capacity") as number | undefined;
    const label = graph.getNodeAttribute(chuteId, "label") as string | undefined;
    const baseName = label?.split(" (")[0] ?? chuteId;
    const updated = [...itemsInChute, item];

    graph.setNodeAttribute(chuteId, "itemsInChute", updated);

    if (capacity) {
        graph.setNodeAttribute(chuteId, "label", `${baseName} (${updated.length}/${capacity})`);
        if (updated.length > capacity) {
            graph.setNodeAttribute(chuteId, "color", "red");
        }
        return;
    }

    graph.setNodeAttribute(chuteId, "label", `${baseName} (${updated.length})`);
};
