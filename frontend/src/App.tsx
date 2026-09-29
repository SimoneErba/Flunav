import React from 'react';
import { BrowserRouter, Routes, Route, Navigate, Outlet, useLocation } from 'react-router-dom';
import { Toaster } from 'react-hot-toast';

import { LoginPage } from './components/LoginPage';
import { GraphThemeProvider } from './context/theme.context';
import { SimulationProvider } from './context/simulation.context';
import { AuthProvider, useAuth } from './context/auth.context';
import { WebSocketProvider } from './hooks/websocket/useWebSocketConnection';
import './index.css';

const ScenariosPage = React.lazy(() => import('./components/scenarios/ScenariosPage').then(module => ({ default: module.ScenariosPage })));
const LiveWorkspace = React.lazy(() => import('./workspaces/LiveWorkspace'));
const AdminWorkspace = React.lazy(() => import('./workspaces/AdminWorkspace'));
const AssistantPage = React.lazy(() => import('./components/assistant/AssistantPage')
  .then(module => ({ default: module.AssistantPage })));
const MultiSimulationsPage = React.lazy(() => import('./components/multisimulation/MultiSimulationsPage')
  .then(module => ({ default: module.MultiSimulationsPage })));

// ============================================================================
// 1. AUTH GUARD
// ============================================================================
const RequireAuth = () => {
  const { isAuthenticated } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }
  return <Outlet />;
};

// ============================================================================
// 4. ROOT APP
// ============================================================================
function App() {
  return (
    <GraphThemeProvider>
      <AuthProvider>
        <SimulationProvider>
          <WebSocketProvider>
            <Toaster position="bottom-center" reverseOrder={false} />
            
            <BrowserRouter>
              <Routes>
                {/* Public Route */}
                <Route path="/login" element={<LoginPage />} />

                {/* Protected Routes */}
                <Route element={<RequireAuth />}>
                  {/* Redirect root to live */}
                  <Route path="/" element={<Navigate to="/live" replace />} />
                  
                  {/* Operational View (Graph) */}
                  <Route path="/live" element={<React.Suspense fallback={<div className="p-6">Loading graph…</div>}><LiveWorkspace /></React.Suspense>} />
                  <Route path="/multi-simulations" element={(
                    <React.Suspense fallback={<div className="p-6">Loading multi-simulations…</div>}>
                      <MultiSimulationsPage />
                    </React.Suspense>
                  )} />
                  
                  <Route path="/scenarios" element={<React.Suspense fallback={<div className="p-6">Loading scenarios…</div>}><ScenariosPage /></React.Suspense>} />
                  {/* Admin View (Tables/Forms) */}
                  <Route path="/admin" element={<React.Suspense fallback={<div className="p-6">Loading admin…</div>}><AdminWorkspace /></React.Suspense>} />
                  <Route path="/admin/destination-mappings" element={<React.Suspense fallback={<div className="p-6">Loading admin…</div>}><AdminWorkspace /></React.Suspense>} />
                  <Route path="/admin/sensors" element={<React.Suspense fallback={<div className="p-6">Loading admin…</div>}><AdminWorkspace /></React.Suspense>} />
                  <Route path="/admin/bi" element={<React.Suspense fallback={<div className="p-6">Loading admin…</div>}><AdminWorkspace /></React.Suspense>} />
                  <Route path="/assistant" element={(
                    <React.Suspense fallback={<div className="p-6">Loading assistant…</div>}>
                      <AssistantPage />
                    </React.Suspense>
                  )} />
                </Route>

                {/* Catch All */}
                <Route path="*" element={<Navigate to="/live" replace />} />
              </Routes>
            </BrowserRouter>
          </WebSocketProvider>

        </SimulationProvider>
      </AuthProvider>
    </GraphThemeProvider>
  );
}

export default App;
