import { useEffect, useRef } from "react";
import { useSigma } from "@react-sigma/core";
import { ItemResponse } from "../../../api-client/api";
import { useSimulationContext } from "../../../context/simulation.context";
import type { ClockReader } from "./useSimulationClock";
import type { ActiveItems, ConveyorKeys } from "./useGraphRuntime";

export const useGraphAnimation = (
    activeItemsRef: React.MutableRefObject<ActiveItems>,
    edgeKeysRef: React.MutableRefObject<ConveyorKeys>,
    now: ClockReader,
    draggedNodeRef: React.MutableRefObject<string | null>
) => {
    const { designMode } = useSimulationContext();
    const sigma = useSigma();
    const animationFrameId = useRef<number | null>(null);
    const staging = useRef({ itemRevision: -1, edgeRevision: -1, indexes: new Map<string, number>() });

    /** Interpolates only from confirmed item checkpoints; domain events own transitions. */
    useEffect(() => {
        if (designMode) return;
        const graph = sigma.getGraph();
        const setIfChanged = (itemId: string, key: string, value: unknown): boolean => {
            if (graph.getNodeAttribute(itemId, key) === value) return false;
            graph.setNodeAttribute(itemId, key, value);
            return true;
        };
        const entryTimes = new WeakMap<ItemResponse, number>();
        const entryTime = (item: ItemResponse) => {
            let timestamp = entryTimes.get(item);
            if (timestamp === undefined) {
                timestamp = new Date(item.entryTimestamp ?? 0).getTime();
                entryTimes.set(item, timestamp);
            }
            return timestamp;
        };
        const updateStaging = () => {
            const items = activeItemsRef.current;
            if (staging.current.itemRevision === items.revision
                && staging.current.edgeRevision === edgeKeysRef.current.revision) return staging.current.indexes;
            const indexes = new Map<string, number>();
            const stagedByEdge = new Map<string, Array<[string, ItemResponse]>>();
            items.forEach((item, itemId) => {
                if (!item.currentEdgeId) return;
                const edge = edgeKeysRef.current.get(item.currentEdgeId);
                if (!edge || !graph.hasEdge(edge) || graph.getEdgeAttribute(edge, "conveyorType") !== "STAGING") return;
                const staged = stagedByEdge.get(edge) ?? [];
                staged.push([itemId, item]);
                stagedByEdge.set(edge, staged);
            });
            stagedByEdge.forEach(itemsOnEdge => {
                itemsOnEdge.sort(([leftId, left], [rightId, right]) => {
                    if (left.stagingOrder !== undefined && right.stagingOrder !== undefined) {
                        return left.stagingOrder - right.stagingOrder;
                    }
                    const timeDifference = entryTime(left) - entryTime(right);
                    return timeDifference || (right.progress ?? 0) - (left.progress ?? 0)
                        || leftId.localeCompare(rightId);
                });
                itemsOnEdge.forEach(([itemId], index) => indexes.set(itemId, index));
            });
            staging.current = { itemRevision: items.revision,
                edgeRevision: edgeKeysRef.current.revision, indexes };
            return indexes;
        };

        const animate = () => {
            let needsRefresh = false;
            const stagingIndexes = updateStaging();
            const virtualTime = now();
            // Many items share a conveyor; read its visible geometry once per frame.
            const edges = new Map<string, {
                attrs: ReturnType<typeof graph.getEdgeAttributes>;
                source: ReturnType<typeof graph.getNodeAttributes>;
                target: ReturnType<typeof graph.getNodeAttributes>;
            }>();
            const resolveEdge = (id: string) => {
                let resolved = edges.get(id);
                if (resolved) return resolved;
                const key = edgeKeysRef.current.get(id);
                if (!key || !graph.hasEdge(key)) return undefined;
                resolved = { attrs: graph.getEdgeAttributes(key),
                    source: graph.getNodeAttributes(graph.source(key)),
                    target: graph.getNodeAttributes(graph.target(key)) };
                edges.set(id, resolved);
                return resolved;
            };
            activeItemsRef.current.forEach((item, itemId) => {
                if (itemId === draggedNodeRef.current || !graph.hasNode(itemId)) return;
                if (item.currentEdgeId) {
                    const edge = resolveEdge(item.currentEdgeId);
                    if (!edge) return;
                    const { attrs, source, target } = edge;
                    const length = Number(attrs.length);
                    const speed = Number(attrs.speed);
                    const checkpointProgress = Math.min(1, Math.max(0, item.progress ?? 0));
                    let progress = checkpointProgress;
                    if (item.active !== false && !item.flowPaused && !attrs.flowStopped
                        && speed > 0 && length > 0) {
                        const elapsed = Math.max(0, virtualTime - entryTime(item));
                        progress = Math.min(1, checkpointProgress + elapsed / ((length / speed) * 1000));
                    }
                    if (attrs.conveyorType === "STAGING" && length > 0) {
                        const spacing = Number(attrs.minDistance ?? 0.1);
                        const index = stagingIndexes.get(itemId) ?? 0;
                        progress = Math.min(progress, Math.max(0, 1 - index * spacing / length));
                    }
                    needsRefresh = setIfChanged(itemId, "x", source.x + (target.x - source.x) * progress) || needsRefresh;
                    needsRefresh = setIfChanged(itemId, "y", source.y + (target.y - source.y) * progress) || needsRefresh;
                    needsRefresh = setIfChanged(itemId, "hidden", false) || needsRefresh;
                    needsRefresh = setIfChanged(itemId, "currentEdgeId", item.currentEdgeId) || needsRefresh;
                    needsRefresh = setIfChanged(itemId, "locationId", null) || needsRefresh;
                } else if (item.locationId && graph.hasNode(item.locationId)) {
                    const location = graph.getNodeAttributes(item.locationId);
                    needsRefresh = setIfChanged(itemId, "x", location.x) || needsRefresh;
                    needsRefresh = setIfChanged(itemId, "y", location.y) || needsRefresh;
                    needsRefresh = setIfChanged(itemId, "hidden", false) || needsRefresh;
                    needsRefresh = setIfChanged(itemId, "currentEdgeId", null) || needsRefresh;
                    needsRefresh = setIfChanged(itemId, "locationId", item.locationId) || needsRefresh;
                }
            });
            if (needsRefresh) sigma.refresh();
            animationFrameId.current = requestAnimationFrame(animate);
        };
        animationFrameId.current = requestAnimationFrame(animate);
        return () => { if (animationFrameId.current !== null) cancelAnimationFrame(animationFrameId.current); };
    }, [activeItemsRef, edgeKeysRef, draggedNodeRef, sigma, designMode, now]);
};
