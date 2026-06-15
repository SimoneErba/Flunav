import React, { useState, useEffect } from "react";
import { createPortal } from "react-dom";
import { PropertiesEditor } from "../properties.editor";
import { confirmToast } from "../graph/utils/toastUtils";
import { SharedButtons } from "./shared.buttons";
import { ItemResponse } from "../../../api-client/api";

const LOCATION_TYPES = [
  { value: "JUNCTION", label: "Junction" },
  { value: "DECISION_POINT", label: "Decision point" },
  { value: "CHUTE", label: "Chute" },
  { value: "ACCUMULATION", label: "Accumulation" },
  { value: "ROAD", label: "Road" },
  { value: "GENERIC", label: "Generic" },
];

export interface NodeEditorData {
  nodeId: string;
  name: string;
  capacity?: number;
  locationType?: string;
  properties?: Record<string, unknown>;
  itemsInChute?: ItemResponse[];
}

interface NodeEditorProps {
  data: NodeEditorData;
  onClose: () => void;
  onSubmit: (updatedData: { name: string; capacity: number; locationType: string; properties: Record<string, unknown> }) => void;
  onDelete: (nodeId: string) => void;
}

export const NodeEditor = React.memo(({ data, onClose, onSubmit, onDelete }: NodeEditorProps) => {
  const [name, setName] = useState(data.name || "");
  const [capacity, setCapacity] = useState(data.capacity || 0);
  const [locationType, setLocationType] = useState(data.locationType || "GENERIC");
  const [properties, setProperties] = useState(data.properties || {});

  useEffect(() => {
    setName(data.name || "");
    setCapacity(data.capacity || 0);
    setLocationType(data.locationType || "GENERIC");
    setProperties(data.properties || {});
  }, [data]);

  const handleSubmit = () => {
    if (name.trim()) {
      onSubmit({ name: name.trim(), capacity: Number(capacity), locationType, properties });
      onClose();
    }
  };

  const handleDelete = () => {
    confirmToast(`Delete location "${data.name}"?`, () => { onDelete(data.nodeId); onClose(); });
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleSubmit();
    else if (e.key === 'Escape') onClose();
  };

  const isChute = locationType === "CHUTE";
  const itemsInChute = data.itemsInChute ?? [];

  const inputClass = `
    w-full p-2 rounded border text-sm
    bg-gray-50 dark:bg-gray-900 
    border-gray-300 dark:border-gray-600 
    text-gray-900 dark:text-white
    focus:outline-none focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400
  `;

  const labelClass = "block mb-1 text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wide";

  const content = (
    <div className="
      fixed top-24 left-5 z-[1000] w-72 p-4 
      flex flex-col gap-4
      bg-white dark:bg-gray-800 
      text-gray-900 dark:text-gray-100
      border border-gray-200 dark:border-gray-700 
      rounded-lg shadow-xl animate-slide-in
      max-h-[85vh] overflow-y-auto 
    ">
      {/* Header */}
      <div className="flex justify-between items-center border-b border-gray-200 dark:border-gray-700 pb-2">
        <h4 className="text-lg font-semibold m-0">Edit Location</h4>
        <span className="text-xs font-mono text-gray-500 dark:text-gray-400 truncate max-w-[100px]" title={data.nodeId}>
          {data.nodeId}
        </span>
      </div>

      {/* Inputs */}
      <div className="flex flex-col gap-3">
        {/* Name */}
        <div>
          <label className={labelClass}>Name</label>
          <input
            type="text" value={name}
            onChange={e => setName(e.target.value)}
            onKeyDown={handleKeyPress}
            autoFocus className={inputClass}
          />
        </div>

        {/* Location Type */}
        <div>
          <label className={labelClass}>Type</label>
          <select
            value={locationType}
            onChange={e => setLocationType(e.target.value)}
            className={`${inputClass} cursor-pointer`}
          >
            {LOCATION_TYPES.map(t => (
              <option key={t.value} value={t.value}>{t.label}</option>
            ))}
          </select>
        </div>

        {/* Capacity */}
        <div>
          <label className={labelClass}>Capacity</label>
          <input
            type="number" value={capacity} min="0"
            onChange={e => setCapacity(parseFloat(e.target.value))}
            onKeyDown={handleKeyPress}
            className={`${inputClass} dark:[color-scheme:dark]`}
          />
        </div>
      </div>

      {/* Properties */}
      <div className="border-t border-gray-200 dark:border-gray-700 pt-2">
        <PropertiesEditor properties={properties} onChange={setProperties} />
      </div>

      {/* Items in Chute — only shown when type is CHUTE */}
      {isChute && (
        <div className="border-t border-gray-200 dark:border-gray-700 pt-2 flex flex-col gap-2">
          <div className="flex items-center justify-between">
            <label className={labelClass}>Items in Chute</label>
            <span className="text-xs font-mono bg-gray-100 dark:bg-gray-700 px-2 py-0.5 rounded-full">
              {itemsInChute.length}{data.capacity ? `/${data.capacity}` : ""}
            </span>
          </div>

          {itemsInChute.length === 0 ? (
            <p className="text-xs text-gray-400 dark:text-gray-500 italic">No items currently in chute.</p>
          ) : (
            <ul className="flex flex-col gap-1 max-h-40 overflow-y-auto pr-1">
              {itemsInChute.map((item) => (
                <li
                  key={item.id}
                  className="
                    flex items-center justify-between
                    px-2 py-1.5 rounded
                    bg-gray-50 dark:bg-gray-900
                    border border-gray-200 dark:border-gray-700
                    text-xs
                  "
                >
                  <span className="font-medium truncate max-w-[140px]" title={item.name}>
                    {item.name ?? item.id}
                  </span>
                  <span className="font-mono text-gray-400 dark:text-gray-500 truncate max-w-[80px]" title={item.id}>
                    {item.id}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {/* Actions */}
      <SharedButtons onClose={onClose} onDelete={handleDelete} onSubmit={handleSubmit} />
    </div>
  );

  return createPortal(content, document.body);
});
