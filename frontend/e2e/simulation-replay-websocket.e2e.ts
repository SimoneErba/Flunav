import { test, expect } from "./fixtures";
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

    await startStompRecorder(page, backendUrl, simulationTopics(simulationId));

    const playbackWallStart = Date.now();
    const playbackResponse = await request.post(`${backendUrl}/api/simulations/${simulationId}/playback/start`, {
      headers: authHeaders(),
      data: { speedFactor: replaySpeedFactor },
    });
    expect(playbackResponse.status()).toBe(202);

    const replayTimeoutMs =
      Math.ceil((liveCaptureEnd - restoreTimestamp) / replaySpeedFactor) + replayToleranceMs + 5_000;

    await expect
      .poll(async () => eventKeys(eventsInWindow(await getRecordedSocketEvents(page), liveCaptureStart, liveCaptureEnd)), {
        timeout: replayTimeoutMs,
      })
      .toEqual(expectedKeys);

    const replayEvents = eventsInWindow(await getRecordedSocketEvents(page), liveCaptureStart, liveCaptureEnd);
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

const authHeaders = () => ({
  Authorization: `Bearer ${session.token}`,
});

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
