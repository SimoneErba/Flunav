import React, { useRef, useState } from 'react';
import toast from 'react-hot-toast';
import { useApi } from '../../hooks/useApi';
import { AxiosError } from 'axios'; // Import AxiosError for better type checking

export const GraphImportExport = ({ onImportSuccess }: { onImportSuccess?: () => void }) => {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [isImporting, setIsImporting] = useState(false);
  const { graphApi } = useApi(); // Ensure useApi returns an object with graphApi

  // --- Export ---
  const handleExport = async () => {
    try {
      // Correctly configure Axios to expect a binary blob response
      const response = await graphApi.exportGraph({
        responseType: 'blob',
      });

      const exportedData: unknown = response.data;
      const blob = exportedData instanceof Blob ? exportedData : new Blob([String(exportedData)]);
      const disposition = response.headers['content-disposition'] ?? ''; // Access headers as an object
      const filename = disposition.match(/filename="?([^"]+)"?/)?.[1] ?? 'graph.flugraph';

      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a); // Append to body to make it clickable in all browsers
      a.click();
      document.body.removeChild(a); // Clean up
      URL.revokeObjectURL(url);

      toast.success('Graph exported successfully');
    } catch (e) {
      toast.error('Export failed');
      console.error(e);
    }
  };

  // --- Import ---
  const handleFileSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]; // This 'file' is a File object, which extends Blob
    if (!file) return;

    // Reset input so the same file can be re-selected if the first import fails
    e.target.value = '';

    setIsImporting(true);

    try {
      // **THE FIX IS HERE:**
      // Pass the raw File object directly as the 'file' argument.
      // The generated 'graphApi.importGraph' method is responsible for
      // internally creating the FormData and appending this 'file'
      // under the correct part name (e.g., 'file').
      const response = await graphApi.importGraph(file);

      // For Axios, the JSON response is typically in `response.data`
      const result = response.data;

      if (!result.success) {
        toast.error(`Import failed: ${result.message}`);
        return;
      }

      toast.success(`Loaded: ${result.message}`);
      onImportSuccess?.();

    } catch (error) {
      // More robust error handling
      let errorMessage = 'Import failed — invalid file, network issue, or server error.';
      if (error instanceof AxiosError && error.response?.data) {
        // Attempt to extract message from backend JSON error response
        const apiError = error.response.data as { message?: string };
        if (apiError.message) {
          errorMessage = `Import failed: ${apiError.message}`;
        }
      }
      toast.error(errorMessage);
      console.error(error);
    } finally {
      setIsImporting(false);
    }
  };

  return (
    <div className="flex items-center gap-1">
      {/* Export Button */}
      <button
        onClick={handleExport}
        title="Export graph as .flugraph file"
        aria-label="Export graph"
        className="flex items-center rounded-md p-2 text-xs font-medium
          text-gray-600 dark:text-gray-300
          hover:bg-gray-100 dark:hover:bg-gray-800
          border border-transparent hover:border-gray-200 dark:hover:border-gray-700
          transition-all"
      >
        {/* Download arrow icon */}
        <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24"
          fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
          <polyline points="7 10 12 15 17 10"/>
          <line x1="12" y1="15" x2="12" y2="3"/>
        </svg>
      </button>

      {/* Import Button */}
      <button
        onClick={() => fileInputRef.current?.click()}
        disabled={isImporting}
        title="Import a .flugraph file"
        aria-label="Import graph"
        className="flex items-center rounded-md p-2 text-xs font-medium
          text-gray-600 dark:text-gray-300
          hover:bg-gray-100 dark:hover:bg-gray-800
          border border-transparent hover:border-gray-200 dark:hover:border-gray-700
          transition-all disabled:opacity-50 disabled:cursor-not-allowed"
      >
        {isImporting ? (
          /* Spinner */
          <svg className="animate-spin" xmlns="http://www.w3.org/2000/svg" width="13" height="13"
            fill="none" viewBox="0 0 24 24">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
          </svg>
        ) : (
          /* Upload arrow icon */
          <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24"
            fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
            <polyline points="17 8 12 3 7 8"/>
            <line x1="12" y1="3" x2="12" y2="15"/>
          </svg>
        )}
      </button>

      {/* Hidden file input */}
      <input
        ref={fileInputRef}
        type="file"
        accept=".flugraph,application/json"
        className="hidden"
        onChange={handleFileSelected}
      />
    </div>
  );
};
