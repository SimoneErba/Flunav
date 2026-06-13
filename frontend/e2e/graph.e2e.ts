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
});

test("admin imports and persists destination exit JSON-array CSV", async ({ page, request }) => {
  const destination = "e2e-csv-destination";
  const exits = ["e2e-csv-exit-a", "e2e-csv-exit-b"];

  await page.goto("/admin/destination-mappings");
  const section = page.getByRole("heading", { name: "Destinations To Exits" }).locator("xpath=ancestor::section");
  await section.locator('input[type="file"]').setInputFiles("e2e/fixtures/destination-exits.csv");
  await section.getByRole("button", { name: "Save" }).click();

  await expect.poll(async () => {
    const response = await request.get(`${backendUrl}/api/destination-exit-mappings`, {
      headers: { Authorization: `Bearer ${session.token}` },
    });
    return response.json();
  }).toEqual([{ destination, exits }]);
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
