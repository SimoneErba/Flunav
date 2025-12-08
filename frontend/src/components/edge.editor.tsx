import { useState } from "react";
import { useTheme, THEMES } from "../context/theme.context"; // Import Context

export interface EdgeEditorData {
  edgeId: string;
  sourceId: string;
  targetId: string;
  speed: number;
  length: number;
  isMainPath?: boolean; // Added this as it was missing in interface but used in logic
}

interface EdgeEditorProps {
  data: EdgeEditorData;
  onClose: () => void;
  onSubmit: (updatedData: { speed: number; length: number; isMainPath: boolean }) => void;
  onDelete: (edgeId: string, sourceId: string, targetId: string) => void;
}

export const EdgeEditor = ({ data, onClose, onSubmit, onDelete }: EdgeEditorProps) => {
  const { mode } = useTheme();
  const theme = THEMES[mode];

  const [speed, setSpeed] = useState(data.speed);
  const [length, setLength] = useState(data.length);
  const [isMainPath, setIsMainPath] = useState(data.isMainPath || false);

  const handleSubmit = () => {
    onSubmit({ speed: Number(speed), length: Number(length), isMainPath });
    onClose();
  };

  const handleDelete = () => {
    if (window.confirm(`Delete connection?`)) {
      onDelete(data.edgeId, data.sourceId, data.targetId);
      onClose();
    }
  };

  // Dynamic Styles based on Theme
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
    colorScheme: mode // Forces browser native inputs (checkboxes/numbers) to match theme
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
        Edit Connection
      </h4>
      
      <div style={{ fontSize: "0.9em", opacity: 0.8 }}>
        <div>From: {data.sourceId}</div>
        <div>To: {data.targetId}</div>
      </div>
      
      <div>
        <label style={{ display: "block", marginBottom: "4px", fontSize: "0.9em" }}>Speed (m/s):</label>
        <input 
            type="number" 
            value={speed} 
            min="0.1" 
            step="0.1"
            onChange={e => setSpeed(parseFloat(e.target.value))} 
            style={inputStyle}
        />
      </div>
      
      <div>
        <label style={{ display: "block", marginBottom: "4px", fontSize: "0.9em" }}>Length (m):</label>
        <input 
            type="number" 
            value={length} 
            min="1" 
            onChange={e => setLength(parseFloat(e.target.value))} 
            style={inputStyle}
        />
      </div>
      
      <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
        <input 
            type="checkbox" 
            checked={isMainPath} 
            onChange={e => setIsMainPath(e.target.checked)}
            style={{ accentColor: "#007bff" }}
        />
        <label style={{ fontSize: "0.9em" }}>Is Main Path</label>
      </div>

      <div style={{ display: "flex", gap: "8px", marginTop: "10px" }}>
        <button onClick={handleDelete} style={{ ...buttonStyle, backgroundColor: "#d32f2f", color: "white" }} title="Delete">🗑️</button>
        <button onClick={onClose} style={buttonStyle} title="Close">Cancel</button>
        <button onClick={handleSubmit} style={{ ...buttonStyle, backgroundColor: "#28a745", color: "white" }} title="Save">Save</button>
      </div>
    </div>
  );
};