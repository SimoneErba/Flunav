import React, { useRef, useState, memo } from "react";

type DockSide = 'left' | 'right' | 'bottom';

// --- ICONS (Browser DevTools Style) ---

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

const DockButton = memo(({ Svg, isActive, onClick }: { Svg: React.FC<any>, isActive: boolean, onClick: () => void }) => (
  <button 
    onClick={(e) => { e.stopPropagation(); onClick(); }} 
    className={`p-1 leading-none border border-gray-500 rounded text-white cursor-pointer transition-colors ${isActive ? 'bg-white/20' : 'bg-transparent hover:bg-white/10'}`}
  >
    <Svg className="w-4 h-4" />
  </button>
));

const SettingsPanel = () => {
  const [isExpanded, setIsExpanded] = useState(false);
  const [dockSide, setDockSide] = useState<DockSide>('bottom');
  
  // --- STATE CHANGE: Store dimensions separately ---
  const [dimensions, setDimensions] = useState({ width: 350, height: 300 });

  // Refs for drag logic
  const startPos = useRef(0);
  const startDim = useRef(0); // Stores the specific dimension being resized
  const raf = useRef<number | null>(null);

  // --- RESIZE LOGIC ---
  const startResize = (e: React.PointerEvent) => {
    // Capture the starting mouse position
    startPos.current = dockSide === 'bottom' ? e.clientY : e.clientX;
    
    // Capture the current dimension based on orientation
    startDim.current = dockSide === 'bottom' ? dimensions.height : dimensions.width;

    document.addEventListener("pointermove", onResize);
    document.addEventListener("pointerup", stopResize);
    
    document.body.style.userSelect = 'none';
  };

  const onResize = (e: PointerEvent) => {
    if (raf.current) cancelAnimationFrame(raf.current);

    raf.current = requestAnimationFrame(() => {
      let delta = 0;
      
      if (dockSide === 'bottom') {
        delta = startPos.current - e.clientY; // Dragging UP increases height
      } else if (dockSide === 'right') {
        delta = startPos.current - e.clientX; // Dragging LEFT increases width
      } else {
        delta = e.clientX - startPos.current; // Dragging RIGHT increases width
      }

      const newSize = Math.max(200, startDim.current + delta);

      // Update specific dimension while keeping the other one intact
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

  // --- COLLAPSED STATE ---
  if (!isExpanded) {
    return (
      <button
        onClick={() => setIsExpanded(true)}
        className="fixed bottom-4 left-4 z-[1000] bg-gray-800/90 text-white px-4 py-2 rounded-lg shadow-lg cursor-pointer border border-gray-600 hover:bg-gray-700/90 transition-colors"
      >
        ⚙️ Settings
      </button>
    );
  }

  // --- DOCKING STYLES ---
  const containerClasses = `fixed z-[1000] bg-gray-800/90 text-white flex shadow-2xl backdrop-blur-sm`;
  
  let sideStyles: React.CSSProperties = {};
  let borderClass = '';
  let cursorClass = '';

  if (dockSide === 'bottom') {
    sideStyles = { bottom: 0, left: 0, width: '100%', height: dimensions.height, flexDirection: 'column' };
    borderClass = 'border-t border-gray-600';
    cursorClass = 'cursor-row-resize h-2 w-full';
  } else if (dockSide === 'right') {
    sideStyles = { top: 0, right: 0, height: '100%', width: dimensions.width, flexDirection: 'row' };
    borderClass = 'border-l border-gray-600';
    cursorClass = 'cursor-col-resize w-2 h-full';
  } else if (dockSide === 'left') {
    sideStyles = { top: 0, left: 0, height: '100%', width: dimensions.width, flexDirection: 'row-reverse' };
    borderClass = 'border-r border-gray-600';
    cursorClass = 'cursor-col-resize w-2 h-full';
  }

  return (
    <div style={sideStyles} className={`${containerClasses} ${borderClass}`}>
      
      {/* RESIZE HANDLE */}
      <div
        onPointerDown={startResize}
        className={`${cursorClass} bg-transparent hover:bg-white/20 transition-colors shrink-0 z-50`}
      />

      {/* MAIN CONTENT WRAPPER */}
      <div className="flex-1 flex flex-col overflow-hidden min-w-0">
        
        {/* Header Bar */}
        <div className="flex justify-between items-center px-4 py-2 border-b border-gray-600 bg-black/20 shrink-0">
          <strong className="text-base">Settings</strong>
          
          <div className="flex items-center gap-3">
            {/* Dock Controls */}
            <div className="flex gap-1 border border-gray-600 rounded-lg p-0.5 bg-gray-900/50">
              <DockButton Svg={IconDockLeft} isActive={dockSide === 'left'} onClick={() => setDockSide('left')} />
              <DockButton Svg={IconDockBottom} isActive={dockSide === 'bottom'} onClick={() => setDockSide('bottom')} />
              <DockButton Svg={IconDockRight} isActive={dockSide === 'right'} onClick={() => setDockSide('right')} />
            </div>

            {/* Close Button */}
            <button
              onClick={() => setIsExpanded(false)}
              className="text-white hover:text-red-400 w-6 h-6 flex items-center justify-center rounded transition-colors"
            >
              ✕
            </button>
          </div>
        </div>

        {/* Scrollable Content */}
        <div className="flex-1 overflow-y-auto p-4">
          <div className="flex flex-col gap-3">
            <div>
                <label className="block mb-1 text-xs font-bold text-gray-400 uppercase tracking-wide">Animation Speed</label>
                <input type="text" readOnly value="1.0x" className="w-full p-2 rounded border text-sm bg-gray-700/50 border-gray-600 text-white focus:outline-none" />
            </div>
            <div>
                <label className="block mb-1 text-xs font-bold text-gray-400 uppercase tracking-wide">Display Labels</label>
                <input type="text" readOnly value="On" className="w-full p-2 rounded border text-sm bg-gray-700/50 border-gray-600 text-white focus:outline-none" />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default memo(SettingsPanel);