import { useCallback, useEffect, useRef, useState } from "react";
import type { AxiosError } from "axios";
import toast from "react-hot-toast";

import type { SensorMappingRecord } from "../../api-client";
import { useApi } from "../../hooks/useApi";

type SensorRow = SensorMappingRecord & { localId: string };

const inputClass = "w-full rounded border border-gray-300 bg-gray-50 p-2 outline-none focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-900";
const localId = () => `sensor_${crypto.randomUUID?.() || Date.now()}`;

const parseCsv = (text: string): string[][] => {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = "";
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    const next = text[index + 1];
    if (character === '"' && quoted && next === '"') {
      cell += '"';
      index += 1;
    } else if (character === '"') {
      quoted = !quoted;
    } else if (character === "," && !quoted) {
      row.push(cell);
      cell = "";
    } else if ((character === "\n" || character === "\r") && !quoted) {
      if (character === "\r" && next === "\n") index += 1;
      row.push(cell);
      if (row.some(value => value.trim())) rows.push(row);
      row = [];
      cell = "";
    } else {
      cell += character;
    }
  }
  row.push(cell);
  if (row.some(value => value.trim())) rows.push(row);
  return rows;
};

const csvCell = (value: unknown) => `"${String(value ?? "").replace(/"/g, '""')}"`;

const downloadCsv = (rows: unknown[][]) => {
  const blob = new Blob([rows.map(row => row.map(csvCell).join(",")).join("\n")], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = "sensor-mappings.csv";
  anchor.click();
  URL.revokeObjectURL(url);
};

export const SensorMappingManagement = () => {
  const { sensorMappingApi, conveyorsApi } = useApi();
  const fileRef = useRef<HTMLInputElement>(null);
  const [rows, setRows] = useState<SensorRow[]>([]);
  const [conveyorIds, setConveyorIds] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [sensorDraft, setSensorDraft] = useState<SensorRow | null>(null);

  const loadMappings = useCallback(async () => {
    setLoading(true);
    try {
      const [mappingResponse, conveyorResponse] = await Promise.all([
        sensorMappingApi.getSensorMappings(),
        conveyorsApi.getAllConveyors(),
      ]);
      setRows(mappingResponse.data.map(mapping => ({ ...mapping, localId: localId() })));
      setConveyorIds(conveyorResponse.data.map(conveyor => conveyor.id).filter((id): id is string => Boolean(id)));
    } catch (error) {
      console.error("Failed to load sensor mappings", error);
      toast.error("Failed to load sensor mappings");
    } finally {
      setLoading(false);
    }
  }, [conveyorsApi, sensorMappingApi]);

  useEffect(() => {
    void loadMappings();
  }, [loadMappings]);

  const save = async () => {
    const payload = rows.map(row => ({
      sensorName: row.sensorName?.trim(),
      conveyorId: row.conveyorId?.trim(),
      progress: Number(row.progress),
    }));
    setSaving(true);
    try {
      await sensorMappingApi.updateSensorMappings(payload);
      toast.success("Sensor mappings saved");
      await loadMappings();
    } catch (error) {
      const axiosError = error as AxiosError<{ message?: string }>;
      toast.error(axiosError.response?.data?.message || (error as Error).message || "Failed to save sensor mappings");
    } finally {
      setSaving(false);
    }
  };

  const importCsv = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => {
      const [header, ...csvRows] = parseCsv(String(reader.result || ""));
      const columns = header?.map(column => column.trim()) ?? [];
      if (columns.length !== 3 || columns[0] !== "sensorName" || columns[1] !== "conveyorId" || columns[2] !== "progress") {
        toast.error("CSV header must be sensorName,conveyorId,progress");
        return;
      }
      setRows(csvRows.map(cells => ({
        localId: localId(),
        sensorName: cells[0]?.trim(),
        conveyorId: cells[1]?.trim(),
        progress: Number(cells[2]),
      })));
      toast.success("Sensor CSV imported. Review and save to persist.");
    };
    reader.readAsText(file);
  };

  const addSensor = () => {
    if (!sensorDraft) return;
    const sensorName = sensorDraft.sensorName?.trim();
    const conveyorId = sensorDraft.conveyorId?.trim();
    const progress = Number(sensorDraft.progress);
    if (!sensorName || !conveyorId || !Number.isFinite(progress) || progress < 0 || progress > 100) {
      toast.error("Enter a sensor name, conveyor ID, and progress from 0 to 100.");
      return;
    }
    setRows(current => [...current, { ...sensorDraft, sensorName, conveyorId, progress }]);
    setSensorDraft(null);
  };

  return (
    <section className="bg-white p-6 rounded-lg shadow border border-gray-200 dark:border-gray-700 dark:bg-gray-800">
      <div className="mb-4 flex flex-col gap-4 border-b border-gray-200 pb-4 dark:border-gray-700 md:flex-row md:items-center md:justify-between">
        <div>
          <h2 className="text-lg font-bold">Sensors</h2>
          <p className="text-sm text-gray-500 dark:text-gray-400">Map an external sensor to a conveyor position.</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <button onClick={() => setSensorDraft({ localId: localId(), sensorName: "", conveyorId: "", progress: 0 })} className="rounded bg-blue-600 px-3 py-2 text-sm font-semibold text-white hover:bg-blue-700">Add Sensor</button>
          <button onClick={() => void loadMappings()} disabled={loading} className="rounded bg-gray-100 px-3 py-2 text-sm font-semibold disabled:opacity-50 dark:bg-gray-700">Reload</button>
          <button onClick={() => fileRef.current?.click()} disabled={loading} className="rounded bg-gray-100 px-3 py-2 text-sm font-semibold disabled:opacity-50 dark:bg-gray-700">Import CSV</button>
          <button onClick={() => downloadCsv([["sensorName", "conveyorId", "progress"], ...rows.map(row => [row.sensorName, row.conveyorId, row.progress])])} disabled={loading} className="rounded bg-gray-100 px-3 py-2 text-sm font-semibold disabled:opacity-50 dark:bg-gray-700">Export CSV</button>
          <button onClick={() => void save()} disabled={saving} className="rounded bg-green-600 px-3 py-2 text-sm font-semibold text-white disabled:opacity-50 hover:bg-green-700">{saving ? "Saving..." : "Save"}</button>
          <input ref={fileRef} type="file" accept=".csv,text/csv" className="hidden" onChange={event => {
            const file = event.target.files?.[0];
            if (file) importCsv(file);
            event.target.value = "";
          }} />
        </div>
      </div>

      {sensorDraft && <AddSensorModal
        draft={sensorDraft}
        conveyorIds={conveyorIds}
        onChange={setSensorDraft}
        onCancel={() => setSensorDraft(null)}
        onSave={addSensor}
      />}

      <datalist id="sensor-conveyors">{conveyorIds.map(id => <option key={id} value={id} />)}</datalist>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[700px] text-sm">
          <thead className="bg-gray-50 text-left text-xs uppercase text-gray-500 dark:bg-gray-700/50">
            <tr><th className="p-3">Sensor name</th><th className="p-3">Conveyor ID</th><th className="p-3">Progress (%)</th><th className="p-3">Actions</th></tr>
          </thead>
          <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
            {rows.map(row => <tr key={row.localId}>
              <td className="p-3"><input value={row.sensorName || ""} onChange={event => setRows(current => current.map(item => item.localId === row.localId ? { ...item, sensorName: event.target.value } : item))} className={inputClass} /></td>
              <td className="p-3"><input list="sensor-conveyors" value={row.conveyorId || ""} onChange={event => setRows(current => current.map(item => item.localId === row.localId ? { ...item, conveyorId: event.target.value } : item))} className={inputClass} /></td>
              <td className="p-3"><input type="number" min="0" max="100" step="any" value={row.progress ?? ""} onChange={event => setRows(current => current.map(item => item.localId === row.localId ? { ...item, progress: Number(event.target.value) } : item))} className={inputClass} /></td>
              <td className="p-3"><button onClick={() => setRows(current => current.filter(item => item.localId !== row.localId))} className="rounded px-2 py-1 text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20">Delete</button></td>
            </tr>)}
            {!rows.length && <tr><td colSpan={4} className="p-8 text-center italic text-gray-500">{loading ? "Loading sensors..." : "No sensors configured."}</td></tr>}
          </tbody>
        </table>
      </div>
    </section>
  );
};

