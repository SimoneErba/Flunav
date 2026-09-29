import React, { useState, useEffect } from "react";
import toast from "react-hot-toast";
import { PropertiesEditor } from "../properties.editor";
import { confirmToast } from "../graph/utils/toastUtils";
import { SharedButtons } from "./shared.buttons";
import { presetsApi } from "../../api/scenarios";
import type { ConveyorPreset } from "../../api-client";

export interface EdgeEditorData {
  edgeId: string;
  sourceId: string;
  targetId: string;
  speed: number;
  length: number;
  minDistance?: number;
  capacity?: number;
  mainPath?: boolean;
  conveyorType?: "BELT" | "ROLLER" | "CHUTE" | "STAGING";
  properties?: Record<string, unknown>;
}

interface EdgeEditorProps {
  data: EdgeEditorData;
  onClose: () => void;
  // Unified submission handler
  onSubmit: (updatedData: { speed: number; length: number; minDistance: number; capacity: number | null; mainPath: boolean; conveyorType: EdgeEditorData["conveyorType"]; properties: Record<string, unknown> }) => void;
  onDelete: (edgeId: string, sourceId: string, targetId: string) => void;
}

export const EdgeEditor = ({ data, onClose, onSubmit, onDelete }: EdgeEditorProps) => {
  // --- Master State ---
  const [presets, setPresets] = useState<ConveyorPreset[]>([]);
  useEffect(() => { void presetsApi.listConveyorPresets().then(response => setPresets(response.data)).catch(() => toast.error("Could not load presets")); }, []);
  const [speed, setSpeed] = useState(data.speed);
  const [length, setLength] = useState(data.length);
  const [minDistance, setMinDistance] = useState(data.minDistance ?? 0);
  const [capacity, setCapacity] = useState<number | null>(data.capacity ?? null);
  const [mainPath, setmainPath] = useState(data.mainPath || false);
  const [conveyorType, setConveyorType] = useState(data.conveyorType || "BELT");
  const [properties, setProperties] = useState(data.properties || {});

  // Sync state if selected edge changes
  useEffect(() => {
    setSpeed(data.speed);
    setLength(data.length);
    setMinDistance(data.minDistance ?? 0);
    setCapacity(data.capacity ?? null);
    setmainPath(data.mainPath || false);
    setConveyorType(data.conveyorType || "BELT");
    setProperties(data.properties || {});
  }, [data]);

  const handleSubmit = () => {
    if (!Number.isFinite(speed) || speed < 0 || !Number.isFinite(length) || length <= 0
        || !Number.isFinite(minDistance) || minDistance < 0 || capacity !== null && (!Number.isInteger(capacity) || capacity <= 0)) {
      toast.error('Enter valid speed, length, spacing and capacity');
      return;
    }
    onSubmit({ 
      speed: Number(speed), 
      length: Number(length), 
      minDistance,
      capacity,
      mainPath,
      conveyorType,
      properties: properties
    });
    onClose();
  };

  const handleDelete = () => {
    confirmToast(
        `Delete connection?`,
        () => {
            onDelete(data.edgeId, data.sourceId, data.targetId);
            onClose();
            toast.success("Edge deleted");
        }
    );
  };

  return (
    <div className="
      absolute top-5 left-5 z-[1000] w-72 p-4
      flex flex-col gap-4
      bg-white dark:bg-gray-800 
      text-gray-900 dark:text-gray-100
      border border-gray-200 dark:border-gray-700 
      rounded-lg shadow-xl animate-slide-in
      max-h-[85vh] overflow-y-auto
    ">
      {/* Header */}
      <div className="border-b border-gray-200 dark:border-gray-700 pb-2">
        <h4 className="text-lg font-semibold m-0">Edit Connection</h4>
        <div className="flex items-center gap-2 text-xs font-mono text-gray-500 dark:text-gray-400 mt-1">
          <span className="truncate max-w-[100px]" title={data.sourceId}>{data.sourceId}</span>
          <span className="text-gray-400">➝</span>
          <span className="truncate max-w-[100px]" title={data.targetId}>{data.targetId}</span>
        </div>
      </div>
      
      <label className="text-xs">Component preset
        <select className="w-full rounded border p-2 dark:bg-gray-900" aria-label="Component preset" defaultValue="" onChange={event => {
          const preset = presets.find(value => value.id === event.target.value);
          if (!preset) return;
          setSpeed(preset.speed!); setLength(preset.length!); setMinDistance(preset.minDistance!);
          setCapacity(preset.capacity ?? null); setmainPath(preset.mainPath ?? false); setConveyorType(preset.type!);
        }}><option value="">Choose illustrative defaults</option>{presets.map(preset => <option key={preset.id} value={preset.id}>{preset.name} · {preset.length} m · {preset.speed} m/s · {preset.minDistance} m spacing</option>)}</select>
      </label>
      <button className="rounded border p-2 text-xs" onClick={async () => {
        const name = window.prompt("Custom preset name");
        if (!name) return;
        try {
          const result = await presetsApi.saveConveyorPreset({ name, type: conveyorType, length, speed, minDistance, capacity: capacity ?? undefined, mainPath, properties: {} });
          setPresets(previous => [...previous, result.data]); toast.success("Preset saved");
        } catch { toast.error("Could not save preset"); }
      }}>Save settings as custom preset</button>
      <p className="text-xs text-gray-500">Presets use illustrative defaults, with no manufacturer validation. Submit the edit to apply.</p>
      <label className="text-xs">Minimum spacing (m)
        <input className="w-full rounded border p-2 dark:bg-gray-900" type="number" min="0" step="0.01" value={minDistance} onChange={event => setMinDistance(Number(event.target.value))} />
      </label>
      <label className="text-xs">Capacity (items; empty means unbounded)
        <input className="w-full rounded border p-2 dark:bg-gray-900" type="number" min="1" step="1" value={capacity ?? ''} onChange={event => setCapacity(event.target.value === '' ? null : Number(event.target.value))} />
      </label>
      {/* Inputs */}
      <div className="flex flex-col gap-3">
          {/* Speed */}
          <div>
            <label className="block mb-1 text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wide">
              Speed (m/s)
            </label>
            <input 
                type="number" 
                value={speed} 
                min="0.1" 
                step="0.1"
                onChange={e => setSpeed(parseFloat(e.target.value))} 
                className="
                  w-full p-2 rounded border text-sm
                  bg-gray-50 dark:bg-gray-900 
                  border-gray-300 dark:border-gray-600 
                  text-gray-900 dark:text-white
                  focus:outline-none focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400
                  dark:[color-scheme:dark]
                "
            />
          </div>
          
          {/* Length */}
          <div>
            <label className="block mb-1 text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wide">
              Length (m)
            </label>
            <input 
                type="number" 
                value={length} 
                min="1" 
                onChange={e => setLength(parseFloat(e.target.value))} 
                className="
                  w-full p-2 rounded border text-sm
                  bg-gray-50 dark:bg-gray-900 
                  border-gray-300 dark:border-gray-600 
                  text-gray-900 dark:text-white
                  focus:outline-none focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400
                  dark:[color-scheme:dark]
                "
            />
          </div>
          
          {/* Main Path Checkbox */}
          <div>
            <label className="block mb-1 text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wide">
              Conveyor type
            </label>
            <select
              value={conveyorType}
              onChange={(event) => setConveyorType(event.target.value as EdgeEditorData["conveyorType"])}
              className="w-full p-2 rounded border text-sm bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="BELT">Belt</option>
              <option value="ROLLER">Roller</option>
              <option value="CHUTE">Chute</option>
              <option value="STAGING">Staging</option>
            </select>
          </div>

          {/* Main Path Checkbox */}
          <div className="flex items-center gap-2 py-1">
            <input 
                type="checkbox" 
                id="mainPath"
                checked={mainPath} 
                onChange={e => setmainPath(e.target.checked)}
                className="w-4 h-4 text-blue-600 bg-gray-100 border-gray-300 rounded focus:ring-blue-500 dark:focus:ring-blue-600 dark:ring-offset-gray-800 focus:ring-2 dark:bg-gray-700 dark:border-gray-600 cursor-pointer"
            />
            <label htmlFor="mainPath" className="text-sm font-medium text-gray-700 dark:text-gray-300 cursor-pointer select-none">
              Is Main Path
            </label>
          </div>
      </div>

      {/* Unified Properties Editor */}
      <div className="border-t border-gray-200 dark:border-gray-700 pt-2">
        <PropertiesEditor 
          properties={properties} 
          onChange={setProperties} 
        />
      </div>

      {/* Actions */}
      <SharedButtons onClose={onClose} onDelete={handleDelete} onSubmit={handleSubmit} />
    </div>
  );
};
