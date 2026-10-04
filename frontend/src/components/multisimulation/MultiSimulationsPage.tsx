import { useCallback, useEffect, useMemo, useState } from 'react';
import toast from 'react-hot-toast';

import { axiosInstance } from '../../api/axiosInstance';
import { experimentExportApi, downloadDocument } from '../../api/scenarios';
import {
  multiSimulationApi,
  type ArrivalDistribution,
  type ConveyorFailureConfiguration,
  type DestinationProbability,
  type MetricDistribution,
  type MultiSimulationConfiguration,
  type MultiSimulationEstimate,
  type MultiSimulationReport,
  type MultiSimulationResponse,
} from '../../api/multiSimulation';
import { useAuth } from '../../context/auth.context';
import { useWebSocketEvents } from '../../hooks/websocket/useWebSocketEvents';
import { conveyorFailureDefaults } from '../conveyors/presets';
import { AppHeader } from '../AppHeader';
import { AppNavigation } from '../AppNavigation';

interface TopologyLocation { id: string; name: string; type: string; active: boolean; }
interface TopologyConveyor { id: string; name: string; properties?: Record<string, unknown>; }
interface TopologyData { locations: TopologyLocation[]; conveyors: TopologyConveyor[]; }
interface DestinationExitMapping { destination: string; exits: string[]; }

const terminalStatuses = new Set(['COMPLETED', 'COMPLETED_WITH_FAILURES', 'CANCELLED', 'FAILED']);

const formatDuration = (seconds: number) => {
  if (seconds < 60) return `${seconds} sec`;
  if (seconds < 3600) return `${Math.ceil(seconds / 60)} min`;
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.ceil((seconds % 3600) / 60);
  return minutes ? `${hours} h ${minutes} min` : `${hours} h`;
};

