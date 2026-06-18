import type { ItemInput, ItemResponse } from "../../../api-client/api";

const HIGH_PRIORITY_LABELS = new Set(["HIGH", "EXPEDITE", "URGENT", "CRITICAL"]);
const HIGH_NUMERIC_PRIORITY = 10;

type ItemLike = Pick<ItemResponse, "properties"> | Pick<ItemInput, "properties"> | undefined | null;

/**
 * Interprets item priority from loose custom properties for visual emphasis.
 * The frontend accepts both numeric and label-based values because integrations
 * may send priority in either shape.
 */
export const isHighPriorityItem = (item: ItemLike): boolean => {
  const properties = item?.properties as Record<string, unknown> | undefined;
  if (!properties) return false;

  const priorityEntry = Object.entries(properties).find(([key]) => key.toLowerCase() === "priority");
  if (!priorityEntry) return false;

  const priority = priorityEntry[1];
  if (typeof priority === "number") return priority >= HIGH_NUMERIC_PRIORITY;
  if (typeof priority === "string") {
    const numericPriority = Number(priority);
    if (Number.isFinite(numericPriority)) return numericPriority >= HIGH_NUMERIC_PRIORITY;
    return HIGH_PRIORITY_LABELS.has(priority.trim().toUpperCase());
  }

  return false;
};

/**
 * Returns the Sigma node attributes used by the bordered-square renderer.
 * Keeping this derived in one helper makes loaders and websocket updates apply
 * the same high-priority styling.
 */
export const getItemPriorityVisualAttributes = (item: ItemLike) => {
  const highPriority = isHighPriorityItem(item);
  return {
    highPriority,
    borderColor: highPriority ? "#facc15" : "#000000",
    borderSize: highPriority ? 3 : 0,
  };
};
