import { test } from "./fixtures";
import { expect } from "@playwright/test";
import {
  activateConveyor,
  createConveyor,
  createItem,
  createLocation,
  deactivateConveyor,
  emptyChute,
  seedMovingItemGraph,
  uniqueE2eId,
  updateDestinationMappings,
  updateDestinationExitMappings,
  updateSensorMappings,
  updateConveyorSpeed,
} from "./helpers/api";
import { AuthSession, installAuthSession, loginAsSuperadmin } from "./helpers/auth";
import {
  expectItemPositionStable,
  getItemPosition,
  waitForChuteItemCount,
  waitForConveyorSpeed,
  waitForEdge,
  waitForGraphTestApi,
  waitForItem,
  waitForItemDestinationAndPath,
  waitForItemToMove,
  waitForItemToMoveFrom,
  waitForNode,
} from "./helpers/graph";
import { getHudValues, waitForHudValue } from "./helpers/metrics";

const backendUrl = process.env.E2E_BACKEND_URL ?? "http://127.0.0.1:18080";
let session: AuthSession;

test.describe.configure({ mode: "serial" });

test.beforeAll(async ({ request }) => {
  session = await loginAsSuperadmin(request, backendUrl);
});

test.beforeEach(async ({ page }) => {
  await installAuthSession(page, session);
});

test.afterEach(async ({ request }) => {
  await updateDestinationMappings(request, backendUrl, session, []);
  await updateDestinationExitMappings(request, backendUrl, session, []);
  await updateSensorMappings(request, backendUrl, session, []);
});

test("URL and navigation share What If and Live transitions", async ({ page, request }) => {
  const created = page.waitForResponse(response =>
    response.url().endsWith('/api/simulations/what-if') && response.ok());
  await page.goto('/live?mode=what-if');
  const branch = await (await created).json() as { id: string };
  try {
    const modes = page.getByRole('navigation', { name: 'Operational modes' });
    await expect(modes.getByRole('button', { name: 'What-if' })).toHaveClass(/bg-blue-600/);
    await modes.getByRole('button', { name: 'Live' }).click();
    await expect(modes.getByRole('button', { name: 'Live' })).toHaveClass(/bg-blue-600/);
    await expect.poll(async () => {
      const response = await request.get(`${backendUrl}/api/simulations/${branch.id}`, {
        headers: { Authorization: `Bearer ${session.token}` },
      });
      return response.status();
    }).toBe(404);
  } finally {
    await request.delete(`${backendUrl}/api/simulations/${branch.id}`, {
      headers: { Authorization: `Bearer ${session.token}` },
    });
  }
});

test("websocket-created graph entities appear and animate without reload", async ({ page, request }) => {
  const id = uniqueE2eId("ws-create");
  const sourceId = `${id}-source`;
  const targetId = `${id}-target`;
  const conveyorId = `${id}-conveyor`;
  const itemId = `${id}-item`;

  await page.goto("/live");
  await waitForGraphTestApi(page);

  await createLocation(request, backendUrl, session, {
    id: sourceId,
    name: "WebSocket Source",
    latitude: 0,
    longitude: 0,
  });
  await waitForNode(page, sourceId);

  await createLocation(request, backendUrl, session, {
    id: targetId,
    name: "WebSocket Target",
    latitude: 100,
    longitude: 0,
  });
  await waitForNode(page, targetId);

  await createConveyor(request, backendUrl, session, {
    id: conveyorId,
    sourceId,
    targetId,
    length: 1_000,
    speed: 50,
  });
  await waitForEdge(page, conveyorId);

  await createItem(request, backendUrl, session, {
    id: itemId,
    name: "WebSocket Item",
    locationId: conveyorId,
    positionType: "CONVEYOR",
    progress: 0,
  });
  await waitForItem(page, itemId);
  await waitForItemToMove(page, itemId);
});

