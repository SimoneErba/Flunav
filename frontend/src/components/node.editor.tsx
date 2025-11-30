import { useState } from "react";
import { editorStyle, iconGroupStyle } from "../styles/styles";

export interface NodeEditorData {
  nodeId: string;
  name: string;
}

interface NodeEditorProps {
  data: NodeEditorData;
  onClose: () => void;
  onSubmit: (updatedData: { name: string }) => void;
  onDelete: (nodeId: string) => void;
}

export const NodeEditor = ({ data, onClose, onSubmit, onDelete }: NodeEditorProps) => {
  const [name, setName] = useState(data.name);

  const handleSubmit = () => {
    if (name.trim()) {
      onSubmit({ name: name.trim() });
      onClose();
    }
  };

  const handleDelete = () => {
    if (window.confirm(`Are you sure you want to delete "${data.name}"?`)) {
      onDelete(data.nodeId);
      onClose();
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      handleSubmit();
    } else if (e.key === 'Escape') {
      onClose();
    }
  };

  return (
    <div style={editorStyle}>
      <h4>Edit Node</h4>
      
      <label>Name:</label>
      <input 
        type="text" 
        value={name} 
        onChange={e => setName(e.target.value)}
        onKeyPress={handleKeyPress}
        autoFocus
      />

      <div style={iconGroupStyle}>
        <button onClick={handleDelete} title="Delete">🗑️</button>
        <button onClick={onClose} title="Close">❌</button>
        <button onClick={handleSubmit} title="Submit">✔️</button>
      </div>
    </div>
  );
};
