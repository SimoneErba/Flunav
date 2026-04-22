/* eslint-disable react-refresh/only-export-components */
import React, { createContext, useContext, useState, useEffect } from "react";
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

  // Al caricamento, controlliamo se c'è un token salvato
  useEffect(() => {
    if (import.meta.env.VITE_DEMO_MODE === 'true') {
      const demoUser = { username: "Demo User", role: "ADMIN" };
      setToken("demo-token");
      setUser(demoUser);
      setIsLoading(false);
      return;
    }

    const storedToken = localStorage.getItem("flumen_token");
    const storedRefreshToken = localStorage.getItem("flumen_refresh_token");
    const storedUser = localStorage.getItem("flumen_user");

    if (storedToken && storedUser) {
      setToken(storedToken);
      if (storedRefreshToken) setRefreshToken(storedRefreshToken);
      setUser(JSON.parse(storedUser));
    }
    setIsLoading(false);
  }, []);

  const login = (newToken: string, newRefreshToken: string, username: string, role: string) => {
    const newUser = { username, role };
    setToken(newToken);
    setRefreshToken(newRefreshToken);
    setUser(newUser);
    
    localStorage.setItem("flumen_token", newToken);
    localStorage.setItem("flumen_refresh_token", newRefreshToken);
    localStorage.setItem("flumen_user", JSON.stringify(newUser));
  };

  const logout = () => {
    setToken(null);
    setRefreshToken(null);
    setUser(null);
    localStorage.removeItem("flumen_token");
    localStorage.removeItem("flumen_refresh_token");
    localStorage.removeItem("flumen_user");
    // Opzionale: ricarica la pagina per pulire stati residui
    window.location.href = "/login";
  };

  useEffect(() => {
    const interceptor = axiosInstance.interceptors.response.use(
      (response) => response,
      async (error) => {
        const originalRequest = error.config;
        
        // Prevent infinite loop: if the 401 comes from the refresh endpoint itself, logout
        if (originalRequest.url?.includes("/auth/refresh-token")) {
            logout();
            return Promise.reject(error);
        }

        // Se errore 401/403 e non abbiamo già provato a fare refresh
        if ((error.response?.status === 401 || error.response?.status === 403) && !originalRequest._retry && refreshToken) {
          originalRequest._retry = true;
          
          try {
            // Usiamo axios diretto o un'istanza dedicata per evitare loop
            const authApi = new AuthControllerApi(new Configuration({ basePath: baseURL }));
            const response = await authApi.refreshtoken({ refreshToken });
            
            const { accessToken: newToken, refreshToken: newRefreshToken } = response.data;
            
            if (newToken) {
                // Aggiorna stato e storage
                setToken(newToken);
                if (newRefreshToken) {
                    setRefreshToken(newRefreshToken);
                    localStorage.setItem("flumen_refresh_token", newRefreshToken);
                }
                localStorage.setItem("flumen_token", newToken);
                
                // Aggiorna header richiesta originale
                originalRequest.headers.Authorization = `Bearer ${newToken}`;
                return axiosInstance(originalRequest);
            }
          } catch (refreshError) {
            console.error("Token refresh failed", refreshError);
            logout();
          }
        }
        return Promise.reject(error);
      }
    );

    return () => {
      axiosInstance.interceptors.response.eject(interceptor);
    };
  }, [refreshToken]); // Dipende da refreshToken perché lo usa nella closure

  if (isLoading) {
    return <div className="h-screen w-screen flex items-center justify-center bg-gray-50 dark:bg-gray-900">Loading...</div>;
  }

  return (
    <AuthContext.Provider value={{ user, token, refreshToken, login, logout, isAuthenticated: !!token }}>
      {children}
    </AuthContext.Provider>
  );
};
