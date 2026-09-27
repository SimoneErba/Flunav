import { test, expect } from './fixtures';
import { activateConveyor, createConveyor, createItem, createLocation, uniqueE2eId } from './helpers/api';
import { installAuthSession, loginAsSuperadmin } from './helpers/auth';
import { waitForConveyorSpeed, waitForEdge, waitForGraphTestApi, waitForNode, waitForItem } from './helpers/graph';

const backendUrl = process.env.E2E_BACKEND_URL ?? 'http://127.0.0.1:18080';
let cleanup: { token: string; items: string[]; locations: string[]; conveyors: Array<[string, string]> };

test.afterEach(async ({ request, page }) => {
  await page.close();
  if (!cleanup) return;
  const headers = { Authorization: `Bearer ${cleanup.token}` };
  for (const id of cleanup.items) {
    expect((await request.delete(`${backendUrl}/api/items/${id}`, { headers })).ok()).toBeTruthy();
  }
  for (const [sourceId, targetId] of cleanup.conveyors) {
    expect((await request.delete(`${backendUrl}/api/conveyors`, { headers, params: { sourceId, targetId } })).ok()).toBeTruthy();
  }
  for (const id of cleanup.locations) {
    expect((await request.delete(`${backendUrl}/api/locations/${id}`, { headers })).ok()).toBeTruthy();
  }
});

for (const clockLagMs of [0, 2_000]) {
test(`new items traverse every conveyor without reload (clock lag ${clockLagMs}ms)`, async ({ page, request }) => {
  const session = await loginAsSuperadmin(request, backendUrl);
  cleanup = { token: session.token, items: [], locations: [], conveyors: [] };
  await installAuthSession(page, session);
  const messages: Array<{ operation?: string; data?: { id?: string; locationId?: string; currentEdgeId?: string | null }; itemId?: string; edgeId?: string }> = [];
  page.on('websocket', socket => socket.on('framereceived', ({ payload }) => {
    const frame = payload.toString();
    if (!frame.startsWith('MESSAGE\n')) return;
    const envelope = JSON.parse(frame.slice(frame.indexOf('\n\n') + 2).replace(/\0+$/, '')) as {
      payload: (typeof messages)[number];
    };
    messages.push(envelope.payload);
  }));
  if (clockLagMs) await page.clock.install({ time: new Date(Date.now() - clockLagMs) });
  await page.goto('/live');
  await waitForGraphTestApi(page);
  const prefix = uniqueE2eId('visibility');
  const nodes = Array.from({ length: 4 }, (_, index) => `${prefix}-node-${index}`);
  const itemId = `${prefix}-item`;
  for (const [index, id] of nodes.entries()) {
    await createLocation(request, backendUrl, session, {
      id, name: id, latitude: index * 20, longitude: 0,
      type: index === 3 ? 'CHUTE' : 'JUNCTION', capacity: index === 3 ? 10 : 0,
    });
    cleanup.locations.push(id);
    await waitForNode(page, id);
  }
  const edges: string[] = [];
  for (let index = 0; index < nodes.length - 1; index++) {
    const id = `${prefix}-edge-${index}`;
    edges.push(id);
    await createConveyor(request, backendUrl, session, {
      id, sourceId: nodes[index], targetId: nodes[index + 1], length: 1, speed: 0.25,
    });
    cleanup.conveyors.push([nodes[index], nodes[index + 1]]);
    await waitForEdge(page, id);
    await activateConveyor(request, backendUrl, session, id);
    await waitForConveyorSpeed(page, id, 0.25);
  }

  let snapshotRequests = 0;
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/api/graph') snapshotRequests++;
  });
  // Sample every rendered frame so a later successful poll cannot mask disappearance.
  const observation = page.evaluate(({ itemId, destination, firstEdge }) => new Promise<{
    failures: string[]; visitedEdges: string[]; reachedDestination: boolean; movedOnFirst: boolean; workspaceRenders: number;
  }>(resolve => {
    const failures: string[] = [];
    const visitedEdges = new Set<string>();
    let seen = false;
    let movedOnFirst = false;
    const started = performance.now();
    const renderStart = window.__workspaceRenderCount ?? 0;
    const sample = () => {
      const item = window.__graphTestApi!.getItem(itemId);
      const chuteItems = window.__graphTestApi!.getNode(destination)?.attributes.itemsInChute;
      const reachedDestination = Array.isArray(chuteItems) && chuteItems.some(item => item.id === itemId);
      if (item.graphNode) {
        seen = true;
        const attrs = item.graphNode.attributes;
        if (attrs.hidden === true) failures.push('hidden');
        if (!Number.isFinite(Number(attrs.x)) || !Number.isFinite(Number(attrs.y))) failures.push('invalid coordinates');
        if (typeof item.activeItem?.currentEdgeId === 'string') visitedEdges.add(item.activeItem.currentEdgeId);
        if (item.activeItem?.currentEdgeId === firstEdge && Number(attrs.x) > 1 && Number(attrs.x) < 19) {
          movedOnFirst = true;
        }
      } else if (seen && !reachedDestination) failures.push('removed');
      if (reachedDestination || performance.now() - started > 20_000) {
        resolve({ failures, visitedEdges: [...visitedEdges], reachedDestination, movedOnFirst,
          workspaceRenders: (window.__workspaceRenderCount ?? 0) - renderStart });
      } else requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
  }), { itemId, destination: nodes[3], firstEdge: edges[0] });
  await createItem(request, backendUrl, session, {
    id: itemId, name: itemId, locationId: nodes[0], positionType: 'LOCATION', priority: 1,
  });
  cleanup.items.push(itemId);
  const result = await observation;
  expect(result.failures).toEqual([]);
  expect(result.visitedEdges).toEqual(edges);
  expect(result.movedOnFirst).toBe(true);
  expect(result.reachedDestination).toBe(true);
  expect(result.workspaceRenders, 'playback should not render React at frame rate').toBeLessThan(20);
  expect(snapshotRequests, 'a REST reload must not hide missing websocket state').toBe(0);
  const creationIndex = messages.findIndex(message => message.operation === 'CREATED' && message.data?.id === itemId);
  const departureIndex = messages.findIndex(message => message.itemId === itemId && message.edgeId === edges[0]);
  expect(creationIndex).toBeGreaterThanOrEqual(0);
  expect(messages[creationIndex].data).toMatchObject({ locationId: nodes[0], currentEdgeId: null });
  expect(departureIndex, 'the initial move must arrive after creation').toBeGreaterThan(creationIndex);

  // A later arrival must work after all initial graph loading has finished too.
  const laterItem = `${prefix}-later`;
  await createItem(request, backendUrl, session, {
    id: laterItem, name: laterItem, locationId: nodes[0], positionType: 'LOCATION',
  });
  cleanup.items.push(laterItem);
  await expect.poll(() => page.evaluate(id => window.__graphTestApi!.getItem(id).activeItem?.currentEdgeId, laterItem))
    .toBe(edges[0]);
  expect(snapshotRequests).toBe(0);
});
}

