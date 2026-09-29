import React, { useRef, useState, useEffect, useCallback, memo } from "react";
import { useApi } from "../hooks/useApi";
import { DisplayRule, DisplayRuleColorResult, DisplayRuleDataTypeEnum, DisplayRuleOperatorEnum } from "../api-client";
import toast from "react-hot-toast";
import { RuleRow } from "./editors/RuleRow";
import { v4 as uuidv4 } from 'uuid';
import { PathAnalytics } from "./analytics/PathAnalytics";
import { OperationalAnalytics } from "./analytics/OperationalAnalytics";
import { AnomalyFeed } from "./analytics/AnomalyFeed";
import { useAuth } from "../context/auth.context";
import { AdminCommands } from "./admin/AdminCommands";

type ExtendedDisplayRule = DisplayRule & { _localId: string };
type TabType = 'settings' | 'charts' | 'commands';
type DockSide = 'left' | 'right' | 'bottom';
type IconProps = React.SVGProps<SVGSVGElement>;

// --- ICONS ---
const IconDockLeft = (props: IconProps) => (
  <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><line x1="9" y1="3" x2="9" y2="21" />
  </svg>
);
const IconDockBottom = (props: IconProps) => (
  <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><line x1="3" y1="15" x2="21" y2="15" />
  </svg>
);
const IconDockRight = (props: IconProps) => (
  <svg {...props} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><line x1="15" y1="3" x2="15" y2="21" />
  </svg>
);

const DockButton = memo(({ Svg, isActive, onClick }: { Svg: React.FC<IconProps>, isActive: boolean, onClick: () => void }) => (
  <button
    onClick={(e) => { e.stopPropagation(); onClick(); }}
    className={`p-1 leading-none border border-gray-500 rounded text-gray-900 dark:text-white cursor-pointer transition-colors ${isActive ? 'bg-gray-200 dark:bg-white/20' : 'bg-transparent hover:bg-gray-100 dark:hover:bg-white/10'}`}
  >
    <Svg className="w-4 h-4" />
  </button>
));

interface SettingsPanelProps {
  onColorsUpdated: (result: DisplayRuleColorResult) => void;
}

