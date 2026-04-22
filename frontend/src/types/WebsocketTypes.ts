import { ConveyorResponse } from '../api-client/api';
import { ItemPositionTypeEnum as PositionTypeEnum } from '../api-client/api';

// --- Enums ---
export enum CrudOperation {
    CREATED = 'CREATED',
    DELETED = 'DELETED'
}

export enum PositionStatus {
    UPDATED = 'UPDATED',
    LOST = 'LOST'
}

// --- Envelope ---
export interface SocketEnvelope<T> {
    payload: T;
    senderId: string | null;
    timestamp: number;
}

// --- Payloads ---

// Matches Java: record PositionUpdate(String itemId, String edgeId, PositionStatus status, PositionType type, Double progress)
export interface PositionUpdate {
    itemId: string;
    edgeId: string | null;
    status: PositionStatus;
    type: PositionTypeEnum;
    progress: number | null;
}

export interface ConnectionMessage {
    from: string;
    to: string;
    operation: CrudOperation;
    data: ConveyorResponse | null;
}

export interface EntityMessage<T> {
    operation: CrudOperation;
    data: T;
}

export interface EntityUpdateMessage {
    id: string;
    properties: Record<string, unknown>;
}

export interface SimulationStatusUpdate {
    status: string;
}

export interface SimulationSpeedUpdate {
    speed: number;
}
