import { test, expect } from './fixtures';
import { installAuthSession, loginAsSuperadmin, type AuthSession } from './helpers/auth';
import { createLocation, createConveyor, createItem, uniqueE2eId, updateConveyorSpeed } from './helpers/api';
import { waitForChuteItemCount, waitForConveyorSpeed, waitForGraphTestApi, waitForItem } from './helpers/graph';
import type { SimulationStateResponse } from '../src/api-client';

const backendUrl = process.env.E2E_BACKEND_URL ?? 'http://127.0.0.1:18080';
let session: AuthSession;
const branches = new Set<string>();
const headers = () => ({ Authorization: `Bearer ${session.token}` });

test.beforeAll(async ({ request }) => { session = await loginAsSuperadmin(request, backendUrl); });
test.beforeEach(async ({ page }) => {
  await installAuthSession(page, session);
  page.on('response', async response => {
    if (response.url().endsWith('/api/simulations/what-if') && response.ok()) {
      const branch = await response.json() as SimulationStateResponse;
      if (branch.id) branches.add(branch.id);
    }
  });
});
test.afterEach(async ({ request, page }) => {
  await page.close();
  for (const id of [...branches].reverse()) await request.delete(`${backendUrl}/api/simulations/${id}`, { headers: headers() });
  branches.clear();
});

