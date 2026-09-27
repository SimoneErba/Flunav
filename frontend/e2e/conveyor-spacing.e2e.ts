import { test, expect } from './fixtures';
import { createConveyor, createItem, createLocation, uniqueE2eId } from './helpers/api';
import { installAuthSession, loginAsSuperadmin } from './helpers/auth';
import { waitForEdge, waitForGraphTestApi, waitForItem, waitForNode } from './helpers/graph';

const backendUrl = process.env.E2E_BACKEND_URL ?? 'http://127.0.0.1:18080';

test('belt and roller feeders hold independently at a slow merge', async ({ page, request }) => {
  const session = await loginAsSuperadmin(request, backendUrl);
  await installAuthSession(page, session);
  await page.goto('/live');
  await waitForGraphTestApi(page);

  const prefix = uniqueE2eId('spacing');
  const nodes = {
    belt: `${prefix}-belt-source`, roller: `${prefix}-roller-source`,
    merge: `${prefix}-merge`, exit: `${prefix}-exit`,
  };
  const edges = { belt: `${prefix}-belt`, roller: `${prefix}-roller`, shared: `${prefix}-shared` };
  const ids = { occupant: `${prefix}-occupant`, belt: `${prefix}-belt-item`, roller: `${prefix}-roller-item` };
  const headers = { Authorization: `Bearer ${session.token}` };

  try {
    for (const [index, id] of Object.values(nodes).entries()) {
      await createLocation(request, backendUrl, session, {
        id, name: id, latitude: index * 20, longitude: index === 1 ? 20 : 0,
        type: id === nodes.exit ? 'CHUTE' : 'JUNCTION', capacity: 20,
      });
      await waitForNode(page, id);
    }
    for (const [id, sourceId, targetId, type, speed] of [
      [edges.belt, nodes.belt, nodes.merge, 'BELT', 1],
      [edges.roller, nodes.roller, nodes.merge, 'ROLLER', 1],
      [edges.shared, nodes.merge, nodes.exit, 'BELT', 0.05],
    ] as const) {
      await createConveyor(request, backendUrl, session, {
        id, sourceId, targetId, type, length: 1, speed, minDistance: 0.05,
      });
      await waitForEdge(page, id);
    }

    await createItem(request, backendUrl, session, {
      id: ids.occupant, name: ids.occupant, locationId: edges.shared,
      progress: 0, properties: { lengthCm: 15 },
    });
    await waitForItem(page, ids.occupant);
    await createItem(request, backendUrl, session, {
      id: ids.belt, name: ids.belt, locationId: edges.belt, progress: 100,
      properties: { lengthCm: 15 },
    });
    await createItem(request, backendUrl, session, {
      id: ids.roller, name: ids.roller, locationId: edges.roller, progress: 100,
    });
    await waitForItem(page, ids.belt);
    await waitForItem(page, ids.roller);

    const state = () => page.evaluate(({ ids, edges }) => ({
      beltPaused: window.__graphTestApi!.getItem(ids.belt).activeItem?.flowPaused,
      rollerPaused: window.__graphTestApi!.getItem(ids.roller).activeItem?.flowPaused,
      beltStopped: window.__graphTestApi!.getEdge(edges.belt)?.attributes.flowStopped,
      rollerStopped: window.__graphTestApi!.getEdge(edges.roller)?.attributes.flowStopped,
    }), { ids, edges });
    await expect.poll(state).toMatchObject({
      beltPaused: true, rollerPaused: true, beltStopped: true, rollerStopped: false,
    });

    const pausedX = await page.evaluate(id =>
      Number(window.__graphTestApi!.getItem(id).graphNode?.attributes.x), ids.belt);
    await page.waitForTimeout(500);
    const stillX = await page.evaluate(id =>
      Number(window.__graphTestApi!.getItem(id).graphNode?.attributes.x), ids.belt);
    expect(stillX).toBeCloseTo(pausedX, 2);

    await expect.poll(() => page.evaluate(id =>
      window.__graphTestApi!.getItem(id).activeItem?.currentEdgeId, ids.belt),
    { timeout: 12_000 }).toBe(edges.shared);
    await expect.poll(() => page.evaluate(id =>
      window.__graphTestApi!.getItem(id).activeItem?.currentEdgeId, ids.roller),
    { timeout: 12_000 }).toBe(edges.shared);
  } finally {
    for (const id of Object.values(ids)) {
      await request.delete(`${backendUrl}/api/items/${id}`, { headers });
    }
    for (const [sourceId, targetId] of [
      [nodes.merge, nodes.exit], [nodes.belt, nodes.merge], [nodes.roller, nodes.merge],
    ]) {
      await request.delete(`${backendUrl}/api/conveyors`, {
        headers, params: { sourceId, targetId },
      });
    }
    for (const id of Object.values(nodes)) {
      await request.delete(`${backendUrl}/api/locations/${id}`, { headers });
    }
  }
});
