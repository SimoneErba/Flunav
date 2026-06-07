import { test, expect } from "./fixtures";
import { createConveyor, createItem, createLocation, uniqueE2eId } from "./helpers/api";
import { AuthSession, installAuthSession, loginAsSuperadmin } from "./helpers/auth";
import { destroyLineSimulator, startLineSimulator, type RunningSimulator } from "./helpers/simulator";
import {
  getRecordedSocketEvents,
  startStompRecorder,
  stopStompRecorder,
  type RecordedSocketEvent,
} from "./helpers/stompRecorder";

const backendUrl = process.env.E2E_BACKEND_URL ?? "http://127.0.0.1:18080";
const liveTopics = ["/topic/items", "/topic/positions", "/topic/locations", "/topic/connections"];
const captureDurationMs = 15_000;
const replaySpeedFactor = 50;
const replayToleranceMs = 3_000;

let session: AuthSession;

type GraphItem = {
  id?: string;
  locationId?: string | null;
  currentEdgeId?: string | null;
  progress?: number | null;
};

type GraphResponse = {
  items?: GraphItem[];
};

test.describe.configure({ mode: "serial" });

test.beforeAll(async ({ request }) => {
  session = await loginAsSuperadmin(request, backendUrl);
});

test("simulation replay websocket emits the recorded line simulator events on schedule", async ({ page, request }) => {
  test.setTimeout(180_000);

  let simulator: RunningSimulator | undefined;
  let simulationId: string | undefined;

  await installAuthSession(page, session);
  await page.goto("/live");

  await destroyLineSimulator(backendUrl, session.token);
  await page.waitForTimeout(2_000);

  try {
    await startStompRecorder(page, backendUrl, liveTopics);

    const liveCaptureStart = Date.now();
    simulator = startLineSimulator(backendUrl, session.token);
    await simulator.waitForOutput(/Starting periodic item injection/, 45_000);

    const remainingCaptureMs = captureDurationMs - (Date.now() - liveCaptureStart);
    if (remainingCaptureMs > 0) {
      await page.waitForTimeout(remainingCaptureMs);
    }
    const liveCaptureEnd = Date.now();

    await simulator.stop();
    simulator = undefined;

    const liveEvents = eventsInWindow(await getRecordedSocketEvents(page), liveCaptureStart, liveCaptureEnd);
    await stopStompRecorder(page);

    expect(liveEvents.length).toBeGreaterThan(0);
    const expectedKeys = eventKeys(liveEvents);

    await page.waitForTimeout(2_000);

    const restoreTimestamp = liveCaptureStart - 60_000;
    const createResponse = await request.post(`${backendUrl}/api/simulations`, {
      headers: authHeaders(),
      data: { timestamp: new Date(restoreTimestamp).toISOString() },
    });
    expect(createResponse.status()).toBe(202);

    const simulation = (await createResponse.json()) as { id: string };
    simulationId = simulation.id;

    await expect
      .poll(async () => {
        const response = await request.get(`${backendUrl}/api/simulations/${simulationId}`, {
          headers: authHeaders(),
        });
        if (!response.ok()) {
          return `HTTP_${response.status()}`;
        }
        const body = (await response.json()) as { status?: string };
        return body.status;
      }, { timeout: 60_000 })
      .toBe("READY");

    const replaySimulationTopics = simulationTopics(simulationId);
    await startStompRecorder(page, backendUrl, [...liveTopics, ...replaySimulationTopics]);

    const playbackWallStart = Date.now();
    const playbackResponse = await request.post(`${backendUrl}/api/simulations/${simulationId}/playback/start`, {
      headers: authHeaders(),
      data: { speedFactor: replaySpeedFactor },
    });
    expect(playbackResponse.status()).toBe(202);

    const replayTimeoutMs =
      Math.ceil((liveCaptureEnd - restoreTimestamp) / replaySpeedFactor) + replayToleranceMs + 5_000;

    await expect
      .poll(
        async () => {
          const recordedEvents = await getRecordedSocketEvents(page);
          const simulationEvents = recordedEvents.filter((event) => replaySimulationTopics.includes(event.topic));
          return eventKeys(eventsInWindow(simulationEvents, liveCaptureStart, liveCaptureEnd));
        },
        { timeout: replayTimeoutMs },
      )
      .toEqual(expectedKeys);

    const recordedReplayEvents = await getRecordedSocketEvents(page);
    const replayEvents = eventsInWindow(
      recordedReplayEvents.filter((event) => replaySimulationTopics.includes(event.topic)),
      liveCaptureStart,
      liveCaptureEnd,
    );
    const leakedLiveEvents = eventsInWindow(
      recordedReplayEvents.filter((event) => liveTopics.includes(event.topic)),
      liveCaptureStart,
      liveCaptureEnd,
    );

    expect(leakedLiveEvents, "simulation replay events must not be broadcast on live topics").toEqual([]);
    expectReplayTiming(liveEvents, replayEvents, playbackWallStart, restoreTimestamp);
  } finally {
    await stopStompRecorder(page).catch(() => undefined);
    await simulator?.stop();

    if (simulationId) {
      await request.delete(`${backendUrl}/api/simulations/${simulationId}`, { headers: authHeaders() });
    }

    await destroyLineSimulator(backendUrl, session.token);
  }
});

