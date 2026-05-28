import type { AbstractGraph } from "graphology-types";
import type { ItemResponse } from "../../../api-client/api";

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
