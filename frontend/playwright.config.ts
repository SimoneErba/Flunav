import { defineConfig, devices } from "@playwright/test";

const backendUrl = process.env.E2E_BACKEND_URL ?? "http://127.0.0.1:18080";
const frontendUrl = process.env.E2E_FRONTEND_URL ?? "http://localhost:5173";

export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.e2e.ts",
  timeout: 90_000,
  expect: {
    timeout: 15_000,
  },
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: frontendUrl,
    trace: "retain-on-failure",
  },
  webServer: [
    {
      command:
        "./mvnw -DskipTests test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.flunav.backend.e2e.PlaywrightBackendLauncher",
      cwd: "../backend",
      url: `${backendUrl}/api-docs`,
      timeout: 240_000,
      reuseExistingServer: !process.env.CI,
      stdout: "pipe",
      stderr: "pipe",
    },
    {
      command: "pnpm dev --host 127.0.0.1 --port 5173",
      cwd: ".",
      url: frontendUrl,
      timeout: 120_000,
      reuseExistingServer: !process.env.CI,
      stdout: "pipe",
      stderr: "pipe",
      env: {
        VITE_API_BASE_URL: backendUrl,
        VITE_GRAPH_TEST_API: "true",
      },
    },
  ],
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
