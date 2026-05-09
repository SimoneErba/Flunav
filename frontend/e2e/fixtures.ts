import { test as base } from "@playwright/test";

export const test = base.extend({
  page: async ({ page }, use) => {
    page.on("console", (message) => {
      console.log(`[browser:${message.type()}] ${message.text()}`);
    });

    page.on("pageerror", (error) => {
      console.error(`[browser:pageerror] ${error.message}`);
    });

    page.on("requestfailed", (request) => {
      console.warn(`[browser:requestfailed] ${request.method()} ${request.url()} ${request.failure()?.errorText}`);
    });

    await use(page);
  },
});

export { expect } from "@playwright/test";
