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

const MINUTE_MS = 60_000;

export const PathAnalytics = ({ className = '' }: PathAnalyticsProps) => {
  const [data, setData] = useState<ThroughputMetric[]>([]);
  const [loading, setLoading] = useState(true);

  const { analyticsApi } = useApi();
  const { subscribeToThroughputUpdates } = useWebSocketEvents();
  const { activeSimulation } = useSimulationContext();

  // ------------------------------------------------------------
  // Fill missing minutes with zero values
  // ------------------------------------------------------------
  const fillTimeGaps = useCallback(
    (rawData: ThroughputMetric[]): ThroughputMetric[] => {
      if (rawData.length < 2) return rawData;

      const sorted = [...rawData].sort(
        (a, b) =>
          new Date(a.timestamp).getTime() -
          new Date(b.timestamp).getTime()
      );

      const filled: ThroughputMetric[] = [];

      for (let i = 0; i < sorted.length - 1; i++) {
        const current = sorted[i];
        const next = sorted[i + 1];

        filled.push(current);

        const currentMs = new Date(current.timestamp).getTime();
        const nextMs = new Date(next.timestamp).getTime();
        const diffMinutes = Math.floor((nextMs - currentMs) / MINUTE_MS);

        // Fill idle minutes
        for (let j = 1; j < diffMinutes; j++) {
          filled.push({
            timestamp: new Date(currentMs + j * MINUTE_MS).toISOString(),
            itemsEntered: 0,
            itemsExited: 0,
            segmentsProcessed: 0,
          });
        }
      }

      filled.push(sorted[sorted.length - 1]);
      return filled;
    },
    []
  );

  // ------------------------------------------------------------
  // Initial load (history)
  // ------------------------------------------------------------
  useEffect(() => {
    let mounted = true;

    const fetchHistory = async () => {
      try {
        const response = await analyticsApi.getThroughputHistory(24);

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
  }, [analyticsApi, fillTimeGaps]);

  // ------------------------------------------------------------
  // Live updates (WebSocket)
  // ------------------------------------------------------------
  useEffect(() => {
    const handleUpdate = (metric: ThroughputMetric) => {
      setData((prev) => {
        if (prev.length === 0) return [metric];

        const newData = [...prev];
        const last = newData[newData.length - 1];

        const lastMinute =
          Math.floor(new Date(last.timestamp).getTime() / MINUTE_MS);
        const currentMinute =
          Math.floor(new Date(metric.timestamp).getTime() / MINUTE_MS);

        // Same minute → replace (aggregated backend)
        if (lastMinute === currentMinute) {
          newData[newData.length - 1] = metric;
          return newData;
        }

        // Fill gaps if we missed minutes
        const diffMinutes = currentMinute - lastMinute;

        for (let j = 1; j < diffMinutes; j++) {
          newData.push({
            timestamp: new Date(
              new Date(last.timestamp).getTime() + j * MINUTE_MS
            ).toISOString(),
            itemsEntered: 0,
            itemsExited: 0,
            segmentsProcessed: 0,
          });
        }

        newData.push(metric);
        return newData;
      });
    };

    const unsubscribe = subscribeToThroughputUpdates(handleUpdate);
    return () => unsubscribe();
  }, [subscribeToThroughputUpdates, activeSimulation?.id]);

  // ------------------------------------------------------------
  // UI
  // ------------------------------------------------------------
  if (loading) {
    return (
      <div
        className={`p-8 flex justify-center items-center bg-white dark:bg-gray-800 rounded-lg shadow ${className}`}
      >
        <span className="text-gray-500 animate-pulse">
          Loading analytics...
        </span>
      </div>
    );
  }

  return (
    <div
      className={`p-4 bg-white dark:bg-gray-800 rounded-lg shadow ${className}`}
    >
      <div className="flex justify-between items-center mb-4">
        <h2 className="text-xl font-bold dark:text-white">
          System Throughput
        </h2>
        <span className="text-xs text-gray-500">
          Live updates (1 min)
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
              })
            }
            minTickGap={30}
          />
          <YAxis fontSize={12} />
          <Tooltip
            labelFormatter={(v) => new Date(v).toLocaleString()}
            formatter={(value, name) => {
              if (name === 'itemsEntered') return [value, 'Entered'];
              if (name === 'itemsExited') return [value, 'Exited'];
              if (name === 'segmentsProcessed') return [value, 'Segments'];
              return [value, name];
            }}
          />
          <Legend />
          <Line
            type="monotone"
            dataKey="itemsEntered"
            stroke="#10b981"
            strokeWidth={2}
            dot={false}
            isAnimationActive={false}
          />
          <Line
            type="monotone"
            dataKey="itemsExited"
            stroke="#f43f5e"
            strokeWidth={2}
            dot={false}
            isAnimationActive={false}
          />
          <Line
            type="monotone"
            dataKey="segmentsProcessed"
            stroke="#3b82f6"
            strokeWidth={2}
            dot={false}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
};
