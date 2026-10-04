import { test, expect } from './fixtures';
import { installAuthSession, loginAsSuperadmin, type AuthSession } from './helpers/auth';
import type { TemplateInstance, SavedScenario, SimulationStateResponse } from '../src/api-client';

const backendUrl = process.env.E2E_BACKEND_URL ?? 'http://127.0.0.1:18080';
let session: AuthSession;
const runtimes = new Set<string>();
test.beforeAll(async ({ request }) => { session = await loginAsSuperadmin(request, backendUrl); });
test.beforeEach(async ({ page }) => {
  await installAuthSession(page, session);
  page.on('response', async response => {
    if (response.url().includes('/api/simulation-templates/') && response.url().endsWith('/instantiate') && response.ok()) {
      const instance = await response.json() as TemplateInstance;
      if (instance.runtime?.id) runtimes.add(instance.runtime.id);
    }
    if (/\/api\/scenarios\/[^/]+\/open/.test(response.url()) && response.ok()) {
      const runtime = await response.json() as SimulationStateResponse;
      if (runtime.id) runtimes.add(runtime.id);
    }
  });
});
test.afterEach(async ({ request, page }) => {
  await page.close();
  for (const id of runtimes) await request.delete(`${backendUrl}/api/simulations/${id}`, { headers: { Authorization: `Bearer ${session.token}` } });
  runtimes.clear();
});

