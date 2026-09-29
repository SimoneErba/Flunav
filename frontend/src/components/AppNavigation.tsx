import { useNavigate } from 'react-router-dom';

import { useAuth } from '../context/auth.context';

export type OperationalMode = 'live' | 'replay' | 'what-if' | 'simulations';

interface AppNavigationProps {
  activeMode?: OperationalMode;
  designMode?: boolean;
  disabled?: boolean;
  onLive?: () => void;
  onReplay?: () => void;
  onWhatIf?: () => void;
  onDesignSystem?: () => void;
}

const modeButtonClass = (active: boolean) => `
  flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-semibold transition-colors
  disabled:cursor-not-allowed disabled:opacity-40
  ${active
    ? 'bg-blue-600 text-white shadow-sm'
    : 'text-gray-600 hover:bg-gray-100 hover:text-gray-950 dark:text-gray-300 dark:hover:bg-gray-800 dark:hover:text-white'}
`;

export const AppNavigation = ({
  activeMode,
  designMode = false,
  disabled = false,
  onLive,
  onReplay,
  onWhatIf,
  onDesignSystem,
}: AppNavigationProps) => {
  const navigate = useNavigate();
  const { user } = useAuth();
  const canAccessUsers = user?.role === 'SUPERADMIN';
  const canAccessSetup = user?.role === 'ADMIN' || user?.role === 'SUPERADMIN';

  const runOrNavigate = (action: (() => void) | undefined, fallback: string) => {
    if (action) action();
    else navigate(fallback);
  };

  return (
    <div className="flex items-center gap-2">
      <nav className="flex items-center gap-0.5 rounded-lg bg-gray-100/80 p-0.5 dark:bg-gray-800/80" aria-label="Operational modes">
        <button type="button" className={modeButtonClass(activeMode === 'live')} disabled={disabled} onClick={() => runOrNavigate(onLive, '/live?mode=live')}>
          <span className="h-2 w-2 rounded-full bg-emerald-500" aria-hidden="true" />
          Live
        </button>
        <button type="button" className={modeButtonClass(activeMode === 'replay')} disabled={disabled} onClick={() => runOrNavigate(onReplay, '/live?mode=replay')}>
          <span aria-hidden="true">↶</span>
          Replay
        </button>
        <button type="button" className={modeButtonClass(activeMode === 'what-if')} disabled={disabled} onClick={() => runOrNavigate(onWhatIf, '/live?mode=what-if')}>
          <span aria-hidden="true">◇</span>
          What-if
        </button>
        <button type="button" className={modeButtonClass(activeMode === 'simulations')} disabled={disabled || !canAccessSetup} onClick={() => navigate('/multi-simulations')}>
          <span aria-hidden="true">▦</span>
          Simulations
        </button>
      </nav>

      <details className="group relative">
        <summary className={`flex cursor-pointer list-none items-center gap-1.5 rounded-md px-2.5 py-1.5 text-sm font-semibold transition-colors [&::-webkit-details-marker]:hidden ${designMode ? 'bg-blue-50 text-blue-700 dark:bg-blue-950/40 dark:text-blue-300' : 'text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800'}`}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
            <circle cx="12" cy="12" r="3" />
            <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6v.2h-4V21a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3 14H2.8v-4H3a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1a1.7 1.7 0 0 0 1.9.3A1.7 1.7 0 0 0 10 3V2.8h4V3a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.6 1h.2v4H21a1.7 1.7 0 0 0-1.6 1Z" />
          </svg>
          Setup
          <span className="text-[10px] transition-transform group-open:rotate-180" aria-hidden="true">▾</span>
        </summary>
        <div className="absolute right-0 top-full z-[3010] mt-2 w-52 overflow-hidden rounded-lg border border-gray-200 bg-white py-1 shadow-xl dark:border-gray-700 dark:bg-gray-900">
          <MenuButton label="Scenario library" onClick={() => navigate('/scenarios')} />
          {canAccessSetup && <MenuButton label="Mappings" onClick={() => navigate('/admin/destination-mappings')} />}
          {canAccessSetup && <MenuButton label="Sensors" onClick={() => navigate('/admin/sensors')} />}
          {canAccessUsers && <MenuButton label="Users" onClick={() => navigate('/admin')} />}
          {canAccessSetup && <MenuButton label="BI / Analytics" onClick={() => navigate('/admin/bi')} />}
          <div className="my-1 border-t border-gray-100 dark:border-gray-800" />
          <MenuButton label={designMode ? 'Exit Design System' : 'Design System'} onClick={() => runOrNavigate(onDesignSystem, '/live?mode=design')} />
        </div>
      </details>

      <button
        type="button"
        onClick={() => navigate('/assistant')}
        className="rounded-md p-2 text-indigo-600 transition-colors hover:bg-indigo-50 dark:text-indigo-300 dark:hover:bg-indigo-950/40"
        title="Assistant"
        aria-label="Open assistant"
      >
        <span className="text-base leading-none" aria-hidden="true">✨</span>
      </button>
    </div>
  );
};

const MenuButton = ({ label, onClick }: { label: string; onClick: () => void }) => (
  <button
    type="button"
    onClick={onClick}
    className="block w-full px-3 py-2 text-left text-sm text-gray-700 hover:bg-gray-100 dark:text-gray-200 dark:hover:bg-gray-800"
  >
    {label}
  </button>
);
