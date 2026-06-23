import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts';
import { useWebSocketEvents } from '../../hooks/websocket/useWebSocketEvents';
import { useSimulationContext } from '../../context/simulation.context';
import { useApi } from '../../hooks/useApi';
import { ThroughputMetric } from '../../api-client/api';

interface PathAnalyticsProps {
  className?: string;
}

const LIVE_BUCKET_SECONDS = 5;
const HISTORY_BUCKET_SECONDS = 300;
const HISTORY_BUCKET_MS = HISTORY_BUCKET_SECONDS * 1000;
const HISTORY_HOURS = 24;

const emptyBucket = (
  timestamp: number,
  previousCurrent = 0,
  bucketSeconds = HISTORY_BUCKET_SECONDS
): ThroughputMetric => ({
  timestamp: new Date(timestamp).toISOString(),
  itemsEntered: 0,
  itemsExited: 0,
  itemsCurrent: previousCurrent,
  bucketSeconds,
});

const metricTimestamp = (metric: ThroughputMetric): number =>
  new Date(metric.timestamp ?? 0).getTime();

const historyBucketStart = (timestamp: number): number =>
  Math.floor(timestamp / HISTORY_BUCKET_MS) * HISTORY_BUCKET_MS;

/**
 * Projects 5-second websocket metrics into the same 5-minute buckets used by
 * history so every point on the chart has one consistent unit.
 */
const mergeLiveMetrics = (
  history: ThroughputMetric[],
  liveMetrics: Map<number, ThroughputMetric>
): ThroughputMetric[] => {
  const buckets = new Map<number, ThroughputMetric>();

  history.forEach((metric) => {
    const timestamp = metricTimestamp(metric);
    if (Number.isFinite(timestamp)) {
      buckets.set(timestamp, metric);
    }
  });

  [...liveMetrics.entries()]
    .sort(([left], [right]) => left - right)
    .forEach(([timestamp, metric]) => {
      const bucketTimestamp = historyBucketStart(timestamp);
      const current = buckets.get(bucketTimestamp) ?? emptyBucket(bucketTimestamp);
      buckets.set(bucketTimestamp, {
        timestamp: new Date(bucketTimestamp).toISOString(),
        itemsEntered: (current.itemsEntered ?? 0) + (metric.itemsEntered ?? 0),
        itemsExited: (current.itemsExited ?? 0) + (metric.itemsExited ?? 0),
        itemsCurrent: metric.itemsCurrent ?? current.itemsCurrent ?? 0,
        bucketSeconds: HISTORY_BUCKET_SECONDS,
      });
    });

  return [...buckets.values()]
    .sort((left, right) => metricTimestamp(left) - metricTimestamp(right))
    .slice(-(HISTORY_HOURS * 60 * 60) / HISTORY_BUCKET_SECONDS);
};

