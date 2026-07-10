import { useEffect, useState } from "react";
import { useSigma } from "@react-sigma/core";
import { ItemResponseRoutingStatusEnum } from "../../api-client/api";
import type { ItemResponse, ThroughputMetric } from "../../api-client/api";
import { useApi } from "../../hooks/useApi";
import { useWebSocketEvents } from "../../hooks/websocket/useWebSocketEvents";
import { isHighPriorityItem } from "./utils/itemPriority";

interface LiveHudProps {
  activeItemsRef: React.MutableRefObject<Map<string, ItemResponse>>;
  simulationId?: string;
}

interface HudCounts {
  active: number;
  priority: number;
  routed: number;
  waiting: number;
  unrouted: number;
  failed: number;
  completed: number;
  stopped: number;
  fullChutes: number;
}

interface ThroughputCounts {
  entered: number;
  cleared: number;
}

const EMPTY_COUNTS: HudCounts = {
  active: 0,
  priority: 0,
  routed: 0,
  waiting: 0,
  unrouted: 0,
  failed: 0,
  completed: 0,
  stopped: 0,
  fullChutes: 0,
};

const EMPTY_THROUGHPUT: ThroughputCounts = {
  entered: 0,
  cleared: 0,
};

const HUD_REFRESH_MS = 500;

/**
 * Narrows graph attributes before using them in HUD counters.
 * Sigma attributes can come from API data, websocket patches, or editor forms, so
 * numeric values are normalized at the display boundary.
 */
const asNumber = (value: unknown): number | null => {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string") {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return null;
};

const isTrackedCapacityLocation = (locationType: unknown): boolean => {
  const normalized = String(locationType ?? "").toUpperCase();
  return normalized === "CHUTE" || normalized === "ACCUMULATION";
};

const areHudCountsEqual = (a: HudCounts, b: HudCounts): boolean =>
  a.active === b.active &&
  a.priority === b.priority &&
  a.routed === b.routed &&
  a.waiting === b.waiting &&
  a.unrouted === b.unrouted &&
  a.failed === b.failed &&
  a.completed === b.completed &&
  a.stopped === b.stopped &&
  a.fullChutes === b.fullChutes;

const formatMetricValue = (value: number): string => Number(value || 0).toLocaleString();

const getThroughputCounts = (metric: ThroughputMetric): ThroughputCounts => ({
  entered: metric.itemsEntered ?? 0,
  cleared: metric.itemsExited ?? 0,
});