export const MultiSimulationsPage = () => {
  const { user } = useAuth();
  const { connected, subscribeToMultiSimulationStatus } = useWebSocketEvents();
  const canRun = user?.role === 'ADMIN' || user?.role === 'SUPERADMIN';
  const [simulations, setSimulations] = useState<MultiSimulationResponse[]>([]);
  const [selected, setSelected] = useState<MultiSimulationResponse>();
  const [report, setReport] = useState<MultiSimulationReport>();
  const [topology, setTopology] = useState<TopologyData>({ locations: [], conveyors: [] });
  const [destinationMappings, setDestinationMappings] = useState<DestinationExitMapping[]>([]);
  const [showCreate, setShowCreate] = useState(false);
  const [loading, setLoading] = useState(true);

  const refreshList = useCallback(async () => {
    const values = await multiSimulationApi.list();
    setSimulations(values);
    setSelected(previous => previous ? values.find(value => value.id === previous.id) ?? previous : previous);
  }, []);

  useEffect(() => {
    Promise.all([
      refreshList(),
      axiosInstance.get<TopologyData>('/api/graph', { params: { topologyOnly: true } }),
      axiosInstance.get<DestinationExitMapping[]>('/api/destination-exit-mappings'),
    ]).then(([, graph, mappings]) => {
      setTopology(graph.data);
      setDestinationMappings(mappings.data);
    }).catch(error => toast.error(`Could not load multi-simulations: ${String(error)}`))
      .finally(() => setLoading(false));
  }, [refreshList]);

  const selectedId = selected?.id;
  const selectedStatus = selected?.status;

  useEffect(() => {
    if (!selectedId || !selectedStatus || terminalStatuses.has(selectedStatus) || selectedStatus === 'DRAFT') return;

    let active = true;
    const applyUpdate = (value: MultiSimulationResponse) => {
      if (!active) return;
      setSelected(previous => previous?.id === value.id ? value : previous);
      setSimulations(previous => previous.map(item => item.id === value.id ? value : item));
    };
    const refresh = () => multiSimulationApi.get(selectedId).then(applyUpdate).catch(console.warn);

    if (connected) {
      const unsubscribe = subscribeToMultiSimulationStatus(selectedId, applyUpdate);
      void refresh();
      return () => {
        active = false;
        unsubscribe();
      };
    }

    void refresh();
    const interval = window.setInterval(refresh, 5000);
    return () => {
      active = false;
      window.clearInterval(interval);
    };
  }, [connected, selectedId, selectedStatus, subscribeToMultiSimulationStatus]);

  useEffect(() => {
    if (!selected || !['COMPLETED', 'COMPLETED_WITH_FAILURES', 'CANCELLED'].includes(selected.status)) {
      setReport(undefined);
      return;
    }
    multiSimulationApi.report(selected.id).then(setReport).catch(() => setReport(undefined));
  }, [selected]);

  return (
    <div className="flex h-screen flex-col bg-gray-50 text-gray-900 dark:bg-[#121212] dark:text-white">
      <AppHeader
        centerContent={<span className="font-semibold">Multi-simulations</span>}
        leftActions={<AppNavigation activeMode="simulations" />}
        rightActions={canRun ? (
          <button type="button" disabled={loading} onClick={() => setShowCreate(true)} className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-bold text-white hover:bg-blue-700">
            New multi-simulation
          </button>
        ) : undefined}
      />
      <main className="grid min-h-0 flex-1 grid-cols-1 overflow-hidden lg:grid-cols-[360px_1fr]">
        <aside className="overflow-y-auto border-r border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-900">
          {loading && <p className="text-sm text-gray-500">Loading…</p>}
          {!loading && simulations.length === 0 && <p className="text-sm text-gray-500">No multi-simulations yet.</p>}
          <div className="space-y-2">
            {simulations.map(simulation => (
              <button key={simulation.id} type="button" onClick={() => { setSelected(simulation); setShowCreate(false); }}
                className={`w-full rounded-lg border p-3 text-left ${selected?.id === simulation.id ? 'border-blue-500 bg-blue-50 dark:bg-blue-950/30' : 'border-gray-200 hover:bg-gray-50 dark:border-gray-700 dark:hover:bg-gray-800'}`}>
                <div className="font-semibold">{simulation.configuration.name}</div>
                <div className="mt-1 flex justify-between text-xs text-gray-500">
                  <span>{simulation.status.replace(/_/g, ' ')}</span>
                  <span>{simulation.completedRuns}/{simulation.totalRuns}</span>
                </div>
              </button>
            ))}
          </div>
        </aside>
        <section className="overflow-y-auto p-5 md:p-8">
          {showCreate ? (
            <CreationForm topology={topology} destinationMappings={destinationMappings} onCreated={value => {
              setSimulations(previous => [value, ...previous]);
              setSelected(value);
              setShowCreate(false);
            }} />
          ) : selected ? (
            <SimulationDetails simulation={selected} report={report} canRun={canRun} onChanged={value => {
              setSelected(value);
              setSimulations(previous => previous.map(item => item.id === value.id ? value : item));
            }} />
          ) : (
            <div className="mx-auto mt-24 max-w-lg text-center text-gray-500">
              Select a multi-simulation or create one from the current topology.
            </div>
          )}
        </section>
      </main>
    </div>
  );
};

