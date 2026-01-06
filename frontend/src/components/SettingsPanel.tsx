import React, { useRef, useState, useEffect, useCallback, memo } from "react";
import { useApi } from "../hooks/useApi";
import { DisplayRule, DisplayRuleDataTypeEnum, DisplayRuleOperatorEnum } from "../api-client";

// --- RuleRow Component ---
interface RuleRowProps {
  rule: DisplayRule;
  orientation: 'horizontal' | 'vertical';
  onChange: (updatedRule: DisplayRule) => void;
  onDelete: () => void;
}

export const RuleRow = ({ rule, orientation, onChange, onDelete }: RuleRowProps) => {

  const handleTypeChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    onChange({ ...rule, dataType: e.target.value as DisplayRuleDataTypeEnum });
  };

  const handleOperatorChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    onChange({ ...rule, operator: e.target.value as DisplayRuleOperatorEnum });
  };

  const handleValueChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    onChange({ ...rule, value: e.target.value });
  };

  const handleColorChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    onChange({ ...rule, color: e.target.value });
  };

  return (
    <div className={`flex gap-2 ${orientation === 'horizontal' ? 'flex-row' : 'flex-col'}`}>
      <input
        type="text"
        value={rule.fieldName || ''}
        placeholder="Field name"
        onChange={(e) => onChange({ ...rule, fieldName: e.target.value })}
        className="flex-1 p-2 rounded border text-sm
                   bg-white text-gray-900 border-gray-300
                   dark:bg-gray-800 dark:text-white dark:border-gray-600
                   focus:outline-none focus:ring-2 focus:ring-blue-500"
      />

      <select
        value={rule.dataType || ''}
        onChange={handleTypeChange}
        className="p-2 rounded border text-sm
                   bg-white text-gray-900 border-gray-300
                   dark:bg-gray-800 dark:text-white dark:border-gray-600
                   focus:outline-none focus:ring-2 focus:ring-blue-500"
      >
        <option value="" disabled>Select Type</option>
        {Object.values(DisplayRuleDataTypeEnum).map(type => (
          <option key={type} value={type}>{type}</option>
        ))}
      </select>

      <select
        value={rule.operator || ''}
        onChange={handleOperatorChange}
        className="p-2 rounded border text-sm
                   bg-white text-gray-900 border-gray-300
                   dark:bg-gray-800 dark:text-white dark:border-gray-600
                   focus:outline-none focus:ring-2 focus:ring-blue-500"
      >
        <option value="" disabled>Select Operator</option>
        {Object.values(DisplayRuleOperatorEnum).map(op => (
          <option key={op} value={op}>{op}</option>
        ))}
      </select>

      <input
        type="text"
        value={rule.value || ''}
        placeholder="Value"
        onChange={handleValueChange}
        className="flex-1 p-2 rounded border text-sm
                   bg-white text-gray-900 border-gray-300
                   dark:bg-gray-800 dark:text-white dark:border-gray-600
                   focus:outline-none focus:ring-2 focus:ring-blue-500"
      />

      <input
        type="color"
        value={rule.color || '#ffffff'}
        onChange={handleColorChange}
        className="w-10 h-10 p-0 border rounded
                   bg-white border-gray-300 dark:bg-gray-800 dark:border-gray-600"
      />

      <button
        onClick={onDelete}
        className="px-2 py-1 text-sm font-medium rounded bg-red-100 hover:bg-red-200 dark:bg-red-900/30 dark:text-red-400 dark:hover:bg-red-800 transition-colors"
      >
        ✕
      </button>
    </div>
  );
};

// --- ICONS ---
const IconDockLeft = (props: any) => (
  <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
    <line x1="9" y1="3" x2="9" y2="21" />
  </svg>
);
const IconDockBottom = (props: any) => (
  <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
    <line x1="3" y1="15" x2="21" y2="15" />
  </svg>
);
const IconDockRight = (props: any) => (
  <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
    <line x1="15" y1="3" x2="15" y2="21" />
  </svg>
);

