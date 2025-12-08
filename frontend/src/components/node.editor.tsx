import { useState } from "react";
import { useTheme, THEMES } from "../context/theme.context";

export interface NodeEditorData {
  nodeId: string;
  name: string;
  capacity?: number;
}

interface NodeEditorProps {
  data: NodeEditorData;
  onClose: () => void;
  onSubmit: (updatedData: { name: string; capacity: number }) => void;
  onDelete: (nodeId: string) => void;
}

export const NodeEditor = ({ data, onClose, onSubmit, onDelete }: NodeEditorProps) => {
  const { mode } = useTheme();
  const theme = THEMES[mode];

  const [name, setName] = useState(data.name);
  const [capacity, setCapacity] = useState(data.capacity || 0);

  const handleSubmit = () => {
    if (name.trim()) {
      onSubmit({ name: name.trim(), capacity: Number(capacity) });
      onClose();
    }
  };

  const handleDelete = () => {
    if (window.confirm(`Delete node "${data.name}"?`)) {
      onDelete(data.nodeId);
      onClose();
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleSubmit();
    else if (e.key === 'Escape') onClose();
  };

  // Dynamic Styles
  const panelStyle: React.CSSProperties = {
    position: "absolute",
    top: "20px",
    left: "20px",
    padding: "15px",
    borderRadius: "8px",
    boxShadow: "0 4px 15px rgba(0,0,0,0.3)",
    zIndex: 1000,
    width: "250px",
    backgroundColor: theme.uiBackground,
    color: theme.uiText,
    border: `1px solid ${theme.uiBorder}`,
    display: "flex",
    flexDirection: "column",
    gap: "10px"
  };

  const inputStyle: React.CSSProperties = {
    padding: "6px",
    borderRadius: "4px",
    border: `1px solid ${theme.uiBorder}`,
    backgroundColor: theme.inputBackground,
    color: theme.inputColor,
    width: "100%",
    colorScheme: mode
  };

  const buttonStyle: React.CSSProperties = {
    padding: "6px 12px",
    borderRadius: "4px",
    border: "none",
    cursor: "pointer",
    backgroundColor: mode === 'dark' ? '#444' : '#e0e0e0',
    color: theme.uiText,
    flex: 1
  };

  return (
    <div style={panelStyle}>
      <h4 style={{ margin: "0 0 10px 0", borderBottom: `1px solid ${theme.uiBorder}`, paddingBottom: "5px" }}>
        Edit Location
      </h4>
      
      <div>
        <label style={{ display: "block", marginBottom: "4px", fontSize: "0.9em" }}>Name:</label>
        <input 
            type="text" 
            value={name} 
            onChange={e => setName(e.target.value)}
            onKeyDown={handleKeyPress}
            autoFocus
            style={inputStyle}
        />
      </div>

      <div>
        <label style={{ display: "block", marginBottom: "4px", fontSize: "0.9em" }}>Capacity:</label>
        <input 
            type="number" 
            value={capacity} 
            min="0"
            onChange={e => setCapacity(parseFloat(e.target.value))}
            onKeyDown={handleKeyPress}
            style={inputStyle}
        />
      </div>

      <div style={{ display: "flex", gap: "8px", marginTop: "10px" }}>
        <button onClick={handleDelete} style={{ ...buttonStyle, backgroundColor: "#d32f2f", color: "white" }} title="Delete">🗑️</button>
        <button onClick={onClose} style={buttonStyle} title="Close">Cancel</button>
        <button onClick={handleSubmit} style={{ ...buttonStyle, backgroundColor: "#28a745", color: "white" }} title="Save">Save</button>
      </div>
    </div>
  );
};