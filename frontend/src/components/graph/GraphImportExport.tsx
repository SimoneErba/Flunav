import { useRef, useState } from 'react';
import toast from 'react-hot-toast';
import { Link } from 'react-router-dom';
import type { ScenarioDocument } from '../../api-client';
import { scenariosApi, downloadDocument } from '../../api/scenarios';
import { useSimulationContext } from '../../context/simulation.context';
import { useAuth } from '../../context/auth.context';

export const GraphImportExport = ({ onImportSuccess }: { onImportSuccess?: () => void }) => {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const { activeSimulation, setActiveSimulation } = useSimulationContext();
  const { user } = useAuth();
  const canEdit = user?.role === 'ADMIN' || user?.role === 'SUPERADMIN';
  const [exportOptions, setExportOptions] = useState(false);
  const [includeItems, setIncludeItems] = useState(false);
  const [preview, setPreview] = useState<ScenarioDocument>();
  const [fileText, setFileText] = useState('');
  const [busy, setBusy] = useState(false);
  const actionClass = 'rounded border border-gray-300 px-3 py-1.5 text-sm dark:border-gray-600 disabled:opacity-40';

  const exportScenario = async () => {
    setBusy(true);
    try {
      const headers = activeSimulation?.id ? { 'X-Simulation-ID': activeSimulation.id } : undefined;
      const result = await scenariosApi.captureScenario('Scenario', '', includeItems, { headers });
      downloadDocument('scenario.flusim', result.data);
      setExportOptions(false);
    } catch { toast.error('Export failed. Pause the simulation before exporting.'); }
    finally { setBusy(false); }
  };
  const previewFile = async (file: File) => {
    setBusy(true);
    try {
      if (file.size > 10 * 1024 * 1024) throw new Error('File exceeds 10 MiB');
      const text = await file.text();
      const result = await scenariosApi.validateScenarioImport(text);
      setFileText(text);
      setPreview(result.data);
    } catch (error) { toast.error(error instanceof Error ? error.message : 'Invalid scenario file'); }
    finally { setBusy(false); }
  };
  const importFile = async () => {
    setBusy(true);
    try {
      const saved = (await scenariosApi.importScenario(fileText)).data;
      const runtime = (await scenariosApi.openScenario(saved.id!, saved.revision)).data;
      setActiveSimulation(runtime);
      setPreview(undefined);
      onImportSuccess?.();
      toast.success('Opened detached scenario');
    } catch { toast.error('Could not import and open scenario'); }
    finally { setBusy(false); }
  };
  return <div className="flex items-center gap-1 text-gray-900 dark:text-gray-100">
    <button className={actionClass} aria-label="Export graph" onClick={() => setExportOptions(true)}>Export</button>
    <button className={actionClass} aria-label="Import graph" disabled={busy || !canEdit} onClick={() => fileInputRef.current?.click()}>Import</button>
    <Link className={actionClass} to="/scenarios">Scenarios</Link>
    <input ref={fileInputRef} className="hidden" type="file" accept=".flusim,application/json" onChange={event => {
      const file = event.target.files?.[0]; event.target.value = ''; if (file) void previewFile(file);
    }} />
    {(exportOptions || preview) && <div className="fixed inset-0 z-[4000] flex items-center justify-center bg-black/40 p-4">
      <div role="dialog" aria-modal="true" aria-label={preview ? 'Import preview' : 'Export scenario'} className="w-full max-w-md space-y-4 rounded-xl bg-white p-6 shadow-xl dark:bg-gray-900">
        <h2 className="text-lg font-semibold">{preview ? `Import ${preview.name}` : 'Export scenario'}</h2>
        <p className="text-sm">This file starts a fresh run from the saved baseline. Scheduled queues and live subscriptions are not included.</p>
        {preview ? <><p>{preview.baseline?.locations?.length} locations · {preview.baseline?.conveyors?.length} conveyors · {preview.baseline?.items?.length} initial items</p>
          <p className="text-sm">{preview.description}</p><button className={actionClass} disabled={busy} onClick={() => void importFile()}>Import and open isolated copy</button></>
          : <><label className="flex gap-2"><input type="checkbox" checked={includeItems} onChange={event => setIncludeItems(event.target.checked)} />Include initial items</label>
          <button className={actionClass} disabled={busy} onClick={() => void exportScenario()}>Download .flusim</button></>}
        <button className={actionClass} onClick={() => { setExportOptions(false); setPreview(undefined); }}>Cancel</button>
      </div>
    </div>}
  </div>;
};
