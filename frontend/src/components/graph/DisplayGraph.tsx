import React, { useState } from "react";
import { SigmaContainer } from "@react-sigma/core";
import { NodeSquareProgram } from "@sigma/node-square";
import "@react-sigma/core/lib/react-sigma.min.css";

import { GraphData } from "../../api-client/api";
import { GraphThemeProvider, GraphThemeController, ThemedBackground } from "../../context/theme.context";
import { ThemeToggle } from "../theme.toggle";
import { GraphEvents } from "./GraphEvents";
import { useSimulationClock } from "./hooks/useSimulationClock";
import { sigmaStyle } from "../../styles/styles";

interface DisplayGraphProps {
    initialGraphData: GraphData;
    simulationId?: string;
    playbackSpeed?: number;
    isPaused?: boolean;
}

export const DisplayGraph = ({ 
    initialGraphData, 
    simulationId, 
    playbackSpeed = 1.0, 
    isPaused = false 
}: DisplayGraphProps) => {
  
  const [hoveredEdge, setHoveredEdge] = useState<string | null>(null);
  const simTime = useSimulationClock(initialGraphData?.timestamp, playbackSpeed, isPaused);

  const date = new Date(simTime);
  const timeString = date.toLocaleTimeString([], { hour12: false }) + "." + date.getMilliseconds().toString().padStart(3, '0');

  return (
    <GraphThemeProvider>
      <ThemedBackground>
        <div style={{ width: '100%', height: '100%', position: 'relative' }}>
            
            {/* Simulation Clock Overlay */}
            <div style={{ 
                position: 'absolute', top: 20, left: '50%', transform: 'translateX(-50%)',
                zIndex: 9999, background: 'rgba(0,0,0,0.6)', color: 'white', 
                padding: '4px 12px', borderRadius: '4px', fontFamily: 'monospace'
            }}>
                {timeString}
            </div>

            <div style={{ position: 'absolute', top: 20, right: 20, zIndex: 9999 }}>
                <ThemeToggle />
            </div>

            <SigmaContainer
                style={{ ...sigmaStyle, backgroundColor: 'transparent', cursor: hoveredEdge ? 'pointer' : 'default' }}
                settings={{
                    nodeProgramClasses: { square: NodeSquareProgram },
                    enableEdgeEvents: true,
                    autoRescale: true
                }}
            >
                <GraphThemeController />
                <GraphEvents 
                    initialGraphData={initialGraphData} 
                    setHoveredEdge={setHoveredEdge} 
                    simulationId={simulationId}
                    simTime={simTime} 
                />
            </SigmaContainer>
        </div>
      </ThemedBackground>
    </GraphThemeProvider>
  );
};

export default DisplayGraph;