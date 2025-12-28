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

export const baseURL = getApiBaseUrl();

export const CLIENT_ID = uuidv4();