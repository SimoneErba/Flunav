import type { APIRequestContext, Page } from "@playwright/test";
import { expect } from "@playwright/test";
import type { AuthSession } from "./auth";
import { waitForGraphTestApi } from "./graph";

export const simulationAuthHeaders = (session: AuthSession, simulationId?: string) => ({
  Authorization: `Bearer ${session.token}`,
  ...(simulationId ? { "X-Simulation-ID": simulationId } : {}),
});

export const waitForSimulationReady = async (
  request: APIRequestContext,
  backendUrl: string,
  session: AuthSession,
  simulationId: string,
) => {
  await expect
    .poll(async () => {
      const response = await request.get(`${backendUrl}/api/simulations/${simulationId}`, {
        headers: simulationAuthHeaders(session),
      });
      if (!response.ok()) {
        return `HTTP_${response.status()}`;
      }
      return ((await response.json()) as { status?: string }).status;
    }, { timeout: 60_000 })
    .toBe("READY");
};

export const startSimulationFromUi = async (
  page: Page,
  request: APIRequestContext,
  backendUrl: string,
  session: AuthSession,
) => {
  await page.goto("/live");
  await waitForGraphTestApi(page);

  const createResponsePromise = page.waitForResponse((response) =>
    response.url() === `${backendUrl}/api/simulations` && response.request().method() === "POST",
  );

  await page.getByRole("button", { name: "Time Travel" }).click();
  await page.getByRole("button", { name: "Start" }).click();

  const createResponse = await createResponsePromise;
  expect(createResponse.status()).toBe(202);
  const simulationId = ((await createResponse.json()) as { id: string }).id;

  await waitForSimulationReady(request, backendUrl, session, simulationId);
  await expect(page.getByRole("button", { name: "Exit Sim" })).toBeVisible({ timeout: 60_000 });
  await expect(page.getByText("Reconstructing Historical State...")).toHaveCount(0);
  await waitForGraphTestApi(page);

  return simulationId;
};
