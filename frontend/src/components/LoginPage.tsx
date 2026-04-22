import React, { useState } from "react";
import { useAuth } from "../context/auth.context";
import { useNavigate } from "react-router-dom";
import toast from "react-hot-toast";
import { useApi } from "../hooks/useApi";

interface LoginResponse {
  token?: string;
  refreshToken?: string;
  role?: string;
}

export const LoginPage = () => {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  
  const { login } = useAuth();
  const { authApi } = useApi();
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);

    try {
      const response = await authApi.login({ username, password });
      const { token, refreshToken, role } = response.data as LoginResponse;
      
      if (!token || !refreshToken || !role) {
        throw new Error("Missing authentication payload");
      }

      login(token, refreshToken, username, role);
      toast.success(`Welcome back, ${username}!`);
      navigate("/"); // Redirect alla home
    } catch (error) {
      console.error(error);
      toast.error("Invalid credentials");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-100 dark:bg-[#121212] text-gray-900 dark:text-white transition-colors">
      <div className="bg-white dark:bg-gray-800 p-8 rounded-xl shadow-2xl w-full max-w-md border border-gray-200 dark:border-gray-700">
        
        <div className="text-center mb-8">
          <img src="/logo.svg" alt="Logo" className="h-12 mx-auto mb-4" />
          <h2 className="text-2xl font-bold">Sign In</h2>
          <p className="text-gray-500 dark:text-gray-400 text-sm">Enter your credentials to access the Digital Twin</p>
        </div>

        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <div>
            <label className="block text-sm font-medium mb-1 text-gray-700 dark:text-gray-300">Username</label>
            <input
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="w-full p-2.5 rounded-lg border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 focus:ring-2 focus:ring-blue-500 outline-none transition-all"
              placeholder="admin"
              required
            />
          </div>
          
          <div>
            <label className="block text-sm font-medium mb-1 text-gray-700 dark:text-gray-300">Password</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full p-2.5 rounded-lg border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 focus:ring-2 focus:ring-blue-500 outline-none transition-all"
              placeholder="••••••••"
              required
            />
          </div>

          <button
            type="submit"
            disabled={loading}
            className="mt-2 w-full py-3 rounded-lg bg-blue-600 hover:bg-blue-700 text-white font-bold transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading ? "Signing in..." : "Login"}
          </button>
        </form>
      </div>
    </div>
  );
};
