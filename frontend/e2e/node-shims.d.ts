declare module "node:child_process" {
  export type ChildProcessWithoutNullStreams = {
    exitCode: number | null;
    stdout: {
      on(event: "data", listener: (chunk: { toString: (encoding?: string) => string }) => void): void;
    };
    stderr: {
      on(event: "data", listener: (chunk: { toString: (encoding?: string) => string }) => void): void;
    };
    once(event: "error", listener: (error: Error) => void): void;
    once(event: "exit", listener: (code: number | null) => void): void;
    kill(signal: "SIGTERM" | "SIGKILL"): void;
  };

  export function spawn(
    command: string,
    args: string[],
    options: {
      cwd?: string | URL;
      env?: Record<string, string | undefined>;
      stdio?: ["ignore", "pipe", "pipe"];
    },
  ): ChildProcessWithoutNullStreams;
}
