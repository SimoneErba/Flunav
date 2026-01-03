import React, { useState, useEffect } from "react";
import { createPortal } from "react-dom";
import { PropertiesEditor } from "./properties.editor";
import { confirmToast } from "./graph/utils/toastUtils";

export interface NodeEditorData {
  nodeId: string;
  name: string;
  capacity?: number;
  properties?: Record<string, any>;
}

interface NodeEditorProps {
  data: NodeEditorData;
  onClose: () => void;
  // Unified submission handler
  onSubmit: (updatedData: { name: string; capacity: number; properties: Record<string, any> }) => void;
  onDelete: (nodeId: string) => void;
}

export const NodeEditor = ({ data, onClose, onSubmit, onDelete }: NodeEditorProps) => {
  // --- Master State ---
  const [name, setName] = useState(data.name || "");
  const [capacity, setCapacity] = useState(data.capacity || 0);
  const [properties, setProperties] = useState(data.properties || {});

  // Sync state if the selected node changes while panel is open
  useEffect(() => {
    setName(data.name || "");
    setCapacity(data.capacity || 0);
    setProperties(data.properties || {});
  }, [data]);

  const handleSubmit = () => {
    if (name.trim()) {
      onSubmit({ 
        name: name.trim(), 
        capacity: Number(capacity),
        properties: properties 
      });
      onClose();
    }
  };

  const handleDelete = () => {
    confirmToast(
        `Delete location "${data.name}"?`,
        () => {
            onDelete(data.nodeId);
            onClose();
        }
    );
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleSubmit();
    else if (e.key === 'Escape') onClose();
  };

  // Render via Portal to break out of the graph container z-index context
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
      
      {/* Inputs Container */}
      <div className="flex flex-col gap-3">
          {/* Name */}
          <div>
            <label className="block mb-1 text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wide">
              Name
            </label>
            <input 
                type="text" 
                value={name} 
                onChange={e => setName(e.target.value)}
                onKeyDown={handleKeyPress}
                autoFocus
                className="
                  w-full p-2 rounded border text-sm
                  bg-gray-50 dark:bg-gray-900 
                  border-gray-300 dark:border-gray-600 
                  text-gray-900 dark:text-white
                  focus:outline-none focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400
                "
            />
          </div>

          {/* Capacity */}
          <div>
            <label className="block mb-1 text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wide">
              Capacity
            </label>
            <input 
                type="number" 
                value={capacity} 
                min="0"
                onChange={e => setCapacity(parseFloat(e.target.value))}
                onKeyDown={handleKeyPress}
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
      </div>

      {/* Unified Properties Editor */}
      <div className="border-t border-gray-200 dark:border-gray-700 pt-2">
        <PropertiesEditor 
          properties={properties} 
          onChange={setProperties} 
        />
      </div>

      {/* Actions */}
      <div className="flex gap-2 mt-2 pt-2 border-t border-gray-200 dark:border-gray-700">
        <button 
          onClick={handleDelete} 
          className="px-3 py-2 rounded bg-red-100 text-red-600 hover:bg-red-200 dark:bg-red-900/30 dark:text-red-400 transition-colors"
          title="Delete"
        >
          🗑️
        </button>
        
        <button 
          onClick={onClose} 
          className="
            flex-1 px-3 py-2 rounded font-medium transition-colors text-sm
            bg-gray-100 hover:bg-gray-200 
            dark:bg-gray-700 dark:hover:bg-gray-600
            text-gray-800 dark:text-gray-200 
          "
        >
          Cancel
        </button>
        
        <button 
          onClick={handleSubmit} 
          className="
            flex-1 px-3 py-2 rounded font-medium text-white text-sm transition-colors shadow-sm
            bg-green-600 hover:bg-green-700
          "
        >
          Save
        </button>
      </div>
    </div>
  );

  return createPortal(content, document.body);
};