test("websocket-created mapped item exposes destination and path without reload", async ({ page, request }) => {
  const id = uniqueE2eId("ws-destination");
  const sourceId = `${id}-source`;
  const destinationId = `${id}-destination`;
  const logicalDestination = `${id}-logical-destination`;
  const conveyorId = `${id}-conveyor`;
  const itemId = `${id}-item`;
  const flightNumber = `${id}-flight`;
  const now = Date.now();

  await page.goto("/live");
  await waitForGraphTestApi(page);
  const beforePriorityCount = (await getHudValues(page)).Priority;

  await createLocation(request, backendUrl, session, {
    id: sourceId,
    name: "Mapped Source",
    latitude: 0,
    longitude: 0,
  });
  await waitForNode(page, sourceId);

  await createLocation(request, backendUrl, session, {
    id: destinationId,
    name: "Mapped Destination",
    latitude: 100,
    longitude: 0,
  });
  await waitForNode(page, destinationId);

  await createConveyor(request, backendUrl, session, {
    id: conveyorId,
    sourceId,
    targetId: destinationId,
    length: 100,
    speed: 20,
  });
  await waitForEdge(page, conveyorId);

  await updateDestinationMappings(request, backendUrl, session, [
    {
      fieldName: "flight_number",
      dataType: "STRING",
      operator: "EQUAL",
      value: flightNumber,
      destinations: [logicalDestination],
      validFrom: new Date(now - 60_000).toISOString(),
      rushAt: new Date(now - 30_000).toISOString(),
      validTo: new Date(now + 3_600_000).toISOString(),
    },
  ]);
  await updateDestinationExitMappings(request, backendUrl, session, [
    {
      destination: logicalDestination,
      exits: [destinationId],
    },
  ]);

  await createItem(request, backendUrl, session, {
    id: itemId,
    name: "Mapped WebSocket Item",
    locationId: sourceId,
    positionType: "LOCATION",
    properties: { flight_number: flightNumber },
  });

  await waitForItem(page, itemId);
  await waitForItemDestinationAndPath(
    page,
    itemId,
    [logicalDestination],
    destinationId,
    [sourceId, destinationId],
  );
  await expect.poll(() => page.evaluate((id) => {
    const attributes = window.__graphTestApi?.getNode(id)?.attributes;
    return attributes && {
      priority: attributes.priority,
      effectivePriority: attributes.effectivePriority,
      rushActive: attributes.rushActive,
    };
  }, itemId)).toEqual({ priority: 0, effectivePriority: 1, rushActive: true });
  await waitForHudValue(page, "priority", beforePriorityCount + 1);
});

test("admin imports and persists destination exit JSON-array CSV", async ({ page, request }) => {
  const destination = "e2e-csv-destination";
  const exits = ["e2e-csv-exit-a", "e2e-csv-exit-b"];

  await page.goto("/admin/destination-mappings");
  const section = page.getByRole("heading", { name: "Destinations To Exits" }).locator("xpath=ancestor::section");
  await expect(section.getByRole("button", { name: "Import CSV" })).toBeEnabled();
  await section.locator('input[type="file"]').setInputFiles("e2e/fixtures/destination-exits.csv");
  await section.getByRole("button", { name: "Save" }).click();

  await expect.poll(async () => {
    const response = await request.get(`${backendUrl}/api/destination-exit-mappings`, {
      headers: { Authorization: `Bearer ${session.token}` },
    });
    return response.json();
  }).toEqual([{ destination, exits }]);
});

