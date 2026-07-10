import type { Page } from "@playwright/test";
import { expect } from "@playwright/test";
import { getRecordedSocketEvents } from "./stompRecorder";

export type HudMetric =
  | "active"
  | "priority"
  | "routed"
  | "waiting"
  | "unrouted"
  | "failed"
  | "completed"
  | "in-5s"
  | "cleared-5s"
  | "stopped"
  | "full-chutes";

export type ThroughputSocketMetric = {
  timestamp?: string;
  itemsEntered?: number;
  itemsExited?: number;
  itemsCurrent?: number;
  bucketSeconds?: number;
};

const hudLabels: Record<HudMetric, string> = {
  active: "Active",
  priority: "Priority",
  routed: "Routed",
  waiting: "Waiting",
  unrouted: "Unrouted",
  failed: "Failed",
  completed: "Completed",
  "in-5s": "In/5s",
  "cleared-5s": "Cleared/5s",
  stopped: "Stopped",
  "full-chutes": "Full chutes",
};

const hudTestIds: Record<HudMetric, string> = {
  active: "live-hud-active",
  priority: "live-hud-priority",
  routed: "live-hud-routed",
  waiting: "live-hud-waiting",
  unrouted: "live-hud-unrouted",
  failed: "live-hud-failed",
  completed: "live-hud-completed",
  "in-5s": "live-hud-in-5s",
  "cleared-5s": "live-hud-cleared-5s",
  stopped: "live-hud-stopped",
  "full-chutes": "live-hud-full-chutes",
};

const parseHudValue = (text: string | null): number => {
  const normalized = (text ?? "").replace(/[^\d.-]/g, "");
  const parsed = Number(normalized);
  return Number.isFinite(parsed) ? parsed : Number.NaN;
};

export const getHudValues = async (page: Page): Promise<Record<string, number>> => {
  await expect(page.getByTestId("live-hud")).toBeVisible();

  const values: Record<string, number> = {};
  for (const metric of Object.keys(hudTestIds) as HudMetric[]) {
    const text = await page.getByTestId(hudTestIds[metric]).locator("span").last().textContent();
    values[hudLabels[metric]] = parseHudValue(text);
  }
  return values;
};

export const waitForHudValue = async (page: Page, metric: HudMetric, value: number) => {
  await expect
    .poll(async () => {
      const text = await page.getByTestId(hudTestIds[metric]).locator("span").last().textContent();
      return parseHudValue(text);
    })
    .toBe(value);
};

export const waitForThroughputSocketMetric = async (
  page: Page,
  topic: string,
  predicate: (metric: ThroughputSocketMetric) => boolean,
): Promise<ThroughputSocketMetric> => {
  let matchedMetric: ThroughputSocketMetric | undefined;

  await expect
    .poll(async () => {
      const events = await getRecordedSocketEvents(page);
      matchedMetric = events
        .filter((event) => event.topic === topic)
        .map((event) => event.envelope.payload as ThroughputSocketMetric)
        .find(predicate);
      return Boolean(matchedMetric);
    }, { timeout: 20_000 })
    .toBe(true);

  return matchedMetric!;
};