const AddSensorModal = ({ draft, conveyorIds, onChange, onCancel, onSave }: {
  draft: SensorRow;
  conveyorIds: string[];
  onChange: (draft: SensorRow) => void;
  onCancel: () => void;
  onSave: () => void;
}) => (
  <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" role="dialog" aria-modal="true" aria-labelledby="add-sensor-title">
    <div className="w-full max-w-lg rounded-lg bg-white p-6 shadow-xl dark:bg-gray-800">
      <h3 id="add-sensor-title" className="text-lg font-bold">Add Sensor</h3>
      <div className="mt-4 space-y-4">
        <label className="block text-sm font-medium">Sensor name<input autoFocus value={draft.sensorName || ""} onChange={event => onChange({ ...draft, sensorName: event.target.value })} className={`${inputClass} mt-1`} /></label>
        <label className="block text-sm font-medium">Conveyor ID<input list="sensor-conveyors" value={draft.conveyorId || ""} onChange={event => onChange({ ...draft, conveyorId: event.target.value })} className={`${inputClass} mt-1`} /></label>
        <label className="block text-sm font-medium">Progress (%)<input type="number" min="0" max="100" step="any" value={draft.progress ?? ""} onChange={event => onChange({ ...draft, progress: Number(event.target.value) })} className={`${inputClass} mt-1`} /></label>
      </div>
      <div className="mt-6 flex justify-end gap-2">
        <button onClick={onCancel} className="rounded bg-gray-100 px-3 py-2 text-sm font-semibold dark:bg-gray-700">Cancel</button>
        <button onClick={onSave} className="rounded bg-blue-600 px-3 py-2 text-sm font-semibold text-white hover:bg-blue-700">Add</button>
      </div>
    </div>
  </div>
);
