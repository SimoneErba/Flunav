import React, { useState } from "react";
import { SigmaContainer } from "@react-sigma/core";
import { NodeSquareProgram } from "@sigma/node-square";
import "@react-sigma/core/lib/react-sigma.min.css";

import { GraphData } from "../../api-client/api";
import { GraphThemeController } from "../../context/theme.context";
import { ThemeToggle } from "../theme.toggle";
import { GraphEvents } from "./GraphEvents";
import { sigmaStyle } from "../../styles/styles";

interface DisplayGraphProps {
    initialGraphData: GraphData;
    simulationId?: string;
    playbackSpeed?: number;
    isPaused?: boolean;
    simTime: number;
}

export const DisplayGraph = ({ 
    initialGraphData, 
    simulationId,
    simTime
}: DisplayGraphProps) => {
  
  const [hoveredEdge, setHoveredEdge] = useState<string | null>(null);

  return (
        <div style={{ width: '100%', height: '100%', position: 'relative' }}>
    
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
  );
};

export default DisplayGraph;