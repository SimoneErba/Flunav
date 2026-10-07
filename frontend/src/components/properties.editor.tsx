import React, { useState } from "react";
import toast from "react-hot-toast";

interface PropertiesEditorProps {
  properties: Record<string, unknown>;
  onChange: (updatedProperties: Record<string, unknown>) => void;
  reservedKeys?: string[];
}

type PropertyType = "text" | "number" | "boolean" | "datetime";

export const PropertiesEditor = ({ properties = {}, onChange, reservedKeys = [] }: PropertiesEditorProps) => {
  // State for adding new property
  const [isAdding, setIsAdding] = useState(false);
  const [newKey, setNewKey] = useState("");
  const [newType, setNewType] = useState<PropertyType>("text");

  // Helper to detect type of existing values
  const getInputType = (value: unknown): PropertyType => {
    if (typeof value === "boolean") return "boolean";
    if (typeof value === "number") return "number";
    if (typeof value === "string" && !isNaN(Date.parse(value)) && value.includes("T")) {
      return "datetime";
    }
    return "text";
  };

  const formatForDateTimeInput = (isoString: string) => {
    if (!isoString) return "";
    try {
      return new Date(isoString).toISOString().slice(0, 16);
    } catch {
      return "";
    }
  };

  const handleFieldChange = (key: string, rawValue: string | boolean, type: PropertyType) => {
    let finalValue: unknown = rawValue;

    if (type === "number" && typeof rawValue === "string") {
      finalValue = parseFloat(rawValue);
    } else if (type === "datetime" && typeof rawValue === "string") {
      finalValue = new Date(rawValue).toISOString();
    }

    const newProperties = { ...properties, [key]: finalValue };
    onChange(newProperties);
  };

  const handleDelete = (keyToDelete: string) => {
    const newProperties = { ...properties };
    delete newProperties[keyToDelete];
    onChange(newProperties);
  };

  const handleAdd = () => {
    if (!newKey.trim()) {
      toast.error("Key name cannot be empty");
      return;
    }
    if (Object.prototype.hasOwnProperty.call(properties, newKey)) {
      toast.error("Key already exists");
      return;
    }
    if (reservedKeys.some(key => key.toLowerCase() === newKey.trim().toLowerCase())) {
      toast.error(`${newKey.trim()} is a dedicated field`);
      return;
    }

    let initialValue: string | number | boolean;
    switch (newType) {
      case "number": initialValue = 0; break;
      case "boolean": initialValue = false; break;
      case "datetime": initialValue = new Date().toISOString(); break;
      default: initialValue = "";
    }

    const newProperties = { ...properties, [newKey]: initialValue };
    onChange(newProperties);
    
    // Reset state
    setNewKey("");
    setNewType("text");
    setIsAdding(false);
  };

  return (
    <div className="flex flex-col gap-2 mt-2">
      <div className="flex justify-between items-end border-b border-gray-200 dark:border-gray-700 pb-1">
        <div className="text-xs font-bold text-gray-500 uppercase tracking-wide">
          Properties
        </div>
      </div>

      {(!properties || Object.keys(properties).length === 0) && !isAdding && (
        <div className="text-gray-400 italic text-xs p-2 text-center">No properties</div>
      )}

      {/* Existing Properties List */}
      <div className="flex flex-col gap-2">
        {Object.entries(properties).map(([key, value]) => {
          const type = getInputType(value);

          return (
            <div key={key} className="group relative flex flex-col gap-1">
              <div className="flex justify-between items-center">
                <label className="text-xs text-gray-600 dark:text-gray-400 opacity-80 font-mono">
                    {key}:
                </label>
                
                {/* Delete Button - Appears on Group Hover */}
                <button
                    onClick={() => handleDelete(key)}
                    className="
                        opacity-0 group-hover:opacity-100 transition-opacity duration-200
                        p-1 rounded hover:bg-red-100 dark:hover:bg-red-900/30 text-red-500
                    "
                    title="Remove Property"
                >
                    <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path></svg>
                </button>
              </div>
              
              {/* Input Area */}
              <div className="relative">
                  {type === "boolean" && (
                    <div className="flex items-center gap-2 p-1.5 rounded border border-transparent hover:border-gray-200 dark:hover:border-gray-700 transition-colors">
                      <input
                        type="checkbox"
                        checked={!!value}
                        onChange={(e) => handleFieldChange(key, e.target.checked, "boolean")}
                        className="cursor-pointer w-4 h-4 accent-blue-600"
                      />
                      <span className={`text-xs font-bold ${value ? "text-green-600" : "text-red-500"}`}>
                        {value ? "True" : "False"}
                      </span>
                    </div>
                  )}

                  {type === "number" && (
                    <input
                      type="number"
                      step="any"
                      value={typeof value === "number" ? value : 0}
                      onChange={(e) => handleFieldChange(key, e.target.value, "number")}
                      className="w-full p-1.5 rounded text-sm font-mono border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 text-gray-900 dark:text-white focus:ring-1 focus:ring-blue-500 outline-none"
                    />
                  )}

                  {type === "datetime" && (
                    <input
                      type="datetime-local"
                      value={formatForDateTimeInput(typeof value === "string" ? value : "")}
                      onChange={(e) => handleFieldChange(key, e.target.value, "datetime")}
                      className="w-full p-1.5 rounded text-sm border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 text-gray-900 dark:text-white focus:ring-1 focus:ring-blue-500 outline-none dark:[color-scheme:dark]"
                    />
                  )}

                  {type === "text" && (
                    <input
                      type="text"
                      value={typeof value === "string" ? value : String(value ?? "")}
                      onChange={(e) => handleFieldChange(key, e.target.value, "text")}
                      className="w-full p-1.5 rounded text-sm border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 text-gray-900 dark:text-white focus:ring-1 focus:ring-blue-500 outline-none"
                    />
                  )}
              </div>
            </div>
          );
        })}
      </div>

      {/* Add Property Section */}
      {isAdding ? (
        <div className="flex flex-col gap-2 mt-2 p-2 bg-gray-50 dark:bg-gray-700/50 rounded border border-gray-200 dark:border-gray-600 animate-slide-in">
            <div className="text-xs font-bold text-gray-500">New Property</div>
            <input 
                type="text" 
                placeholder="Key Name" 
                value={newKey}
                onChange={e => setNewKey(e.target.value)}
                className="w-full p-1.5 rounded text-sm border border-gray-300 dark:border-gray-500 bg-white dark:bg-gray-800 focus:outline-none focus:ring-1 focus:ring-blue-500 text-gray-900 dark:text-white"
            />
            <select
                value={newType}
                onChange={e => setNewType(e.target.value as PropertyType)}
                className="w-full p-1.5 rounded text-sm border border-gray-300 dark:border-gray-500 bg-white dark:bg-gray-800 focus:outline-none text-gray-900 dark:text-white"
            >
                <option value="text">Text</option>
                <option value="number">Number</option>
                <option value="boolean">Boolean</option>
                <option value="datetime">DateTime</option>
            </select>
            <div className="flex gap-2 mt-1">
                <button onClick={() => setIsAdding(false)} className="flex-1 px-2 py-1 text-xs rounded bg-gray-200 dark:bg-gray-600 hover:bg-gray-300 dark:hover:bg-gray-500 text-gray-800 dark:text-white">Cancel</button>
                <button onClick={handleAdd} className="flex-1 px-2 py-1 text-xs rounded bg-blue-600 hover:bg-blue-700 text-white font-bold">Add</button>
            </div>
        </div>
      ) : (
        <button 
            onClick={() => setIsAdding(true)}
            className="mt-1 w-full py-1.5 text-xs font-medium text-blue-600 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-900/20 border border-dashed border-blue-300 dark:border-blue-700 rounded transition-colors flex items-center justify-center gap-1"
        >
            <span>+ Add Property</span>
        </button>
      )}
    </div>
  );
};