test('percentage position checkpoints stay on the correct part of the conveyor', async ({ page, request }) => {
  const session = await loginAsSuperadmin(request, backendUrl);
  cleanup = { token: session.token, items: [], locations: [], conveyors: [] };
  await installAuthSession(page, session);
  await page.goto('/live');
  await waitForGraphTestApi(page);
  const prefix = uniqueE2eId('checkpoint');
  for (const [index, suffix] of ['source', 'target'].entries()) {
    const id = `${prefix}-${suffix}`;
    await createLocation(request, backendUrl, session, {
      id, name: id, latitude: index * 100, longitude: 0, type: 'JUNCTION', capacity: 0,
    });
    cleanup.locations.push(id);
    await waitForNode(page, id);
  }
  const edge = `${prefix}-edge`;
  await createConveyor(request, backendUrl, session, {
    id: edge, sourceId: `${prefix}-source`, targetId: `${prefix}-target`, length: 100, speed: 1,
  });
  cleanup.conveyors.push([`${prefix}-source`, `${prefix}-target`]);
  await waitForEdge(page, edge);
  const itemId = `${prefix}-item`;
  await createItem(request, backendUrl, session, {
    id: itemId, name: itemId, locationId: edge, positionType: 'CONVEYOR', progress: 25,
  });
  cleanup.items.push(itemId);
  await waitForItem(page, itemId);
  const readX = () => page.evaluate(id => Number(window.__graphTestApi!.getItem(id).graphNode?.attributes.x), itemId);
  await expect.poll(readX).toBeGreaterThanOrEqual(25);
  expect(await readX()).toBeLessThan(35);
  const response = await request.post(`${backendUrl}/api/positions`, {
    headers: { Authorization: `Bearer ${session.token}` },
    data: { itemId, locationId: edge, progress: 50 },
  });
  expect(response.ok()).toBeTruthy();
  await expect.poll(readX).toBeGreaterThanOrEqual(50);
  expect(await readX()).toBeLessThan(60);
});
