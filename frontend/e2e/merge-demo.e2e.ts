import { test, expect } from './fixtures';
import { installAuthSession, loginAsSuperadmin } from './helpers/auth';
import { waitForGraphTestApi } from './helpers/graph';

const backendUrl = process.env.E2E_BACKEND_URL ?? 'http://127.0.0.1:18080';

test('merge demo transfers visible items onto the outlet and discharges them', async ({ page, request }) => {
  test.setTimeout(120_000);
  const session = await loginAsSuperadmin(request, backendUrl);
  const headers = { Authorization: `Bearer ${session.token}` };
  let simulationId: string | undefined;
  await installAuthSession(page, session);
  await page.goto('/live');
  await waitForGraphTestApi(page);
  await page.getByRole('button', { name: /Live interactions/ }).click();
  await page.getByRole('button', { name: 'Commands', exact: true }).click();
  const launched = page.waitForResponse(response => response.url().endsWith('/api/client-demo/conveyor-spacing') && response.ok());
  await page.getByRole('button', { name: 'Run rollers and belt merge', exact: true }).click();
  simulationId = (await (await launched).json()).id;
  try {
    await expect.poll(() => page.evaluate(() => window.__graphTestApi!.getItem('CS-BELT-1').activeItem?.currentEdgeId)).toBe('Conveyor_CS-BELT-SOURCE_CS-MERGE');
    await expect.poll(() => page.evaluate(() => window.__graphTestApi!.getItem('CS-BELT-1').activeItem?.flowPaused)).toBe(true);
    await expect.poll(() => page.evaluate(() => Number(window.__graphTestApi!.getItem('CS-BELT-1').graphNode?.attributes.y))).toBeCloseTo(7.6, 2);
    await expect.poll(() => page.evaluate(() => Number(window.__graphTestApi!.getItem('CS-BELT-2').graphNode?.attributes.y))).toBeCloseTo(5.6, 2);
    await page.waitForTimeout(500);
    expect(await page.evaluate(() => Number(window.__graphTestApi!.getItem('CS-BELT-2').graphNode?.attributes.y))).toBeCloseTo(5.6, 2);
    await expect.poll(() => page.evaluate(() => window.__graphTestApi!.getItem('CS-ROLLER-1').activeItem?.flowPaused)).toBe(true);
    const leaders = await page.evaluate(() => ({
      belt: Number(window.__graphTestApi!.getItem('CS-BELT-1').graphNode?.attributes.x),
      roller: Number(window.__graphTestApi!.getItem('CS-ROLLER-1').graphNode?.attributes.x),
    }));
    expect(leaders.roller - leaders.belt).toBeGreaterThan(0.35);
    await expect.poll(() => page.evaluate(() => window.__graphTestApi!.getItem('CS-BELT-1').activeItem?.currentEdgeId), { timeout: 15_000 }).toBe('Conveyor_CS-MERGE_CS-EXIT');
    await expect.poll(() => page.evaluate(() => window.__graphTestApi!.getItem('CS-BELT-1').activeItem?.flowPaused)).toBe(false);
    await expect.poll(() => page.evaluate(() => Number(window.__graphTestApi!.getItem('CS-BELT-1').graphNode?.attributes.y)), { timeout: 5_000 }).toBeGreaterThan(8);
    await expect.poll(() => page.evaluate(() => window.__graphTestApi!.getItem('CS-ROLLER-1').activeItem?.currentEdgeId), { timeout: 15_000 }).toBe('Conveyor_CS-MERGE_CS-EXIT');
    expect(await page.evaluate(() => window.__graphTestApi!.getItem('CS-BELT-2').activeItem?.currentEdgeId)).toBe('Conveyor_CS-BELT-SOURCE_CS-MERGE');
    await expect.poll(() => page.evaluate(() => {
      const contents = window.__graphTestApi!.getNode('CS-EXIT')?.attributes.itemsInChute;
      return Array.isArray(contents) ? contents.length : 0;
    }), { timeout: 90_000 }).toBe(9);
  } finally {
    if (simulationId) await request.delete(`${backendUrl}/api/simulations/${simulationId}`, { headers });
  }
});
