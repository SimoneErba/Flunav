import React from "react";
import { useTheme } from "./../context/theme.context";

export const ThemeToggle = () => {
  const { mode, toggleTheme } = useTheme();

  return (
    <button
      onClick={toggleTheme}
      style={{
        position: "absolute",
        top: "20px",
        right: "20px",
        zIndex: 1000,
        padding: "8px 16px",
        borderRadius: "20px",
        border: "none",
        cursor: "pointer",
        backgroundColor: mode === "light" ? "#333" : "#fff",
        color: mode === "light" ? "#fff" : "#333",
        fontWeight: "bold",
        boxShadow: "0 2px 5px rgba(0,0,0,0.2)"
      }}
    >
      {mode === "light" ? "🌙 Dark Mode" : "☀️ Light Mode"}
    </button>
  );
};