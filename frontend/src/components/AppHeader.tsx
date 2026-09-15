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

  return (
    <header className="
      relative
      min-h-16 px-4 py-2 gap-4
      bg-white/90 dark:bg-gray-900/90 backdrop-blur-md
      border-b border-gray-200 dark:border-gray-800
      flex items-center
      shrink-0 z-50 sticky top-0
      transition-all duration-300
    ">
      
      {/* --- LEFT SECTION --- */}
      <div className="relative z-20 flex items-center gap-3 flex-1 min-w-0 overflow-x-auto">
        {/* Logo Area */}
        <div className="flex items-center gap-3 shrink-0">
          {/* Increased to h-12 (48px) */}
          <img 
            src="/logo.svg" 
            alt="Logo" 
            className="h-12 w-auto object-contain" 
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
      <div className="relative z-10 flex shrink-0 items-center">
        {centerContent}
      </div>

      {/* --- RIGHT SECTION --- */}
      <div className="relative z-20 flex min-w-0 flex-1 items-center justify-end gap-2 pl-2">
        {rightActions && (
          <div className="flex items-center gap-2 pr-1 shrink-0">
            {rightActions}
          </div>
        )}

        <ThemeToggle />

        {user && (
          <div className="flex items-center gap-3 pl-4 ml-2 border-l border-gray-200 dark:border-gray-700">
            <div className="text-right hidden md:block cursor-default">
              <div className="text-sm font-semibold text-gray-800 dark:text-gray-100 leading-none">
                {user.username}
              </div>
              <div className="text-[10px] font-bold text-gray-500 uppercase tracking-wide mt-1">
                {user.role}
              </div>
            </div>
            
            <button 
              onClick={logout}
              className="group p-2 rounded-lg text-gray-400 hover:text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20 transition-all"
              title="Logout"
            >
              <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
            </button>
          </div>
        )}
      </div>
    </header>
  );
};
