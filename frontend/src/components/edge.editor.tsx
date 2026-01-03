import React, { useState, useEffect } from "react";
import toast from "react-hot-toast";
import { PropertiesEditor } from "./properties.editor";
import { confirmToast } from "./graph/utils/toastUtils";

export interface EdgeEditorData {
  edgeId: string;
  sourceId: string;
  targetId: string;
  speed: number;
  length: number;
  isMainPath?: boolean;
  properties?: Record<string, any>;
}

interface EdgeEditorProps {
  data: EdgeEditorData;
  onClose: () => void;
  // Unified submission handler
  onSubmit: (updatedData: { speed: number; length: number; isMainPath: boolean; properties: Record<string, any> }) => void;
  onDelete: (edgeId: string, sourceId: string, targetId: string) => void;
}

export const EdgeEditor = ({ data, onClose, onSubmit, onDelete }: EdgeEditorProps) => {
  // --- Master State ---
  const [speed, setSpeed] = useState(data.speed);
  const [length, setLength] = useState(data.length);
  const [isMainPath, setIsMainPath] = useState(data.isMainPath || false);
  const [properties, setProperties] = useState(data.properties || {});

  // Sync state if selected edge changes
  useEffect(() => {
    setSpeed(data.speed);
    setLength(data.length);
    setIsMainPath(data.isMainPath || false);
    setProperties(data.properties || {});
  }, [data]);

  const handleSubmit = () => {
    onSubmit({ 
      speed: Number(speed), 
      length: Number(length), 
      isMainPath,
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
          <div className="flex items-center gap-2 py-1">
            <input 
                type="checkbox" 
                id="isMainPath"
                checked={isMainPath} 
                onChange={e => setIsMainPath(e.target.checked)}
                className="w-4 h-4 text-blue-600 bg-gray-100 border-gray-300 rounded focus:ring-blue-500 dark:focus:ring-blue-600 dark:ring-offset-gray-800 focus:ring-2 dark:bg-gray-700 dark:border-gray-600 cursor-pointer"
            />
            <label htmlFor="isMainPath" className="text-sm font-medium text-gray-700 dark:text-gray-300 cursor-pointer select-none">
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
};