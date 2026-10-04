import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import toast from 'react-hot-toast';
import type { ConveyorPreset } from '../../api-client';
import { presetsApi } from '../../api/scenarios';
import { useAuth } from '../../context/auth.context';
import { AppHeader } from '../AppHeader';
import { AppNavigation } from '../AppNavigation';
import { ConveyorPreview } from './ConveyorPreview';
import { libraryButton as button, libraryPrimary, librarySave, libraryInput, libraryLabel, libraryPanel, libraryCheckbox } from '../scenarios/libraryStyles';


export const ConveyorTemplatesPage = () => {
  const { user } = useAuth();
  const canEdit = user?.role === 'ADMIN' || user?.role === 'SUPERADMIN';
  const [presets, setPresets] = useState<ConveyorPreset[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [busy, setBusy] = useState(false);
  const [draft, setDraft] = useState<ConveyorPreset>({ name: '', description: '', type: 'BELT', speed: 1, length: 10, minDistance: 0, mainPath: false });
  const [failureRate, setFailureRate] = useState(0);
  const [repairSeconds, setRepairSeconds] = useState<number | undefined>(60);
  const load = () => {
    setLoading(true); setError(false);
    void presetsApi.listConveyorPresets().then(response => setPresets(response.data))
      .catch(() => setError(true)).finally(() => setLoading(false));
  };
  useEffect(load, []);
  const save = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      const saved = (await presetsApi.saveConveyorPreset({ ...draft, properties: {
        failuresPerHour: failureRate, ...(repairSeconds == null ? {} : { repairDurationSeconds: repairSeconds }),
      } })).data;
      setPresets(previous => [...previous, saved]);
      setDraft(previous => ({ ...previous, name: '' }));
      toast.success('Conveyor template saved');
    } catch { toast.error('Could not save conveyor template'); }
    finally { setBusy(false); }
  };
  return <div className="h-full overflow-y-auto bg-gray-100 text-gray-900 dark:bg-[#121212] dark:text-gray-100">
    <AppHeader leftActions={<AppNavigation />} />
    <main className="mx-auto max-w-7xl space-y-6 p-4 md:p-8">
      <div className="flex flex-wrap items-end justify-between gap-4 border-b border-gray-200 pb-6 dark:border-gray-800">
        <div className="max-w-2xl space-y-2">
          <p className="text-xs font-semibold uppercase tracking-[.18em] text-blue-600 dark:text-blue-400">System setup / Component library</p>
          <h1 className="text-2xl font-bold tracking-tight md:text-3xl">Conveyor templates</h1>
          <p className="text-sm leading-relaxed text-gray-500 dark:text-gray-400">Configure once, reuse across your system. Hold Alt and drag between locations to choose a template when creating a conveyor.</p>
        </div>
        <div className="flex flex-wrap gap-2"><Link className={button} to="/scenarios">Scenario library</Link><Link className={libraryPrimary} to="/live?mode=design">Open Design System <span aria-hidden="true">↗</span></Link></div>
      </div>
      <div className="flex items-center justify-between"><h2 className="text-sm font-semibold">Available components</h2><span className="font-mono text-xs text-gray-500">{presets.length} templates</span></div>
      {loading && <p>Loading conveyor templates…</p>}
      {error && <div role="alert">Could not load conveyor templates. <button className={button} onClick={load}>Retry</button></div>}
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">{presets.map(preset => <article key={preset.id} className={`${libraryPanel} flex flex-col overflow-hidden transition-colors hover:border-blue-400 dark:hover:border-blue-500`}>
        <div className="flex items-center justify-between border-b border-gray-100 px-4 py-3 dark:border-gray-700">
          <span className="rounded bg-blue-50 px-2 py-1 text-[10px] font-bold tracking-widest text-blue-700 dark:bg-blue-950/40 dark:text-blue-300">{preset.type}</span>
          <span className="font-mono text-[10px] uppercase text-gray-400">Component / v{preset.version}</span>
        </div>
        <div className="border-b border-gray-100 bg-gray-50 px-6 py-3 dark:border-gray-700 dark:bg-gray-900/50"><ConveyorPreview type={preset.type} /></div>
        <div className="flex flex-1 flex-col gap-4 p-4">
          <div className="min-h-16"><h2 className="font-semibold">{preset.name}</h2><p className="mt-1 text-xs leading-relaxed text-gray-500 dark:text-gray-400">{preset.description}</p></div>
          <dl className="grid grid-cols-3 gap-x-3 gap-y-4">
            {[
              ['Speed', `${preset.speed} m/s`], ['Length', `${preset.length} m`], ['Spacing', `${preset.minDistance} m`],
              ['Capacity', preset.capacity ?? 'Unbounded'], ['Failures / h', preset.properties?.failuresPerHour ?? 0], ['Repair', preset.properties?.repairDurationSeconds == null ? 'Manual' : `${preset.properties.repairDurationSeconds} s`],
            ].map(([label, value]) => <div key={label}><dt className="text-[10px] font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">{label}</dt><dd className="mt-1 font-mono text-sm font-medium tabular-nums">{value}</dd></div>)}
          </dl>
          {canEdit && <button className={`${button} mt-auto w-full`} onClick={() => {
            setDraft({ ...preset, name: `${preset.name} copy` });
            setFailureRate(Number(preset.properties?.failuresPerHour ?? 0));
            setRepairSeconds(preset.properties?.repairDurationSeconds == null ? undefined : Number(preset.properties.repairDurationSeconds));
            document.getElementById('new-conveyor-template')?.scrollIntoView({ behavior: 'smooth' });
          }}>Use as starting point <span aria-hidden="true">↓</span></button>}
        </div>
      </article>)}</div>
      {canEdit && <form id="new-conveyor-template" onSubmit={event => void save(event)} className={`${libraryPanel} scroll-mt-20 overflow-hidden`}>
        <div className="border-b border-gray-200 px-6 py-4 dark:border-gray-700"><h2 className="text-lg font-bold">Create conveyor template</h2><p className="mt-1 text-sm text-gray-500 dark:text-gray-400">Set the component specification and simulation defaults.</p></div>
        <div className="space-y-6 p-6">
        <fieldset className="grid items-end gap-4 sm:grid-cols-2 lg:grid-cols-3"><legend className="mb-4 text-xs font-bold uppercase tracking-widest text-gray-500 dark:text-gray-400">01 / Component identity</legend>
          <label className={libraryLabel}>Template name<input className={`${libraryInput} mt-2 font-normal normal-case tracking-normal`} required value={draft.name} onChange={event => setDraft({ ...draft, name: event.target.value })} /></label>
          <label className={libraryLabel}>Description<input className={`${libraryInput} mt-2 font-normal normal-case tracking-normal`} value={draft.description} onChange={event => setDraft({ ...draft, description: event.target.value })} /></label>
          <label className={libraryLabel}>Conveyor type<select className={`${libraryInput} mt-2 font-normal normal-case tracking-normal`} value={draft.type} onChange={event => setDraft({ ...draft, type: event.target.value as ConveyorPreset['type'] })}>{['BELT', 'ROLLER', 'CHUTE', 'STAGING'].map(type => <option key={type}>{type}</option>)}</select></label>
        </fieldset>
        <fieldset className="grid items-end gap-4 sm:grid-cols-2 lg:grid-cols-4"><legend className="mb-4 text-xs font-bold uppercase tracking-widest text-gray-500 dark:text-gray-400">02 / Motion &amp; capacity</legend>
          <label className={libraryLabel}>Speed (m/s)<input className={`${libraryInput} mt-2 font-normal normal-case tracking-normal`} type="number" required min="0" step="any" value={draft.speed} onChange={event => setDraft({ ...draft, speed: event.target.valueAsNumber })} /></label>
          <label className={libraryLabel}>Length (m)<input className={`${libraryInput} mt-2 font-normal normal-case tracking-normal`} type="number" required min="0.001" step="any" value={draft.length} onChange={event => setDraft({ ...draft, length: event.target.valueAsNumber })} /></label>
          <label className={libraryLabel}>Minimum spacing (m)<input className={`${libraryInput} mt-2 font-normal normal-case tracking-normal`} type="number" required min="0" step="any" value={draft.minDistance} onChange={event => setDraft({ ...draft, minDistance: event.target.valueAsNumber })} /></label>
          <label className={libraryLabel}>Capacity (items; empty means unbounded)<input className={`${libraryInput} mt-2 font-normal normal-case tracking-normal`} type="number" min="1" step="1" value={draft.capacity ?? ''} onChange={event => setDraft({ ...draft, capacity: event.target.value === '' ? undefined : event.target.valueAsNumber })} /></label>
        </fieldset>
        <fieldset className="grid items-end gap-4 sm:grid-cols-2"><legend className="mb-4 text-xs font-bold uppercase tracking-widest text-gray-500 dark:text-gray-400">03 / Reliability</legend>
          <label className={libraryLabel}>Failures per hour<input className={`${libraryInput} mt-2 font-normal normal-case tracking-normal`} type="number" required min="0" step="any" value={failureRate} onChange={event => setFailureRate(event.target.valueAsNumber)} /></label>
          <label className={libraryLabel}>Repair time (seconds; empty means no automatic repair)<input className={`${libraryInput} mt-2 font-normal normal-case tracking-normal`} type="number" min="1" step="1" value={repairSeconds ?? ''} onChange={event => setRepairSeconds(event.target.value === '' ? undefined : event.target.valueAsNumber)} /></label>
        </fieldset>
        <p className="text-xs text-gray-500 dark:text-gray-400">Failure defaults prefill simulation setup. Live failures are driven by incoming events.</p>
        <label className="flex items-center gap-2 text-sm"><input className={libraryCheckbox} type="checkbox" checked={draft.mainPath} onChange={event => setDraft({ ...draft, mainPath: event.target.checked })} />Main path</label>
        </div>
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-gray-200 bg-gray-50 px-6 py-4 dark:border-gray-700 dark:bg-gray-900/40"><p className="text-xs text-gray-500 dark:text-gray-400">Saved templates are available in the conveyor editor and creation dialog.</p>
        <button className={librarySave} disabled={busy}>{busy ? 'Saving…' : 'Save conveyor template'}</button></div>
      </form>}
    </main>
  </div>;
};
