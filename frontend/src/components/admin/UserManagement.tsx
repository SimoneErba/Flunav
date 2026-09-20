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
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);

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

  const handleCreate = async () => {
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
      setIsCreateModalOpen(false);
      
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
    <div className="space-y-6">
      <section className="bg-white dark:bg-gray-800 p-6 rounded-lg shadow border border-gray-200 dark:border-gray-700">
        <div className="mb-4 flex flex-col gap-4 border-b border-gray-200 pb-4 dark:border-gray-700 md:flex-row md:items-center md:justify-between">
          <h2 className="text-lg font-bold">Users</h2>
          <button onClick={() => setIsCreateModalOpen(true)} className="rounded bg-blue-600 px-3 py-2 text-sm font-semibold text-white hover:bg-blue-700">Add User</button>
        </div>
        
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
                    <button onClick={() => handleDelete(u.username)} className="rounded px-2 py-1 text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20">Delete</button>
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
      </section>

      {isCreateModalOpen && <CreateUserModal
        username={username}
        password={password}
        role={role}
        loading={loading}
        onUsernameChange={setUsername}
        onPasswordChange={setPassword}
        onRoleChange={setRole}
        onCancel={() => setIsCreateModalOpen(false)}
        onSubmit={() => void handleCreate()}
      />}
    </div>
  );
};

const CreateUserModal = ({ username, password, role, loading, onUsernameChange, onPasswordChange, onRoleChange, onCancel, onSubmit }: {
  username: string;
  password: string;
  role: CreateUserRoleEnum;
  loading: boolean;
  onUsernameChange: (username: string) => void;
  onPasswordChange: (password: string) => void;
  onRoleChange: (role: CreateUserRoleEnum) => void;
  onCancel: () => void;
  onSubmit: () => void;
}) => (
  <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" role="dialog" aria-modal="true" aria-labelledby="add-user-title">
    <form onSubmit={event => { event.preventDefault(); onSubmit(); }} className="w-full max-w-lg rounded-lg bg-white p-6 shadow-xl dark:bg-gray-800">
      <h3 id="add-user-title" className="text-lg font-bold">Add User</h3>
      <div className="mt-4 space-y-4">
        <label className="block text-sm font-medium">Username<input autoFocus required value={username} onChange={event => onUsernameChange(event.target.value)} placeholder="e.g. operator_line_1" className="mt-1 w-full rounded border border-gray-300 bg-gray-50 p-2 outline-none focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-900" /></label>
        <label className="block text-sm font-medium">Password<input type="password" required value={password} onChange={event => onPasswordChange(event.target.value)} placeholder="Set initial password" className="mt-1 w-full rounded border border-gray-300 bg-gray-50 p-2 outline-none focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-900" /></label>
        <label className="block text-sm font-medium">Role<select value={role} onChange={event => onRoleChange(event.target.value as CreateUserRoleEnum)} className="mt-1 w-full rounded border border-gray-300 bg-gray-50 p-2 outline-none focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-900"><option value={CreateUserRoleEnum.Viewer}>VIEWER (Read Only)</option><option value={CreateUserRoleEnum.Admin}>ADMIN (Editor)</option><option value={CreateUserRoleEnum.Superadmin}>SUPERADMIN (Full Access)</option></select></label>
      </div>
      <div className="mt-6 flex justify-end gap-2"><button type="button" onClick={onCancel} className="rounded bg-gray-100 px-3 py-2 text-sm font-semibold dark:bg-gray-700">Cancel</button><button type="submit" disabled={loading} className="rounded bg-blue-600 px-3 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50">{loading ? "Creating..." : "Add User"}</button></div>
    </form>
  </div>
);
