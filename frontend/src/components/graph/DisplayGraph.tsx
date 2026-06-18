import { useMemo, useState } from "react";
import { SigmaContainer } from "@react-sigma/core";
import { NodeSquareProgram } from "@sigma/node-square";
import "@react-sigma/core/lib/react-sigma.min.css";

import { DisplayRuleColorResult, GraphData } from "../../api-client/api";
import { GraphEvents } from "./GraphEvents";
import { GraphHighlighter } from "./GraphHighlighter";
import { ItemEditorData } from "../editors/item.editor";
import { NodeBorderedSquareProgram } from "./rendering/NodeBorderedSquareProgram";

interface DisplayGraphProps {
    initialGraphData: GraphData;
    simulationId?: string;
    simTime: number;
    colorOverrides?: DisplayRuleColorResult | null;
}

export interface HoverTarget {
    nodeId: string;
    x: number;
    y: number;
    attributes: ItemEditorData; 
}

export const DisplayGraph = ({ 
    initialGraphData, 
    simulationId,
    simTime,
    colorOverrides
}: DisplayGraphProps) => {
    
    const [hoverTarget, setHoverTarget] = useState<HoverTarget | null>(null);
    const [selectedItemData, setSelectedItemData] = useState<ItemEditorData | null>(null);
    
    const highlightedItem = selectedItemData;

    /**
     * Keeps Sigma renderer settings stable across graph data updates.
     * Node program registration should not be recreated on every render because
     * graph hooks mutate the existing Sigma instance incrementally.
     */
    const settings = useMemo(() => ({
        nodeProgramClasses: { square: NodeSquareProgram, borderedSquare: NodeBorderedSquareProgram },
        enableEdgeEvents: true,
        autoRescale: true,
        renderEdgeLabels: true, 
        defaultEdgeType: "arrow",
        zIndex: true,
        allowInvalidContainer: true,
    }), []);

    return (
        <div className="w-full h-full">
            <SigmaContainer 
                settings={settings}
                // Passiamo le classi anche al contenitore interno di Sigma
                className="w-full h-full !bg-transparent"
            >
                <GraphHighlighter 
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
                    colorOverrides={colorOverrides}
                />
            </SigmaContainer>
        </div>
    );
};
