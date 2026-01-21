import { useState, useEffect } from "react";
import { PropertiesEditor } from "../properties.editor";
import { confirmToast } from "../graph/utils/toastUtils";
import { SharedButtons } from "./shared.buttons";

interface ItemEditorProps {
  data: any;
  onClose: () => void;
  onSubmit: (updatedData: any) => void; 
  onDelete: (id: string) => void;
}

export const ItemEditor = ({ data, onClose, onSubmit, onDelete }: ItemEditorProps) => {
  // Master State
  const [name, setName] = useState(data.label || "");
  const [properties, setProperties] = useState(data.properties || {});

  // Sync state if data prop changes (e.g. selection change)
  useEffect(() => {
    setName(data.label || "");
    setProperties(data.properties || {});
  }, [data]);

  const handleSubmit = () => {
    // Single payload sent to parent
    onSubmit({
      name: name,
      properties: properties
    });
    // Optional: close on save, or stay open
    // onClose(); 
  };

  const handleDelete = () => {
    confirmToast(`Delete item ${data.id}?`, () => {
      onDelete(data.id);
      onClose();
    });
  };

  return (
    <div className="
      absolute top-5 left-5 z-[1000] w-80 p-4
      flex flex-col gap-4
      bg-white dark:bg-gray-800 
      text-gray-900 dark:text-gray-100
      border border-gray-200 dark:border-gray-700 
      rounded-lg shadow-xl animate-slide-in
      max-h-[85vh] overflow-y-auto
    ">
      {/* Header */}
      <div className="flex justify-between items-center border-b border-gray-200 dark:border-gray-700 pb-2">
        <h4 className="text-lg font-semibold m-0">Item Inspector</h4>
        <span className="text-xs font-mono text-gray-500 dark:text-gray-400">
          {data.id}
        </span>
      </div>

      {/* Name Input (Now Editable) */}
      <div>
        <label className="block text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wide mb-1">
          Name
        </label>
        <input 
          type="text" 
          value={name}
          onChange={(e) => setName(e.target.value)}
          className="w-full p-2 rounded border text-sm bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 focus:ring-2 focus:ring-blue-500 outline-none"
        />
      </div>

      {/* Status (Read Only) */}
      <div>
          <label className="block text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wide mb-1">
            Status
          </label>
          <div className={`font-bold text-sm ${data.isActive ? 'text-green-600' : 'text-amber-600'}`}>
              {data.isActive ? "ACTIVE": "INACTIVE"}
          </div>
      </div>

      {/* Unified Properties Editor */}
      <div className="border-t border-gray-200 dark:border-gray-700 pt-2">
        <PropertiesEditor 
          properties={properties} 
          onChange={(newProps) => setProperties(newProps)} 
        />
      </div>

      {/* Action Buttons */}
      <SharedButtons onClose={onClose} onDelete={handleDelete} onSubmit={handleSubmit} />
    </div>
  );
};