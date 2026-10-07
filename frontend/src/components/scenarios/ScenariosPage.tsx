import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import toast from 'react-hot-toast';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { SavedScenario, ScenarioDocument, SimulationComparison, ComparisonReport, MultiSimulationResponse } from '../../api-client';
import { scenariosApi, templatesApi, comparisonsApi, downloadDocument } from '../../api/scenarios';
import { useSimulationContext } from '../../context/simulation.context';
import { useAuth } from '../../context/auth.context';
import { conveyorFailureDefaults } from '../conveyors/presets';
import { AppHeader } from '../AppHeader';
import { AppNavigation } from '../AppNavigation';

import { libraryButton as button, libraryPrimary, librarySave, libraryInput as input, libraryLabel, libraryPanel, libraryCheckbox } from './libraryStyles';
const metricLabels: Record<string, string> = {
  throughputPerHour: 'Throughput (items/hour)', averageJourneyTimeSeconds: 'Mean journey (s)', p95JourneyTimeSeconds: 'p95 journey (s)',
  itemsCompleted: 'Completed items', itemsRemaining: 'Remaining items', maximumSystemPopulation: 'Maximum population (items)',
  recirculationRatePercent: 'Recirculation (passes/100 completions)', recirculationCount: 'Recirculation passes', conveyorFailureCount: 'Conveyor failures',
};
const metricLabel = (name: string) => metricLabels[name] ?? name.replace(/^conveyor\./, '').replace(/\.downtimePercent$/, ' downtime (%)');

