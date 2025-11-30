import { useState } from "react";
import { buttonGroupStyle, editorStyle, iconGroupStyle } from "../styles/styles";

export interface EdgeEditorData {
  edgeId: string;
  sourceId: string;
  targetId: string;
  speed: number;
  length: number;
}

interface EdgeEditorProps {
  data: EdgeEditorData;
  onClose: () => void;
  onSubmit: (updatedData: { speed: number; length: number; isReversed: boolean }) => void;
  onDelete: (edgeId: string, sourceId: string, targetId: string) => void;
}

export const EdgeEditor = ({ data, onClose, onSubmit, onDelete }: EdgeEditorProps) => {
  const [speed, setSpeed] = useState(data.speed);
  const [length, setLength] = useState(data.length);
  const [isReversed, setIsReversed] = useState(false);

  const handleSubmit = () => {
    onSubmit({ speed: Number(speed), length: Number(length), isReversed });
    onClose();
  };

  const handleDelete = () => {
    if (window.confirm(`Are you sure you want to delete the path from ${data.sourceId} to ${data.targetId}?`)) {
      onDelete(data.edgeId, data.sourceId, data.targetId);
      onClose();
    }
  };

  const currentSourceLabel = isReversed ? data.targetId : data.sourceId;
  const currentTargetLabel = isReversed ? data.sourceId : data.targetId;

  return (
    <div style={editorStyle}>
      <h4>Edit Path</h4>
      <p><b>From:</b> {currentSourceLabel}</p>
      <p><b>To:</b> {currentTargetLabel}</p>
      
      <label>Speed:</label>
      <input type="number" value={speed} min="0" onChange={e => setSpeed(parseFloat(e.target.value))} />
      
      <label>Length:</label>
      <input type="number" value={length} min="0" onChange={e => setLength(parseFloat(e.target.value))} />
      
      <div style={buttonGroupStyle}>
        <button onClick={() => setIsReversed(!isReversed)}>
          {isReversed ? 'Direction Reversed' : 'Reverse Direction'}
        </button>
      </div>

      <div style={iconGroupStyle}>
        <button onClick={handleDelete} title="Delete">🗑️</button>
        <button onClick={onClose} title="Close">❌</button>
        <button onClick={handleSubmit} title="Submit">✔️</button>
      </div>
    </div>
  );
};