// --- Dock Button ---
const DockButton = memo(({ Svg, isActive, onClick }: { Svg: React.FC<any>, isActive: boolean, onClick: () => void }) => (
  <button
    onClick={(e) => { e.stopPropagation(); onClick(); }}
    className={`p-1 leading-none border border-gray-500 rounded text-gray-900 dark:text-white cursor-pointer transition-colors ${isActive ? 'bg-gray-200 dark:bg-white/20' : 'bg-transparent hover:bg-gray-100 dark:hover:bg-white/10'}`}
  >
    <Svg className="w-4 h-4" />
  </button>
));

// --- SettingsPanel ---
type DockSide = 'left' | 'right' | 'bottom';

const SettingsPanel = () => {
  const [isExpanded, setIsExpanded] = useState(false);
  const [dockSide, setDockSide] = useState<DockSide>('bottom');
  const [rules, setRules] = useState<DisplayRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const { displayRuleApi } = useApi();

  // --- Dimensions ---
  const [dimensions, setDimensions] = useState({ width: 350, height: 300 });
  const startPos = useRef(0);
  const startDim = useRef(0);
  const raf = useRef<number | null>(null);

  const startResize = (e: React.PointerEvent) => {
    startPos.current = dockSide === 'bottom' ? e.clientY : e.clientX;
    startDim.current = dockSide === 'bottom' ? dimensions.height : dimensions.width;
    document.addEventListener("pointermove", onResize);
    document.addEventListener("pointerup", stopResize);
    document.body.style.userSelect = 'none';
  };

  const onResize = (e: PointerEvent) => {
    if (raf.current) cancelAnimationFrame(raf.current);
    raf.current = requestAnimationFrame(() => {
      let delta = dockSide === 'bottom' ? startPos.current - e.clientY : startPos.current - e.clientX;
      const newSize = Math.max(200, startDim.current + delta);
      setDimensions(prev => ({
        ...prev,
        [dockSide === 'bottom' ? 'height' : 'width']: newSize
      }));
    });
  };

  const stopResize = () => {
    document.removeEventListener("pointermove", onResize);
    document.removeEventListener("pointerup", stopResize);
    document.body.style.userSelect = '';
  };

  // --- Rule management ---
  const addRule = () => {
    setRules(prev => [
      ...prev,
      { id: `rule_${Date.now()}`, fieldName: '', dataType: DisplayRuleDataTypeEnum.String, operator: DisplayRuleOperatorEnum.Equal, value: '', color: '#ffffff' }
    ]);
  };

  const updateRule = (index: number, updatedRule: DisplayRule) => {
    setRules(prev => {
      const newRules = [...prev];
      newRules[index] = updatedRule;
      return newRules;
    });
  };

  const deleteRule = (index: number) => setRules(prev => prev.filter((_, i) => i !== index));

  const sendRules = async () => {
    try {
      setSaving(true);
      await displayRuleApi.updateDisplayRules(rules);
      alert("Rules saved successfully!");
    } catch (err) {
      console.error(err);
      alert("Failed to save rules");
    } finally {
      setSaving(false);
    }
  };

  const fetchRules = useCallback(async () => {
    try {
      setLoading(true);
      const backendRules = (await displayRuleApi.getDisplayRules()).data;
      setRules(backendRules);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [displayRuleApi]);

  useEffect(() => { fetchRules(); }, [fetchRules]);

  // --- Collapsed state ---
  if (!isExpanded) {
    return (
      <button
        onClick={() => setIsExpanded(true)}
        className="fixed bottom-4 left-4 z-[1000] bg-gray-100 dark:bg-gray-800 text-gray-900 dark:text-white px-4 py-2 rounded-lg shadow-lg border border-gray-300 dark:border-gray-600 hover:bg-gray-200 dark:hover:bg-gray-700 transition-colors"
      >
        ⚙️ Settings
      </button>
    );
  }

  // --- Docking styles ---
  const containerClasses = `fixed z-[1000] flex shadow-2xl backdrop-blur-sm bg-gray-50 dark:bg-gray-900 text-gray-900 dark:text-white`;

  let sideStyles: React.CSSProperties = {};
  let borderClass = '';
  let cursorClass = '';

  if (dockSide === 'bottom') {
    sideStyles = { bottom: 0, left: 0, width: '100%', height: dimensions.height, flexDirection: 'column' };
    borderClass = 'border-t border-gray-300 dark:border-gray-600';
    cursorClass = 'cursor-row-resize h-2 w-full';
  } else if (dockSide === 'right') {
    sideStyles = { top: 0, right: 0, height: '100%', width: dimensions.width, flexDirection: 'row' };
    borderClass = 'border-l border-gray-300 dark:border-gray-600';
    cursorClass = 'cursor-col-resize w-2 h-full';
  } else {
    sideStyles = { top: 0, left: 0, height: '100%', width: dimensions.width, flexDirection: 'row-reverse' };
    borderClass = 'border-r border-gray-300 dark:border-gray-600';
    cursorClass = 'cursor-col-resize w-2 h-full';
  }

  return (
    <div style={sideStyles} className={`${containerClasses} ${borderClass}`}>
      <div
        onPointerDown={startResize}
        className={`${cursorClass} bg-transparent hover:bg-gray-200 dark:hover:bg-white/20 transition-colors shrink-0 z-50`}
      />

      <div className="flex-1 flex flex-col overflow-hidden min-w-0">
        <div className="flex justify-between items-center px-4 py-2 border-b border-gray-300 dark:border-gray-600 bg-gray-100 dark:bg-black/20 shrink-0">
          <strong className="text-base">Display Rules</strong>

          <div className="flex items-center gap-3">
            <div className="flex gap-1 border border-gray-300 dark:border-gray-600 rounded-lg p-0.5 bg-gray-100 dark:bg-gray-800/50">
              <DockButton Svg={IconDockLeft} isActive={dockSide === 'left'} onClick={() => setDockSide('left')} />
              <DockButton Svg={IconDockBottom} isActive={dockSide === 'bottom'} onClick={() => setDockSide('bottom')} />
              <DockButton Svg={IconDockRight} isActive={dockSide === 'right'} onClick={() => setDockSide('right')} />
            </div>

            <button
              onClick={() => setIsExpanded(false)}
              className="text-gray-900 dark:text-white hover:text-red-500 w-6 h-6 flex items-center justify-center rounded transition-colors"
            >
              ✕
            </button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto p-4 flex flex-col gap-2">
          {loading ? (
            <div className="text-gray-500 dark:text-gray-400 text-sm text-center py-4">Loading rules...</div>
          ) : (
            rules.map((rule, idx) => (
              <RuleRow
                key={idx}
                rule={rule}
                onChange={updated => updateRule(idx, updated)}
                onDelete={() => deleteRule(idx)}
                orientation={dockSide === 'bottom' ? 'horizontal' : 'vertical'}
              />
            ))
          )}

          <button
            onClick={addRule}
            className="cursor-pointer mt-1 w-full py-1.5 text-xs font-medium text-blue-600 hover:bg-blue-100 dark:text-blue-400 dark:hover:bg-blue-900/20 border border-dashed border-blue-300 rounded transition-colors flex items-center justify-center gap-1"
          >
            + Add Display Rule
          </button>

          <button
            onClick={sendRules}
            disabled={saving || rules.length === 0}
            className={`mt-2 w-full py-2 text-sm font-semibold rounded ${
              saving || rules.length === 0 ? 'bg-gray-300 dark:bg-gray-600 cursor-not-allowed text-gray-600 dark:text-gray-400' : 'bg-green-600 hover:bg-green-700 text-white'
            } transition-colors`}
          >
            {saving ? "Saving..." : "Save Changes"}
          </button>
        </div>
      </div>
    </div>
  );
};

export default memo(SettingsPanel);