export const ScenariosPage = () => {
  const navigate = useNavigate();
  const { activeSimulation, setActiveSimulation } = useSimulationContext();
  const { user } = useAuth();
  const canEdit = user?.role === 'ADMIN' || user?.role === 'SUPERADMIN';
  const [saved, setSaved] = useState<SavedScenario[]>([]);
  const [templates, setTemplates] = useState<ScenarioDocument[]>([]);
  const [studies, setStudies] = useState<SimulationComparison[]>([]);
  const [selectedId, setSelectedId] = useState(localStorage.getItem('flunav_scenario_id') ?? '');
  const [name, setName] = useState('My scenario');
  const [includeItems, setIncludeItems] = useState(false);
  const [referenceId, setReferenceId] = useState('');
  const [alternativeId, setAlternativeId] = useState('');
  const [studyName, setStudyName] = useState('Comparison');
  const [mappingText, setMappingText] = useState('{}');
  const [duration, setDuration] = useState(300);
  const [runs, setRuns] = useState(5);
  const [seed, setSeed] = useState(42);
  const [sweepConveyor, setSweepConveyor] = useState('');
  const [sweepParameter, setSweepParameter] = useState<'speed' | 'capacity' | 'minDistance'>('speed');
  const [sweepValues, setSweepValues] = useState('');
  const [study, setStudy] = useState<SimulationComparison>();
  const [report, setReport] = useState<ComparisonReport>();
  const [progress, setProgress] = useState<MultiSimulationResponse[]>([]);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    const [scenarios, builtins, comparisons] = await Promise.all([scenariosApi.listScenarios(), templatesApi.listSimulationTemplates(), comparisonsApi.listSimulationComparisons()]);
    setSaved(scenarios.data); setTemplates(builtins.data); setStudies(comparisons.data);
  }, []);
  useEffect(() => { void refresh().catch(() => toast.error('Could not load scenario library')); }, [refresh]);
  useEffect(() => {
    if (!study?.id) return;
    let disposed = false;
    const update = async () => {
      try {
        const [results, statuses] = await Promise.all([comparisonsApi.getComparisonReport(study.id!), comparisonsApi.getComparisonProgress(study.id!)]);
        if (!disposed) { setReport(results.data); setProgress(statuses.data); }
      } catch { if (!disposed) toast.error('Could not refresh comparison'); }
    };
    void update();
    const interval = window.setInterval((): void => { void update(); }, 2000);
    return () => { disposed = true; window.clearInterval(interval); };
  }, [study?.id]);

  const action = async (task: () => Promise<void>) => {
    setBusy(true);
    try { await task(); await refresh(); }
    catch (error) { toast.error(error instanceof Error ? error.message : 'Action failed'); }
    finally { setBusy(false); }
  };
  const remember = (value: SavedScenario) => {
    setSelectedId(value.id!); localStorage.setItem('flunav_scenario_id', value.id!); setName(value.name ?? 'Scenario');
  };
  const open = async (value: SavedScenario) => {
    const runtime = (await scenariosApi.openScenario(value.id!, value.revision)).data;
    remember(value); setActiveSimulation(runtime); navigate('/live');
  };
  const capture = async () => {
    const current = saved.find(value => value.id === selectedId);
    const headers = activeSimulation?.id ? { 'X-Simulation-ID': activeSimulation.id } : undefined;
    const document = (await scenariosApi.captureScenario(name, current?.description ?? '', includeItems, { headers })).data;
    if (current?.document?.experiment) document.experiment = { ...current.document.experiment, simulationStartTime: document.baseline?.timestamp, includeActiveItems: includeItems };
    const result = selectedId ? await scenariosApi.saveScenarioRevision(selectedId, document) : await scenariosApi.saveScenario(document);
    remember(result.data); toast.success('Saved immutable scenario revision');
  };
  const createStudy = async () => {
    const reference = saved.find(value => value.id === referenceId)?.document;
    if (!reference?.experiment) throw new Error('Choose a scenario with experiment inputs, such as a teaching template');
    const configuration = { ...reference.experiment, simulationDurationSeconds: duration, numberOfRuns: runs, baseSeed: seed, inputGeneratorVersion: '2', includeActiveItems: false };
    const mapping: unknown = JSON.parse(mappingText);
    if (!mapping || typeof mapping !== 'object' || Array.isArray(mapping) || Object.values(mapping).some(value => typeof value !== 'string')) throw new Error('Mapping must contain reference ids mapped to alternative ids');
    const referenceMapping = mapping as Record<string, string>;
    const prepare = (source: ScenarioDocument, label: string, mapped = false) => {
      const copy = structuredClone(source);
      copy.generatorVersion = '2'; copy.experiment = { ...configuration, name: label };
      if (mapped) {
        copy.experiment.sourceLocationId = referenceMapping[configuration.sourceLocationId!] ?? configuration.sourceLocationId;
        copy.experiment.destinations = configuration.destinations?.map(destination => ({ ...destination, destination: referenceMapping[destination.destination!] ?? destination.destination }));
      }
      if (mapped) copy.experiment.conveyorFailures = copy.experiment.conveyorFailures?.map(failure => ({ ...failure, conveyorId: referenceMapping[failure.conveyorId!] ?? failure.conveyorId }));
      const existingFailures = new Set(copy.experiment.conveyorFailures?.map(failure => failure.conveyorId));
      copy.experiment.conveyorFailures = [...(copy.experiment.conveyorFailures ?? []), ...conveyorFailureDefaults(copy.baseline?.conveyors ?? []).filter(failure => !existingFailures.has(failure.conveyorId))];
      if (copy.baseline) { copy.baseline.timestamp = reference.baseline?.timestamp; copy.baseline.items = []; }
      return { name: label, scenario: copy, referenceMapping: mapped ? referenceMapping : {} };
    };
    const alternatives = [prepare(reference, 'Reference')];
    if (sweepValues.trim()) {
      const values = sweepValues.split(',').map(value => Number(value.trim()));
      if (!sweepConveyor || values.some(value => !Number.isFinite(value))) throw new Error('Choose a conveyor and finite sweep values');
      for (const value of values) {
        const variant = prepare(reference, `${sweepParameter} ${value}`);
        const conveyor = variant.scenario.baseline?.conveyors?.find(edge => edge.id === sweepConveyor);
        if (!conveyor) throw new Error('Unknown conveyor');
        conveyor[sweepParameter] = value;
        alternatives.push(variant);
      }
    } else {
      const alternative = saved.find(value => value.id === alternativeId);
      if (!alternative?.document) throw new Error('Choose an alternative or enter sweep values');
      alternatives.push(prepare(alternative.document, alternative.name ?? 'Alternative', true));
    }
    const created = (await comparisonsApi.createSimulationComparison({ name: studyName, alternatives })).data;
    setStudy(created);
    toast.success('Comparison saved with frozen baselines');
  };
  const chart = report?.alternatives?.map(alternative => {
    const completed = alternative.runs?.filter(run => run.status === 'COMPLETED' && run.metrics) ?? [];
    return { name: alternative.name, throughput: completed.length ? completed.reduce((sum, run) => sum + (run.metrics?.throughputPerHour ?? 0), 0) / completed.length : undefined };
  }) ?? [];

  return <div className="h-full overflow-y-auto bg-gray-100 text-gray-900 dark:bg-[#121212] dark:text-gray-100">
    <AppHeader leftActions={<AppNavigation />} />
    <main className="mx-auto max-w-7xl space-y-8 p-4 md:p-8">
      <section className="space-y-5">
        <div className="flex flex-wrap items-end justify-between gap-4 border-b border-gray-200 pb-6 dark:border-gray-800"><div className="space-y-2">
          <p className="text-xs font-semibold uppercase tracking-[.18em] text-blue-600 dark:text-blue-400">System setup / Scenario workspace</p>
          <h1 className="text-2xl font-bold tracking-tight md:text-3xl">Scenario library</h1>
        </div><Link className={button} to="/conveyor-templates">Conveyor templates <span aria-hidden="true">↗</span></Link></div>
        <p className="text-sm text-gray-500">Saved revisions reopen as detached projects. Exports start fresh runs; queues and live subscriptions are not saved.</p>
        <div className={`${libraryPanel} flex flex-wrap items-end gap-4 p-5`}>
          <label className={`${libraryLabel} min-w-0 flex-1 sm:min-w-64`}>Scenario name<input aria-label="Scenario name" className={`${input} mt-2 font-normal normal-case tracking-normal`} value={name} onChange={event => setName(event.target.value)} /></label>
          <label className="flex min-h-10 items-center gap-2 text-sm"><input className={libraryCheckbox} type="checkbox" checked={includeItems} onChange={event => setIncludeItems(event.target.checked)} /> Include initial items</label>
          <button className={librarySave} disabled={busy || !canEdit} onClick={() => void action(capture)}>Save {selectedId ? 'new revision' : 'current model'}</button>
          <button className={button} onClick={() => { setSelectedId(''); localStorage.removeItem('flunav_scenario_id'); }}>Save as new project</button>
        </div>
        <div className="grid gap-3 md:grid-cols-3">{saved.map(value => <article key={value.id} className={`${libraryPanel} space-y-3 border-t-2 border-t-blue-500 p-5`}>
          <h2 className="font-semibold">{value.name} · revision {value.revision}</h2><p className="text-sm">{value.description}</p>
          <div className="flex flex-wrap gap-2">
            <button className={libraryPrimary} disabled={busy || !canEdit} onClick={() => void action(() => open(value))}>Open and edit</button>
            <button className={button} disabled={busy || !canEdit} onClick={() => void action(async () => { remember((await scenariosApi.duplicateScenario(value.id!, value.revision)).data); })}>Duplicate</button>
            <button className={button} disabled={busy || !canEdit} onClick={() => void action(async () => { await scenariosApi.renameScenario(value.id!, { name, description: value.description }); })}>Rename to entered name</button>
            <button className={button} disabled={busy || !canEdit} onClick={() => void action(async () => { await scenariosApi.saveScenarioAsTemplate(value.id!, value.revision); })}>Save reusable template</button>
            <button className={button} onClick={() => downloadDocument('scenario.flusim', value.document)}>Export</button>
          </div>
        </article>)}</div>
      </section>
      <section className="space-y-3"><h2 className="text-lg font-bold tracking-tight">Teaching templates</h2>
        <p className="text-sm text-gray-500">These templates contain complete layouts. <Link className="text-blue-600 underline dark:text-blue-400" to="/conveyor-templates">Browse individual conveyor templates</Link> for belts, rollers and other components.</p>
        <div className="grid gap-4 md:grid-cols-2">{templates.map(template => <article key={template.provenance?.templateId} className={`${libraryPanel} space-y-4 p-5`}>
          <h3 className="font-semibold">{template.name} · v{template.provenance?.templateVersion}</h3>
          <svg aria-label={`${template.name} preview`} viewBox="-1 -4 12 9" className="h-36 w-full rounded border border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-900">
            {template.baseline?.conveyors?.map(edge => {
              const source = template.baseline?.locations?.find(node => node.id === edge.sourceId);
              const target = template.baseline?.locations?.find(node => node.id === edge.targetId);
              return <line key={edge.id} x1={source?.latitude} y1={source?.longitude} x2={target?.latitude} y2={target?.longitude} stroke="#3b82f6" strokeWidth="0.12" strokeLinecap="round" />;
            })}
            {template.baseline?.locations?.map(node => <circle key={node.id} cx={node.latitude} cy={node.longitude} r=".2" fill={node.type === 'CHUTE' ? '#22c55e' : '#3b82f6'} />)}
          </svg>
          <p className="text-sm">{template.description}</p>
          <p className="font-mono text-xs text-gray-500 dark:text-gray-400">{template.baseline?.conveyors?.map(edge => `${edge.id}: ${edge.length} m, ${edge.speed} m/s`).join(' · ')}</p>
          <button className={libraryPrimary} disabled={busy || !canEdit} onClick={() => void action(async () => {
            const result = (await templatesApi.instantiateSimulationTemplate(template.provenance!.templateId!)).data;
            remember(result.scenario!); setActiveSimulation(result.runtime!); navigate('/live');
          })}>Instantiate isolated project</button>
        </article>)}</div>
      </section>
      <section className={`${libraryPanel} space-y-5 p-5 md:p-6`}><div className="border-b border-gray-200 pb-4 dark:border-gray-700"><h2 className="text-lg font-bold tracking-tight">Controlled comparisons</h2><p className="mt-1 text-sm text-gray-500 dark:text-gray-400">Compare layouts or sweep conveyor settings under the same experiment conditions.</p></div>
        <div className="grid items-end gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <label className={`${libraryLabel}`}>Comparison name<input aria-label="Comparison name" className={`${input} mt-2 font-normal normal-case tracking-normal`} value={studyName} onChange={event => setStudyName(event.target.value)} /></label>
          <label className={libraryLabel}>Reference scenario<select aria-label="Reference scenario" className={`${input} mt-2 font-normal normal-case tracking-normal`} value={referenceId} onChange={event => setReferenceId(event.target.value)}><option value="">Reference scenario</option>{saved.map(value => <option key={value.id} value={value.id}>{value.name} r{value.revision}</option>)}</select></label>
          <label className={libraryLabel}>Alternative scenario<select aria-label="Alternative scenario" className={`${input} mt-2 font-normal normal-case tracking-normal`} value={alternativeId} onChange={event => setAlternativeId(event.target.value)}><option value="">Alternative scenario</option>{saved.map(value => <option key={value.id} value={value.id}>{value.name}</option>)}</select></label>
          <label className={libraryLabel}>Duration (s) <input aria-label="Duration seconds" className={`${input} mt-2 font-normal normal-case tracking-normal`} type="number" value={duration} min="1" onChange={event => setDuration(Number(event.target.value))} /></label>
          <label className={libraryLabel}>Replications <input aria-label="Replications" className={`${input} mt-2 font-normal normal-case tracking-normal`} type="number" value={runs} min="1" onChange={event => setRuns(Number(event.target.value))} /></label>
          <label className={libraryLabel}>Seed <input aria-label="Base seed" className={`${input} mt-2 font-normal normal-case tracking-normal`} type="number" value={seed} onChange={event => setSeed(Number(event.target.value))} /></label>
        </div>
        <label className={libraryLabel}>Source / destination mapping
          <input aria-label="Reference mapping JSON" className={`${input} mt-2 font-mono font-normal normal-case tracking-normal`} value={mappingText} onChange={event => setMappingText(event.target.value)} />
        </label>
        <div className="grid items-end gap-4 rounded border border-gray-200 bg-gray-50 p-4 dark:border-gray-700 dark:bg-gray-900/40 sm:grid-cols-2 lg:grid-cols-4">
          <label className={libraryLabel}>Sweep conveyor<select aria-label="Sweep conveyor" className={`${input} mt-2 font-normal normal-case tracking-normal`} value={sweepConveyor} onChange={event => setSweepConveyor(event.target.value)}><option value="">Optional parameter sweep</option>{saved.find(value => value.id === referenceId)?.document?.baseline?.conveyors?.map(edge => <option key={edge.id} value={edge.id}>{edge.name ?? edge.id}</option>)}</select></label>
          <label className={libraryLabel}>Sweep parameter<select aria-label="Sweep parameter" className={`${input} mt-2 font-normal normal-case tracking-normal`} value={sweepParameter} onChange={event => setSweepParameter(event.target.value as typeof sweepParameter)}><option value="speed">Speed (m/s)</option><option value="capacity">Capacity (items)</option><option value="minDistance">Spacing (m)</option></select></label>
          <label className={`${libraryLabel}`}>Sweep values<input aria-label="Sweep values" className={`${input} mt-2 font-normal normal-case tracking-normal`} placeholder="Explicit values: 0.5, 1, 2" value={sweepValues} onChange={event => setSweepValues(event.target.value)} /></label>
          <button className={`${libraryPrimary} min-h-10`} disabled={busy || !canEdit} onClick={() => void action(createStudy)}>Create comparison</button>
        </div>
        <label className={libraryLabel}>Import comparison JSON
          <input className="mt-2 block w-full text-sm font-normal normal-case tracking-normal text-gray-500 file:mr-3 file:rounded file:border-0 file:bg-gray-100 file:px-3 file:py-2 file:text-sm file:font-semibold file:text-gray-700 dark:file:bg-gray-700 dark:file:text-gray-200" type="file" accept=".json,application/json" disabled={busy || !canEdit} onChange={event => {
            const file = event.target.files?.[0]; event.target.value = '';
            if (file) void action(async () => {
              if (file.size > 10 * 1024 * 1024) throw new Error('Comparison file exceeds 10 MiB');
              const payload: unknown = JSON.parse(await file.text());
              if (!payload || typeof payload !== 'object' || !('definition' in payload)) throw new Error('Invalid comparison bundle');
              setStudy((await comparisonsApi.importSimulationComparison(payload as import('../../api-client').Bundle)).data);
            });
          }} />
        </label>
        <div className="flex flex-wrap gap-2">{studies.map(value => <button key={value.id} className={button} onClick={() => setStudy(value)}>{value.name}</button>)}</div>
        {study && <div className="space-y-4 rounded-lg border border-gray-200 bg-gray-50 p-5 dark:border-gray-700 dark:bg-gray-900/40">
          <h3 className="font-semibold">{study.name}</h3>
          <div className="flex flex-wrap gap-2">
            <button className={button} disabled={busy || !canEdit || study.frozen} onClick={() => void action(async () => { setStudy((await comparisonsApi.runSimulationComparison(study.id!)).data); })}>Run comparison</button>
            <button className={button} disabled={busy || !canEdit} onClick={() => void action(async () => { await comparisonsApi.cancelSimulationComparison(study.id!); })}>Cancel comparison</button>
            <button className={button} onClick={() => void action(async () => { downloadDocument('comparison.json', (await comparisonsApi.exportSimulationComparison(study.id!, 'json')).data); })}>Download JSON</button>
            <button className={button} onClick={() => void action(async () => { downloadDocument('comparison.csv', (await comparisonsApi.exportSimulationComparison(study.id!, 'csv')).data); })}>Download CSV</button>
            <button className={button} onClick={() => void action(async () => { downloadDocument('comparison-summary.csv', (await comparisonsApi.exportComparisonSummary(study.id!)).data); })}>Download summary CSV</button>
            <button className={button} disabled={busy || !canEdit} onClick={() => void action(async () => { setStudy((await comparisonsApi.rerunSimulationComparison(study.id!)).data); })}>New run from frozen definition</button>
          </div>
          {progress.map(value => <p key={value.id} className="text-sm">{value.configuration?.name}: {value.status} · completed {value.completedRuns}/{value.totalRuns} · failed {value.failedRuns}</p>)}
          <p className="text-xs text-gray-500">95% intervals use paired completed runs. Journey times describe completed items; unfinished items remain visible. P5–P95 is a run distribution range. Recirculation is passes per 100 completions and may exceed 100.</p>
          <div className="h-56"><ResponsiveContainer width="100%" height="100%"><BarChart data={chart}><CartesianGrid strokeDasharray="3 3" /><XAxis dataKey="name" /><YAxis /><Tooltip /><Bar dataKey="throughput" name="Throughput (items/hour)" fill="#3b82f6" /></BarChart></ResponsiveContainer></div>
          {report?.alternatives?.map(alternative => <div key={alternative.experimentId} className="overflow-x-auto">
            <h4 className="font-semibold">{alternative.name}</h4>
            <table className="w-full text-left text-sm [&_th]:bg-gray-100 [&_th]:p-3 [&_th]:text-xs [&_th]:font-semibold [&_th]:uppercase [&_th]:text-gray-500 [&_td]:border-b [&_td]:border-gray-200 [&_td]:p-3 [&_td]:tabular-nums dark:[&_th]:bg-gray-800 dark:[&_td]:border-gray-700"><thead><tr><th>Metric</th><th>Difference</th><th>95% paired interval</th><th>Pairs</th><th>Excluded</th></tr></thead><tbody>{Object.entries(alternative.differences ?? {}).map(([metric, difference]) => <tr key={metric}><td>{metricLabel(metric)}</td><td>{difference.meanDifference?.toFixed(3) ?? 'Unavailable'}</td><td>{difference.lower95 == null ? 'Unavailable' : `${difference.lower95.toFixed(3)} – ${difference.upper95?.toFixed(3)}`}</td><td>{difference.pairs}</td><td>{difference.excludedRuns}</td></tr>)}</tbody></table>
            <table className="mt-3 w-full text-left text-sm [&_th]:bg-gray-100 [&_th]:p-3 [&_th]:text-xs [&_th]:uppercase [&_th]:text-gray-500 [&_td]:border-b [&_td]:border-gray-200 [&_td]:p-3 [&_td]:tabular-nums dark:[&_th]:bg-gray-800 dark:[&_td]:border-gray-700"><thead><tr><th>Run</th><th>Status</th><th>Completed</th><th>Remaining</th><th>Mean journey (s)</th><th>p95 (s)</th><th>Downtime (%)</th></tr></thead><tbody>{alternative.runs?.map(run => <tr key={run.runIndex}><td>{run.runIndex}</td><td>{run.status}</td><td>{run.metrics?.itemsCompleted ?? '—'}</td><td>{run.metrics?.itemsRemaining ?? '—'}</td><td>{run.metrics?.itemsCompleted ? run.metrics.averageJourneyTimeSeconds?.toFixed(3) : 'Unavailable'}</td><td>{run.metrics?.itemsCompleted ? run.metrics.p95JourneyTimeSeconds?.toFixed(3) : 'Unavailable'}</td><td>{Object.entries(run.metrics?.conveyorDowntimePercent ?? {}).map(([conveyor, percent]) => `${conveyor}: ${percent.toFixed(2)}`).join(' · ') || '—'}</td></tr>)}</tbody></table>
          </div>)}
        </div>}
      </section>
    </main>
  </div>;
};