for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
  test(`live what-if starts paused and exits to live at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto('/live');
    await waitForGraphTestApi(page);
    const strip = page.getByLabel('Workspace mode');
    await expect(strip.getByText('LIVE', { exact: true })).toHaveAttribute('title', /production/);
    const created = page.waitForResponse(response => response.url().endsWith('/api/simulations/what-if') && response.ok());
    await strip.getByRole('button', { name: 'What If', exact: true }).click();
    const branch = await (await created).json() as SimulationStateResponse;
    expect(branch.status).toBe('PAUSED');
    expect(branch.kind).toBe('WHAT_IF_LIVE');
    await expect(strip.getByText('WHAT IF', { exact: true })).toHaveAttribute('title', /Isolated/);
    await expect(page.getByRole('button', { name: 'Play', exact: false })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Design System', exact: true })).toHaveCount(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
    if (viewport.width === 1440) {
      await page.getByRole('combobox').selectOption('50');
      await page.getByRole('button', { name: 'Play', exact: false }).click();
      await expect(strip.getByText('FUTURE', { exact: true })).toHaveAttribute('title', /permanently frozen/);
      await page.getByRole('button', { name: 'Pause', exact: false }).click();
      await expect(page.getByRole('button', { name: 'Play', exact: false })).toBeVisible();
    }
    await strip.getByRole('button', { name: 'Exit What If' }).click();
    await expect(strip.getByText('LIVE', { exact: true })).toBeVisible();
    await waitForGraphTestApi(page);
  });
}

test('an edit from live branches automatically and applies the pending command only to the branch', async ({ page, request }) => {
  const id = uniqueE2eId('what-if-edit');
  await createLocation(request, backendUrl, session, { id: `${id}-a`, name: 'Entry', latitude: 0, longitude: 0 });
  await createLocation(request, backendUrl, session, { id: `${id}-b`, name: 'Exit', latitude: 100, longitude: 0 });
  await createConveyor(request, backendUrl, session, { id, sourceId: `${id}-a`, targetId: `${id}-b`, speed: 0 });
  await page.goto('/live');
  await waitForGraphTestApi(page);
  await page.getByRole('button', { name: /Live interactions/ }).click();
  await page.getByRole('button', { name: 'Commands', exact: true }).click();
  await page.getByRole('combobox').selectOption('STOP_CONVEYOR');
  await page.getByPlaceholder('e.g. conveyor-01').fill(id);
  const created = page.waitForResponse(response => response.url().endsWith('/api/simulations/what-if') && response.ok());
  const mutated = page.waitForResponse(response => response.url().endsWith(`/api/conveyors/${id}/deactivate`));
  await page.getByRole('button', { name: 'Send Command' }).click();
  const branch = await (await created).json() as SimulationStateResponse;
  const mutation = await mutated;
  expect(mutation.ok()).toBeTruthy();
  expect(mutation.request().headers()['x-simulation-id']).toBe(branch.id);
  const live = await request.get(`${backendUrl}/api/graph`, { headers: headers() });
  const isolated = await request.get(`${backendUrl}/api/simulations/${branch.id}/graph`, { headers: headers() });
  expect((await live.json()).conveyors.find((belt: { id: string }) => belt.id === id).active).toBe(true);
  expect((await isolated.json()).conveyors.find((belt: { id: string }) => belt.id === id).active).toBe(false);
  await expect(page.getByLabel('Workspace mode').getByText('WHAT IF', { exact: true })).toBeVisible();
});

test('a topology change made in what-if exists only in that scenario', async ({ page, request }) => {
  await page.goto('/live');
  await waitForGraphTestApi(page);
  const strip = page.getByLabel('Workspace mode');
  const created = page.waitForResponse(response => response.url().endsWith('/api/simulations/what-if') && response.ok());
  await strip.getByRole('button', { name: 'What If', exact: true }).click();
  const branch = await (await created).json() as SimulationStateResponse;

  const createdLocation = page.waitForResponse(response =>
    response.url().endsWith('/api/locations') && response.request().method() === 'POST' && response.ok());
  await page.locator('.sigma-mouse').click({ position: { x: 45, y: 45 } });
  const locationResponse = await createdLocation;
  const locationId = (locationResponse.request().postDataJSON() as { id: string }).id;
  expect(locationResponse.request().headers()['x-simulation-id']).toBe(branch.id);

  const live = await request.get(`${backendUrl}/api/graph`, { headers: headers() });
  const isolated = await request.get(`${backendUrl}/api/simulations/${branch.id}/graph`, { headers: headers() });
  expect((await live.json()).locations.some((location: { id: string }) => location.id === locationId)).toBe(false);
  expect((await isolated.json()).locations.some((location: { id: string }) => location.id === locationId)).toBe(true);

  await strip.getByRole('button', { name: 'Exit What If' }).click();
  await expect(strip.getByText('LIVE', { exact: true })).toBeVisible();
});

test('two browser workspaces keep concurrent what-if branches independent', async ({ page, browser, request }) => {
  const id = uniqueE2eId('what-if-concurrent');
  const sourceId = `${id}-source`;
  const targetId = `${id}-target`;
  const conveyorId = `${id}-conveyor`;
  const secondPage = await browser.newPage();

  try {
    await createLocation(request, backendUrl, session, { id: sourceId, name: 'Entry', latitude: 0, longitude: 0 });
    await createLocation(request, backendUrl, session, { id: targetId, name: 'Exit', latitude: 100, longitude: 0 });
    await createConveyor(request, backendUrl, session, { id: conveyorId, sourceId, targetId, speed: 1 });
    await installAuthSession(secondPage, session);

    await Promise.all([page.goto('/live'), secondPage.goto('/live')]);
    await Promise.all([waitForGraphTestApi(page), waitForGraphTestApi(secondPage)]);

    const firstCreated = page.waitForResponse(response => response.url().endsWith('/api/simulations/what-if') && response.ok());
    await page.getByLabel('Workspace mode').getByRole('button', { name: 'What If', exact: true }).click();
    const firstBranch = await (await firstCreated).json() as SimulationStateResponse;

    const secondCreated = secondPage.waitForResponse(response => response.url().endsWith('/api/simulations/what-if') && response.ok());
    await secondPage.getByLabel('Workspace mode').getByRole('button', { name: 'What If', exact: true }).click();
    const secondBranch = await (await secondCreated).json() as SimulationStateResponse;
    branches.add(secondBranch.id!);
    expect(secondBranch.id).not.toBe(firstBranch.id);

    await updateConveyorSpeed(request, backendUrl, session, conveyorId, 2, firstBranch.id);
    await updateConveyorSpeed(request, backendUrl, session, conveyorId, 3, secondBranch.id);
    await waitForConveyorSpeed(page, conveyorId, 2);
    await waitForConveyorSpeed(secondPage, conveyorId, 3);

    const [live, firstGraph, secondGraph] = await Promise.all([
      request.get(`${backendUrl}/api/graph`, { headers: headers() }),
      request.get(`${backendUrl}/api/simulations/${firstBranch.id}/graph`, { headers: headers() }),
      request.get(`${backendUrl}/api/simulations/${secondBranch.id}/graph`, { headers: headers() }),
    ]);
    expect((await live.json()).conveyors.find((belt: { id: string }) => belt.id === conveyorId).speed).toBe(1);
    expect((await firstGraph.json()).conveyors.find((belt: { id: string }) => belt.id === conveyorId).speed).toBe(2);
    expect((await secondGraph.json()).conveyors.find((belt: { id: string }) => belt.id === conveyorId).speed).toBe(3);
  } finally {
    await secondPage.close();
  }
});

test('a present-time what-if mirrors live events, then freezes input after reaching its future destination', async ({ page, request }) => {
  const id = uniqueE2eId('what-if-speed');
  const sourceId = `${id}-source`;
  const chuteId = `${id}-chute`;
  const conveyorId = `${id}-conveyor`;
  const itemId = `${id}-item`;

  await createLocation(request, backendUrl, session, { id: sourceId, name: 'Entry', latitude: 0, longitude: 0 });
  await createLocation(request, backendUrl, session, {
    id: chuteId, name: 'Exit chute', latitude: 100, longitude: 0, type: 'CHUTE', capacity: 10,
  });
  await createConveyor(request, backendUrl, session, {
    id: conveyorId, sourceId, targetId: chuteId, length: 4, speed: 1,
  });

  await page.goto('/live');
  await waitForGraphTestApi(page);
  const strip = page.getByLabel('Workspace mode');
  const created = page.waitForResponse(response => response.url().endsWith('/api/simulations/what-if') && response.ok());
  await strip.getByRole('button', { name: 'What If', exact: true }).click();
  const branch = await (await created).json() as SimulationStateResponse;
  expect(branch.id).toBeTruthy();

  // This event is produced after the fork and must be accepted by the still-live branch.
  await createItem(request, backendUrl, session, {
    id: itemId, name: 'Live arrival', locationId: sourceId, positionType: 'LOCATION',
  });
  await waitForItem(page, itemId);

  await updateConveyorSpeed(request, backendUrl, session, conveyorId, 2, branch.id);
  await waitForConveyorSpeed(page, conveyorId, 2);

  await page.getByRole('combobox').selectOption('50');
  await page.getByRole('button', { name: 'Play', exact: false }).click();
  await waitForChuteItemCount(page, chuteId, 1);
  await expect(strip.getByText('FUTURE', { exact: true })).toBeVisible();

  const lateItemId = `${id}-late-item`;
  await createItem(request, backendUrl, session, {
    id: lateItemId, name: 'Too late for scenario', locationId: sourceId, positionType: 'LOCATION',
  });
  await expect.poll(async () => {
    const liveGraph = await request.get(`${backendUrl}/api/graph`, { headers: headers() });
    return (await liveGraph.json()).items.some((item: { id: string }) => item.id === lateItemId);
  }).toBe(true);
  await page.waitForTimeout(500);
  expect(await page.evaluate(id => window.__graphTestApi!.getItem(id).graphNode, lateItemId)).toBeNull();

  const liveGraph = await request.get(`${backendUrl}/api/graph`, { headers: headers() });
  expect((await liveGraph.json()).conveyors.find((belt: { id: string }) => belt.id === conveyorId).speed).toBe(1);

  await strip.getByRole('button', { name: 'Exit What If' }).click();
  await expect(strip.getByText('LIVE', { exact: true })).toBeVisible();
});

test('Design System loads topology only and creates production nodes without branching', async ({ page, request }) => {
  const id = uniqueE2eId('what-if-design');
  await createLocation(request, backendUrl, session, { id, name: 'Chute', type: 'CHUTE', latitude: 0, longitude: 0 });
  await createItem(request, backendUrl, session, { id: `${id}-item`, name: 'Box', locationId: id, positionType: 'LOCATION' });
  await page.goto('/live');
  await waitForGraphTestApi(page);
  const topology = page.waitForResponse(response => response.url().includes('/api/graph?topologyOnly=true'));
  await page.getByRole('button', { name: 'Design System', exact: true }).click();
  expect((await (await topology).json()).items).toEqual([]);
  await waitForGraphTestApi(page);
  await expect(page.getByTestId('live-hud')).toHaveCount(0);
  await expect(page.getByRole('button', { name: /Live interactions/ })).toHaveCount(0);
  expect(await page.evaluate(() => window.__graphTestApi?.getSnapshot().activeItems.length)).toBe(0);
  const create = page.waitForResponse(response => response.url().endsWith('/api/locations') && response.request().method() === 'POST');
  await page.locator('.sigma-mouse').click({ position: { x: 45, y: 45 } });
  const response = await create;
  expect(response.ok()).toBeTruthy();
  expect(response.request().headers()['x-simulation-id']).toBeUndefined();
  await expect(page.getByLabel('Workspace mode').getByText('LIVE', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await expect(page.getByTestId('live-hud')).toBeVisible();
});

test('historical what-if restores its paused source when exited', async ({ page }) => {
  await page.goto('/live');
  await waitForGraphTestApi(page);
  await page.getByRole('button', { name: 'Time Travel' }).click();
  const createdSource = page.waitForResponse(response => response.url().endsWith('/api/simulations') && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Start', exact: true }).click();
  const source = await (await createdSource).json() as SimulationStateResponse;
  branches.add(source.id!);
  await expect(page.getByRole('button', { name: 'Play', exact: false })).toBeVisible({ timeout: 30000 });
  const strip = page.getByLabel('Workspace mode');
  await expect(strip.getByText('SIMULATION', { exact: true })).toHaveAttribute('title', /Historical/);
  const created = page.waitForResponse(response => response.url().endsWith('/api/simulations/what-if') && response.ok());
  await strip.getByRole('button', { name: 'What If', exact: true }).click();
  const branch = await (await created).json() as SimulationStateResponse;
  expect(branch.sourceSimulationId).toBe(source.id);
  await expect(strip.getByText('WHAT IF SIMULATION', { exact: true })).toBeVisible();
  await strip.getByRole('button', { name: 'Exit What If' }).click();
  await expect(strip.getByText('SIMULATION', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Play', exact: false })).toBeVisible();
});