test("simulation replay leaves the live view and live movement schedule untouched", async ({ request }) => {
  test.setTimeout(120_000);

  const id = uniqueE2eId("simulation-live-isolation");
  const sourceId = `${id}-source`;
  const targetId = `${id}-target`;
  const conveyorId = `${id}-conveyor`;
  const itemId = `${id}-item`;
  const restoreTimestamp = new Date(Date.now() - 1_000).toISOString();
  let simulationId: string | undefined;

  try {
    const createSimulationResponse = await request.post(`${backendUrl}/api/simulations`, {
      headers: authHeaders(),
      data: { timestamp: restoreTimestamp },
    });
    expect(createSimulationResponse.status()).toBe(202);
    simulationId = ((await createSimulationResponse.json()) as { id: string }).id;
    await waitForSimulationReady(request, simulationId);

    await createLocation(request, backendUrl, session, {
      id: sourceId,
      name: "Isolation Source",
      latitude: 0,
      longitude: 0,
    });
    await createLocation(request, backendUrl, session, {
      id: targetId,
      name: "Isolation Chute",
      latitude: 100,
      longitude: 0,
      type: "CHUTE",
      capacity: 10,
    });
    await createConveyor(request, backendUrl, session, {
      id: conveyorId,
      sourceId,
      targetId,
      length: 20,
      speed: 1,
    });
    await createItem(request, backendUrl, session, {
      id: itemId,
      name: "Isolation Item",
      locationId: conveyorId,
      positionType: "CONVEYOR",
      progress: 0,
    });

    const liveBeforeReplay = await getGraphItem(request, `${backendUrl}/api/graph`, itemId);
    expect(liveBeforeReplay.currentEdgeId).toBe(conveyorId);
    expect(liveBeforeReplay.locationId).toBeNull();

    await new Promise((resolve) => setTimeout(resolve, 2_000));

    const playbackResponse = await request.post(`${backendUrl}/api/simulations/${simulationId}/playback/start`, {
      headers: authHeaders(),
      data: { speedFactor: 50 },
    });
    expect(playbackResponse.status()).toBe(202);

    await expect
      .poll(
        async () => {
          const item = await findGraphItem(
            request,
            `${backendUrl}/api/simulations/${simulationId}/graph`,
            itemId,
          );
          return item?.locationId;
        },
        { timeout: 15_000, message: "simulation item did not complete its queued movement" },
      )
      .toBe(targetId);

    const liveDuringReplay = await getGraphItem(request, `${backendUrl}/api/graph`, itemId);
    expect(liveDuringReplay.currentEdgeId).toBe(conveyorId);
    expect(liveDuringReplay.locationId).toBeNull();

    await expect
      .poll(
        async () => {
          const item = await getGraphItem(request, `${backendUrl}/api/graph`, itemId);
          return item.locationId;
        },
        { timeout: 30_000, message: "live movement schedule was canceled or replaced by simulation replay" },
      )
      .toBe(targetId);
  } finally {
    if (simulationId) {
      await request.delete(`${backendUrl}/api/simulations/${simulationId}`, { headers: authHeaders() });
    }
  }
});

const authHeaders = () => ({
  Authorization: `Bearer ${session.token}`,
});

