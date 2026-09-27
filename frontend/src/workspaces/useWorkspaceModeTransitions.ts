import { useCallback, useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import toast from 'react-hot-toast';
import type { OperationalMode } from '../components/AppNavigation';
import { useSimulationContext } from '../context/simulation.context';

type Mode = OperationalMode | 'design';

export const useWorkspaceModeTransitions = (
  isWhatIf: boolean,
  setIsSelectingDate: (value: boolean) => void,
  openReplay: () => void,
  returnToLive: () => Promise<void>,
) => {
  const { activeSimulation, designMode, setDesignMode, enterWhatIf, exitWhatIf } = useSimulationContext();
  const [searchParams, setSearchParams] = useSearchParams();
  const handledRequest = useRef<string | null>(null);

  /** Navigation and URL requests enter the same mode transition path. */
  const selectMode = useCallback((mode: Mode, fromUrl = false) => {
    if (mode === 'live') {
      setIsSelectingDate(false);
      setDesignMode(false);
      if (isWhatIf) void exitWhatIf().catch(() => toast.error('Could not exit What If'));
      else if (activeSimulation) void returnToLive();
    } else if (mode === 'replay') {
      setDesignMode(false);
      openReplay();
    } else if (mode === 'what-if') {
      setIsSelectingDate(false);
      setDesignMode(false);
      if (!isWhatIf) void enterWhatIf().catch(() => toast.error('Could not start What If'));
    } else if (mode === 'design' && !activeSimulation) {
      setDesignMode(fromUrl || !designMode);
    }
  }, [activeSimulation, designMode, enterWhatIf, exitWhatIf, isWhatIf, openReplay,
    returnToLive, setDesignMode, setIsSelectingDate]);

  useEffect(() => {
    const mode = searchParams.get('mode');
    if (!mode) {
      handledRequest.current = null;
      return;
    }
    if (!['live', 'replay', 'what-if', 'design'].includes(mode)) return;
    const request = searchParams.toString();
    if (handledRequest.current === request) return;
    handledRequest.current = request;
    setSearchParams({}, { replace: true });
    selectMode(mode as Mode, true);
  }, [searchParams, selectMode, setSearchParams]);

  return {
    selectLive: () => selectMode('live'),
    selectReplay: () => selectMode('replay'),
    selectWhatIf: () => selectMode('what-if'),
    selectDesign: () => selectMode('design'),
  };
};
