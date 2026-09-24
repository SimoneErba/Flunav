import React from "react";
import { ThemeToggle } from "./theme.toggle";
import { useAuth } from ".././context/auth.context";

interface AppHeaderProps {
  centerContent?: React.ReactNode;
  leftActions?: React.ReactNode;
  rightActions?: React.ReactNode;
}

export const AppHeader = ({ centerContent, leftActions, rightActions }: AppHeaderProps) => {
  const { user, logout } = useAuth();
  const initials = user?.username
    .split(/\s+/)
    .map(part => part[0])
    .join('')
    .slice(0, 2)
    .toUpperCase();

  return (
    <header className="
      relative
      h-[52px] px-3 gap-3
      bg-white/90 dark:bg-gray-900/90 backdrop-blur-md
      border-b border-gray-200 dark:border-gray-800
      flex items-center
      shrink-0 z-[3000] sticky top-0
      transition-all duration-300
    ">
      
      {/* --- LEFT SECTION --- */}
      <div className="relative z-20 flex min-w-0 items-center gap-3 overflow-visible">
        {/* Logo Area */}
        <div className="flex shrink-0 items-center gap-3">
          <img
            src="/logo.svg"
            alt="Flunav"
            className="h-10 w-auto object-contain"
          />
        </div>

        {/* Context Actions (Settings, Back, etc.) */}
        {leftActions && (
          <div className="flex items-center gap-2 pl-2 border-l border-gray-200 dark:border-gray-700 shrink-0">
            {leftActions}
          </div>
        )}
      </div>

      {/* --- CENTER SECTION --- */}
      <div className="relative z-10 flex min-w-0 flex-1 items-center justify-center">
        {centerContent}
      </div>

      {/* --- RIGHT SECTION --- */}
      <div className="relative z-20 flex shrink-0 items-center justify-end gap-1 pl-1">
        {rightActions && (
          <div className="flex shrink-0 items-center gap-1 pr-1">
            {rightActions}
          </div>
        )}

        <ThemeToggle />

        {user && (
          <details className="group relative ml-1 border-l border-gray-200 pl-2 dark:border-gray-700">
            <summary className="flex cursor-pointer list-none items-center gap-1 rounded-md p-1 text-sm font-semibold text-gray-700 hover:bg-gray-100 dark:text-gray-200 dark:hover:bg-gray-800 [&::-webkit-details-marker]:hidden" aria-label="Open user menu">
              <span className="flex h-7 w-7 items-center justify-center rounded-full bg-blue-600 text-xs font-bold text-white">{initials}</span>
              <span className="text-[10px] transition-transform group-open:rotate-180" aria-hidden="true">▾</span>
            </summary>
            <div className="absolute right-0 top-full z-50 mt-2 w-52 rounded-lg border border-gray-200 bg-white p-2 shadow-xl dark:border-gray-700 dark:bg-gray-900">
              <div className="border-b border-gray-100 px-2 pb-2 dark:border-gray-800">
                <div className="text-sm font-semibold text-gray-900 dark:text-white">{user.username}</div>
                <div className="mt-0.5 text-xs font-medium uppercase tracking-wide text-gray-500">{user.role}</div>
              </div>
              <button type="button" onClick={logout} className="mt-1 w-full rounded-md px-2 py-2 text-left text-sm font-medium text-red-600 hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-950/30">
                Sign out
              </button>
            </div>
          </details>
        )}
      </div>
    </header>
  );
};
