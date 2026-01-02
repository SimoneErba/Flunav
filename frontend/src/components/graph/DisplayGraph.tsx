import { useMemo, useState } from "react";
import { SigmaContainer } from "@react-sigma/core";
import { NodeSquareProgram } from "@sigma/node-square";
import "@react-sigma/core/lib/react-sigma.min.css";

import { GraphData } from "../../api-client/api";
import { GraphEvents } from "./GraphEvents";
import { GraphHighlighter } from "./GraphHighlighter";

interface DisplayGraphProps {
    initialGraphData: GraphData;
    simulationId?: string;
    simTime: number;
}

export interface HoverTarget {
    nodeId: string;
    x: number;
    y: number;
    attributes: any; 
}

export const DisplayGraph = ({ 
    initialGraphData, 
    simulationId,
    simTime
}: DisplayGraphProps) => {
    
    const [hoverTarget, setHoverTarget] = useState<HoverTarget | null>(null);
    const [selectedItemData, setSelectedItemData] = useState<any | null>(null);
    
    const highlightedItem = selectedItemData;

    const settings = useMemo(() => ({
        nodeProgramClasses: { square: NodeSquareProgram }, 
        enableEdgeEvents: true,
        autoRescale: true,
        renderEdgeLabels: true, 
        defaultEdgeType: "arrow",
        zIndex: true
    }), []);

    return (
        // --- FIX: Usa classi Tailwind invece di style={{ width: '100%', height: '100%' }} ---
        // Questo div riempirà il <main> che ha flex-1
        <div className="w-full h-full">
            <SigmaContainer 
                settings={settings}
                // Passiamo le classi anche al contenitore interno di Sigma
                className="w-full h-full !bg-transparent"
            >
                <GraphHighlighter 
                    initialGraphData={initialGraphData} 
                    highlightedItem={highlightedItem} 
                />

                <GraphEvents 
                    initialGraphData={initialGraphData}
                    simulationId={simulationId}
                    simTime={simTime}
                    hoverTarget={hoverTarget}
                    setHoverTarget={setHoverTarget}
                    selectedItemData={selectedItemData}
                    setSelectedItemData={setSelectedItemData}
                />
            </SigmaContainer>
        </div>
    );
};