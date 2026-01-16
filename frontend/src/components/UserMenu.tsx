import { useAuth } from "../context/auth.context";

const UserMenu = () => {
  const { user, logout } = useAuth();
  
  if (!user) return null;

  return (
    <div className="flex items-center gap-3 ml-2 pl-2 border-l border-gray-300 dark:border-gray-600">
      <div className="text-right hidden md:block">
        <div className="text-xs font-bold text-gray-900 dark:text-white">{user.username}</div>
        <div className="text-[10px] text-gray-500 uppercase">{user.role}</div>
      </div>
      <button 
        onClick={logout}
        className="p-2 text-gray-500 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20 rounded-full transition-colors"
        title="Logout"
      >
        <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
      </button>
    </div>
  );
};