import type { APIRequestContext, Page } from "@playwright/test";
import { expect } from "@playwright/test";

export type AuthSession = {
  token: string;
  refreshToken: string;
  user: {
    username: string;
    role: string;
  };
};

export const loginAsSuperadmin = async (
  request: APIRequestContext,
  baseUrl: string,
): Promise<AuthSession> => {
  const response = await request.post(`${baseUrl}/api/auth/login`, {
    data: {
      username: "admin",
      password: "Flun4v!",
    },
  });

  expect(response.ok()).toBeTruthy();
  const body = (await response.json()) as { token: string; refreshToken: string; role: string };

  return {
    token: body.token,
    refreshToken: body.refreshToken,
    user: {
      username: "admin",
      role: body.role,
    },
  };
};

export const installAuthSession = async (page: Page, session: AuthSession) => {
  await page.addInitScript((auth) => {
    window.localStorage.setItem("flunav_token", auth.token);
    window.localStorage.setItem("flunav_refresh_token", auth.refreshToken);
    window.localStorage.setItem("flunav_user", JSON.stringify(auth.user));
  }, session);
};
