import { useState } from "react";
import { confirmToast } from "./graph/utils/toastUtils";
import { createPortal } from "react-dom";

export interface NodeEditorData {
  nodeId: string;
  name: string;
  capacity?: number;
}

interface NodeEditorProps {
  data: NodeEditorData;
  onClose: () => void;
  onSubmit: (updatedData: { name: string; capacity: number }) => void;
  onDelete: (nodeId: string) => void;
}

export const NodeEditor = ({ data, onClose, onSubmit, onDelete }: NodeEditorProps) => {
  const [name, setName] = useState(data.name);
  const [capacity, setCapacity] = useState(data.capacity || 0);

  const handleSubmit = () => {
    if (name.trim()) {
      onSubmit({ name: name.trim(), capacity: Number(capacity) });
      onClose();
    }
  };

  const handleDelete = () => {
    confirmToast(
        `Delete node "${data.name}"?`,
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

  const content = (
    <div className="
      fixed top-24 left-5 z-[1000] w-64 p-4 
      flex flex-col gap-3
      bg-white dark:bg-gray-800 
      text-gray-900 dark:text-gray-100
      border border-gray-200 dark:border-gray-700 
      rounded-lg shadow-xl animate-slide-in
      max-h-[80vh] overflow-y-auto 
    ">
      <h4 className="text-lg font-semibold border-b border-gray-200 dark:border-gray-700 pb-2 m-0">
        Edit Location
      </h4>
      
      <div>
        <label className="block mb-1 text-sm font-medium text-gray-600 dark:text-gray-400">
          Name:
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

      <div>
        <label className="block mb-1 text-sm font-medium text-gray-600 dark:text-gray-400">
          Capacity:
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

      <div className="flex gap-2 mt-2">
        <button 
          onClick={handleDelete} 
          className="px-3 py-1.5 rounded bg-red-600 text-white hover:bg-red-700 transition-colors"
          title="Delete"
        >
          🗑️
        </button>
        
        <button 
          onClick={onClose} 
          className="
            flex-1 px-3 py-1.5 rounded font-medium transition-colors
            bg-gray-200 dark:bg-gray-700 
            text-gray-800 dark:text-gray-200 
            hover:bg-gray-300 dark:hover:bg-gray-600
          "
          title="Close"
        >
          Cancel
        </button>
        
        <button 
          onClick={handleSubmit} 
          className="
            flex-1 px-3 py-1.5 rounded font-medium text-white transition-colors
            bg-green-600 hover:bg-green-700
          "
          title="Save"
        >
          Save
        </button>
      </div>
    </div>
  );

  return createPortal(content, document.body);
};