import { writeFile } from 'node:fs/promises';
import { test, expect } from './fixtures';
import { createConveyor, createItem, createLocation, seedMovingItemGraph, uniqueE2eId, updateConveyorSpeed } from './helpers/api';
import { installAuthSession, loginAsSuperadmin, type AuthSession } from './helpers/auth';
import { getItemPosition, waitForGraphTestApi, waitForItem, waitForItemToMove, waitForNode } from './helpers/graph';

const backendUrl = process.env.E2E_BACKEND_URL ?? 'http://127.0.0.1:18080';
let session: AuthSession;
let seededCleanup: Awaited<ReturnType<typeof seedMovingItemGraph>> | null = null;
const headers = () => ({ Authorization: `Bearer ${session.token}` });

test.beforeAll(async ({ request }) => { session = await loginAsSuperadmin(request, backendUrl); });
test.beforeEach(async ({ page }) => { seededCleanup = null; await installAuthSession(page, session); });
test.afterEach(async ({ request, page }) => {
  await page.close();
  if (!seededCleanup) return;
  const { itemId, sourceId, targetId } = seededCleanup;
  await request.delete(`${backendUrl}/api/items/${itemId}`, { headers: headers() });
  await request.delete(`${backendUrl}/api/conveyors`, { headers: headers(), params: { sourceId, targetId } });
  await request.delete(`${backendUrl}/api/locations/${sourceId}`, { headers: headers() });
  await request.delete(`${backendUrl}/api/locations/${targetId}`, { headers: headers() });
});

test('reconnect reconciles missed topology with one snapshot and keeps items moving', async ({ page, request }) => {
  await page.addInitScript(() => {
    const OriginalSocket = window.WebSocket;
    window.WebSocket = class extends OriginalSocket {
      constructor(url: string | URL, protocols?: string | string[]) {
        super(url, protocols);
        if (String(url).includes('/ws/websocket')) {
          (window as Window & { __e2eSocket?: WebSocket }).__e2eSocket = this;
        }
      }
    };
  });
  const seeded = seededCleanup = await seedMovingItemGraph(request, backendUrl, session, { length: 100_000, speed: 50 });
  let graphRequests = 0;
  page.on('request', req => { if (/\/api\/graph(?:\?|$)/.test(req.url())) graphRequests++; });
  await page.goto('/live');
  await waitForGraphTestApi(page);
  await waitForItem(page, seeded.itemId);
  await expect.poll(() => page.evaluate(() =>
    (window as Window & { __e2eSocket?: WebSocket }).__e2eSocket?.readyState)).toBe(1);
  await page.waitForTimeout(300);
  const initialRequests = graphRequests;
  await page.evaluate(() => (window as Window & { __e2eSocket?: WebSocket }).__e2eSocket?.close());
  const locationId = uniqueE2eId('missed-location');
  const refreshed = page.waitForResponse(response => /\/api\/graph(?:\?|$)/.test(response.url()) && response.ok());
  await createLocation(request, backendUrl, session, { id: locationId, name: 'Missed while disconnected', latitude: 20, longitude: 20 });
  await refreshed;
  await waitForNode(page, locationId);
  expect(graphRequests - initialRequests).toBe(1);
  await waitForItemToMove(page, seeded.itemId);
});

test('clock pause and speed changes preserve motion and What If isolation', async ({ page, request }) => {
  const seeded = seededCleanup = await seedMovingItemGraph(request, backendUrl, session, { length: 100_000, speed: 50 });
  const created = page.waitForResponse(response => response.url().endsWith('/api/simulations/what-if') && response.ok());
  await page.goto('/live?mode=what-if');
  const branch = await (await created).json() as { id: string };
  try {
    await waitForGraphTestApi(page);
    await waitForItem(page, seeded.itemId);
    const clock = () => page.evaluate(() => window.__graphTestApi!.getSnapshot().simTime);
    const pausedTime = await clock();
    const pausedPosition = await getItemPosition(page, seeded.itemId);
    await page.waitForTimeout(400);
    expect(await clock()).toBe(pausedTime);
    const stillPaused = await getItemPosition(page, seeded.itemId);
    expect(stillPaused.x).toBeCloseTo(pausedPosition.x, 6);
    expect(stillPaused.y).toBeCloseTo(pausedPosition.y, 6);
    await updateConveyorSpeed(request, backendUrl, session, seeded.conveyorId, 100, branch.id);
    const live = await request.get(`${backendUrl}/api/graph`, { headers: headers() });
    expect((await live.json()).conveyors.find((edge: { id: string }) => edge.id === seeded.conveyorId).speed).toBe(50);
    await page.getByRole('button', { name: /Play/ }).click();
    await expect(page.getByRole('button', { name: /Pause/ })).toBeVisible();
    await waitForItemToMove(page, seeded.itemId);
    const initialRenders = await page.evaluate(() => window.__workspaceRenderCount ?? 0);
    const movingPosition = await getItemPosition(page, seeded.itemId);
    await page.waitForTimeout(1000);
    expect((await page.evaluate(() => window.__workspaceRenderCount ?? 0)) - initialRenders).toBeLessThan(20);
    expect(await getItemPosition(page, seeded.itemId)).not.toEqual(movingPosition);
    const speedChanged = page.waitForResponse(response => response.request().method() === 'PATCH' && response.url().endsWith(`/api/simulations/${branch.id}/playback`));
    await page.getByRole('combobox').selectOption('5');
    expect((await speedChanged).ok()).toBeTruthy();
    await page.waitForTimeout(200);
    const fasterTime = await clock();
    await page.waitForTimeout(500);
    expect((await clock()) - fasterTime).toBeGreaterThan(1500);
    await page.getByRole('button', { name: /Pause/ }).click();
    await expect(page.getByRole('button', { name: /Play/ })).toBeVisible();
    await page.waitForTimeout(150);
    const stoppedTime = await clock();
    await page.waitForTimeout(400);
    expect(await clock()).toBe(stoppedTime);
    const deleted = page.waitForResponse(response => response.request().method() === 'DELETE' && response.url().endsWith(`/api/simulations/${branch.id}`));
    await page.getByRole('navigation', { name: 'Operational modes' }).getByRole('button', { name: 'Live', exact: true }).click();
    expect((await deleted).status()).toBe(204);
    expect((await request.get(`${backendUrl}/api/simulations/${branch.id}`, { headers: headers() })).status()).toBe(404);
    await waitForItemToMove(page, seeded.itemId);
  } finally {
    await request.delete(`${backendUrl}/api/simulations/${branch.id}`, { headers: headers() });
  }
});

