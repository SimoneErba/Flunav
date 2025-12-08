import { Configuration } from '../api-client';
import axios from 'axios';
import { v4 as uuidv4 } from 'uuid';

const baseURL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

export const CLIENT_ID = uuidv4();

export const axiosInstance = axios.create({
    baseURL,
    headers: {
        'Content-Type': 'application/json',
        'X-Sender-ID': CLIENT_ID,
    },
});

export const apiConfig = new Configuration({
    basePath: baseURL,
    baseOptions: {
        headers: {
            'X-Sender-ID': CLIENT_ID
        }
    }
});

// Add response interceptor for error handling
axiosInstance.interceptors.response.use(
    (response) => response,
    (error) => {
        console.error('API Error:', error);
        return Promise.reject(error);
    }
);