import React, { useEffect, createContext, useContext, useState } from "react";
import { useSigma } from "@react-sigma/core";

// --- 1. Define Palettes ---
export const THEMES = {
  light: {
    background: "#ffffff",
    label: "#000000",
    nodeDefault: "#333333",
    edgeDefault: "#cccccc",
    itemColor: "#d32f2f", // Darker red for light bg
  },
  dark: {
    background: "#1a1a1a",
    label: "#ffffff",
    nodeDefault: "#4db6ac", // Bright Teal for dark bg
    edgeDefault: "#555555",
    itemColor: "#ff5252", // Bright red for dark bg
  },
};

type ThemeMode = "light" | "dark";

// --- 2. Context for the Switch ---
const ThemeContext = createContext<{
  mode: ThemeMode;
  toggleTheme: () => void;
}>({ mode: "light", toggleTheme: () => {} });

export const useTheme = () => useContext(ThemeContext);

// --- 3. The Provider (Wraps your App or Graph) ---
export const GraphThemeProvider = ({ children }: { children: React.ReactNode }) => {
  const [mode, setMode] = useState<ThemeMode>("dark"); // Default to dark

  const toggleTheme = () => {
    setMode((prev) => (prev === "light" ? "dark" : "light"));
  };

  return (
    <ThemeContext.Provider value={{ mode, toggleTheme }}>
      {children}
    </ThemeContext.Provider>
  );
};

export const ThemedBackground = ({ children }: { children: React.ReactNode }) => {
  const { mode } = useTheme();
  const theme = THEMES[mode];

  return (
    <div
      style={{
        width: "100%",
        height: "100%",
        position: "relative",
        // This applies the color from the theme
        backgroundColor: theme.background, 
        transition: "background-color 0.3s ease",
      }}
    >
      {children}
    </div>
  );
};

// --- 4. The Controller (Lives INSIDE SigmaContainer) ---
export const GraphThemeController = () => {
  const { mode } = useTheme();
  const sigma = useSigma();

  useEffect(() => {
    const graph = sigma.getGraph();
    const theme = THEMES[mode];
    const settings = sigma.getSettings();

    // A. Update Canvas Settings (Labels, Background)
    // Note: Sigma container background is CSS, but we can set it via DOM or parent
    const container = document.getElementById("sigma-container-root");
    if (container) container.style.backgroundColor = theme.background;

    settings.labelColor = { color: theme.label };
    settings.edgeLabelColor = { color: theme.label };
    
    // B. Update Graph Data (Nodes & Edges)
    // We iterate to ensure contrast (e.g. dark nodes on dark bg = bad)
    
    graph.forEachNode((node, attrs) => {
      // Keep Items Red, but adjust brightness
      if (attrs.isItem) {
        graph.setNodeAttribute(node, "color", theme.itemColor);
      } else {
        // Locations
        graph.setNodeAttribute(node, "color", theme.nodeDefault);
      }
      // Update label color if you store it on node (optional, usually handled by settings)
    });

    graph.forEachEdge((edge, attrs) => {
      // Update edge color
      graph.setEdgeAttribute(edge, "color", theme.edgeDefault);
    });

    // Refresh to apply changes
    sigma.refresh();
    
  }, [mode, sigma]);

  return null;
};