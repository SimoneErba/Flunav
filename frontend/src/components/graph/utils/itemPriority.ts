import type { ItemInput, ItemResponse } from "../../../api-client/api";

const HIGH_NUMERIC_PRIORITY = 0.8;

type ItemLike = Pick<ItemResponse, "priority"> | Pick<ItemInput, "priority"> | undefined | null;

/**
 * Counts operationally high-priority items independently of display-rule styling.
 */
export const isHighPriorityItem = (item: ItemLike): boolean => {
  return typeof item?.priority === "number"
    && Number.isFinite(item.priority)
    && item.priority >= HIGH_NUMERIC_PRIORITY;
};
