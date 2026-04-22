import React, { useState, useEffect, useCallback } from "react";
import { useApi } from "../../hooks/useApi";
import toast from "react-hot-toast";
import { CreateUserRoleEnum, UserResponse } from "../../api-client";
import type { AxiosError } from "axios";

export const UserManagement = () => {
  const { userApi } = useApi();
  const [users, setUsers] = useState<UserResponse[]>([]);
  const [loading, setLoading] = useState(false);

  // Form State
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [role, setRole] = useState<CreateUserRoleEnum>(CreateUserRoleEnum.Viewer);

  const fetchUsers = useCallback(async () => {
    try {
      const res = await userApi.getAllUsers();
      setUsers(res.data);
    } catch (error) {
      console.error("Failed to fetch users", error);
      // Don't toast here to avoid spamming if permission denied on mount
    }
  }, [userApi]);

  useEffect(() => {
    fetchUsers();
  }, [fetchUsers]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username || !password) return;

    setLoading(true);
    try {
      await userApi.createUser({
        username,
        password,
        role
      });
      toast.success(`User ${username} created!`);
      
      // Reset form
      setUsername("");
      setPassword("");
      setRole(CreateUserRoleEnum.Viewer);
      
      // Refresh list
      fetchUsers();
    } catch (error) {
      const axiosError = error as AxiosError<{ message?: string }>;
      console.error(error);
      toast.error("Failed to create user. " + (axiosError.response?.data?.message || ""));
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async (usernameToDelete: string) => {
    if (!window.confirm(`Are you sure you want to delete ${usernameToDelete}?`)) return;
    
    try {
      await userApi.deleteUser(usernameToDelete);
      toast.success("User deleted");
      fetchUsers();
    } catch {
      toast.error("Failed to delete user");
    }
  };

  return (
    <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
      
      {/* LEFT COLUMN: CREATE USER FORM */}
      <div className="bg-white dark:bg-gray-800 p-6 rounded-lg shadow border border-gray-200 dark:border-gray-700 h-fit">
        <h2 className="text-lg font-bold mb-4 border-b border-gray-200 dark:border-gray-700 pb-2">Register New User</h2>
        <form onSubmit={handleCreate} className="flex flex-col gap-4">
          
          <div>
            <label className="block text-sm font-medium mb-1 text-gray-600 dark:text-gray-400">Username</label>
            <input 
              type="text" 
              value={username}
              onChange={e => setUsername(e.target.value)}
              className="w-full p-2 rounded border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 focus:ring-2 focus:ring-blue-500 outline-none"
              placeholder="e.g. operator_line_1"
              required
            />
          </div>

          <div>
            <label className="block text-sm font-medium mb-1 text-gray-600 dark:text-gray-400">Password</label>
            <input 
              type="password" 
              value={password}
              onChange={e => setPassword(e.target.value)}
              className="w-full p-2 rounded border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 focus:ring-2 focus:ring-blue-500 outline-none"
              placeholder="Set initial password"
              required
            />
          </div>

          <div>
            <label className="block text-sm font-medium mb-1 text-gray-600 dark:text-gray-400">Role</label>
            <select 
              value={role}
              onChange={e => setRole(e.target.value as CreateUserRoleEnum)}
              className="w-full p-2 rounded border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 focus:ring-2 focus:ring-blue-500 outline-none"
            >
              <option value={CreateUserRoleEnum.Viewer}>VIEWER (Read Only)</option>
              <option value={CreateUserRoleEnum.Admin}>ADMIN (Editor)</option>
              <option value={CreateUserRoleEnum.Superadmin}>SUPERADMIN (Full Access)</option>
            </select>
          </div>

          <button 
            type="submit" 
            disabled={loading}
            className="mt-2 w-full py-2 bg-blue-600 hover:bg-blue-700 text-white font-bold rounded transition-colors disabled:opacity-50"
          >
            {loading ? "Creating..." : "Create User"}
          </button>
        </form>
      </div>

      {/* RIGHT COLUMN: USER LIST */}
      <div className="lg:col-span-2 bg-white dark:bg-gray-800 p-6 rounded-lg shadow border border-gray-200 dark:border-gray-700">
        <h2 className="text-lg font-bold mb-4 border-b border-gray-200 dark:border-gray-700 pb-2">Existing Users</h2>
        
        <div className="overflow-x-auto">
          <table className="w-full text-sm text-left">
            <thead className="text-xs text-gray-500 uppercase bg-gray-50 dark:bg-gray-700/50">
              <tr>
                <th className="px-4 py-3">Username</th>
                <th className="px-4 py-3">Role</th>
                <th className="px-4 py-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
              {users.map((u) => (
                <tr key={u.username} className="hover:bg-gray-50 dark:hover:bg-gray-700/30">
                  <td className="px-4 py-3 font-medium">{u.username}</td>
                  <td className="px-4 py-3">
                    <span className={`
                      px-2 py-1 rounded text-xs font-bold
                      ${u.role === 'SUPERADMIN' ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300' : ''}
                      ${u.role === 'ADMIN' ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300' : ''}
                      ${u.role === 'VIEWER' ? 'bg-gray-100 text-gray-700 dark:bg-gray-700 dark:text-gray-300' : ''}
                    `}>
                      {u.role}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    <button 
                      onClick={() => handleDelete(u.username)}
                      className="text-red-500 hover:text-red-700 hover:bg-red-50 dark:hover:bg-red-900/20 p-1.5 rounded transition-colors"
                      title="Delete User"
                    >
                      🗑️
                    </button>
                  </td>
                </tr>
              ))}
              {users.length === 0 && (
                <tr>
                  <td colSpan={3} className="px-4 py-8 text-center text-gray-500 italic">
                    No users found (or you don't have permission to view them).
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

    </div>
  );
};
