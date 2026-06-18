import { useEffect, useState } from "react";
import { useSigma } from "@react-sigma/core";
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
      const occupancyByLocation = new Map<string, number>();
      let priority = 0;

      activeItemsRef.current.forEach((item) => {
        if (isHighPriorityItem(item)) priority++;

        if (item.locationId) {
          occupancyByLocation.set(item.locationId, (occupancyByLocation.get(item.locationId) ?? 0) + 1);
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
        if (!isTrackedCapacityLocation(attributes.locationType)) return;

        const capacity = asNumber(attributes.capacity);
        if (capacity === null || capacity <= 0) return;

        const storedItems = Array.isArray(attributes.itemsInChute) ? attributes.itemsInChute.length : 0;
        const knownOccupancy = occupancyByLocation.get(nodeId) ?? 0;
        const occupancy = Math.max(storedItems, knownOccupancy);

        if (occupancy >= capacity) {
          fullChutes++;
        }
      });

      const nextCounts: HudCounts = {
        active: activeItemsRef.current.size,
        priority,
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
    { label: "Active", value: counts.active },
    { label: "Priority", value: counts.priority },
    { label: "In/min", value: throughput.entered },
    { label: "Cleared/min", value: throughput.cleared },
    { label: "Stopped", value: counts.stopped, alert: counts.stopped > 0 },
    { label: "Full chutes", value: counts.fullChutes, alert: counts.fullChutes > 0 },
  ];

  return (
    <div className="absolute left-4 top-4 z-[110] max-w-[calc(100%-2rem)] rounded-lg border border-gray-200 bg-white/95 px-3 py-2 text-gray-900 shadow-lg backdrop-blur dark:border-gray-700 dark:bg-gray-900/90 dark:text-gray-100">
      <div className="flex max-w-xl flex-wrap items-center gap-x-4 gap-y-2">
        {items.map((item) => (
          <div key={item.label} className="flex min-w-0 items-baseline gap-1.5">
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
