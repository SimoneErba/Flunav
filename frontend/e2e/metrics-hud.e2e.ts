import { test, expect } from "./fixtures";
import {
  createConveyor,
  createItem,
  createLocation,
  emptyChute,
  uniqueE2eId,
} from "./helpers/api";
import { AuthSession, installAuthSession, loginAsSuperadmin } from "./helpers/auth";
import { waitForGraphTestApi, waitForEdge, waitForItem, waitForNode } from "./helpers/graph";
import {
  getHudValues,
  waitForHudValue,
  waitForThroughputSocketMetric,
} from "./helpers/metrics";
import {
  getRecordedSocketEvents,
  startStompRecorder,
  stopStompRecorder,
} from "./helpers/stompRecorder";

const backendUrl = process.env.E2E_BACKEND_URL ?? "http://127.0.0.1:18080";
let session: AuthSession;

test.describe.configure({ mode: "serial" });

test.beforeAll(async ({ request }) => {
  session = await loginAsSuperadmin(request, backendUrl);
});

test.beforeEach(async ({ page }) => {
  await installAuthSession(page, session);
});

test.afterEach(async ({ page }) => {
  await stopStompRecorder(page).catch(() => undefined);
});

test("live throughput websocket updates the HUD and then returns to idle", async ({ page, request }) => {
  const id = uniqueE2eId("hud-throughput");
  const locationId = `${id}-source`;
  const itemId = `${id}-item`;
  const liveTopic = "/topic/analytics/throughput";

  await page.goto("/live");
  await waitForGraphTestApi(page);
  await waitForHudValue(page, "in-5s", 0);

  await startStompRecorder(page, backendUrl, [liveTopic]);

  await createLocation(request, backendUrl, session, {
    id: locationId,
    name: "HUD Throughput Source",
    latitude: 0,
    longitude: 0,
  });
  await waitForNode(page, locationId);

  await createItem(request, backendUrl, session, {
    id: itemId,
    name: "HUD Throughput Item",
    locationId,
    positionType: "LOCATION",
  });

  await waitForThroughputSocketMetric(
    page,
    liveTopic,
    (metric) => metric.itemsEntered === 1 && metric.itemsCurrent !== undefined,
  );
  await waitForHudValue(page, "in-5s", 1);

  await waitForThroughputSocketMetric(
    page,
    liveTopic,
    (metric) => metric.itemsEntered === 0 && metric.itemsExited === 0,
  );
  await waitForHudValue(page, "in-5s", 0);
});

test("live HUD counters reflect active, priority, stopped conveyor, and full chute state", async ({
  page,
  request,
}) => {
  const id = uniqueE2eId("hud-counters");
  const sourceId = `${id}-source`;
  const targetId = `${id}-target`;
  const chuteId = `${id}-chute`;
  const conveyorId = `${id}-stopped-conveyor`;
  const activeItemId = `${id}-active-item`;
  const priorityItemId = `${id}-priority-item`;

  await page.goto("/live");
  await waitForGraphTestApi(page);
  const before = await getHudValues(page);

  await createLocation(request, backendUrl, session, {
    id: sourceId,
    name: "HUD Source",
    latitude: 0,
    longitude: 0,
  });
  await createLocation(request, backendUrl, session, {
    id: targetId,
    name: "HUD Target",
    latitude: 100,
    longitude: 0,
  });
  await createLocation(request, backendUrl, session, {
    id: chuteId,
    name: "HUD Full Chute",
    latitude: 50,
    longitude: 50,
    type: "CHUTE",
    capacity: 1,
  });

  await waitForNode(page, sourceId);
  await waitForNode(page, targetId);
  await waitForNode(page, chuteId);

  await createConveyor(request, backendUrl, session, {
    id: conveyorId,
    sourceId,
    targetId,
    speed: 0,
    active: false,
  });
  await waitForEdge(page, conveyorId);

  await createItem(request, backendUrl, session, {
    id: activeItemId,
    name: "HUD Active Item",
    locationId: sourceId,
    positionType: "LOCATION",
  });
  await createItem(request, backendUrl, session, {
    id: priorityItemId,
    name: "HUD Priority Item",
    locationId: chuteId,
    positionType: "LOCATION",
    priority: 1,
  });
  await waitForItem(page, activeItemId);
  await waitForItem(page, priorityItemId);

  await waitForHudValue(page, "active", before.Active + 2);
  await waitForHudValue(page, "priority", before.Priority + 1);
  await waitForHudValue(page, "stopped", before.Stopped + 1);
  await waitForHudValue(page, "full-chutes", before["Full chutes"] + 1);

  await emptyChute(request, backendUrl, session, chuteId);

  await waitForHudValue(page, "full-chutes", before["Full chutes"]);
  await waitForHudValue(page, "active", before.Active + 1);
});