test("admin saves multiple logical and direct destinations and exits from list editors", async ({ page, request }) => {
  const id = uniqueE2eId("destination-list");
  const directLocationId = `${id}-direct-location`;
  const firstExitId = `${id}-exit-a`;
  const secondExitId = `${id}-exit-b`;
  const logicalDestination = `${id}-logical-destination`;
  const propertyValue = `${id}-property-value`;

  await createLocation(request, backendUrl, session, { id: directLocationId, name: "Direct destination", latitude: 0, longitude: 0 });
  await createLocation(request, backendUrl, session, { id: firstExitId, name: "First exit", latitude: 100, longitude: 0 });
  await createLocation(request, backendUrl, session, { id: secondExitId, name: "Second exit", latitude: 200, longitude: 0 });

  await page.goto("/admin/destination-mappings");
  const exitsSection = page.getByRole("heading", { name: "Destinations To Exits" }).locator("xpath=ancestor::section");
  await exitsSection.getByRole("button", { name: "Add Mapping" }).click();
  const exitDialog = page.getByRole("dialog");
  await exitDialog.getByLabel("Destination", { exact: true }).fill(logicalDestination);
  await exitDialog.getByLabel("Exits 1").fill(firstExitId);
  await exitDialog.getByRole("button", { name: "+ Add exit" }).click();
  await exitDialog.getByLabel("Exits 2").fill(secondExitId);
  await exitDialog.getByRole("button", { name: "Add", exact: true }).click();
  await exitsSection.getByRole("button", { name: "Save", exact: true }).click();

  await expect.poll(async () => {
    const response = await request.get(`${backendUrl}/api/destination-exit-mappings`, {
      headers: { Authorization: `Bearer ${session.token}` },
    });
    return response.json();
  }).toEqual([{ destination: logicalDestination, exits: [firstExitId, secondExitId] }]);

  const propertiesSection = page.getByRole("heading", { name: "Property To Destinations" }).locator("xpath=ancestor::section");
  await propertiesSection.getByRole("button", { name: "Add Mapping" }).click();
  const propertyDialog = page.getByRole("dialog");
  await propertyDialog.getByLabel("Field", { exact: true }).fill("flight_number");
  await propertyDialog.getByLabel("Value", { exact: true }).fill(propertyValue);
  await propertyDialog.getByLabel("Destinations 1").fill(logicalDestination);
  await propertyDialog.getByRole("button", { name: "+ Add destination" }).click();
  await propertyDialog.getByLabel("Destinations 2").fill(directLocationId);
  await propertyDialog.getByRole("button", { name: "Add", exact: true }).click();
  await propertiesSection.getByRole("button", { name: "Save", exact: true }).click();

  await expect.poll(async () => {
    const response = await request.get(`${backendUrl}/api/destination-mappings`, {
      headers: { Authorization: `Bearer ${session.token}` },
    });
    return response.json();
  }).toEqual([expect.objectContaining({
    fieldName: "flight_number",
    value: propertyValue,
    destinations: [logicalDestination, directLocationId],
  })]);
});

test("admin accepts legacy and rush destination-mapping CSV files", async ({ page, request }) => {
  await page.goto("/admin/destination-mappings");
  const section = page.getByRole("heading", { name: "Property To Destinations" }).locator("xpath=ancestor::section");

  await expect(section.getByRole("button", { name: "Import CSV" })).toBeEnabled();
  await section.locator('input[type="file"]').setInputFiles("e2e/fixtures/destination-properties-legacy.csv");
  await expect(section.locator("tbody tr").first().locator("input").nth(1)).toHaveValue("LEGACY-123");
  await section.getByRole("button", { name: "Save" }).click();
  await expect.poll(async () => {
    const response = await request.get(`${backendUrl}/api/destination-mappings`, {
      headers: { Authorization: `Bearer ${session.token}` },
    });
    return response.json();
  }).toEqual([expect.objectContaining({ value: "LEGACY-123", rushAt: null })]);

  await expect(section.getByRole("button", { name: "Import CSV" })).toBeEnabled();
  await section.locator('input[type="file"]').setInputFiles("e2e/fixtures/destination-properties-rush.csv");
  await expect(section.locator("tbody tr").first().locator("input").nth(1)).toHaveValue("RUSH-123");
  await section.getByRole("button", { name: "Save" }).click();
  await expect.poll(async () => {
    const response = await request.get(`${backendUrl}/api/destination-mappings`, {
      headers: { Authorization: `Bearer ${session.token}` },
    });
    return response.json();
  }).toEqual([expect.objectContaining({ value: "RUSH-123", rushAt: "2098-01-01T00:00:00Z" })]);
});

