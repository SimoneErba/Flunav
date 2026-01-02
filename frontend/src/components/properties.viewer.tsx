import React from "react";
import { useTheme, THEMES } from "../context/theme.context";

interface PropertiesViewerProps {
  properties?: Record<string, any>;
}

export const PropertiesViewer = ({ properties }: PropertiesViewerProps) => {
  const { mode } = useTheme();
  const theme = THEMES[mode];

  if (!properties || Object.keys(properties).length === 0) {
    return <div style={{ color: "#888", fontStyle: "italic", fontSize: "0.9em" }}>No custom properties</div>;
  }

  const renderValue = (value: any) => {
    if (typeof value === "boolean") {
      return (
        <span style={{ 
            display: "inline-block", 
            width: "12px", 
            height: "12px", 
            borderRadius: "2px", 
            backgroundColor: value ? "#28a745" : "#dc3545",
            verticalAlign: "middle"
        }} title={value ? "True" : "False"} />
      );
    }
    if (typeof value === "number") {
      return <span style={{ fontFamily: "monospace", color: "#007bff" }}>{value}</span>;
    }
    return <span>{String(value)}</span>;
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "4px", marginTop: "10px" }}>
      <div style={{ fontSize: "0.85em", fontWeight: "bold", textTransform: "uppercase", color: "#888", borderBottom: `1px solid ${theme.uiBorder}` }}>
        Properties
      </div>
      {Object.entries(properties).map(([key, value]) => (
        <div key={key} style={{ display: "flex", justifyContent: "space-between", fontSize: "0.9em" }}>
          <span style={{ color: theme.uiText, opacity: 0.8 }}>{key}:</span>
          <span style={{ fontWeight: 500 }}>{renderValue(value)}</span>
        </div>
      ))}
    </div>
  );
};