const SettingsPanel = ({ onColorsUpdated }: SettingsPanelProps) => {
  const { user } = useAuth();
  const [isExpanded, setIsExpanded] = useState(false);
  const [dockSide, setDockSide] = useState<DockSide>('bottom');
  const [rules, setRules] = useState<ExtendedDisplayRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [draggedIndex, setDraggedIndex] = useState<number | null>(null);
  const [activeTab, setActiveTab] = useState<TabType>('settings');

  const { displayRuleApi } = useApi();
  const canAccessCommands = user?.role === 'ADMIN' || user?.role === 'SUPERADMIN';

  // --- Panel ref for click-outside ---
  const panelRef = useRef<HTMLDivElement>(null);
  const outsidePointerRef = useRef<{ pointerId: number; x: number; y: number; didDrag: boolean } | null>(null);

  // --- Dimensions ---
  const [dimensions, setDimensions] = useState({ width: 350, height: 300 });
  const startPos = useRef(0);
  const startDim = useRef(0);
  const raf = useRef<number | null>(null);

  // --- Click outside to close ---
  useEffect(() => {
    if (!isExpanded) return;

    const handlePointerDown = (e: PointerEvent) => {
      outsidePointerRef.current = panelRef.current?.contains(e.target as Node)
        ? null
        : { pointerId: e.pointerId, x: e.clientX, y: e.clientY, didDrag: false };
    };

    const handlePointerMove = (e: PointerEvent) => {
      const pointer = outsidePointerRef.current;
      if (!pointer || pointer.pointerId !== e.pointerId || pointer.didDrag) return;

      const deltaX = e.clientX - pointer.x;
      const deltaY = e.clientY - pointer.y;
      if (deltaX * deltaX + deltaY * deltaY > 16) {
        pointer.didDrag = true;
      }
    };

    const handlePointerUp = (e: PointerEvent) => {
      const pointer = outsidePointerRef.current;
      if (!pointer || pointer.pointerId !== e.pointerId) return;

      outsidePointerRef.current = null;
      if (pointer.didDrag) return;

      (window as Window & { __suppressNextGraphStageClickUntil?: number }).__suppressNextGraphStageClickUntil = Date.now() + 300;
      setIsExpanded(false);
    };

    const handlePointerCancel = (e: PointerEvent) => {
      if (outsidePointerRef.current?.pointerId === e.pointerId) {
        outsidePointerRef.current = null;
      }
    };

    const timer = setTimeout(() => {
      document.addEventListener('pointerdown', handlePointerDown, true);
      document.addEventListener('pointermove', handlePointerMove, true);
      document.addEventListener('pointerup', handlePointerUp, true);
      document.addEventListener('pointercancel', handlePointerCancel, true);
    }, 100);

    return () => {
      clearTimeout(timer);
      outsidePointerRef.current = null;
      document.removeEventListener('pointerdown', handlePointerDown, true);
      document.removeEventListener('pointermove', handlePointerMove, true);
      document.removeEventListener('pointerup', handlePointerUp, true);
      document.removeEventListener('pointercancel', handlePointerCancel, true);
    };
  }, [isExpanded]);

  useEffect(() => {
    document.body.dataset.liveInteractionsOpen = isExpanded ? 'true' : 'false';

    return () => {
      delete document.body.dataset.liveInteractionsOpen;
    };
  }, [isExpanded]);

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
      let delta;
      if (dockSide === 'bottom') delta = startPos.current - e.clientY;
      else if (dockSide === 'left') delta = e.clientX - startPos.current;
      else delta = startPos.current - e.clientX;
      const newSize = Math.max(200, startDim.current + delta);
      setDimensions(prev => ({ ...prev, [dockSide === 'bottom' ? 'height' : 'width']: newSize }));
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
      { _localId: `rule_${Date.now()}`, fieldName: '', dataType: DisplayRuleDataTypeEnum.String, operator: DisplayRuleOperatorEnum.Equal, value: '' }
    ]);
  };

  const updateRule = (index: number, updatedRule: DisplayRule) => {
    setRules(prev => {
      const newRules = [...prev];
      newRules[index] = { ...updatedRule, _localId: prev[index]._localId };
      return newRules;
    });
  };

  const deleteRule = (index: number) => setRules(prev => prev.filter((_, i) => i !== index));

  const sendRules = async () => {
    try {
      setSaving(true);
      const rulesToSend = rules.map((r, index) => {
        return {
          fieldName: r.fieldName,
          dataType: r.dataType,
          operator: r.operator,
          value: r.value,
          secondOperator: r.secondOperator,
          secondValue: r.secondValue,
          color: r.color,
          borderColor: r.borderColor,
          borderWidth: r.borderWidth,
          priority: index + 1
        };
      });
      const result = (await displayRuleApi.updateDisplayRules(rulesToSend)).data;
      onColorsUpdated(result);
      toast.success("Rules saved successfully!");
    } catch (err) {
      console.error(err);
      toast.error("Failed to save rules");
    } finally {
      setSaving(false);
    }
  };

  const fetchRules = useCallback(async () => {
    try {
      setLoading(true);
      const backendRules = (await displayRuleApi.getDisplayRules()).data;
      backendRules.sort((a, b) => (a.priority || 0) - (b.priority || 0));
      setRules(backendRules.map(r => ({ ...r, _localId: uuidv4() })));
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [displayRuleApi]);

  useEffect(() => { fetchRules(); }, [fetchRules]);

  // --- Drag & Drop ---
  const handleDragStart = (index: number) => setDraggedIndex(index);

  const handleDragOver = (e: React.DragEvent, index: number) => {
    e.preventDefault();
    if (draggedIndex === null || draggedIndex === index) return;
    setRules(prev => {
      const newRules = [...prev];
      const draggedItem = newRules[draggedIndex];
      newRules.splice(draggedIndex, 1);
      newRules.splice(index, 0, draggedItem);
      return newRules;
    });
    setDraggedIndex(index);
  };

  const handleDragEnd = () => setDraggedIndex(null);

  // --- Collapsed state ---
  if (!isExpanded) {
    return (
      <button
        onClick={() => setIsExpanded(true)}
        className="fixed bottom-4 left-4 z-[1000] bg-gray-100 dark:bg-gray-800 text-gray-900 dark:text-white px-4 py-2 rounded-lg shadow-lg border border-gray-300 dark:border-gray-600 hover:bg-gray-200 dark:hover:bg-gray-700 transition-colors"
      >
        ⚙️ Live interactions
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

  const isSide = dockSide === 'left' || dockSide === 'right';
  const panelTitle = activeTab === 'settings'
    ? 'Display Rules'
    : activeTab === 'charts'
      ? 'Analytics'
      : 'Commands';

  return (
    // 👇 panelRef goes on the outermost div — the one with sideStyles
    <div ref={panelRef} style={sideStyles} className={`${containerClasses} ${borderClass}`}>

      {/* Resize handle */}
      <div
        onPointerDown={startResize}
        className={`${cursorClass} bg-transparent hover:bg-gray-200 dark:hover:bg-white/20 transition-colors shrink-0 z-50`}
      />

      <div className="flex-1 flex flex-col overflow-hidden min-w-0 relative">

        {/* Header */}
        {isSide ? (
          // --- Side panels: two rows ---
          <div className="border-b border-gray-300 dark:border-gray-600 bg-gray-100 dark:bg-black/20 shrink-0">
            {/* Row 1: Title + Close */}
            <div className="flex justify-between items-center px-4 py-2 border-b border-gray-200 dark:border-gray-700">
              <strong className="text-base">{panelTitle}</strong>
              <button
                onClick={() => setIsExpanded(false)}
                className="text-gray-500 dark:text-gray-400 hover:text-red-500 w-6 h-6 flex items-center justify-center rounded transition-colors"
              >
                ✕
              </button>
            </div>
            {/* Row 2: Tab buttons + Dock buttons */}
            <div className="flex items-center justify-between px-3 py-1.5 gap-2">
              <div className="flex items-center gap-1">
                <button
                  onClick={() => setActiveTab('settings')}
                  className={`px-2.5 py-1 rounded-lg text-xs font-medium transition-colors ${
                    activeTab === 'settings'
                      ? 'bg-blue-600 text-white dark:bg-blue-500'
                      : 'bg-gray-200 text-gray-700 dark:bg-gray-700 dark:text-gray-300 hover:bg-gray-300 dark:hover:bg-gray-600'
                  }`}
                >
                  Settings
                </button>
                <button
                  onClick={() => setActiveTab('charts')}
                  className={`px-2.5 py-1 rounded-lg text-xs font-medium transition-colors ${
                    activeTab === 'charts'
                      ? 'bg-blue-600 text-white dark:bg-blue-500'
                      : 'bg-gray-200 text-gray-700 dark:bg-gray-700 dark:text-gray-300 hover:bg-gray-300 dark:hover:bg-gray-600'
                  }`}
                >
                  Charts
                </button>
                {canAccessCommands && (
                  <button
                    onClick={() => setActiveTab('commands')}
                    className={`px-2.5 py-1 rounded-lg text-xs font-medium transition-colors ${
                      activeTab === 'commands'
                        ? 'bg-blue-600 text-white dark:bg-blue-500'
                        : 'bg-gray-200 text-gray-700 dark:bg-gray-700 dark:text-gray-300 hover:bg-gray-300 dark:hover:bg-gray-600'
                    }`}
                  >
                    Commands
                  </button>
                )}
              </div>
              <div className="flex gap-1 border border-gray-300 dark:border-gray-600 rounded-lg p-0.5 bg-gray-100 dark:bg-gray-800/50">
                <DockButton Svg={IconDockLeft} isActive={dockSide === 'left'} onClick={() => setDockSide('left')} />
                <DockButton Svg={IconDockBottom} isActive={false} onClick={() => setDockSide('bottom')} />
                <DockButton Svg={IconDockRight} isActive={dockSide === 'right'} onClick={() => setDockSide('right')} />
              </div>
            </div>
          </div>
        ) : (
          // --- Bottom panel: single row ---
          <div className="flex justify-between items-center px-4 py-2 border-b border-gray-300 dark:border-gray-600 bg-gray-100 dark:bg-black/20 shrink-0">
            <strong className="text-base">{panelTitle}</strong>
            <div className="flex items-center gap-3">
              <button
                onClick={() => setActiveTab('settings')}
                className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${
                  activeTab === 'settings'
                    ? 'bg-blue-600 text-white dark:bg-blue-500'
                    : 'bg-gray-200 text-gray-700 dark:bg-gray-700 dark:text-gray-300 hover:bg-gray-300 dark:hover:bg-gray-600'
                }`}
              >
                Settings
              </button>
              <button
                onClick={() => setActiveTab('charts')}
                className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${
                  activeTab === 'charts'
                    ? 'bg-blue-600 text-white dark:bg-blue-500'
                    : 'bg-gray-200 text-gray-700 dark:bg-gray-700 dark:text-gray-300 hover:bg-gray-300 dark:hover:bg-gray-600'
                }`}
              >
                Charts
              </button>
              {canAccessCommands && (
                <button
                  onClick={() => setActiveTab('commands')}
                  className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${
                    activeTab === 'commands'
                      ? 'bg-blue-600 text-white dark:bg-blue-500'
                      : 'bg-gray-200 text-gray-700 dark:bg-gray-700 dark:text-gray-300 hover:bg-gray-300 dark:hover:bg-gray-600'
                  }`}
                >
                  Commands
                </button>
              )}
              <div className="flex gap-1 border border-gray-300 dark:border-gray-600 rounded-lg p-0.5 bg-gray-100 dark:bg-gray-800/50">
                <DockButton Svg={IconDockLeft} isActive={false} onClick={() => setDockSide('left')} />
                <DockButton Svg={IconDockBottom} isActive={dockSide === 'bottom'} onClick={() => setDockSide('bottom')} />
                <DockButton Svg={IconDockRight} isActive={false} onClick={() => setDockSide('right')} />
              </div>
              <button
                onClick={() => setIsExpanded(false)}
                className="text-gray-900 dark:text-white hover:text-red-500 w-6 h-6 flex items-center justify-center rounded transition-colors"
              >
                ✕
              </button>
            </div>
          </div>
        )}

        {/* Content */}
        {activeTab === "settings" ? (
          <div className="flex-1 overflow-y-auto p-4 flex flex-col gap-2">
            {loading ? (
              <div className="text-gray-500 dark:text-gray-400 text-sm text-center py-4">Loading rules...</div>
            ) : (
              rules.map((rule, idx) => (
                <div
                  key={rule._localId}
                  draggable
                  onDragStart={() => handleDragStart(idx)}
                  onDragOver={(e) => handleDragOver(e, idx)}
                  onDragEnd={handleDragEnd}
                  className={`transition-opacity ${draggedIndex === idx ? 'opacity-50' : 'opacity-100'}`}
                >
                  <RuleRow
                    rule={rule}
                    onChange={updated => updateRule(idx, updated)}
                    onDelete={() => deleteRule(idx)}
                    orientation={dockSide === 'bottom' ? 'horizontal' : 'vertical'}
                  />
                </div>
              ))
            )}

            <div className={`flex gap-2 ${dockSide === 'bottom' ? 'flex-row' : 'flex-col'}`}>
              <button
                onClick={addRule}
                className="cursor-pointer mt-1 w-full py-1.5 text-xs font-medium text-blue-600 hover:bg-blue-100 dark:text-blue-400 dark:hover:bg-blue-900/20 border border-dashed border-blue-300 rounded transition-colors flex items-center justify-center gap-1"
              >
                + Add Display Rule
              </button>

              <button
                onClick={sendRules}
                disabled={saving || rules.length === 0}
                className={`mt-1 w-full py-2 text-sm font-semibold rounded ${
                  saving || rules.length === 0
                    ? 'bg-gray-300 dark:bg-gray-600 cursor-not-allowed text-gray-600 dark:text-gray-400'
                    : 'bg-green-600 hover:bg-green-700 text-white'
                } transition-colors`}
              >
                {saving ? "Saving..." : "Save Changes"}
              </button>
            </div>
          </div>
        ) : activeTab === "charts" ? (
          <div className="flex-1 overflow-y-auto p-4 flex flex-col gap-4 bg-gray-50 dark:bg-gray-900">
            <h3 className="text-lg font-semibold text-gray-900 dark:text-white">Analytics</h3>
            <div className="bg-white dark:bg-gray-800 rounded-lg shadow p-4">
              <PathAnalytics />
            </div>
            <OperationalAnalytics />
            <AnomalyFeed />
          </div>
        ) : (
          <div className="flex-1 overflow-y-auto p-4 bg-gray-50 dark:bg-gray-900">
            <div className="bg-white dark:bg-gray-800 rounded-lg shadow p-4">
              <AdminCommands embedded dockSide={dockSide} />
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default memo(SettingsPanel);
