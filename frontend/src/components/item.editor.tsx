import { ItemResponse } from "../api-client";
import { PropertiesViewer } from "./properties.viewer";

interface ItemPanelProps {
  data: ItemResponse;
  onClose: () => void;
}

export const ItemEditor = ({ data, onClose }: ItemPanelProps) => {
  return (
    <div className="
      absolute top-5 left-5 z-[1000] w-72 p-4
      flex flex-col gap-3
      bg-white dark:bg-gray-800 
      text-gray-900 dark:text-gray-100
      border border-gray-200 dark:border-gray-700 
      rounded-lg shadow-xl 
    ">
      {/* Header */}
      <div className="flex justify-between items-center border-b border-gray-200 dark:border-gray-700 pb-2">
        <h4 className="text-lg font-semibold m-0">Item Inspector</h4>
        <span className="text-xs font-mono text-gray-500 dark:text-gray-400">
          {data.id}
        </span>
      </div>

      {/* Name Field */}
      <div>
        <label className="block text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wide mb-1">
          Name
        </label>
        <div className="text-base font-medium truncate">
          {data.name || "Unnamed Item"}
        </div>
      </div>

      {/* Speed & Status Grid */}
      <div className="flex gap-4">
        <div className="flex-1">
            <label className="block text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wide mb-1">
              Status
            </label>
            <div className={`font-bold text-sm ${
                data.status === 'ACTIVE' 
                  ? 'text-green-600 dark:text-green-400' 
                  : 'text-amber-600 dark:text-amber-400'
            }`}>
                {data.status}
            </div>
        </div>
      </div>

      {/* Dynamic Properties Section */}
      {/* We add a separator line before properties for visual separation */}
      <div className="border-t border-gray-200 dark:border-gray-700 pt-2 mt-1">
        <PropertiesViewer properties={data.properties} />
      </div>

      {/* Close Button */}
      <button 
        onClick={onClose} 
        className="
          w-full mt-2 px-4 py-2 rounded font-medium transition-colors
          bg-gray-200 dark:bg-gray-700 
          text-gray-800 dark:text-gray-200 
          hover:bg-gray-300 dark:hover:bg-gray-600
        "
      >
        Close
      </button>
    </div>
  );
};