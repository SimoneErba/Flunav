import { useMemo } from "react";
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
    
    // 1. MEMOIZE SETTINGS
    // This ensures the object reference stays the same between renders.
    // SigmaContainer will see this and say "Oh, settings haven't changed, I can ignore this."
    const settings = useMemo(() => ({
        nodeProgramClasses: { square: NodeSquareProgram },
        enableEdgeEvents: true,
        autoRescale: true
    }), []);

    // 2. MEMOIZE STYLE
    const style = useMemo(() => ({
        ...sigmaStyle, 
        backgroundColor: 'transparent', 
        cursor: 'default' 
    }), []);

  return (
        <div style={{ width: '100%', height: '100%', position: 'relative' }}>
    
            <div style={{ position: 'absolute', top: 20, right: 20, zIndex: 9999 }}>
                <ThemeToggle />
            </div>

            <SigmaContainer
                style={style}
                settings={settings}
            >
                <GraphThemeController />
                <GraphEvents 
                    initialGraphData={initialGraphData} 
                    simulationId={simulationId}
                    simTime={simTime} 
                />
            </SigmaContainer>
        </div>
  );
};

export default DisplayGraph;