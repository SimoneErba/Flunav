import { ConveyorResponse, ItemInput, LocationInput } from '../api-client/api';

// --- Enums ---
export enum CrudOperation {
    CREATED = 'CREATED',
    DELETED = 'DELETED'
}

export enum PositionStatus {
    UPDATED = 'UPDATED',
    LOST = 'LOST' // <--- Make sure this is handled
}

// --- Envelope ---
export interface SocketEnvelope<T> {
    payload: T;
    senderId: string | null;
    timestamp: number;
}

// --- Payloads ---

// Matches Java: PositionUpdate(String itemId, String edgeId, String locationId, PositionStatus status)
export interface PositionUpdate {
    itemId: string;
    edgeId: string | null;
    locationId: string | null; // <--- NEW FIELD ADDED IN BACKEND
    status: PositionStatus;
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
    properties: Record<string, any>;
}

export interface SimulationStatusUpdate {
    status: string;
}

export interface SimulationSpeedUpdate {
    speed: number;
}