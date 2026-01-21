import { useEffect } from "react";

interface SharedButtonsProps{
  onClose: () => void;
  onSubmit: () => void;
  onDelete: () => void;
}

export const SharedButtons = ({onDelete, onClose, onSubmit}: SharedButtonsProps) => {
    useEffect(() => {
    const handler = (e: { key: string; preventDefault: () => void; }) => {
      if (e.key === "Delete") {
        e.preventDefault();
        onDelete();
      }
    };

    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, []);
  
    return (
        <div className="flex gap-2 mt-2 pt-2 border-t border-gray-200 dark:border-gray-700">
        <button 
          onClick={onDelete} 
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
          onClick={onSubmit} 
          className="
            flex-1 px-3 py-2 rounded font-medium text-white text-sm transition-colors shadow-sm
            bg-green-600 hover:bg-green-700
          "
        >
          Save
        </button>
      </div>
    );
}