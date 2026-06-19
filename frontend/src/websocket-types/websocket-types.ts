import { ConveyorResponse, ItemResponse, LocationInput } from "../api-client/api";

export enum CrudOperation {
  CREATED = "CREATED",
  DELETED = "DELETED",
}

export enum PositionStatus {
  UPDATED = "UPDATED",
  LOST = "LOST",
}

/**
 * Payload for messages on the 'positions' topic.
 */
export interface PositionUpdate {
  itemId: string;
  locationId: string | null;
  status: PositionStatus;
}

/**
 * Payload for messages on the 'connections' topic.
 */
export interface ConnectionMessage {
  from: string;
  to: string;
  operation: CrudOperation;
  data: ConveyorResponse
}

export interface EntityMessage<T> {
  operation: CrudOperation;
  data: T;
}

export interface EntityUpdateMessage {
  id: string;
  properties: {
    [key: string]: unknown;
  };
}

export type ItemCreatedMessage = EntityMessage<ItemResponse>;
export type ItemDeletedMessage = EntityMessage<string>;

export type LocationCreatedMessage = EntityMessage<LocationInput>;
export type LocationDeletedMessage = EntityMessage<string>;
