import type { ItemInput, ItemResponse } from "../../../api-client/api";

const HIGH_NUMERIC_PRIORITY = 0.8;

type ItemLike = Pick<ItemResponse, "priority" | "effectivePriority"> | Pick<ItemInput, "priority"> | undefined | null;

/**
 * Counts operationally high-priority items independently of display-rule styling.
 */
export const isHighPriorityItem = (item: ItemLike): boolean => {
  const priority = item && "effectivePriority" in item && typeof item.effectivePriority === "number"
    ? item.effectivePriority
    : item?.priority;
  return typeof priority === "number"
    && Number.isFinite(priority)
    && priority >= HIGH_NUMERIC_PRIORITY;
};
