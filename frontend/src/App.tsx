import './App.css'
import { DisplayGraph } from './components/graph'
import { useGraph } from './hooks/useGraph';
import React, { useState, useEffect } from 'react';
import {
  GraphApi,
  SimulationsApi
} from "./api-client/api";
import { useWebSocket } from './hooks/useWebSocket';

function App() {
  // Your existing hook to get the initial graph data
  const { graphData, loading, refetchGraphData } = useGraph();
  const client = new SimulationsApi();
  // State for the datetime picker
  const [selectedDate, setSelectedDate] = useState(new Date());
  const { connected, subscribeToSimulationStatus } = useWebSocket(); // Usa l'hook

  // State to manage the restore process and provide UI feedback
  const [isRestoring, setIsRestoring] = useState(false);
  
  const [activeSimulation, setActiveSimulation] = useState(null);

  // --- Helper function to format date for the datetime-local input ---
  const toDateTimeLocal = (date) => {
    const year = date.getFullYear();
    const month = (date.getMonth() + 1).toString().padStart(2, '0');
    const day = date.getDate().toString().padStart(2, '0');
    const hours = date.getHours().toString().padStart(2, '0');
    const minutes = date.getMinutes().toString().padStart(2, '0');
    return `${year}-${month}-${day}T${hours}:${minutes}`;
  };

  // --- Function to handle the restore API call ---
  const handleRestore = async () => {
    if (!selectedDate) {
      alert('Please select a valid date and time.');
      return;
    }

    console.log(`Restoring system to: ${selectedDate.toISOString()}`);
    setIsRestoring(true);

      try {
        await client.createSimulation(selectedDate.toISOString())
        alert('System restore initiated successfully! Fetching updated graph...');

        refetchGraphData(); 
      } catch (error) {
        alert(`Erorr while restoring the state: ${error}`)
    } finally {
      setIsRestoring(false);
    }
  };

  useEffect(() => {
      // Se non siamo connessi o non c'è una simulazione attiva in attesa, non fare nulla.
      if (!connected || !activeSimulation || activeSimulation.status === 'READY') {
          return;
      }

      // Sottoscrivi alle notifiche per la nostra simulazione attiva
      const unsubscribe = subscribeToSimulationStatus(activeSimulation.id, (update) => {
          console.log('Received simulation status update:', update);

          // Aggiorna lo stato della simulazione per mostrare il progresso (es. da QUEUED a BUILDING)
          setActiveSimulation(prev => ({ ...prev, status: update.status }));

          if (update.status === 'READY') {
              // È PRONTO!
              alert('Simulation is ready! Fetching the new graph state.');
              
              refetchGraphData(activeSimulation.id);
              
              setIsRestoring(false);
              unsubscribe();
          } else if (update.status === 'FAILED') {
              alert(`Simulation build failed: ${update.message || 'Unknown error'}`);
              setIsRestoring(false);
              unsubscribe();
          }
      });

      return () => unsubscribe();

  }, [connected, activeSimulation, subscribeToSimulationStatus, refetchGraphData]);

  // --- Main Render Logic ---
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', background: '#f0f2f5' }}>
      
      {/* Control Panel Header with Padding */}
      <div style={{ 
        padding: '16px', 
        background: 'white', 
        borderBottom: '1px solid #ddd',
        display: 'flex',
        alignItems: 'center',
        gap: '16px',
        flexShrink: 0 // Prevents the header from shrinking
      }}>
        <h2 style={{ margin: 0, color: '#333' }}>System State Control</h2>
        <input
          type="datetime-local"
          value={toDateTimeLocal(selectedDate)}
          onChange={(e) => setSelectedDate(new Date(e.target.value))}
          disabled={loading || isRestoring}
          style={{ padding: '8px', border: '1px solid #ccc', borderRadius: '4px' }}
        />
        <button
          onClick={handleRestore}
          disabled={loading || isRestoring}
          style={{ 
            padding: '8px 16px', 
            border: 'none', 
            borderRadius: '4px', 
            background: '#007bff', 
            color: 'white',
            cursor: 'pointer'
          }}
        >
          {isRestoring ? 'Restoring...' : 'Restore to this Time'}
        </button>
      </div>

      {/* Main Content Area */}
      <div style={{ flex: 1, position: 'relative' }}>
        {loading ? (
          <div style={{ textAlign: 'center', paddingTop: '50px' }}>Loading Graph...</div>
        ) : (
          <DisplayGraph initialGraphData={graphData} />
        )}
      </div>

    </div>
  );
}

export default App