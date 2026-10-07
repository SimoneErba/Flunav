/* eslint-disable react-refresh/only-export-components */
import React, { createContext, useContext, useState, useEffect, useCallback } from "react";
import type { InternalAxiosRequestConfig } from "axios";
import { axiosInstance } from "../api/axiosInstance";
import { AuthControllerApi, Configuration } from "../api-client";
import { baseURL } from "../api/config";

interface User {
  username: string;
  role: string;
}

interface AuthContextType {
  user: User | null;
  token: string | null;
  refreshToken: string | null;
  login: (token: string, refreshToken: string, username: string, role: string) => void;
  logout: () => void;
  isAuthenticated: boolean;
}

type RetryableRequestConfig = InternalAxiosRequestConfig & {
  _retry?: boolean;
};

const AuthContext = createContext<AuthContextType | null>(null);

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used within an AuthProvider");
  return context;
};

export const AuthProvider = ({ children }: { children: React.ReactNode }) => {
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [refreshToken, setRefreshToken] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  // Bootstrap auth exclusively from localStorage so route guards resolve immediately.
  useEffect(() => {
    if (import.meta.env.VITE_DEMO_MODE === 'true') {
      const demoUser = { username: "Demo User", role: "ADMIN" };
      setToken("demo-token");
      setRefreshToken("demo-refresh-token");
      setUser(demoUser);
      setIsLoading(false);
      return;
    }

    const storedToken = localStorage.getItem("flunav_token");
    const storedRefreshToken = localStorage.getItem("flunav_refresh_token");
    const storedUser = localStorage.getItem("flunav_user");

    if (storedToken && storedUser) {
      setToken(storedToken);
      setUser(JSON.parse(storedUser));
      setRefreshToken(storedRefreshToken);
    } else {
      setToken(null);
      setRefreshToken(null);
      setUser(null);
    }
    setIsLoading(false);
  }, []);

  const login = (newToken: string, newRefreshToken: string, username: string, role: string) => {
    const newUser = { username, role };
    setToken(newToken);
    setRefreshToken(newRefreshToken);
    setUser(newUser);
    
    localStorage.setItem("flunav_token", newToken);
    localStorage.setItem("flunav_refresh_token", newRefreshToken);
    localStorage.setItem("flunav_user", JSON.stringify(newUser));
  };

  // Clear in-memory and persisted auth before forcing navigation to the public login route.
  const logout = useCallback(() => {
    const assistantPrefix = user?.username ? `flunav_assistant:${user.username}:` : null;
    setToken(null);
    setRefreshToken(null);
    setUser(null);
    localStorage.removeItem("flunav_token");
    localStorage.removeItem("flunav_refresh_token");
    localStorage.removeItem("flunav_user");
    if (assistantPrefix) {
      Object.keys(localStorage)
        .filter(key => key.startsWith(assistantPrefix))
        .forEach(key => localStorage.removeItem(key));
    }
    delete axiosInstance.defaults.headers.common.Authorization;

    if (window.location.pathname !== "/login") {
      window.location.assign("/login");
    }
  }, [user?.username]);

  // Refresh at most once per request and force logout on any unrecoverable auth failure.
  useEffect(() => {
    const authApi = new AuthControllerApi(new Configuration({ basePath: baseURL }), undefined, axiosInstance);

    const interceptor = axiosInstance.interceptors.response.use(
      (response) => response,
      async (error) => {
        const originalRequest = error.config as RetryableRequestConfig | undefined;
        const status = error.response?.status;

        if (!originalRequest) {
          return Promise.reject(error);
        }

        if (originalRequest.url?.includes("/auth/refresh-token")) {
          logout();
          return Promise.reject(error);
        }

        if (status === 401 || status === 403) {
          if (!refreshToken || originalRequest._retry) {
            delete originalRequest.headers.Authorization;
            logout();
            return Promise.reject(error);
          }

          originalRequest._retry = true;

          try {
            const response = await authApi.refreshtoken({ refreshToken });
            const { accessToken: newToken, refreshToken: newRefreshToken } = response.data;

            if (newToken) {
              setToken(newToken);
              localStorage.setItem("flunav_token", newToken);

              if (newRefreshToken) {
                setRefreshToken(newRefreshToken);
                localStorage.setItem("flunav_refresh_token", newRefreshToken);
              }

              originalRequest.headers.Authorization = `Bearer ${newToken}`;
              return axiosInstance(originalRequest);
            }

            logout();
          } catch (refreshError) {
            console.error("Token refresh failed", refreshError);
            delete originalRequest.headers.Authorization;
            logout();
          }
        }

        return Promise.reject(error);
      }
    );

    return () => {
      axiosInstance.interceptors.response.eject(interceptor);
    };
  }, [logout, refreshToken]);

  if (isLoading) {
    return <div className="h-screen w-screen flex items-center justify-center bg-gray-50 dark:bg-gray-900">Loading...</div>;
  }

  return (
    <AuthContext.Provider value={{ user, token, refreshToken, login, logout, isAuthenticated: !!token }}>
      {children}
    </AuthContext.Provider>
  );
};
