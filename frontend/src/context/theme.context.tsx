/* eslint-disable react-refresh/only-export-components */
import React, { useEffect, createContext, useContext, useState } from "react";
import { useSigma } from "@react-sigma/core";

// Defined Palette including both CANVAS (WebGL) and UI (HTML Panels) colors
export const THEMES = {
  light: {
    // --- Sigma/WebGL Colors ---
    label: "#000000", 
    nodeDefault: "#333333",
    edgeDefault: "#cccccc",
    itemColor: "#d32f2f",
    
    // --- UI/Panel Colors (Used by Editors) ---
    uiBorder: "#e5e7eb", // Tailwind gray-200
    uiText: "#1f2937",   // Tailwind gray-800
    uiBackground: "#ffffff",
  },
  dark: {
    // --- Sigma/WebGL Colors ---
    label: "#ffffff", 
    nodeDefault: "#4db6ac",
    edgeDefault: "#555555",
    itemColor: "#ff5252",

    // --- UI/Panel Colors (Used by Editors) ---
    uiBorder: "#374151", // Tailwind gray-700
    uiText: "#f3f4f6",   // Tailwind gray-100
    uiBackground: "#1f2937",
  },
};

type ThemeMode = "light" | "dark";

const ThemeContext = createContext<{
  mode: ThemeMode;
  toggleTheme: () => void;
}>({ mode: "light", toggleTheme: () => {} });

export const useTheme = () => useContext(ThemeContext);

export const GraphThemeProvider = ({ children }: { children: React.ReactNode }) => {
  // Check iniziale preferenza sistema
  const [mode, setMode] = useState<ThemeMode>(() => {
    if (typeof window !== 'undefined' && window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
      return "dark";
    }
    return "light";
  });

  // Sincronizza la classe 'dark' sul tag HTML per Tailwind
  useEffect(() => {
    const root = window.document.documentElement;
    if (mode === 'dark') {
      root.classList.add('dark');
      root.style.colorScheme = 'dark'; // Helper per scrollbar native browser
    } else {
      root.classList.remove('dark');
      root.style.colorScheme = 'light';
    }
  }, [mode]);

  const toggleTheme = () => {
    setMode((prev) => (prev === "light" ? "dark" : "light"));
  };

  return (
    <ThemeContext.Provider value={{ mode, toggleTheme }}>
      {children}
    </ThemeContext.Provider>
  );
};

// Controller per aggiornare il Grafo (Sigma.js) quando il tema cambia
export const GraphThemeController = () => {
  const { mode } = useTheme();
  const sigma = useSigma();

  useEffect(() => {
    if (!sigma) return;
    
    const graph = sigma.getGraph();
    const theme = THEMES[mode];
    const settings = sigma.getSettings();

    // 1. Aggiorna Label globali
    settings.labelColor = { attribute: "labelColor", color: theme.label };
    
    // 2. Aggiorna Nodi esistenti
    graph.forEachNode((node, attrs) => {
      if (attrs.isItem) {
         // Mantieni il colore item o aggiornalo se necessario
         graph.setNodeAttribute(node, "color", theme.itemColor); 
      } else {
         graph.setNodeAttribute(node, "color", theme.nodeDefault);
      }
      graph.setNodeAttribute(node, "labelColor", theme.label);
    });

    // 3. Aggiorna Edge esistenti
    graph.forEachEdge((edge) => {
      graph.setEdgeAttribute(edge, "color", theme.edgeDefault);
    });

    sigma.refresh();
  }, [mode, sigma]);

  return null;
};
