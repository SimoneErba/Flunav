import { test, expect } from './fixtures';
import { createLocation, uniqueE2eId } from './helpers/api';
import { installAuthSession, loginAsSuperadmin } from './helpers/auth';
import { waitForGraphTestApi, waitForNode } from './helpers/graph';

const backendUrl = process.env.E2E_BACKEND_URL ?? 'http://127.0.0.1:18080';

test('design node creation keeps the graph visible and control icons centered', async ({ page, request }) => {
  const session = await loginAsSuperadmin(request, backendUrl);
  await installAuthSession(page, session);
  const headers = { Authorization: `Bearer ${session.token}` };
  const anchorId = uniqueE2eId('design-anchor');
  let createdId: string | undefined;
  await createLocation(request, backendUrl, session, {
    id: anchorId, name: 'Design anchor', latitude: 0, longitude: 0,
  });
  try {
    await page.goto('/live?mode=design');
    await waitForGraphTestApi(page);
    await waitForNode(page, anchorId);
    await expect(page.locator('.react-sigma')).toBeVisible();

    for (const dark of [false, true]) {
      await page.evaluate(value => document.documentElement.classList.toggle('dark', value), dark);
      const offsets = await page.locator('.react-sigma-control > button').evaluateAll(buttons =>
        buttons.map(button => {
          const bounds = button.getBoundingClientRect();
          const icon = button.querySelector('svg')!.getBoundingClientRect();
          return {
            x: Math.abs(icon.x + icon.width / 2 - bounds.x - bounds.width / 2),
            y: Math.abs(icon.y + icon.height / 2 - bounds.y - bounds.height / 2),
          };
        }));
      expect(offsets).toHaveLength(4);
      for (const offset of offsets) {
        expect(offset.x).toBeLessThan(1);
        expect(offset.y).toBeLessThan(1);
      }
    }

    const probe = await page.evaluateHandle(() => {
      const renderer = document.querySelector('.react-sigma')!;
      const wrapper = renderer.parentElement!.parentElement!;
      const state = { hidden: false, renderer, canvas: renderer.querySelector('canvas'), observer: null as MutationObserver | null };
      state.observer = new MutationObserver(records => {
        if (getComputedStyle(wrapper).visibility === 'hidden' || records.some(record => record.oldValue?.includes('invisible'))) {
          state.hidden = true;
        }
      });
      state.observer.observe(wrapper, { attributes: true, attributeFilter: ['class'], attributeOldValue: true });
      return state;
    });
    const created = page.waitForResponse(response => response.request().method() === 'POST' && /\/api\/locations(?:\?|$)/.test(response.url()));
    const refreshed = page.waitForResponse(response => /\/api\/graph(?:\?|$)/.test(response.url()) && response.ok());
    const stage = await page.locator('.sigma-container').boundingBox();
    await page.mouse.click(stage!.x + 60, stage!.y + 60);
    const response = await created;
    createdId = (response.request().postDataJSON() as { id: string }).id;
    expect(response.ok()).toBeTruthy();
    await refreshed;
    await waitForNode(page, createdId);
    await expect.poll(() => page.evaluate(id => window.__graphTestApi?.hasNode(id), anchorId)).toBe(true);
    expect(await probe.evaluate(state => {
      state.observer!.disconnect();
      return {
        hidden: state.hidden,
        sameRenderer: state.renderer === document.querySelector('.react-sigma'),
        sameCanvas: state.canvas === state.renderer.querySelector('canvas'),
      };
    })).toEqual({ hidden: false, sameRenderer: true, sameCanvas: true });
    await probe.dispose();
  } finally {
    await page.close();
    if (createdId) await request.delete(`${backendUrl}/api/locations/${createdId}`, { headers });
    await request.delete(`${backendUrl}/api/locations/${anchorId}`, { headers });
  }
});