test("simulation throughput websocket stays isolated from live throughput topic", async ({
  page,
  request,
}) => {
  const id = uniqueE2eId("sim-throughput");
  const restoreTimestamp = new Date(Date.now() - 1_000).toISOString();

  const createSimulationResponse = await request.post(`${backendUrl}/api/simulations`, {
    headers: authHeaders(),
    data: { timestamp: restoreTimestamp },
  });
  expect(createSimulationResponse.status()).toBe(202);
  const simulationId = ((await createSimulationResponse.json()) as { id: string }).id;

  try {
    await waitForSimulationReady(request, simulationId);

    const liveTopic = "/topic/analytics/throughput";
    const simulationTopic = `/topic/simulations/${simulationId}/analytics/throughput`;
    await page.goto("/live");
    await startStompRecorder(page, backendUrl, [liveTopic, simulationTopic]);

    const locationId = `${id}-location`;
    await createSimulationLocation(request, simulationId, {
      id: locationId,
      name: "Simulation Throughput Source",
      latitude: 0,
      longitude: 0,
    });
    await createSimulationItem(request, simulationId, {
      id: `${id}-item`,
      name: "Simulation Throughput Item",
      locationId,
    });

    await waitForThroughputSocketMetric(
      page,
      simulationTopic,
      (metric) => metric.itemsEntered === 1,
    );

    const liveEnteredEvents = (await getRecordedSocketEvents(page)).filter((event) => {
      const metric = event.envelope.payload as { itemsEntered?: number };
      return event.topic === liveTopic && metric.itemsEntered === 1;
    });
    expect(liveEnteredEvents, "simulation throughput must not be broadcast on the live topic").toEqual([]);
  } finally {
    await request.delete(`${backendUrl}/api/simulations/${simulationId}`, { headers: authHeaders() });
  }
});

test("analytics panel renders the live throughput header", async ({ page }) => {
  await page.goto("/live");
  await waitForGraphTestApi(page);

  await page.getByRole("button", { name: "Live interactions" }).click();
  await page.getByRole("button", { name: "Charts" }).click();

  await expect(page.getByText("Live · 5 min totals, refreshed every 5 sec")).toBeVisible();
  await expect(page.getByText("System Throughput")).toBeVisible();
});

const authHeaders = (simulationId?: string) => ({
  Authorization: `Bearer ${session.token}`,
  ...(simulationId ? { "X-Simulation-ID": simulationId } : {}),
});

const waitForSimulationReady = async (
  request: Parameters<typeof createSimulationLocation>[0],
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
};

const createSimulationLocation = async (
  request: Parameters<typeof createItem>[0],
  simulationId: string,
  location: { id: string; name: string; latitude: number; longitude: number },
) => {
  const response = await request.post(`${backendUrl}/api/locations`, {
    headers: authHeaders(simulationId),
    data: {
      ...location,
      type: "GENERIC",
      active: true,
      capacity: 10,
      properties: {},
    },
  });
  expect(response.ok()).toBeTruthy();
};

const createSimulationItem = async (
  request: Parameters<typeof createItem>[0],
  simulationId: string,
  item: { id: string; name: string; locationId: string },
) => {
  const response = await request.post(`${backendUrl}/api/items`, {
    headers: authHeaders(simulationId),
    data: {
      ...item,
      active: true,
      priority: 0,
      positionType: "LOCATION",
      progress: 0,
      properties: {},
      timestamp: new Date().toISOString(),
    },
  });
  expect(response.ok()).toBeTruthy();
};