const CreationForm = ({ topology, destinationMappings, onCreated }: {
  topology: TopologyData;
  destinationMappings: DestinationExitMapping[];
  onCreated: (value: MultiSimulationResponse) => void;
}) => {
  const [name, setName] = useState('Small analysis');
  const [durationHours, setDurationHours] = useState(1);
  const [runs, setRuns] = useState(50);
  const [rate, setRate] = useState(250);
  const [distribution, setDistribution] = useState<ArrivalDistribution>('POISSON');
  const [variation, setVariation] = useState(10);
  const [source, setSource] = useState('');
  const [seed, setSeed] = useState('');
  const [destinations, setDestinations] = useState<DestinationProbability[]>([]);
  const [destinationMode, setDestinationMode] = useState<'logical' | 'chute'>('logical');
  const [failures, setFailures] = useState<ConveyorFailureConfiguration[]>(() => conveyorFailureDefaults(topology.conveyors));
  const [includeActiveItems, setIncludeActiveItems] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [estimate, setEstimate] = useState<MultiSimulationEstimate>();
  const [estimating, setEstimating] = useState(false);

  useEffect(() => {
    if (!source && topology.locations.length) setSource(topology.locations.find(location => location.active)?.id ?? '');
  }, [source, topology.locations]);

  const probabilityTotal = useMemo(() => destinations.reduce((sum, value) => sum + value.probability, 0), [destinations]);
  const hasLogicalDestinations = destinationMappings.length > 0;
  const activeDestinationMode = hasLogicalDestinations ? destinationMode : 'chute';
  const destinationOptions = useMemo(() => {
    if (activeDestinationMode === 'logical') {
      return destinationMappings.map(mapping => [mapping.destination, mapping.destination] as const);
    }
    return topology.locations.filter(location => location.active && location.type === 'CHUTE')
      .map(location => [location.id, location.name || location.id] as const);
  }, [activeDestinationMode, destinationMappings, topology.locations]);
  const updateDestination = (destination: string, probabilityPercent: number) => {
    setDestinations(previous => {
      const without = previous.filter(value => value.destination !== destination);
      return probabilityPercent > 0 ? [...without, { destination, probability: probabilityPercent / 100 }] : without;
    });
  };
  const updateFailure = (conveyorId: string, failuresPerHour: number, repairDurationSeconds: number | null) => {
    setFailures(previous => {
      const without = previous.filter(value => value.conveyorId !== conveyorId);
      return failuresPerHour > 0 ? [...without, { conveyorId, failuresPerHour, repairDurationSeconds }] : without;
    });
  };

  const configuration = useMemo<MultiSimulationConfiguration>(() => ({
    name, simulationDurationSeconds: Math.round(durationHours * 3600), numberOfRuns: runs,
    arrival: { ratePerHour: rate, distribution, rateVariationPercent: variation },
    sourceLocationId: source, destinations, conveyorFailures: failures,
    baseSeed: seed.trim() ? Number(seed) : null, simulationStartTime: null, includeActiveItems,
  }), [name, durationHours, runs, rate, distribution, variation, source, destinations, failures, seed, includeActiveItems]);

  useEffect(() => {
    if (runs < 1 || durationHours <= 0 || rate <= 0 || !Number.isFinite(rate)) {
      setEstimate(undefined);
      setEstimating(false);
      return;
    }
    let active = true;
    setEstimating(true);
    const timer = window.setTimeout(() => {
      multiSimulationApi.estimate(configuration)
        .then(value => { if (active) setEstimate(value); })
        .catch(() => { if (active) setEstimate(undefined); })
        .finally(() => { if (active) setEstimating(false); });
    }, 350);
    return () => { active = false; window.clearTimeout(timer); };
  }, [configuration, runs, durationHours, rate]);

  const submit = async () => {
    if (Math.abs(probabilityTotal - 1) > 0.000001) {
      toast.error('Destination probabilities must total 100%'); return;
    }
    setSubmitting(true);
    try {
      const created = await multiSimulationApi.create(configuration);
      const started = await multiSimulationApi.run(created.id);
      onCreated(started);
      toast.success('Multi-simulation started');
    } catch (error) {
      toast.error(`Could not start: ${String(error)}`);
    } finally { setSubmitting(false); }
  };

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div><h1 className="text-2xl font-bold">Create multi-simulation</h1><p className="text-sm text-gray-500">The current topology, routing configuration, and source clock are captured when the experiment is created.</p></div>
      <div className="grid gap-5 rounded-xl border border-gray-200 bg-white p-6 shadow-sm dark:border-gray-800 dark:bg-gray-900 md:grid-cols-2">
        <Field label="Name"><input value={name} onChange={event => setName(event.target.value)} className="input" /></Field>
        <Field label="Source location"><select value={source} onChange={event => setSource(event.target.value)} className="input">{topology.locations.filter(value => value.active).map(value => <option key={value.id} value={value.id}>{value.name || value.id}</option>)}</select></Field>
        <Field label="Simulated duration (hours)"><NumberInput value={durationHours} min={0.01} step={0.25} onChange={setDurationHours} /></Field>
        <Field label="Runs"><NumberInput value={runs} min={1} step={1} onChange={setRuns} /></Field>
        <Field label="Arrival rate (items/hour)"><NumberInput value={rate} min={0.01} step={1} onChange={setRate} /></Field>
        <Field label="Arrival model"><select value={distribution} onChange={event => setDistribution(event.target.value as ArrivalDistribution)} className="input"><option value="POISSON">Poisson</option><option value="FIXED">Fixed</option></select></Field>
        <Field label="Run-to-run rate variation (%)"><NumberInput value={variation} min={0} step={1} onChange={setVariation} /></Field>
        <Field label="Base seed (optional)"><input value={seed} onChange={event => setSeed(event.target.value)} inputMode="numeric" className="input" placeholder="Generated automatically" /></Field>
        <label className="flex items-center gap-3 text-sm font-medium"><input type="checkbox" checked={includeActiveItems} onChange={event => setIncludeActiveItems(event.target.checked)} className="h-4 w-4" />Include current active items</label>
      </div>
      <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm dark:border-gray-800 dark:bg-gray-900">
        <div className="mb-4 flex justify-between"><h2 className="font-bold">Destination mix</h2><span className={Math.abs(probabilityTotal - 1) < 0.000001 ? 'text-green-600' : 'text-red-600'}>{(probabilityTotal * 100).toFixed(1)}%</span></div>
        {hasLogicalDestinations && <Field label="Destination type"><select value={activeDestinationMode} onChange={event => {
          setDestinationMode(event.target.value as 'logical' | 'chute');
          setDestinations([]);
        }} className="input mb-4"><option value="logical">Logical destinations</option><option value="chute">Chute IDs</option></select></Field>}
        <div className="grid gap-3 md:grid-cols-2">{destinationOptions.map(([destination, label]) => {
          const value = destinations.find(item => item.destination === destination)?.probability ?? 0;
          return <Field key={destination} label={label}><NumberInput value={value * 100} min={0} max={100} step={1} onChange={next => updateDestination(destination, next)} /></Field>;
        })}</div>
        {destinationOptions.length === 0 && <p className="text-sm text-gray-500">Add an active chute to define the mix.</p>}
      </div>
      <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm dark:border-gray-800 dark:bg-gray-900">
        <h2 className="mb-1 font-bold">Conveyor failures</h2><p className="mb-4 text-sm text-gray-500">A rate of zero disables failures. Empty repair duration leaves the conveyor stopped.</p>
        <div className="space-y-3">{topology.conveyors.map(conveyor => {
          const failure = failures.find(value => value.conveyorId === conveyor.id);
          return <div key={conveyor.id} className="grid items-end gap-3 md:grid-cols-[1fr_180px_180px]">
            <div className="pb-2 text-sm font-medium">{conveyor.name || conveyor.id}</div>
            <Field label="Failures/hour"><NumberInput value={failure?.failuresPerHour ?? 0} min={0} step={0.01} onChange={value => updateFailure(conveyor.id, value, failure?.repairDurationSeconds ?? null)} /></Field>
            <Field label="Repair seconds"><input type="number" min="1" value={failure?.repairDurationSeconds ?? ''} onChange={event => updateFailure(conveyor.id, failure?.failuresPerHour ?? 0, event.target.value ? Number(event.target.value) : null)} className="input" /></Field>
          </div>;
        })}</div>
      </div>
      <div className="rounded-xl border border-blue-200 bg-blue-50 p-5 dark:border-blue-900 dark:bg-blue-950/30" aria-live="polite">
        <h2 className="font-bold">Estimated completion time</h2>
        {estimate ? <>
          <p className="mt-2 text-2xl font-bold">About {formatDuration(estimate.estimatedSeconds)}</p>
          <p className="mt-1 text-sm text-gray-600 dark:text-gray-300">Planning range {formatDuration(estimate.lowerSeconds)}–{formatDuration(estimate.upperSeconds)}{estimating ? ' · Updating…' : ''}</p>
          <p className="mt-2 text-xs text-gray-500 dark:text-gray-400">{estimate.parallelRuns} parallel runs (backend limit {estimate.configuredParallelRuns}) · ~{estimate.expectedItemsPerRun.toLocaleString()} items/run ({estimate.expectedItemsTotal.toLocaleString()} total) · {estimate.locationCount} locations + {estimate.conveyorCount} active conveyors · {estimate.reachableExitCount} reachable exits · {estimate.maximumRouteDepth}-hop maximum route</p>
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">Calibrated from local benchmarks; recirculation, failures, other load, and machine speed can change the actual time.</p>
          {includeActiveItems && <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">Existing active items are not included in the item-count estimate.</p>}
        </> : <p className="mt-2 text-sm text-gray-500">{estimating ? 'Calculating…' : 'Estimate unavailable; check runs, duration, and arrival rate.'}</p>}
      </div>
      <button type="button" disabled={submitting || destinations.length === 0} onClick={() => void submit()} className="rounded-lg bg-blue-600 px-5 py-3 font-bold text-white hover:bg-blue-700 disabled:opacity-50">{submitting ? 'Starting…' : 'Run multi-simulation'}</button>
    </div>
  );
};

