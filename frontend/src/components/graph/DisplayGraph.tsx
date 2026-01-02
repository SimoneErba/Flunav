import { useMemo, useState, useCallback } from "react";
import { SigmaContainer } from "@react-sigma/core";
import { NodeSquareProgram } from "@sigma/node-square";
import "@react-sigma/core/lib/react-sigma.min.css";

import { GraphData } from "../../api-client/api";
import { GraphEvents } from "./GraphEvents";
import { sigmaStyle } from "../../styles/styles";
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
    // 1. MEMOIZE SETTINGS
    const settings = useMemo(() => ({
        nodeProgramClasses: { square: NodeSquareProgram }, 
        enableEdgeEvents: true,
        autoRescale: true,
        renderEdgeLabels: true, 
        defaultEdgeType: "arrow",
        zIndex: true 
    }), []);

    // 2. CREATE EDGE LOOKUP MAP (Fix for missing 'graph' instance)
    // We map EdgeID -> { source, target } so we can check the path
    const edgeConnectionMap = useMemo(() => {
        const map = new Map<string, { source: string, target: string }>();
        if (initialGraphData && initialGraphData.connections) {
            initialGraphData.connections.forEach(conn => {
                // Assuming 'id' is the edge ID in Sigma
                map.set(conn.id, { source: conn.sourceId, target: conn.targetId });
            });
        }
        return map;
    }, [initialGraphData]);

    return (
        <div style={{ width: '100%', height: '100%' }}>
            <SigmaContainer 
                settings={settings}
                style={sigmaStyle}
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