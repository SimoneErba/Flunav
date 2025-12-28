import axios from 'axios';
import { v4 as uuidv4 } from 'uuid';

const getApiBaseUrl = () => {
    const envUrl = import.meta.env.VITE_API_BASE_URL;
    if (envUrl && envUrl.startsWith('http')) {
        return envUrl;
    }
    const protocol = window.location.protocol;
    const host = window.location.host;
    return `${protocol}//${host}`;
};

const baseURL = getApiBaseUrl();

export const CLIENT_ID = uuidv4();

export const axiosInstance = axios.create({
    baseURL,
    headers: {
        'Content-Type': 'application/json',
        'X-Sender-ID': CLIENT_ID,
    },
});

axiosInstance.interceptors.response.use(
    (response) => response,
    (error) => {

        console.error('Global API Error Interceptor:', error);
        if (error.response?.status === 401) {
        }
        return Promise.reject(error);
    }
);