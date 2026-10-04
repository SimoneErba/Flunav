import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import toast from 'react-hot-toast';
import type { ConveyorPreset } from '../../api-client';
import { presetsApi } from '../../api/scenarios';
import { ConveyorPreview } from './ConveyorPreview';
import { libraryInput, libraryLabel, libraryButton, libraryPrimary } from '../scenarios/libraryStyles';
import { presetSummary } from './presets';

export const ConveyorCreationDialog = ({ onCreate, onClose }: {
  onCreate: (preset?: ConveyorPreset) => Promise<void>;
  onClose: () => void;
}) => {
  const dialog = useRef<HTMLDialogElement>(null);
  const [presets, setPresets] = useState<ConveyorPreset[]>([]);
  const [selected, setSelected] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    dialog.current?.showModal();
    let active = true;
    void presetsApi.listConveyorPresets().then(response => { if (active) setPresets(response.data); })
      .catch(() => { if (active) setError(true); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);
  const create = async () => {
    setBusy(true);
    try { await onCreate(presets.find(preset => preset.id === selected)); }
    catch { toast.error('Failed to create conveyor'); }
    finally { setBusy(false); }
  };
  const preset = presets.find(value => value.id === selected);
  return <dialog ref={dialog} aria-labelledby="create-conveyor-title" onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}
    className="fixed inset-0 m-auto max-h-[90dvh] w-[calc(100%_-_2rem)] max-w-lg space-y-4 overflow-y-auto rounded-lg border border-gray-200 bg-white p-6 text-gray-900 shadow-xl backdrop:bg-black/40 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100">
    <p className="text-[10px] font-semibold uppercase tracking-[.18em] text-blue-600 dark:text-blue-400">System setup / New connection</p>
    <h2 id="create-conveyor-title" className="text-xl font-bold tracking-tight">Create conveyor</h2>
    <p className="text-sm text-gray-500">Choose an optional template for this connection.</p>
    <label className={libraryLabel}>Conveyor template
      <select autoFocus className={`${libraryInput} mt-2 font-normal normal-case tracking-normal`} value={selected} disabled={busy} onChange={event => setSelected(event.target.value)}>
        <option value="">Default values</option>
        {presets.map(value => <option key={value.id} value={value.id}>{value.name} · {value.speed} m/s</option>)}
      </select>
    </label>
    {loading && <p className="text-sm">Loading templates…</p>}
    {error && <p role="alert" className="text-sm">Templates could not be loaded. You can still create a conveyor with default values.</p>}
    <div className="space-y-3 rounded border border-gray-200 bg-gray-50 p-4 dark:border-gray-700 dark:bg-gray-900/50"><ConveyorPreview type={preset?.type ?? 'BELT'} /><p className="font-mono text-xs leading-relaxed text-gray-600 dark:text-gray-300">{preset ? presetSummary(preset) : 'BELT · 1 m/s · 10 m · unbounded capacity'}</p></div>
    <Link aria-disabled={busy} className="text-sm text-blue-600 underline dark:text-blue-400" to="/conveyor-templates" onClick={event => { if (busy) event.preventDefault(); else onClose(); }}>Browse or create conveyor templates</Link>
    <div className="flex justify-end gap-2 border-t border-gray-200 pt-4 dark:border-gray-700">
      <button className={libraryButton} disabled={busy} onClick={onClose}>Cancel</button>
      <button className={libraryPrimary} disabled={busy} onClick={() => void create()}>{busy ? 'Creating…' : 'Create conveyor'}</button>
    </div>
  </dialog>;
};
