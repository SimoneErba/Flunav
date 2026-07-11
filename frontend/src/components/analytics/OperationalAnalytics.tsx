import { useCallback, useEffect, useMemo, useState } from 'react';
import { ConveyorStopMetric, JourneySummary } from '../../api-client';
import { useSimulationContext } from '../../context/simulation.context';
import { useApi } from '../../hooks/useApi';

const REFRESH_INTERVAL_MS = 5_000;
const WINDOW_MS = 24 * 60 * 60 * 1000;

const formatDuration = (milliseconds?: number) => {
  const value = milliseconds ?? 0;
  if (value < 1_000) return `${Math.round(value)} ms`;
  if (value < 60_000) return `${(value / 1_000).toFixed(value < 10_000 ? 1 : 0)} s`;
  const minutes = Math.floor(value / 60_000);
  const seconds = Math.round((value % 60_000) / 1_000);
  return `${minutes}m ${seconds}s`;
};

const formatPercent = (value?: number) => `${(value ?? 0).toFixed(1)}%`;

export const OperationalAnalytics = () => {
  const { analyticsApi } = useApi();
  const { activeSimulation } = useSimulationContext();
  const [summary, setSummary] = useState<JourneySummary | null>(null);
  const [stops, setStops] = useState<ConveyorStopMetric[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const simulationEnd = activeSimulation?.lastProcessedTimestamp ?? activeSimulation?.timestamp;
  const contextKey = activeSimulation?.id ?? 'live';

  const fetchAnalytics = useCallback(async () => {
    const end = new Date(simulationEnd ?? Date.now());
    const start = new Date(end.getTime() - WINDOW_MS);
    try {
      const [summaryResponse, stopsResponse] = await Promise.all([
        analyticsApi.getJourneySummary(start.toISOString(), end.toISOString()),
        analyticsApi.getConveyorStops(start.toISOString(), end.toISOString()),
      ]);
      setSummary(summaryResponse.data);
      setStops([...(stopsResponse.data ?? [])].sort(
        (left, right) => (right.totalStoppedMillis ?? 0) - (left.totalStoppedMillis ?? 0)
      ));
      setError(null);
    } catch (requestError) {
      console.error('Failed to load operational analytics', requestError);
      setError('Operational analytics are unavailable. The next refresh will retry automatically.');
    } finally {
      setLoading(false);
    }
  }, [analyticsApi, simulationEnd]);

  useEffect(() => {
    setLoading(true);
    setSummary(null);
    setStops([]);
    void fetchAnalytics();
    const intervalId = window.setInterval(fetchAnalytics, REFRESH_INTERVAL_MS);
    return () => window.clearInterval(intervalId);
  }, [contextKey, fetchAnalytics]);

  const cards = useMemo(() => [
    {
      label: 'Completed journeys',
      value: String(summary?.completedItemCount ?? 0),
      detail: 'Successful chute exits',
    },
    {
      label: 'Average traversal',
      value: formatDuration(summary?.averageTraversalMillis),
      detail: `Min ${formatDuration(summary?.minimumTraversalMillis)} · Max ${formatDuration(summary?.maximumTraversalMillis)}`,
    },
    {
      label: 'P95 traversal',
      value: formatDuration(summary?.p95TraversalMillis),
      detail: `P50 ${formatDuration(summary?.p50TraversalMillis)} · P99 ${formatDuration(summary?.p99TraversalMillis)}`,
    },
    {
      label: 'Journeys recirculated',
      value: formatPercent(summary?.completedJourneysWithRecirculationPercentage),
      detail: `${summary?.completedJourneysWithRecirculation ?? 0} journeys · ${summary?.recirculationEventCount ?? 0} reassignments`,
    },
  ], [summary]);

  if (loading && summary === null) {
    return (
      <div className="flex min-h-40 items-center justify-center rounded-lg border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
        <span className="animate-pulse text-sm text-gray-500 dark:text-gray-400">Loading operational analytics...</span>
      </div>
    );
  }

  return (
    <section className="space-y-4" aria-label="Operational analytics">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h2 className="text-base font-semibold text-gray-900 dark:text-white">Journey & availability</h2>
          <p className="text-xs text-gray-500 dark:text-gray-400">
            {activeSimulation ? 'Simulation timeline' : 'Live timeline'} · latest 24 hours · refreshes every 5 seconds
          </p>
        </div>
        {error && (
          <div role="alert" className="max-w-md rounded-md bg-red-50 px-3 py-2 text-xs text-red-700 dark:bg-red-950/40 dark:text-red-300">
            {error}
          </div>
        )}
      </div>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {cards.map((card) => (
          <div key={card.label} className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm dark:border-gray-700 dark:bg-gray-800">
            <p className="text-xs font-medium uppercase tracking-wide text-gray-500 dark:text-gray-400">{card.label}</p>
            <p className="mt-1 text-2xl font-semibold tabular-nums text-gray-900 dark:text-white">{card.value}</p>
            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{card.detail}</p>
          </div>
        ))}
      </div>

      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800">
        <div className="border-b border-gray-200 px-4 py-3 dark:border-gray-700">
          <h3 className="text-sm font-semibold text-gray-900 dark:text-white">Conveyor stops</h3>
          <p className="text-xs text-gray-500 dark:text-gray-400">Ranked by stopped time overlapping the selected window</p>
        </div>
        {stops.length === 0 ? (
          <div className="px-4 py-10 text-center text-sm text-gray-500 dark:text-gray-400">
            No conveyor stop intervals overlap this 24-hour window.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-gray-200 text-sm dark:divide-gray-700">
              <thead className="bg-gray-50 text-left text-xs uppercase tracking-wide text-gray-500 dark:bg-gray-900/60 dark:text-gray-400">
                <tr>
                  <th className="px-4 py-3 font-medium">Conveyor</th>
                  <th className="px-4 py-3 text-right font-medium">Stops</th>
                  <th className="px-4 py-3 text-right font-medium">Stopped</th>
                  <th className="px-4 py-3 text-right font-medium">Average</th>
                  <th className="px-4 py-3 text-right font-medium">Maximum</th>
                  <th className="px-4 py-3 text-right font-medium">Availability</th>
                  <th className="px-4 py-3 font-medium">State</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100 dark:divide-gray-700">
                {stops.map((stop) => (
                  <tr key={stop.conveyorId} className="text-gray-700 dark:text-gray-200">
                    <td className="whitespace-nowrap px-4 py-3 font-medium text-gray-900 dark:text-white">{stop.conveyorId}</td>
                    <td className="px-4 py-3 text-right tabular-nums">{stop.overlappingStopCount ?? 0}</td>
                    <td className="px-4 py-3 text-right tabular-nums">{formatDuration(stop.totalStoppedMillis)}</td>
                    <td className="px-4 py-3 text-right tabular-nums">{formatDuration(stop.averageStoppedMillis)}</td>
                    <td className="px-4 py-3 text-right tabular-nums">{formatDuration(stop.maximumStoppedMillis)}</td>
                    <td className="px-4 py-3 text-right tabular-nums">{formatPercent(stop.availabilityPercentage)}</td>
                    <td className="whitespace-nowrap px-4 py-3">
                      {stop.currentlyStopped ? (
                        <span className="inline-flex rounded-full bg-red-100 px-2 py-1 text-xs font-medium text-red-700 dark:bg-red-950/50 dark:text-red-300">
                          Stopped since {stop.stopStartedAt ? new Date(stop.stopStartedAt).toLocaleTimeString() : 'unknown'}
                        </span>
                      ) : (
                        <span className="inline-flex rounded-full bg-emerald-100 px-2 py-1 text-xs font-medium text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-300">
                          Running
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  );
};
