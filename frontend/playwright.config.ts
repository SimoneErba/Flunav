import { defineConfig, devices } from "@playwright/test";

const backendUrl = process.env.E2E_BACKEND_URL ?? "http://127.0.0.1:18080";
const frontendUrl = process.env.E2E_FRONTEND_URL ?? "http://localhost:5173";
const frontendPort = new URL(frontendUrl).port || "5173";
const testBuild = process.env.E2E_TEST_BUILD === "true";
const reuseServers = process.env.E2E_REUSE_SERVERS === "true";
const allowNonIsolatedBackend = process.env.E2E_ALLOW_NON_ISOLATED_BACKEND === "true";

if (!allowNonIsolatedBackend && /^https?:\/\/(localhost|127\.0\.0\.1):8080\b/.test(backendUrl)) {
  throw new Error(
    "Refusing to run E2E tests against the default live backend on port 8080. " +
      "Use the isolated default backend on 18080 or set E2E_ALLOW_NON_ISOLATED_BACKEND=true.",
  );
}

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
      reuseExistingServer: reuseServers,
      stdout: "pipe",
      stderr: "pipe",
    },
    {
      command: testBuild
        ? `pnpm build && pnpm exec vite preview --host 127.0.0.1 --port ${frontendPort}`
        : `pnpm dev --host 127.0.0.1 --port ${frontendPort}`,
      cwd: ".",
      url: frontendUrl,
      timeout: 120_000,
      reuseExistingServer: reuseServers,
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