const waitForSimulationReady = async (
  request: Parameters<typeof getGraphItem>[0],
  simulationId: string,
) => {
  await expect
    .poll(async () => {
      const response = await request.get(`${backendUrl}/api/simulations/${simulationId}`, {
        headers: authHeaders(),
      });
      if (!response.ok()) {
        return `HTTP_${response.status()}`;
      }
      return ((await response.json()) as { status?: string }).status;
    }, { timeout: 60_000 })
    .toBe("READY");

  const response = await request.get(`${backendUrl}/api/simulations/${simulationId}`, {
    headers: authHeaders(),
  });
  expect(response.ok()).toBeTruthy();
  const state = (await response.json()) as { buildProgress?: number };
  expect(state.buildProgress).toBe(100);
};

const getGraphItem = async (
  request: import("@playwright/test").APIRequestContext,
  url: string,
  itemId: string,
): Promise<GraphItem> => {
  const item = await findGraphItem(request, url, itemId);
  expect(item, `item ${itemId} missing from ${url}`).toBeTruthy();
  return item!;
};

const findGraphItem = async (
  request: import("@playwright/test").APIRequestContext,
  url: string,
  itemId: string,
): Promise<GraphItem | undefined> => {
  const response = await request.get(url, { headers: authHeaders() });
  expect(response.ok()).toBeTruthy();
  const graph = (await response.json()) as GraphResponse;
  return graph.items?.find((candidate) => candidate.id === itemId);
};

const simulationTopics = (simulationId: string) =>
  liveTopics.map((topic) => `/topic/simulations/${simulationId}/${topic.slice("/topic/".length)}`);

const eventsInWindow = (events: RecordedSocketEvent[], startInclusive: number, endInclusive: number) =>
  events.filter((event) => {
    const timestamp = event.envelope.timestamp;
    return timestamp >= startInclusive && timestamp <= endInclusive;
  });

const eventKeys = (events: RecordedSocketEvent[]) => events.map(eventKey).sort();

const eventKey = (event: RecordedSocketEvent) => {
  const topic = topicKind(event.topic);
  const payload = asRecord(event.envelope.payload);
  const base = {
    topic,
    timestamp: event.envelope.timestamp,
  };

  if (topic === "positions") {
    return stableJson({
      ...base,
      edgeId: payload.edgeId ?? null,
      itemId: payload.itemId,
      progress: roundedProgress(payload.progress),
      status: payload.status,
      type: payload.type ?? null,
    });
  }

  if (topic === "connections") {
    const data = asRecord(payload.data);
    return stableJson({
      ...base,
      connectionId: data.id ?? null,
      from: payload.from,
      operation: payload.operation,
      to: payload.to,
    });
  }

  const data = payload.data;
  const entity = typeof data === "string" ? data : asRecord(data).id;
  return stableJson({
    ...base,
    entity,
    operation: payload.operation,
  });
};

const expectReplayTiming = (
  liveEvents: RecordedSocketEvent[],
  replayEvents: RecordedSocketEvent[],
  playbackWallStart: number,
  restoreTimestamp: number,
) => {
  const replayEventsByKey = new Map<string, RecordedSocketEvent[]>();
  for (const replayEvent of replayEvents) {
    const key = eventKey(replayEvent);
    replayEventsByKey.set(key, [...(replayEventsByKey.get(key) ?? []), replayEvent]);
  }

  for (const liveEvent of liveEvents) {
    const key = eventKey(liveEvent);
    const replayEvent = replayEventsByKey.get(key)?.shift();
    expect(replayEvent, `missing replay event ${key}`).toBeTruthy();

    const dueWallTime =
      playbackWallStart + (liveEvent.envelope.timestamp - restoreTimestamp) / replaySpeedFactor + replayToleranceMs;
    expect(replayEvent!.arrivalWallTime, key).toBeLessThanOrEqual(dueWallTime);
  }
};

const topicKind = (topic: string) => {
  if (topic.endsWith("/items")) {
    return "items";
  }
  if (topic.endsWith("/positions")) {
    return "positions";
  }
  if (topic.endsWith("/locations")) {
    return "locations";
  }
  if (topic.endsWith("/connections")) {
    return "connections";
  }
  throw new Error(`Unexpected websocket topic: ${topic}`);
};

const asRecord = (value: unknown): Record<string, unknown> =>
  value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : {};

const roundedProgress = (value: unknown) => (typeof value === "number" ? Number(value.toFixed(6)) : null);

const stableJson = (value: Record<string, unknown>) => JSON.stringify(value);