const SimulationDetails = ({ simulation, report, canRun, onChanged }: { simulation: MultiSimulationResponse; report?: MultiSimulationReport; canRun: boolean; onChanged: (value: MultiSimulationResponse) => void; }) => {
  const finished = terminalStatuses.has(simulation.status);
  const progress = simulation.totalRuns === 0 ? 0 : (simulation.completedRuns + simulation.failedRuns) * 100 / simulation.totalRuns;
  const run = async () => { try { onChanged(await multiSimulationApi.run(simulation.id)); } catch (error) { toast.error(String(error)); } };
  const cancel = async () => { try { onChanged(await multiSimulationApi.cancel(simulation.id)); } catch (error) { toast.error(String(error)); } };
  return <div className="mx-auto max-w-5xl space-y-6">
    <div className="flex flex-wrap items-start justify-between gap-4"><div><h1 className="text-2xl font-bold">{simulation.configuration.name}</h1><p className="text-sm text-gray-500">Seed {simulation.baseSeed} · topology {simulation.topologyVersion.slice(0, 12)}</p></div>{canRun && simulation.status === 'DRAFT' ? <button type="button" onClick={() => void run()} className="rounded-lg bg-blue-600 px-4 py-2 font-bold text-white hover:bg-blue-700">Run</button> : canRun && !finished ? <button type="button" onClick={() => void cancel()} className="rounded-lg border border-red-500 px-4 py-2 font-bold text-red-600">Cancel</button> : null}</div>
    <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm dark:border-gray-800 dark:bg-gray-900">
      <div className="mb-2 flex justify-between text-sm"><span>{simulation.status.replace(/_/g, ' ')}</span><span>{simulation.completedRuns} completed · {simulation.failedRuns} failed · {simulation.totalRuns} total</span></div>
      <div className="h-3 overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700"><div className="h-full bg-blue-600 transition-all" style={{ width: `${Math.min(100, progress)}%` }} /></div>
      {simulation.error && <p className="mt-3 text-sm text-red-600">{simulation.error}</p>}
    </div>
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4"><Info label="Duration" value={`${(simulation.configuration.simulationDurationSeconds / 3600).toFixed(2)} h`} /><Info label="Arrival rate" value={`${simulation.configuration.arrival.ratePerHour}/h`} /><Info label="Arrival model" value={simulation.configuration.arrival.distribution} /><Info label="Failure models" value={String(simulation.configuration.conveyorFailures.length)} /></div>
    <button className="rounded border px-3 py-2 text-sm dark:border-gray-600" onClick={() => {
      void experimentExportApi.exportExperiment(simulation.id, true).then(response => downloadDocument('experiment.flusim', response.data)).catch(() => toast.error('Could not export experiment'));
    }}>Export original experiment and results</button>
    {report && <Report report={report} />}
  </div>;
};

