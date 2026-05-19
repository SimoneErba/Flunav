import type { Page } from "@playwright/test";
import { expect } from "@playwright/test";

export const waitForGraphTestApi = async (page: Page) => {
  await page.waitForFunction(() => window.__graphTestApi?.version === 1);
};

export const getItemPosition = async (page: Page, itemId: string) => {
  const item = await page.evaluate((id) => window.__graphTestApi!.getItem(id), itemId);
  const attrs = item.graphNode?.attributes;
  expect(attrs).toBeTruthy();
  return {
    x: Number(attrs!.x),
    y: Number(attrs!.y),
  };
};

export const waitForNode = async (page: Page, nodeId: string) => {
  await expect.poll(async () => page.evaluate((id) => window.__graphTestApi!.hasNode(id), nodeId)).toBe(true);
};

export const waitForEdge = async (page: Page, edgeId: string) => {
  await expect.poll(async () => page.evaluate((id) => window.__graphTestApi!.hasEdge(id), edgeId)).toBe(true);
};

export const waitForItem = async (page: Page, itemId: string) => {
  await expect
    .poll(async () => page.evaluate((id) => window.__graphTestApi!.getItem(id).graphNode !== null, itemId))
    .toBe(true);
};

export const waitForItemDestinationAndPath = async (
  page: Page,
  itemId: string,
  expectedDestinationId: string,
  expectedPath: string[],
) => {
  await expect
    .poll(async () =>
      page.evaluate((id) => {
        const item = window.__graphTestApi!.getItem(id);
        return {
          graphDestinationId: item.graphNode?.attributes.destinationId,
          graphPath: item.graphNode?.attributes.path,
          activeDestinationId: item.activeItem?.destinationId,
          activePath: item.activeItem?.path,
        };
      }, itemId),
    )
    .toEqual({
      graphDestinationId: expectedDestinationId,
      graphPath: expectedPath,
      activeDestinationId: expectedDestinationId,
      activePath: expectedPath,
    });
};

export const waitForItemToMove = async (page: Page, itemId: string) => {
  const before = await getItemPosition(page, itemId);

  await expect
    .poll(async () => {
      const after = await getItemPosition(page, itemId);
      return Math.hypot(after.x - before.x, after.y - before.y);
    })
    .toBeGreaterThan(0.5);

  return before;
};

export const waitForItemToMoveFrom = async (page: Page, itemId: string, before: { x: number; y: number }) => {
  await expect
    .poll(async () => {
      const after = await getItemPosition(page, itemId);
      return Math.hypot(after.x - before.x, after.y - before.y);
    })
    .toBeGreaterThan(0.5);
};

export const waitForConveyorSpeed = async (page: Page, conveyorId: string, speed: number) => {
  await expect
    .poll(async () =>
      page.evaluate((id) => Number(window.__graphTestApi!.getEdge(id)?.attributes.speed), conveyorId),
    )
    .toBe(speed);
};

export const expectItemPositionStable = async (page: Page, itemId: string, durationMs = 1_500) => {
  const stoppedPosition = await getItemPosition(page, itemId);
  await page.waitForTimeout(durationMs);
  const laterPosition = await getItemPosition(page, itemId);

  expect(laterPosition.x).toBeCloseTo(stoppedPosition.x, 4);
  expect(laterPosition.y).toBeCloseTo(stoppedPosition.y, 4);
};

export const waitForChuteItemCount = async (page: Page, chuteId: string, count: number) => {
  await expect
    .poll(async () =>
      page.evaluate((id) => {
        const attrs = window.__graphTestApi!.getNode(id)?.attributes;
        return Array.isArray(attrs?.itemsInChute) ? attrs.itemsInChute.length : null;
      }, chuteId),
    )
    .toBe(count);
};