test('teaching project saves, compares, exports and imports into detached state', async ({ page }) => {
  await page.goto('/scenarios');
  const line = page.getByRole('article').filter({ has: page.getByRole('heading', { name: 'Simple line · v1', exact: true }) });
  const instantiated = page.waitForResponse(response => response.url().endsWith('/simple-line/instantiate') && response.ok());
  await line.getByRole('button', { name: 'Instantiate isolated project' }).click();
  const instance = await (await instantiated).json() as TemplateInstance;
  expect(instance.runtime?.kind).toBe('DETACHED');
  await expect(page).toHaveURL(/\/live$/);
  await page.getByRole('link', { name: 'Scenarios', exact: true }).click();
  const name = `Teaching ${Date.now()}`;
  await page.getByLabel('Scenario name', { exact: true }).fill(name);
  const saved = page.waitForResponse(response => response.url().endsWith(`/api/scenarios/${instance.scenario!.id}/revisions`) && response.ok());
  await page.getByRole('button', { name: 'Save new revision', exact: true }).click();
  const revision = await (await saved).json() as SavedScenario;
  expect(revision.revision).toBe(2);
  const project = page.getByRole('article').filter({ has: page.getByRole('heading', { name: `${name} · revision 2`, exact: true }) });
  const duplicateResponse = page.waitForResponse(response => response.url().endsWith(`/api/scenarios/${revision.id}/duplicate?revision=2`) && response.ok());
  await project.getByRole('button', { name: 'Duplicate', exact: true }).click();
  const duplicate = await (await duplicateResponse).json() as SavedScenario;
  await page.getByLabel('Reference scenario', { exact: true }).selectOption(revision.id!);
  await page.getByLabel('Alternative scenario', { exact: true }).selectOption(duplicate.id!);
  await page.getByLabel('Comparison name', { exact: true }).fill(`${name} study`);
  await page.getByRole('button', { name: 'Create comparison', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Run comparison', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Run comparison', exact: true }).click();
  await expect(page.getByText(/completed 5\/5/)).toHaveCount(2, { timeout: 30000 });
  await expect(page.getByText(/95% intervals use paired completed runs/)).toBeVisible();
  const csvDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download CSV', exact: true }).click();
  expect((await csvDownload).suggestedFilename()).toBe('comparison.csv');
  const jsonDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download JSON', exact: true }).click();
  expect((await jsonDownload).suggestedFilename()).toBe('comparison.json');
  await project.getByRole('button', { name: 'Open and edit', exact: true }).click();
  await expect(page).toHaveURL(/\/live$/);
  await page.getByRole('button', { name: 'Export graph', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Export scenario' })).toBeVisible();
  await page.getByLabel('Include initial items', { exact: true }).check();
  const captureResponse = page.waitForResponse(response => response.url().includes('/api/scenarios/capture') && response.ok());
  const scenarioDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download .flusim', exact: true }).click();
  const document = await (await captureResponse).json();
  expect((await scenarioDownload).suggestedFilename()).toBe('scenario.flusim');
  await page.locator('input[type="file"]').setInputFiles({ name: 'roundtrip.flusim', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(document)) });
  await expect(page.getByRole('dialog', { name: 'Import preview' })).toBeVisible();
  const imported = page.waitForResponse(response => /\/api\/scenarios\/[^/]+\/open/.test(response.url()) && response.ok());
  await page.getByRole('button', { name: 'Import and open isolated copy', exact: true }).click();
  expect((await (await imported).json() as SimulationStateResponse).kind).toBe('DETACHED');
});

test('scenario library scrolls and links to single conveyor templates', async ({ page }) => {
  await page.setViewportSize({ width: 1000, height: 600 });
  await page.goto('/scenarios');
  await expect(page.getByRole('heading', { name: 'Simple line · v1', exact: true })).toBeVisible();
  await page.mouse.move(900, 450);
  await page.mouse.wheel(0, 800);
  await expect.poll(() => page.locator('main').evaluate(main => main.parentElement!.scrollTop)).toBeGreaterThan(0);
  await page.getByRole('link', { name: 'Browse individual conveyor templates' }).click();
  await expect(page.getByRole('heading', { name: 'Conveyor templates', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Roller · 4 m/s', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Belt · 2 m/s · 0.01 failures/h', exact: true })).toBeVisible();
});

test('drag creation supports cancellation, defaults and individual conveyor templates', async ({ page, request }) => {
  await page.goto('/scenarios');
  const instantiated = page.waitForResponse(response => response.url().endsWith('/simple-line/instantiate') && response.ok());
  await page.getByRole('article').filter({ has: page.getByRole('heading', { name: 'Simple line · v1', exact: true }) })
    .getByRole('button', { name: 'Instantiate isolated project' }).click();
  const instance = await (await instantiated).json() as TemplateInstance;
  const locations = instance.scenario!.document!.baseline!.locations!;
  const source = locations[0].id!;
  const target = locations[locations.length - 1].id!;
  await page.waitForFunction(() => window.__graphTestApi?.version === 1);
  const drag = async () => {
    const positions = await page.evaluate(([from, to]) => ({
      source: window.__graphTestApi!.getNodeViewportPosition(from)!,
      target: window.__graphTestApi!.getNodeViewportPosition(to)!,
    }), [source, target]);
    await page.keyboard.down('Alt');
    await page.mouse.move(positions.source.x, positions.source.y);
    await page.mouse.down();
    await page.mouse.move(positions.target.x, positions.target.y, { steps: 10 });
    await page.mouse.up();
    await page.keyboard.up('Alt');
    await expect(page.getByRole('dialog', { name: 'Create conveyor', exact: true })).toBeVisible();
  };
  await drag();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  const headers = { Authorization: `Bearer ${session.token}`, 'X-Simulation-ID': instance.runtime!.id! };
  expect((await (await request.get(`${backendUrl}/api/graph?topologyOnly=true`, { headers })).json()).conveyors).toHaveLength(2);
  await drag();
  const created = page.waitForResponse(response => response.url().endsWith('/api/conveyors') && response.request().method() === 'POST' && response.ok());
  await page.getByRole('button', { name: 'Create conveyor', exact: true }).click();
  const defaultRequest = (await created).request().postDataJSON();
  expect(defaultRequest).toMatchObject({ speed: 1, length: 10, type: 'BELT', sourceId: source, targetId: target });
  await expect(page.getByRole('dialog', { name: 'Create conveyor', exact: true })).toHaveCount(0);
  expect((await request.delete(`${backendUrl}/api/conveyors`, { headers, params: { sourceId: source, targetId: target } })).ok()).toBeTruthy();
  await expect.poll(() => page.evaluate(() => window.__graphTestApi!.getSnapshot().edgeCount)).toBe(2);
  await drag();
  await page.getByLabel('Conveyor template', { exact: true }).selectOption('belt-reliable');
  const templateCreated = page.waitForResponse(response => response.url().endsWith('/api/conveyors') && response.request().method() === 'POST' && response.ok());
  await page.getByRole('button', { name: 'Create conveyor', exact: true }).click();
  const templateRequest = (await templateCreated).request().postDataJSON();
  expect(templateRequest).toMatchObject({ speed: 2, type: 'BELT', properties: { failuresPerHour: 0.01, repairDurationSeconds: 60 } });
  await expect.poll(async () => {
    const graph = await (await request.get(`${backendUrl}/api/graph?topologyOnly=true`, { headers })).json();
    return graph.conveyors.find((conveyor: { id: string }) => conveyor.id === templateRequest.connectionId)?.properties;
  }).toEqual({ failuresPerHour: 0.01, repairDurationSeconds: 60 });
});
