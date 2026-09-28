import { useCallback, useState, type SetStateAction } from "react";
import type { EdgeEditorData } from "../../editors/edge.editor";
import type { NodeEditorData } from "../../editors/node.editor";
import type { ItemEditorData } from "../../editors/item.editor";

export type GraphSelection =
    | { kind: "item"; data: ItemEditorData }
    | { kind: "node"; data: NodeEditorData }
    | { kind: "edge"; data: EdgeEditorData }
    | null;

/** One tagged selection ensures only one graph editor can be open. */
export const useGraphSelection = () => {
    const [selection, setSelection] = useState<GraphSelection>(null);
    const setSelectedItemData = useCallback((value: SetStateAction<ItemEditorData | null>) => {
        setSelection(current => {
            const previous = current?.kind === "item" ? current.data : null;
            const data = typeof value === "function" ? value(previous) : value;
            return data ? { kind: "item", data } : current?.kind === "item" ? null : current;
        });
    }, []);
    const setSelectedNodeData = useCallback((data: NodeEditorData | null) => {
        setSelection(current => data ? { kind: "node", data } : current?.kind === "node" ? null : current);
    }, []);
    const setSelectedEdgeData = useCallback((data: EdgeEditorData | null) => {
        setSelection(current => data ? { kind: "edge", data } : current?.kind === "edge" ? null : current);
    }, []);
    const clearSelection = useCallback(() => setSelection(null), []);
    const setIsDetailsOpen = useCallback((open: boolean) => {
        if (!open) setSelectedItemData(null);
    }, [setSelectedItemData]);
    return {
        selectedItemData: selection?.kind === "item" ? selection.data : null,
        selectedNodeData: selection?.kind === "node" ? selection.data : null,
        selectedEdgeData: selection?.kind === "edge" ? selection.data : null,
        isDetailsOpen: selection?.kind === "item",
        setSelectedItemData, setSelectedNodeData, setSelectedEdgeData, setIsDetailsOpen, clearSelection,
    };
};

export type GraphSelectionState = ReturnType<typeof useGraphSelection>;
