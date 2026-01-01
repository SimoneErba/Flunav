import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.tsx'
import './index.css'
import { PostHogProvider } from 'posthog-js/react'

const options = {
  api_host: window.location.origin + "/ingest",
  ui_host: 'https://eu.posthog.com',
  defaults: '2025-11-30',
} as const

createRoot(document.getElementById('root')!).render(
    <PostHogProvider apiKey={import.meta.env.VITE_PUBLIC_POSTHOG_KEY} options={options}>
        <App />
    </PostHogProvider>
)
