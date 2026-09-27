import { useRef } from "react";
import type { ItemResponse } from "../../../api-client/api";

// WebSocket handlers mutate this map outside React. Its revision lets the
// animation loop rebuild staging order only after item state actually changes.
export class ActiveItems extends Map<string, ItemResponse> {
    revision = 0;

    override set(key: string, value: ItemResponse): this {
        super.set(key, value);
        this.revision++;
        return this;
    }

    override delete(key: string): boolean {
        const deleted = super.delete(key);
        if (deleted) this.revision++;
        return deleted;
    }

    override clear(): void {
        if (this.size) this.revision++;
        super.clear();
    }
}

export class ConveyorKeys extends Map<string, string> {
    revision = 0;

    override set(key: string, value: string): this {
        super.set(key, value);
        this.revision++;
        return this;
    }

    override delete(key: string): boolean {
        const deleted = super.delete(key);
        if (deleted) this.revision++;
        return deleted;
    }

    override clear(): void {
        if (this.size) this.revision++;
        super.clear();
    }

    touch(): void { this.revision++; }
}

export const useGraphRuntime = () => {
    const activeItemsRef = useRef<ActiveItems>(new ActiveItems());
    const edgeKeysRef = useRef<ConveyorKeys>(new ConveyorKeys());
    return { activeItemsRef, edgeKeysRef };
};
