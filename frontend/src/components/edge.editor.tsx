import { useState } from "react";
import toast from "react-hot-toast";
import { confirmToast } from "./graph/utils/toastUtils";

export interface EdgeEditorData {
  edgeId: string;
  sourceId: string;
  targetId: string;
  speed: number;
  length: number;
  isMainPath?: boolean;
}

interface EdgeEditorProps {
  data: EdgeEditorData;
  onClose: () => void;
  onSubmit: (updatedData: { speed: number; length: number; isMainPath: boolean }) => void;
  onDelete: (edgeId: string, sourceId: string, targetId: string) => void;
}

export const EdgeEditor = ({ data, onClose, onSubmit, onDelete }: EdgeEditorProps) => {
  const [speed, setSpeed] = useState(data.speed);
  const [length, setLength] = useState(data.length);
  const [isMainPath, setIsMainPath] = useState(data.isMainPath || false);

  const handleSubmit = () => {
    onSubmit({ speed: Number(speed), length: Number(length), isMainPath });
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
      flex flex-col gap-3
      bg-white dark:bg-gray-800 
      text-gray-900 dark:text-gray-100
      border border-gray-200 dark:border-gray-700 
      rounded-lg shadow-xl animate-slide-in
    ">
      {/* Header */}
      <div className="border-b border-gray-200 dark:border-gray-700 pb-2">
        <h4 className="text-lg font-semibold m-0">Edit Connection</h4>
        <div className="text-xs font-mono text-gray-500 dark:text-gray-400 mt-1 truncate">
          {data.sourceId} <span className="text-gray-400">➝</span> {data.targetId}
        </div>
      </div>
      
      {/* Speed Input */}
      <div>
        <label className="block mb-1 text-sm font-medium text-gray-600 dark:text-gray-400">
          Speed (m/s):
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
      
      {/* Length Input */}
      <div>
        <label className="block mb-1 text-sm font-medium text-gray-600 dark:text-gray-400">
          Length (m):
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
            className="w-4 h-4 text-blue-600 bg-gray-100 border-gray-300 rounded focus:ring-blue-500 dark:focus:ring-blue-600 dark:ring-offset-gray-800 focus:ring-2 dark:bg-gray-700 dark:border-gray-600"
        />
        <label htmlFor="isMainPath" className="text-sm font-medium text-gray-700 dark:text-gray-300 cursor-pointer select-none">
          Is Main Path
        </label>
      </div>

      {/* Action Buttons */}
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
};