export const LiveHud = ({ activeItemsRef, simulationId }: LiveHudProps) => {
  const sigma = useSigma();
  const { analyticsApi } = useApi();
  const { connected, subscribeToThroughputUpdates } = useWebSocketEvents();

  const [counts, setCounts] = useState<HudCounts>(EMPTY_COUNTS);
  const [throughput, setThroughput] = useState<ThroughputCounts>(EMPTY_THROUGHPUT);

  /**
   * Derives lightweight operational counters from current graph and item refs.
   * The HUD polls local state because graph animation changes visual occupancy
   * more often than backend metric events arrive.
   */
  useEffect(() => {
    const updateCounts = () => {
      const graph = sigma.getGraph();
      const uniqueItems = new Map<string, ItemResponse>();
      const occupancyByLocation = new Map<string, Set<string>>();

      activeItemsRef.current.forEach((item) => {
        if (!item.id) return;
        uniqueItems.set(item.id, item);

        if (item.locationId) {
          const occupants = occupancyByLocation.get(item.locationId) ?? new Set<string>();
          occupants.add(item.id);
          occupancyByLocation.set(item.locationId, occupants);
        }
      });

      let stopped = 0;
      graph.forEachEdge((_edge, attributes) => {
        const speed = asNumber(attributes.speed);
        if (attributes.active === false || speed === 0) {
          stopped++;
        }
      });

      let fullChutes = 0;
      graph.forEachNode((nodeId, attributes) => {
        const chuteItems = Array.isArray(attributes.itemsInChute) ? attributes.itemsInChute as ItemResponse[] : [];
        chuteItems.forEach((item, index) => {
          const itemId = item.id ?? `${nodeId}:chute:${index}`;
          uniqueItems.set(itemId, item);
          const occupants = occupancyByLocation.get(nodeId) ?? new Set<string>();
          occupants.add(itemId);
          occupancyByLocation.set(nodeId, occupants);
        });

        if (!isTrackedCapacityLocation(attributes.locationType)) return;

        const capacity = asNumber(attributes.capacity);
        if (capacity === null || capacity <= 0) return;

        const occupancy = occupancyByLocation.get(nodeId)?.size ?? 0;

        if (occupancy >= capacity) {
          fullChutes++;
        }
      });

      let priority = 0;
      let routed = 0;
      let waiting = 0;
      let unrouted = 0;
      let failed = 0;
      let completed = 0;

      uniqueItems.forEach((item) => {
        if (isHighPriorityItem(item)) priority++;

        switch (item.routingStatus) {
          case ItemResponseRoutingStatusEnum.Assigned:
            routed++;
            break;
          case ItemResponseRoutingStatusEnum.WaitingForCapacity:
            waiting++;
            break;
          case ItemResponseRoutingStatusEnum.Unrouted:
            unrouted++;
            break;
          case ItemResponseRoutingStatusEnum.Failed:
            failed++;
            break;
          case ItemResponseRoutingStatusEnum.Completed:
            completed++;
            break;
        }
      });

      const nextCounts: HudCounts = {
        active: uniqueItems.size,
        priority,
        routed,
        waiting,
        unrouted,
        failed,
        completed,
        stopped,
        fullChutes,
      };

      setCounts((current) => (areHudCountsEqual(current, nextCounts) ? current : nextCounts));
    };

    updateCounts();
    const intervalId = window.setInterval(updateCounts, HUD_REFRESH_MS);

    return () => window.clearInterval(intervalId);
  }, [activeItemsRef, sigma]);

  /**
   * Seeds throughput from the latest live metric and then follows websocket updates.
   * Simulations skip the initial live fetch so their HUD does not briefly display
   * live throughput while subscribing to simulation topics.
   */
  useEffect(() => {
    let mounted = true;
    setThroughput(EMPTY_THROUGHPUT);

    const fetchLatestLiveMetric = async () => {
      if (simulationId) return;

      try {
        const response = await analyticsApi.getThroughputHistory(1);
        if (!mounted) return;

        const latest = response.data?.[response.data.length - 1];
        if (latest) {
          setThroughput(getThroughputCounts(latest));
        }
      } catch (error) {
        console.error("Failed to load latest throughput metric", error);
      }
    };

    fetchLatestLiveMetric();

    const unsubscribe = connected
      ? subscribeToThroughputUpdates((metric) => {
          setThroughput(getThroughputCounts(metric));
        }, simulationId ?? null)
      : () => {};

    return () => {
      mounted = false;
      unsubscribe();
    };
  }, [analyticsApi, connected, simulationId, subscribeToThroughputUpdates]);

  const items = [
    { label: "Active", value: counts.active, testId: "live-hud-active" },
    { label: "Priority", value: counts.priority, testId: "live-hud-priority" },
    { label: "Routed", value: counts.routed, testId: "live-hud-routed" },
    { label: "Waiting", value: counts.waiting, alert: counts.waiting > 0, testId: "live-hud-waiting" },
    { label: "Unrouted", value: counts.unrouted, alert: counts.unrouted > 0, testId: "live-hud-unrouted" },
    { label: "Failed", value: counts.failed, alert: counts.failed > 0, testId: "live-hud-failed" },
    { label: "Completed", value: counts.completed, testId: "live-hud-completed" },
    { label: "In/5s", value: throughput.entered, testId: "live-hud-in-5s" },
    { label: "Cleared/5s", value: throughput.cleared, testId: "live-hud-cleared-5s" },
    { label: "Stopped", value: counts.stopped, alert: counts.stopped > 0, testId: "live-hud-stopped" },
    { label: "Full chutes", value: counts.fullChutes, alert: counts.fullChutes > 0, testId: "live-hud-full-chutes" },
  ];

  return (
    <div
      data-testid="live-hud"
      className="absolute left-4 top-4 z-[110] max-w-[calc(100%-2rem)] rounded-lg border border-gray-200 bg-white/95 px-3 py-2 text-gray-900 shadow-lg backdrop-blur dark:border-gray-700 dark:bg-gray-900/90 dark:text-gray-100"
    >
      <div className="flex max-w-xl flex-wrap items-center gap-x-4 gap-y-2">
        {items.map((item) => (
          <div key={item.label} data-testid={item.testId} className="flex min-w-0 items-baseline gap-1.5">
            <span className="text-[10px] font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">
              {item.label}
            </span>
            <span
              className={`text-sm font-semibold tabular-nums ${
                item.alert ? "text-red-600 dark:text-red-400" : "text-gray-900 dark:text-gray-100"
              }`}
            >
              {formatMetricValue(item.value)}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
};
