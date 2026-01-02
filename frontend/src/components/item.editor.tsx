import React from "react";
import { useTheme, THEMES } from "../context/theme.context";
import { ItemResponse } from "../api-client";
import { PropertiesViewer } from "./properties.viewer";

interface ItemPanelProps {
  data: ItemResponse;
  onClose: () => void;
}

export const ItemEditor = ({ data, onClose }: ItemPanelProps) => {
  const { mode } = useTheme();
  const theme = THEMES[mode];

  // Exact same style as NodeEditor
  const panelStyle: React.CSSProperties = {
    position: "absolute",
    top: "20px",
    left: "20px",
    padding: "15px",
    borderRadius: "8px",
    boxShadow: "0 4px 15px rgba(0,0,0,0.3)",
    zIndex: 1000,
    width: "280px",
    backgroundColor: theme.uiBackground,
    color: theme.uiText,
    border: `1px solid ${theme.uiBorder}`,
    display: "flex",
    flexDirection: "column",
    gap: "10px",
    animation: "slideIn 0.2s ease-out"
  };

  const buttonStyle: React.CSSProperties = {
    padding: "6px 12px",
    borderRadius: "4px",
    border: "none",
    cursor: "pointer",
    backgroundColor: mode === 'dark' ? '#444' : '#e0e0e0',
    color: theme.uiText,
    marginTop: "10px"
  };

  return (
    <div style={panelStyle}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", borderBottom: `1px solid ${theme.uiBorder}`, paddingBottom: "8px" }}>
        <h4 style={{ margin: 0 }}>Item Inspector</h4>
        <span style={{ fontSize: "0.8em", opacity: 0.7 }}>{data.id.split('-')[0]}...</span>
      </div>

      {/* Standard Fields */}
      <div>
        <label style={{ display: "block", fontSize: "0.85em", color: "#888" }}>Name</label>
        <div style={{ fontSize: "1.1em", fontWeight: "bold" }}>{data.name || "Unnamed Item"}</div>
      </div>

      <div style={{ display: "flex", gap: "10px" }}>
        <div style={{ flex: 1 }}>
            <label style={{ display: "block", fontSize: "0.85em", color: "#888" }}>Speed</label>
            <div>{data.speed?.toFixed(2)} m/s</div>
        </div>
        <div style={{ flex: 1 }}>
            <label style={{ display: "block", fontSize: "0.85em", color: "#888" }}>Status</label>
            <div style={{ color: data.status === 'ACTIVE' ? '#28a745' : '#ffc107' }}>{data.status}</div>
        </div>
      </div>

      {/* Dynamic Properties Section */}
      <PropertiesViewer properties={data.properties} />

      <button onClick={onClose} style={buttonStyle}>Close</button>
    </div>
  );
};