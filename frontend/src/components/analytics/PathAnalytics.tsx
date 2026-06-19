import React, { useState, useEffect, useCallback } from 'react';
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

const BUCKET_SECONDS = 5;
const BUCKET_MS = BUCKET_SECONDS * 1000;
const HISTORY_HOURS = 24;

const emptyBucket = (timestamp: number, previousCurrent = 0): ThroughputMetric => ({
  timestamp: new Date(timestamp).toISOString(),
  itemsEntered: 0,
  itemsExited: 0,
  itemsCurrent: previousCurrent,
  bucketSeconds: BUCKET_SECONDS,
});

export const PathAnalytics = ({ className = '' }: PathAnalyticsProps) => {
  const [data, setData] = useState<ThroughputMetric[]>([]);
  const [loading, setLoading] = useState(true);

  const { analyticsApi } = useApi();
  const { connected, subscribeToThroughputUpdates } = useWebSocketEvents();
  const { activeSimulation } = useSimulationContext();

  const simulationId = activeSimulation?.id ?? null;
  const simulationTimestamp = activeSimulation?.lastProcessedTimestamp ?? activeSimulation?.timestamp ?? null;

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
      const gapBuckets = Math.floor((nextMs - currentMs) / BUCKET_MS);

      for (let j = 1; j < gapBuckets; j++) {
        filled.push(emptyBucket(currentMs + j * BUCKET_MS, current.itemsCurrent ?? 0));
      }
    }

    filled.push(sorted[sorted.length - 1]);
    return filled;
  }, []);

  useEffect(() => {
    let mounted = true;
    setLoading(true);
    setData([]);

    const fetchHistory = async () => {
      try {
        const to = simulationTimestamp ?? new Date().toISOString();
        const from = new Date(new Date(to).getTime() - HISTORY_HOURS * 60 * 60 * 1000).toISOString();
        const response = simulationId
          ? await analyticsApi.getThroughputHistory(undefined, from, to, BUCKET_SECONDS)
          : await analyticsApi.getThroughputHistory(HISTORY_HOURS, undefined, undefined, BUCKET_SECONDS);

        if (mounted && response.data) {
          setData(fillTimeGaps(response.data));
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
  }, [analyticsApi, fillTimeGaps, simulationId, simulationTimestamp]);

  useEffect(() => {
    const handleUpdate = (metric: ThroughputMetric) => {
      setData((prev) => {
        if (!metric.timestamp) return prev;
        if (prev.length === 0) return [metric];

        const newData = [...prev];
        const last = newData[newData.length - 1];
        const lastTime = new Date(last.timestamp ?? 0).getTime();
        const metricTime = new Date(metric.timestamp).getTime();
        const lastBucket = Math.floor(lastTime / BUCKET_MS);
        const currentBucket = Math.floor(metricTime / BUCKET_MS);

        if (lastBucket === currentBucket) {
          newData[newData.length - 1] = metric;
          return newData;
        }

        const gapBuckets = currentBucket - lastBucket;
        for (let j = 1; j < gapBuckets; j++) {
          newData.push(emptyBucket(lastTime + j * BUCKET_MS, last.itemsCurrent ?? 0));
        }

        newData.push(metric);
        return newData.slice(-720);
      });
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
          {simulationId ? 'Simulation' : 'Live'} updates (5 sec)
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