test("admin imports sensor CSV and renders a triangle at the configured conveyor progress", async ({ page, request }) => {
  const id = uniqueE2eId("sensor-csv");
  const sourceId = `${id}-source`;
  const targetId = `${id}-target`;
  const conveyorId = `${id}-conveyor`;
  const sensorName = `${id}-scanner`;

  await createLocation(request, backendUrl, session, { id: sourceId, name: "Sensor source", latitude: 0, longitude: 0 });
  await createLocation(request, backendUrl, session, { id: targetId, name: "Sensor target", latitude: 100, longitude: 0 });
  await createConveyor(request, backendUrl, session, { id: conveyorId, sourceId, targetId, length: 100, speed: 10 });

  await page.goto("/admin/sensors");
  const section = page.getByRole("heading", { name: "Sensors" }).locator("xpath=ancestor::section");
  await section.locator('input[type="file"]').setInputFiles({
    name: "sensors.csv",
    mimeType: "text/csv",
    buffer: Buffer.from(`sensorName,conveyorId,progress\n${sensorName},${conveyorId},20\n`),
  });
  await section.getByRole("button", { name: "Save" }).click();
  await expect.poll(async () => {
    const response = await request.get(`${backendUrl}/api/sensor-mappings`, { headers: { Authorization: `Bearer ${session.token}` } });
    return response.json();
  }).toEqual([{ sensorName, conveyorId, progress: 20 }]);

  await page.goto("/live");
  await waitForGraphTestApi(page);
  await expect.poll(() => page.evaluate((nodeId) => window.__graphTestApi?.getNode(nodeId)?.attributes, `sensor:${sensorName}`))
    .toMatchObject({ isSensor: true, type: "triangle", x: 20, y: 0, progress: 20 });
});

test("stop condition freezes moving item", async ({ page, request }) => {
  const seeded = await seedMovingItemGraph(request, backendUrl, session, { length: 1_000, speed: 50 });

  await page.goto("/live");
  await waitForGraphTestApi(page);

  await waitForItemToMove(page, seeded.itemId);

  await deactivateConveyor(request, backendUrl, session, seeded.conveyorId);
  await waitForConveyorSpeed(page, seeded.conveyorId, 0);
  await expectItemPositionStable(page, seeded.itemId);
});

test("conveyor activation resumes a stopped animated item", async ({ page, request }) => {
  const seeded = await seedMovingItemGraph(request, backendUrl, session, { length: 1_000, speed: 50 });

  await page.goto("/live");
  await waitForGraphTestApi(page);
  await waitForItemToMove(page, seeded.itemId);

  await deactivateConveyor(request, backendUrl, session, seeded.conveyorId);
  await waitForConveyorSpeed(page, seeded.conveyorId, 0);
  await expectItemPositionStable(page, seeded.itemId);
  const stoppedPosition = await getItemPosition(page, seeded.itemId);

  await activateConveyor(request, backendUrl, session, seeded.conveyorId);
  await waitForConveyorSpeed(page, seeded.conveyorId, 50);
  await waitForItemToMoveFrom(page, seeded.itemId, stoppedPosition);
});

test("speed change websocket updates edge speed and movement continues", async ({ page, request }) => {
  const seeded = await seedMovingItemGraph(request, backendUrl, session, { length: 1_000, speed: 20 });

  await page.goto("/live");
  await waitForGraphTestApi(page);
  await waitForItemToMove(page, seeded.itemId);

  await updateConveyorSpeed(request, backendUrl, session, seeded.conveyorId, 80);
  await waitForConveyorSpeed(page, seeded.conveyorId, 80);
  await waitForItemToMove(page, seeded.itemId);
});