const Report = ({ report }: { report: MultiSimulationReport }) => <div className="space-y-4"><h2 className="text-xl font-bold">Aggregated report</h2><div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">{Object.entries(report.metrics).map(([name, value]) => <MetricCard key={name} name={name} distribution={value} />)}</div></div>;
const MetricCard = ({ name, distribution }: { name: string; distribution: MetricDistribution }) => <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm dark:border-gray-800 dark:bg-gray-900"><div className="text-sm font-semibold text-gray-500">{name.replace(/([A-Z])/g, ' $1').replace(/^./, value => value.toUpperCase())}</div><div className="mt-2 text-2xl font-bold">{distribution.sampleCount ? distribution.mean.toFixed(2) : 'Unavailable'}</div><div className="mt-2 text-xs text-gray-500">Median {distribution.median.toFixed(2)} · P5–P95 run distribution range {distribution.p5.toFixed(2)}–{distribution.p95.toFixed(2)}</div><div className="text-xs text-gray-500">Min–max {distribution.minimum.toFixed(2)}–{distribution.maximum.toFixed(2)}</div></div>;
const Info = ({ label, value }: { label: string; value: string }) => <div className="rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-900"><div className="text-xs uppercase text-gray-500">{label}</div><div className="mt-1 font-bold">{value}</div></div>;
const Field = ({ label, children }: { label: string; children: React.ReactNode }) => <label className="block"><span className="mb-1 block text-sm font-medium">{label}</span>{children}</label>;
const NumberInput = ({ value, onChange, min, max, step }: { value: number; onChange: (value: number) => void; min?: number; max?: number; step?: number }) => <input type="number" value={value} min={min} max={max} step={step} onChange={event => onChange(Number(event.target.value))} className="input" />;