export const PathAnalytics = ({ className = '' }: PathAnalyticsProps) => {
  const [data, setData] = useState<ThroughputMetric[]>([]);
  const [loading, setLoading] = useState(true);
  const historyRef = useRef<ThroughputMetric[]>([]);
  const liveMetricsRef = useRef<Map<number, ThroughputMetric>>(new Map());
  const analyticsContextRef = useRef<string | null>(null);

  const { analyticsApi } = useApi();
  const { connected, subscribeToThroughputUpdates } = useWebSocketEvents();
  const { activeSimulation } = useSimulationContext();

  const simulationId = activeSimulation?.id ?? null;
  const restoreTimestamp = activeSimulation?.timestamp ?? null;
  const analyticsEndTimestamp = activeSimulation?.lastProcessedTimestamp ?? restoreTimestamp;

  const fillTimeGaps = useCallback((rawData: ThroughputMetric[]): ThroughputMetric[] => {
    if (rawData.length < 2) return rawData;

    const sorted = [...rawData].sort(
      (a, b) => new Date(a.timestamp ?? 0).getTime() - new Date(b.timestamp ?? 0).getTime()
    );
    const filled: ThroughputMetric[] = [];

    for (let i = 0; i < sorted.length - 1; i++) {
      const current = sorted[i];
      const next = sorted[i + 1];
      filled.push(current);

      const currentMs = new Date(current.timestamp ?? 0).getTime();
      const nextMs = new Date(next.timestamp ?? 0).getTime();
      const gapBuckets = Math.floor((nextMs - currentMs) / HISTORY_BUCKET_MS);

      for (let j = 1; j < gapBuckets; j++) {
        filled.push(
          emptyBucket(
            currentMs + j * HISTORY_BUCKET_MS,
            current.itemsCurrent ?? 0,
            HISTORY_BUCKET_SECONDS
          )
        );
      }
    }

    filled.push(sorted[sorted.length - 1]);
    return filled;
  }, []);

  useEffect(() => {
    let mounted = true;
    setLoading(true);
    const contextKey = simulationId ?? 'live';
    if (analyticsContextRef.current !== contextKey) {
      analyticsContextRef.current = contextKey;
      historyRef.current = [];
      liveMetricsRef.current = new Map();
      setData([]);
    }

    const fetchHistory = async () => {
      try {
        const endMs = new Date(analyticsEndTimestamp ?? new Date().toISOString()).getTime();
        const currentBucketStartMs = historyBucketStart(endMs);
        const historyToMs = currentBucketStartMs - 1;
        const historyFromMs = historyToMs - HISTORY_HOURS * 60 * 60 * 1000;

        const [historyResponse, currentResponse] = await Promise.all([
          analyticsApi.getThroughputHistory(
            undefined,
            new Date(historyFromMs).toISOString(),
            new Date(historyToMs).toISOString(),
            HISTORY_BUCKET_SECONDS
          ),
          analyticsApi.getThroughputHistory(
            undefined,
            new Date(currentBucketStartMs).toISOString(),
            new Date(endMs).toISOString(),
            LIVE_BUCKET_SECONDS
          ),
        ]);

        if (mounted) {
          const history = fillTimeGaps(historyResponse.data ?? []);
          const currentMetrics = new Map<number, ThroughputMetric>();
          (currentResponse.data ?? []).forEach((metric) => {
            const timestamp = metricTimestamp(metric);
            if (Number.isFinite(timestamp)) {
              currentMetrics.set(timestamp, metric);
            }
          });
          liveMetricsRef.current.forEach((metric, timestamp) => {
            currentMetrics.set(timestamp, metric);
          });

          historyRef.current = history;
          liveMetricsRef.current = currentMetrics;
          setData(mergeLiveMetrics(history, currentMetrics));
        }
      } catch (err) {
        console.error('Failed to load analytics history', err);
      } finally {
        if (mounted) setLoading(false);
      }
    };

    fetchHistory();
    return () => {
      mounted = false;
    };
  }, [analyticsApi, analyticsEndTimestamp, connected, fillTimeGaps, simulationId]);

  useEffect(() => {
    const handleUpdate = (metric: ThroughputMetric) => {
      const timestamp = metricTimestamp(metric);
      if (!Number.isFinite(timestamp)) return;

      liveMetricsRef.current.set(timestamp, metric);
      setData(mergeLiveMetrics(historyRef.current, liveMetricsRef.current));
    };

    const unsubscribe = connected
      ? subscribeToThroughputUpdates(handleUpdate, simulationId)
      : () => {};
    return () => unsubscribe();
  }, [connected, subscribeToThroughputUpdates, simulationId]);

  if (loading) {
    return (
      <div className={`flex h-72 items-center justify-center ${className}`}>
        <span className="text-sm text-gray-500 animate-pulse dark:text-gray-400">
          Loading analytics...
        </span>
      </div>
    );
  }

  return (
    <div className={`min-h-0 ${className}`}>
      <div className="mb-4 flex items-center justify-between gap-3">
        <h2 className="text-base font-semibold text-gray-900 dark:text-white">
          System Throughput
        </h2>
        <span className="text-xs text-gray-500 dark:text-gray-400">
          {simulationId ? 'Simulation' : 'Live'} · 5 min totals, refreshed every 5 sec
        </span>
      </div>

      <ResponsiveContainer width="100%" height={300}>
        <LineChart data={data}>
          <CartesianGrid strokeDasharray="3 3" opacity={0.2} />
          <XAxis
            dataKey="timestamp"
            fontSize={12}
            tickFormatter={(v) =>
              new Date(v).toLocaleTimeString([], {
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit',
              })
            }
            minTickGap={30}
          />
          <YAxis fontSize={12} allowDecimals={false} />
          <Tooltip
            labelFormatter={(v) => new Date(v).toLocaleString()}
            formatter={(value, name) => {
              if (name === 'itemsEntered') return [value, 'Entered'];
              if (name === 'itemsExited') return [value, 'Exited'];
              if (name === 'itemsCurrent') return [value, 'Current'];
              return [value, name];
            }}
          />
          <Legend />
          <Line
            type="monotone"
            dataKey="itemsEntered"
            name="Entered"
            stroke="#059669"
            strokeWidth={2}
            dot={false}
            isAnimationActive={false}
          />
          <Line
            type="monotone"
            dataKey="itemsExited"
            name="Exited"
            stroke="#dc2626"
            strokeWidth={2}
            dot={false}
            isAnimationActive={false}
          />
          <Line
            type="stepAfter"
            dataKey="itemsCurrent"
            name="Current"
            stroke="#2563eb"
            strokeWidth={2}
            dot={false}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
};
