import React, { createContext, useContext, useState, useEffect } from "react";
import { useApi } from "../hooks/useApi";
import { Configuration } from "../api-client";

interface User {
  username: string;
  role: string;
}

interface AuthContextType {
  user: User | null;
  token: string | null;
  login: (token: string, username: string, role: string) => void;
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
  const [isLoading, setIsLoading] = useState(true);

  // Al caricamento, controlliamo se c'è un token salvato
  useEffect(() => {
    const storedToken = localStorage.getItem("flumen_token");
    const storedUser = localStorage.getItem("flumen_user");

    if (storedToken && storedUser) {
      setToken(storedToken);
      setUser(JSON.parse(storedUser));
    }
    setIsLoading(false);
  }, []);

  const login = (newToken: string, username: string, role: string) => {
    const newUser = { username, role };
    setToken(newToken);
    setUser(newUser);
    
    localStorage.setItem("flumen_token", newToken);
    localStorage.setItem("flumen_user", JSON.stringify(newUser));
  };

  const logout = () => {
    setToken(null);
    setUser(null);
    localStorage.removeItem("flumen_token");
    localStorage.removeItem("flumen_user");
    // Opzionale: ricarica la pagina per pulire stati residui
    window.location.href = "/login";
  };

  // Configurazione globale per iniettare il token nelle chiamate API
  // Nota: Questo dipende da come è fatto il tuo useApi o api-client
  // Se usi openapi-generator, spesso si passa la Configuration al costruttore dell'API
  
  if (isLoading) {
    return <div className="h-screen w-screen flex items-center justify-center bg-gray-50 dark:bg-gray-900">Loading...</div>;
  }

  return (
    <AuthContext.Provider value={{ user, token, login, logout, isAuthenticated: !!token }}>
      {children}
    </AuthContext.Provider>
  );
};