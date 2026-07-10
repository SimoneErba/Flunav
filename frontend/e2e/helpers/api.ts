import type { APIRequestContext } from "@playwright/test";
import { expect } from "@playwright/test";
import type { AuthSession } from "./auth";

const authHeaders = (session: AuthSession) => ({
  Authorization: `Bearer ${session.token}`,
});

export type LocationSeed = {
  id: string;
  name: string;
  latitude: number;
  longitude: number;
  type?: "GENERIC" | "JUNCTION" | "DECISION_POINT" | "CHUTE" | "ACCUMULATION";
  capacity?: number;
};

export type ConveyorSeed = {
  id: string;
  sourceId: string;
  targetId: string;
  length?: number;
  speed?: number;
  active?: boolean;
  mainPath?: boolean;
};

export type ItemSeed = {
  id: string;
  name: string;
  locationId: string;
  priority?: number;
  positionType?: "LOCATION" | "CONVEYOR";
  progress?: number;
  timestamp?: string;
  properties?: Record<string, unknown>;
  destinations?: string[];
};

export type DestinationMappingSeed = {
  fieldName: string;
  dataType: "STRING" | "NUMBER" | "BOOLEAN" | "DATETIME";
  operator: "EQUAL" | "LESSER" | "GREATER";
  value: string;
  destinations: string[];
  validFrom: string;
  validTo: string;
};

export type DestinationExitMappingSeed = {
  destination: string;
  exits: string[];
};

export type SeededMovingItemGraph = {
  sourceId: string;
  targetId: string;
  conveyorId: string;
  itemId: string;
};

export const uniqueE2eId = (label: string) => `e2e-${label}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

export const createLocation = async (
  request: APIRequestContext,
  baseUrl: string,
  session: AuthSession,
  location: LocationSeed,
) => {
  const response = await request.post(`${baseUrl}/api/locations`, {
    headers: authHeaders(session),
    data: {
      id: location.id,
      name: location.name,
      latitude: location.latitude,
      longitude: location.longitude,
      type: location.type ?? "GENERIC",
      active: true,
      capacity: location.capacity ?? 10,
      properties: {},
    },
  });
  expect(response.ok()).toBeTruthy();
};

export const createConveyor = async (
  request: APIRequestContext,
  baseUrl: string,
  session: AuthSession,
  conveyor: ConveyorSeed,
) => {
  const response = await request.post(`${baseUrl}/api/conveyors`, {
    headers: authHeaders(session),
    data: {
      connectionId: conveyor.id,
      sourceId: conveyor.sourceId,
      targetId: conveyor.targetId,
      name: "E2E Conveyor",
      length: conveyor.length ?? 100,
      speed: conveyor.speed ?? 20,
      mainPath: conveyor.mainPath ?? true,
      isActive: conveyor.active ?? true,
      type: "BELT",
      capacity: 10,
      properties: {},
    },
  });
  expect(response.ok()).toBeTruthy();
};

export const createItem = async (
  request: APIRequestContext,
  baseUrl: string,
  session: AuthSession,
  item: ItemSeed,
) => {
  const response = await request.post(`${baseUrl}/api/items`, {
    headers: authHeaders(session),
    data: {
      id: item.id,
      name: item.name,
      active: true,
      priority: item.priority ?? 0,
      locationId: item.locationId,
      positionType: item.positionType ?? "CONVEYOR",
      progress: item.progress ?? 0,
      properties: item.properties ?? {},
      destinations: item.destinations,
      timestamp: item.timestamp ?? new Date().toISOString(),
    },
  });
  expect(response.ok()).toBeTruthy();
};

export const updateDestinationMappings = async (
  request: APIRequestContext,
  baseUrl: string,
  session: AuthSession,
  mappings: DestinationMappingSeed[],
) => {
  const response = await request.put(`${baseUrl}/api/destination-mappings`, {
    headers: authHeaders(session),
    data: mappings,
  });
  expect(response.ok()).toBeTruthy();
};

export const updateDestinationExitMappings = async (
  request: APIRequestContext,
  baseUrl: string,
  session: AuthSession,
  mappings: DestinationExitMappingSeed[],
) => {
  const response = await request.put(`${baseUrl}/api/destination-exit-mappings`, {
    headers: authHeaders(session),
    data: mappings,
  });
  expect(response.ok()).toBeTruthy();
};

export const seedMovingItemGraph = async (
  request: APIRequestContext,
  baseUrl: string,
  session: AuthSession,
  options: { length?: number; speed?: number; active?: boolean; progress?: number } = {},
): Promise<SeededMovingItemGraph> => {
  const id = uniqueE2eId("moving");
  const sourceId = `${id}-source`;
  const targetId = `${id}-target`;
  const conveyorId = `${id}-conveyor`;
  const itemId = `${id}-item`;

  for (const location of [
    { id: sourceId, name: "E2E Source", latitude: 0, longitude: 0 },
    { id: targetId, name: "E2E Target", latitude: 100, longitude: 0 },
  ]) {
    await createLocation(request, baseUrl, session, location);
  }

  await createConveyor(request, baseUrl, session, {
    id: conveyorId,
    sourceId,
    targetId,
    length: options.length,
    speed: options.speed,
    active: options.active,
  });

  await createItem(request, baseUrl, session, {
    id: itemId,
    name: "E2E Item",
    locationId: conveyorId,
    positionType: "CONVEYOR",
    progress: options.progress ?? 0,
  });

  return { sourceId, targetId, conveyorId, itemId };
};

export const deactivateConveyor = async (
  request: APIRequestContext,
  baseUrl: string,
  session: AuthSession,
  conveyorId: string,
) => {
  const response = await request.put(`${baseUrl}/api/conveyors/${encodeURIComponent(conveyorId)}/deactivate`, {
    headers: authHeaders(session),
  });
  expect(response.ok()).toBeTruthy();
};

export const activateConveyor = async (
  request: APIRequestContext,
  baseUrl: string,
  session: AuthSession,
  conveyorId: string,
) => {
  const response = await request.put(`${baseUrl}/api/conveyors/${encodeURIComponent(conveyorId)}/activate`, {
    headers: authHeaders(session),
  });
  expect(response.ok()).toBeTruthy();
};

export const updateConveyorSpeed = async (
  request: APIRequestContext,
  baseUrl: string,
  session: AuthSession,
  conveyorId: string,
  speed: number,
) => {
  const response = await request.put(`${baseUrl}/api/conveyors/${encodeURIComponent(conveyorId)}/speed`, {
    headers: authHeaders(session),
    data: { speed },
  });
  expect(response.ok()).toBeTruthy();
};

export const emptyChute = async (
  request: APIRequestContext,
  baseUrl: string,
  session: AuthSession,
  chuteId: string,
) => {
  const response = await request.put(`${baseUrl}/api/locations/${encodeURIComponent(chuteId)}/empty`, {
    headers: authHeaders(session),
  });
  expect(response.ok()).toBeTruthy();
};