test("chute empty websocket clears items in chute", async ({ page, request }) => {
  const chuteId = uniqueE2eId("chute");
  const itemId = `${chuteId}-item`;

  await createLocation(request, backendUrl, session, {
    id: chuteId,
    name: "E2E Chute",
    latitude: 0,
    longitude: 0,
    type: "CHUTE",
    capacity: 5,
  });
  await createItem(request, backendUrl, session, {
    id: itemId,
    name: "Chute Item",
    locationId: chuteId,
    positionType: "LOCATION",
  });

  await page.goto("/live");
  await waitForGraphTestApi(page);
  await waitForChuteItemCount(page, chuteId, 1);

  await emptyChute(request, backendUrl, session, chuteId);
  await waitForChuteItemCount(page, chuteId, 0);
});

test("admin releases one staged FIFO batch into sequential websocket departures", async ({ page, request }) => {
  const id = uniqueE2eId("staging-release");
  const sourceId = `${id}-source`;
  const junctionId = `${id}-junction`;
  const targetId = `${id}-target`;
  const stagingId = `${id}-staging`;
  const downstreamId = `${id}-downstream`;
  const headId = `${id}-head`;
  const tailId = `${id}-tail`;

  await createLocation(request, backendUrl, session, {
    id: sourceId, name: "Staging Source", latitude: 0, longitude: 0,
  });
  await createLocation(request, backendUrl, session, {
    id: junctionId, name: "Staging Junction", latitude: 100, longitude: 0,
  });
  await createLocation(request, backendUrl, session, {
    id: targetId, name: "Staging Target", latitude: 200, longitude: 0,
  });
  await createConveyor(request, backendUrl, session, {
    id: stagingId, sourceId, targetId: junctionId, length: 4, speed: 1,
    minDistance: 2, type: "STAGING",
  });
  await createConveyor(request, backendUrl, session, {
    id: downstreamId, sourceId: junctionId, targetId, length: 100, speed: 1,
  });
  await createItem(request, backendUrl, session, {
    id: headId, name: "Staged Head", locationId: stagingId, positionType: "CONVEYOR",
  });
  await createItem(request, backendUrl, session, {
    id: tailId, name: "Staged Tail", locationId: stagingId, positionType: "CONVEYOR",
  });

  await page.goto("/live");
  await waitForGraphTestApi(page);
  await waitForItem(page, headId);
  await waitForItem(page, tailId);
  await page.waitForTimeout(4_500);

  await page.getByRole("button", { name: /Live interactions/ }).click();
  await page.getByRole("button", { name: "Commands", exact: true }).click();
  const commands = page.getByRole("heading", { name: "Commands" }).locator("xpath=ancestor::div[form]");
  await commands.getByRole("combobox").selectOption("RELEASE_STAGING");
  await commands.getByPlaceholder("e.g. conveyor-01").fill(stagingId);
  const release = page.waitForResponse((response) =>
    response.url().endsWith(`/api/conveyors/${stagingId}/release`) && response.status() === 202);
  await commands.getByRole("button", { name: "Send Command" }).click();
  await release;

  await expect.poll(async () => page.evaluate(
    ({ itemId }) => window.__graphTestApi!.getItem(itemId).activeItem?.currentEdgeId,
    { itemId: headId },
  )).toBe(downstreamId);
  expect(await page.evaluate(
    ({ itemId }) => window.__graphTestApi!.getItem(itemId).activeItem?.currentEdgeId,
    { itemId: tailId },
  )).toBe(stagingId);
  await expect.poll(async () => page.evaluate(
    ({ itemId }) => window.__graphTestApi!.getItem(itemId).activeItem?.currentEdgeId,
    { itemId: tailId },
  )).toBe(downstreamId);
});
