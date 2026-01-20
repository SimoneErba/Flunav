import axios from "axios";
import { baseURL } from "./config";

// Create a singleton instance
export const axiosInstance = axios.create({
  baseURL: baseURL,
});

axiosInstance.interceptors.request.use(
  (config) => {
    // 1. Read the token directly from storage (Source of Truth)
    const token = localStorage.getItem("flumen_token");
    
    // 2. If token exists, inject it into headers
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);