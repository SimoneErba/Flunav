import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";

export type RunningSimulator = {
  stop: () => Promise<void>;
  waitForOutput: (pattern: RegExp, timeoutMs?: number) => Promise<void>;
  output: () => string;
};

const simulatorCwd = new URL("../../../simulator/", import.meta.url);

const startSimulator = (
  backendUrl: string,
  authToken: string,
  action: "setup" | "destroy",
): RunningSimulator & { exited: Promise<number | null> } => {
  const child = spawn("mvn", ["compile", "exec:java", "-Dexec.mainClass=flunav.simulator.App", "-Dexec.args=line"], {
    cwd: simulatorCwd,
    env: {
      ...process.env,
      AUTH_TOKEN: authToken,
      BASE_URL: `${backendUrl}/api`,
      SIMULATOR_CLEANUP_ITEMS: action === "destroy" ? "0" : undefined,
      SIMULATION_ACTION: action,
      SIMULATION_MODE: "api",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });

  let output = "";
  const append = (chunk: { toString: (encoding?: string) => string }) => {
    output += chunk.toString("utf8");
  };
  child.stdout.on("data", append);
  child.stderr.on("data", append);

  const exited = new Promise<number | null>((resolve, reject) => {
    child.once("error", reject);
    child.once("exit", (code: number | null) => resolve(code));
  });

  return {
    exited,
    output: () => output,
    stop: () => stopProcess(child),
    waitForOutput: async (pattern: RegExp, timeoutMs = 30_000) => {
      const startedAt = Date.now();
      while (Date.now() - startedAt < timeoutMs) {
        if (pattern.test(output)) {
          return;
        }
        if (child.exitCode !== null) {
          throw new Error(`Simulator exited before ${pattern} appeared.\n${output}`);
        }
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
      throw new Error(`Timed out waiting for simulator output ${pattern}.\n${output}`);
    },
  };
};

export const startLineSimulator = (backendUrl: string, authToken: string): RunningSimulator =>
  startSimulator(backendUrl, authToken, "setup");

export const destroyLineSimulator = async (backendUrl: string, authToken: string) => {
  const simulator = startSimulator(backendUrl, authToken, "destroy");
  const exitCode = await simulator.exited;
  if (exitCode !== 0) {
    throw new Error(`Line simulator destroy exited with ${exitCode}.\n${simulator.output()}`);
  }
};

const stopProcess = async (child: ChildProcessWithoutNullStreams) => {
  if (child.exitCode !== null) {
    return;
  }

  const exited = new Promise<void>((resolve) => child.once("exit", () => resolve()));
  child.kill("SIGTERM");

  let killTimer: ReturnType<typeof setTimeout> | undefined;
  const forceKill = new Promise<void>((resolve) => {
    killTimer = setTimeout(() => {
      if (child.exitCode === null) {
        child.kill("SIGKILL");
      }
      resolve();
    }, 3_000);
  });

  await Promise.race([exited, forceKill]);
  if (killTimer) {
    clearTimeout(killTimer);
  }
};
