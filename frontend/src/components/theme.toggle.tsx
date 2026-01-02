import React from "react";
import { useTheme } from "../context/theme.context";

export const ThemeToggle = () => {
  const { mode, toggleTheme } = useTheme();

  return (
    <button
      onClick={toggleTheme}
      className="
        px-4 py-2 rounded-full
        font-bold text-sm shadow-sm border border-gray-200 dark:border-gray-600
        
        transition-all duration-200 ease-in-out 
        hover:scale-105 active:scale-95
        
        bg-gray-100 text-gray-800
        hover:bg-gray-200
        
        dark:bg-gray-700 dark:text-white
        dark:hover:bg-gray-600
      "
      aria-label="Toggle Dark Mode"
    >
      {mode === "light" ? "🌙 Dark" : "☀️ Light"}
    </button>
  );
};