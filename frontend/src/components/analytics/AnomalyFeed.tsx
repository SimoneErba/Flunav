import { useCallback, useEffect, useMemo, useState } from 'react';
import type { AnomalyFinding, AnomalyIncident } from '../../api-client/api';
import { axiosInstance } from '../../api/axiosInstance';
import { useSimulationContext } from '../../context/simulation.context';
import { useWebSocketEvents } from '../../hooks/websocket/useWebSocketEvents';

const cadenceFor = (detector: AnomalyFinding['detector']) =>
  detector === 'OCCUPANCY_JAM' || detector === 'SKIPPED_SENSOR' || detector === 'INVALID_PATH'
    ? '10 seconds'
    : '1 minute';

const score = (value?: number | null) => value == null ? '—' : value.toFixed(2);

export const AnomalyFeed = () => {
  const { activeSimulation } = useSimulationContext();
  const { connected, subscribeToAnomalies } = useWebSocketEvents();
  const [findings, setFindings] = useState<AnomalyFinding[]>([]);
  const [incidents, setIncidents] = useState<AnomalyIncident[]>([]);
  const [error, setError] = useState<string | null>(null);

  const headers = useMemo(() => activeSimulation?.id
    ? { 'X-Simulation-ID': activeSimulation.id }
    : undefined, [activeSimulation?.id]);

  const hydrate = useCallback(async () => {
    try {
      const [findingResponse, incidentResponse] = await Promise.all([
        axiosInstance.get<AnomalyFinding[]>('/api/analytics/anomalies', { headers }),
        axiosInstance.get<AnomalyIncident[]>('/api/analytics/anomaly-incidents', { headers }),
      ]);
      setFindings(findingResponse.data.filter(finding => finding.detector !== 'UNSCORABLE_CAPACITY'));
      setIncidents(incidentResponse.data);
      setError(null);
    } catch (requestError) {
      console.error('Failed to hydrate anomaly feed', requestError);
      setError('Anomaly history is temporarily unavailable.');
    }
  }, [headers]);

  useEffect(() => { void hydrate(); }, [hydrate]);

  useEffect(() => {
    if (!connected) return;
    return subscribeToAnomalies(() => { void hydrate(); }, activeSimulation?.id);
  }, [activeSimulation?.id, connected, hydrate, subscribeToAnomalies]);

  const clearAlarm = async (finding: AnomalyFinding) => {
    if (!finding.componentType || !finding.componentId || !finding.alarmId) return;
    await axiosInstance.post(
      `/api/components/${finding.componentType}/${encodeURIComponent(finding.componentId)}/alarms/${encodeURIComponent(finding.alarmId)}/clear`,
      undefined,
      { headers },
    );
    await hydrate();
  };

  return (
    <section className="space-y-3" aria-label="Scheduled anomaly findings">
      <div>
        <h2 className="text-base font-semibold text-gray-900 dark:text-white">Scheduled anomaly findings</h2>
        <p className="text-xs text-gray-500 dark:text-gray-400">
          Hydrated from detector history; live notifications are added only when their virtual tick is reached.
        </p>
      </div>
      {error && <div role="alert" className="rounded bg-red-50 p-2 text-xs text-red-700 dark:bg-red-950/40 dark:text-red-300">{error}</div>}
      {incidents.slice(0, 3).map((incident) => (
        <div key={incident.incidentId} className="rounded-lg border border-amber-300 bg-amber-50 p-3 dark:border-amber-800 dark:bg-amber-950/30">
          <div className="flex justify-between gap-3 text-xs">
            <span className="font-semibold text-amber-900 dark:text-amber-200">Probable root: {incident.probableRootComponentId}</span>
            <span className="rounded-full bg-amber-200 px-2 py-0.5 font-medium text-amber-900 dark:bg-amber-900 dark:text-amber-100">{incident.confidence}</span>
          </div>
          <p className="mt-1 text-xs text-amber-800 dark:text-amber-300">Evidence across {incident.componentIds?.length ?? 0} connected components. Advisory only.</p>
        </div>
      ))}
      {findings.length === 0 ? (
        <div className="rounded-lg border border-gray-200 bg-white p-8 text-center text-sm text-gray-500 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-400">
          No findings in the selected timeline window.
        </div>
      ) : (
        <div className="grid gap-3 lg:grid-cols-2">
          {findings.map((finding) => (
            <article key={finding.findingId} className="rounded-lg border border-gray-200 bg-white p-3 shadow-sm dark:border-gray-700 dark:bg-gray-800">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <div className="font-semibold text-gray-900 dark:text-white">{finding.detector?.replace(/_/g, ' ') ?? 'ANOMALY'}</div>
                  <div className="text-xs text-gray-500 dark:text-gray-400">{finding.componentType?.toLowerCase() ?? 'component'} {finding.componentId}</div>
                </div>
                <span className="rounded-full bg-blue-100 px-2 py-1 text-[10px] font-semibold text-blue-700 dark:bg-blue-950 dark:text-blue-300">{finding.temporalMode?.replace(/_/g, ' ') ?? 'UNKNOWN'}</span>
              </div>
              <dl className="mt-3 grid grid-cols-2 gap-x-3 gap-y-1 text-xs text-gray-600 dark:text-gray-300">
                <dt>Cadence</dt><dd className="text-right">{cadenceFor(finding.detector)}</dd>
                <dt>Classic z-score</dt><dd className="text-right tabular-nums">{score(finding.zScore)}</dd>
                <dt>Modified z-score</dt><dd className="text-right tabular-nums">{score(finding.modifiedZScore)}</dd>
                <dt>Baseline samples</dt><dd className="text-right tabular-nums">{finding.sampleCount}</dd>
                <dt>Mean / median</dt><dd className="text-right tabular-nums">{score(finding.baselineMean)} / {score(finding.baselineMedian)}</dd>
                <dt>Stddev / MAD</dt><dd className="text-right tabular-nums">{score(finding.baselineStddev)} / {score(finding.baselineMad)}</dd>
                <dt>Alarm</dt><dd className="text-right">{finding.alarmState?.replace(/_/g, ' ') ?? 'NOT APPLICABLE'}</dd>
                <dt>Virtual tick</dt><dd className="text-right">{finding.tickTimestamp ? new Date(finding.tickTimestamp).toLocaleString() : '—'}</dd>
              </dl>
              {(finding.expectedIntermediatePositions?.length ?? 0) > 0 && (
                <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">Expected via {finding.expectedIntermediatePositions?.join(' → ')}</p>
              )}
              {(finding.alarmState === 'ACTIVE' || finding.alarmState === 'ALREADY_ACTIVE') && (
                <button onClick={() => void clearAlarm(finding)} className="mt-3 rounded border border-gray-300 px-2 py-1 text-xs font-medium text-gray-700 hover:bg-gray-100 dark:border-gray-600 dark:text-gray-200 dark:hover:bg-gray-700">
                  Clear advisory alarm
                </button>
              )}
            </article>
          ))}
        </div>
      )}
    </section>
  );
};
