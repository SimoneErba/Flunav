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