test('many-item movement trace stays independent of workspace renders', async ({ page, request }, testInfo) => {
  test.setTimeout(300_000);
  const id = uniqueE2eId('many-items');
  const conveyorId = `${id}-belt`;
  const itemIds = Array.from({ length: 250 }, (_, index) => `${id}-item-${index}`);
  await createLocation(request, backendUrl, session, { id: `${id}-source`, name: 'Trace source', latitude: 0, longitude: 0 });
  await createLocation(request, backendUrl, session, { id: `${id}-target`, name: 'Trace target', latitude: 100, longitude: 0 });
  await createConveyor(request, backendUrl, session, { id: conveyorId, sourceId: `${id}-source`, targetId: `${id}-target`, length: 100_000, speed: 50 });
  try {
    for (let offset = 0; offset < itemIds.length; offset += 20) {
      await Promise.all(itemIds.slice(offset, offset + 20).map((itemId, index) => createItem(request, backendUrl, session, {
        id: itemId, name: 'Trace item', locationId: conveyorId, positionType: 'CONVEYOR', progress: (offset + index) / 5,
      })));
    }
    await page.goto('/live');
    await waitForGraphTestApi(page);
    await waitForItem(page, itemIds[itemIds.length - 1]);
    await page.waitForTimeout(500);
    const cdp = await page.context().newCDPSession(page);
    const traceEvents: unknown[] = [];
    cdp.on('Tracing.dataCollected', event => traceEvents.push(...event.value));
    await cdp.send('Tracing.start', { categories: 'devtools.timeline,v8', transferMode: 'ReportEvents' });
    const before = await getItemPosition(page, itemIds[0]);
    const measured = await page.evaluate(() => new Promise<{ renders: number; frames: number; intervals: number[] }>(resolve => {
      const initialRenders = window.__workspaceRenderCount ?? 0;
      const start = performance.now();
      let previous = start;
      const intervals: number[] = [];
      const sample = (timestamp: number) => {
        intervals.push(timestamp - previous);
        previous = timestamp;
        if (timestamp - start < 3000) requestAnimationFrame(sample);
        else resolve({ renders: (window.__workspaceRenderCount ?? 0) - initialRenders,
          frames: intervals.length, intervals });
      };
      requestAnimationFrame(sample);
    }));
    const complete = new Promise<void>(resolve => cdp.once('Tracing.tracingComplete', () => resolve()));
    await cdp.send('Tracing.end');
    await complete;
    const sorted = measured.intervals.slice(1).sort((a, b) => a - b);
    const summary = { items: itemIds.length, renders: measured.renders, frames: measured.frames,
      medianFrameMs: sorted[Math.floor(sorted.length / 2)], p95FrameMs: sorted[Math.floor(sorted.length * 0.95)] };
    console.log('GRAPH_TRACE', JSON.stringify(summary));
    const summaryPath = testInfo.outputPath('many-item-summary.json');
    const tracePath = testInfo.outputPath('many-item-chrome-trace.json');
    await writeFile(summaryPath, JSON.stringify({ ...summary, intervals: measured.intervals }), 'utf8');
    await writeFile(tracePath, JSON.stringify({ traceEvents }), 'utf8');
    await testInfo.attach('many-item-summary.json', { path: summaryPath, contentType: 'application/json' });
    await testInfo.attach('many-item-chrome-trace.json', { path: tracePath, contentType: 'application/json' });
    expect(await getItemPosition(page, itemIds[0])).not.toEqual(before);
    if (process.env.E2E_MEASURE_BASELINE !== 'true') expect(measured.renders).toBeLessThan(20);
  } finally {
    await page.close();
    for (const itemId of itemIds) await request.delete(`${backendUrl}/api/items/${itemId}`, { headers: headers() });
    await request.delete(`${backendUrl}/api/conveyors`, { headers: headers(), params: { sourceId: `${id}-source`, targetId: `${id}-target` } });
    await request.delete(`${backendUrl}/api/locations/${id}-source`, { headers: headers() });
    await request.delete(`${backendUrl}/api/locations/${id}-target`, { headers: headers() });
  }
});
