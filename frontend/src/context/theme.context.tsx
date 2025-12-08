import React, { useEffect, createContext, useContext, useState } from "react";
import { useSigma } from "@react-sigma/core";

// 1. Define Palettes
export const THEMES = {
  light: {
    // Graph
    background: "#f0f2f5",
    label: "#000000", 
    nodeDefault: "#333333",
    edgeDefault: "#cccccc",
    itemColor: "#d32f2f",
    
    // UI Panels & Controls
    uiBackground: "#ffffff",
    uiText: "#333333",
    uiBorder: "#cccccc",
    inputBackground: "#ffffff",
    inputColor: "#000000",
    buttonHover: "#f0f0f0",
    
    // Specific for Sigma Controls
    controlBg: "#ffffff",
    controlIcon: "#333333",
    controlHover: "#e0e0e0",

    // Header
    headerBackground: "#ffffff",
    headerText: "#333333",
    headerBorder: "#dddddd"
  },
  dark: {
    // Graph
    background: "#1a1a1a", 
    label: "#ffffff", 
    nodeDefault: "#4db6ac",
    edgeDefault: "#555555",
    itemColor: "#ff5252",

    // UI Panels & Controls
    uiBackground: "#2a2a2a",
    uiText: "#ffffff",
    uiBorder: "#444444",
    inputBackground: "#333333",
    inputColor: "#ffffff",
    buttonHover: "#444444",

    // Specific for Sigma Controls
    controlBg: "#333333",
    controlIcon: "#ffffff",
    controlHover: "#555555",

    // Header
    headerBackground: "#252525",
    headerText: "#ffffff",
    headerBorder: "#333333"
  },
};

type ThemeMode = "light" | "dark";

const ThemeContext = createContext<{
  mode: ThemeMode;
  toggleTheme: () => void;
}>({ mode: "dark", toggleTheme: () => {} });

export const useTheme = () => useContext(ThemeContext);

export const GraphThemeProvider = ({ children }: { children: React.ReactNode }) => {
  const [mode, setMode] = useState<ThemeMode>(() => {
    if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
      return "dark";
    }
    return "light";
  });

  const toggleTheme = () => {
    setMode((prev) => (prev === "light" ? "dark" : "light"));
  };

  return (
    <ThemeContext.Provider value={{ mode, toggleTheme }}>
      {children}
    </ThemeContext.Provider>
  );
};

// --- NEW: CSS Injector for Sigma Controls ---
const GlobalGraphStyles = () => {
  const { mode } = useTheme();
  const theme = THEMES[mode];

  return (
    <style>{`
      /* Override Sigma Control Buttons */
      .react-sigma-control {
        background-color: ${theme.controlBg} !important;
        border: 1px solid ${theme.uiBorder} !important;
        box-shadow: 0 2px 5px rgba(0,0,0,0.2) !important;
      }
      
      .react-sigma-control button {
        background-color: transparent !important;
        color: ${theme.controlIcon} !important;
      }

      .react-sigma-control button:hover {
        background-color: ${theme.controlHover} !important;
      }

      .react-sigma-control svg {
        fill: ${theme.controlIcon} !important;
      }
    `}</style>
  );
};

// --- CONTROLLER ---
export const GraphThemeController = () => {
  const { mode } = useTheme();
  const sigma = useSigma();

  useEffect(() => {
    const graph = sigma.getGraph();
    const theme = THEMES[mode];
    const settings = sigma.getSettings();

    // 1. Force Global Label Color
    settings.labelColor = { attribute: "labelColor", color: theme.label };
    
    // 2. Update Nodes
    graph.forEachNode((node, attrs) => {
      // Update Node Color
      if (attrs.isItem) graph.setNodeAttribute(node, "color", theme.itemColor);
      else graph.setNodeAttribute(node, "color", theme.nodeDefault);
      
      // CRITICAL: Force individual label color to match theme
      // Sigma sometimes defaults to node color if this isn't set
      graph.setNodeAttribute(node, "labelColor", theme.label);
    });

    // 3. Update Edges
    graph.forEachEdge((edge) => {
      graph.setEdgeAttribute(edge, "color", theme.edgeDefault);
    });

    sigma.refresh();
  }, [mode, sigma]);

  // Render the CSS styles
  return <GlobalGraphStyles />;
};

export const ThemedBackground = ({ children }: { children: React.ReactNode }) => {
  const { mode } = useTheme();
  const theme = THEMES[mode];
  return (
    <div style={{ width: "100%", height: "100%", position: "relative", backgroundColor: theme.background, transition: "background-color 0.3s ease" }}>
      {children}
    </div>
  );
};