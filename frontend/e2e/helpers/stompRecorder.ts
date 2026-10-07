import type { Page } from "@playwright/test";

export type RecordedSocketEvent = {
  topic: string;
  arrivalWallTime: number;
  envelope: {
    payload: unknown;
    senderId: string | null;
    timestamp: number;
  };
};

type RecorderWindow = Window &
  typeof globalThis & {
    __flunavStompRecorder?: {
      events: RecordedSocketEvent[];
      stop: () => void;
    };
  };

export const startStompRecorder = async (page: Page, backendUrl: string, topics: string[]) => {
  const wsUrl = `${backendUrl.replace(/^http/, "ws")}/ws/websocket`;

  await page.evaluate(
    ({ topics: destinations, wsUrl: websocketUrl }) =>
      new Promise<void>((resolve, reject) => {
        const recorderWindow = window as RecorderWindow;
        recorderWindow.__flunavStompRecorder?.stop();

        const events: RecordedSocketEvent[] = [];
        const socket = new WebSocket(websocketUrl);
        let connected = false;
        let nextSubscriptionId = 1;
        let sockJsTransport = false;
        let rawConnectTimer: number | undefined;

        const sendFrame = (frame: string) => {
          socket.send(sockJsTransport ? JSON.stringify([frame]) : frame);
        };

        const connect = () => {
          sendFrame("CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\0");
        };

        const subscribe = () => {
          for (const destination of destinations) {
            sendFrame(
              `SUBSCRIBE\nid:sub-${nextSubscriptionId++}\ndestination:${destination}\nack:auto\n\n\0`,
            );
          }
          resolve();
        };

        const parseHeaders = (lines: string[]) => {
          const headers: Record<string, string> = {};
          for (const line of lines) {
            const separator = line.indexOf(":");
            if (separator > 0) {
              headers[line.slice(0, separator)] = line.slice(separator + 1);
            }
          }
          return headers;
        };

        const handleStompFrame = (frame: string) => {
          const trimmedFrame = frame.replace(/^\n+/, "");
          if (!trimmedFrame) {
            return;
          }

          const headerEnd = trimmedFrame.indexOf("\n\n");
          if (headerEnd < 0) {
            return;
          }

          const headerLines = trimmedFrame.slice(0, headerEnd).split("\n");
          const command = headerLines[0];
          const headers = parseHeaders(headerLines.slice(1));
          const body = trimmedFrame.slice(headerEnd + 2);

          if (command === "CONNECTED") {
            connected = true;
            subscribe();
            return;
          }

          if (command !== "MESSAGE" || !headers.destination) {
            return;
          }

          events.push({
            topic: headers.destination,
            arrivalWallTime: Date.now(),
            envelope: JSON.parse(body),
          });
        };

        const handleSocketMessage = (data: string) => {
          if (data === "o") {
            sockJsTransport = true;
            if (rawConnectTimer !== undefined) {
              window.clearTimeout(rawConnectTimer);
            }
            connect();
            return;
          }
          if (data === "h" || data === "\n") {
            return;
          }

          const frames = data.startsWith("a[") ? (JSON.parse(data) as string[]) : [data];
          for (const frameGroup of frames) {
            for (const frame of frameGroup.split("\0")) {
              handleStompFrame(frame);
            }
          }
        };

        socket.onopen = () => {
          rawConnectTimer = window.setTimeout(() => {
            if (!connected && socket.readyState === WebSocket.OPEN) {
              connect();
            }
          }, 100);
        };
        socket.onmessage = (message) => {
          if (typeof message.data === "string") {
            handleSocketMessage(message.data);
          }
        };
        socket.onerror = () => reject(new Error(`WebSocket recorder failed to connect to ${websocketUrl}`));
        socket.onclose = () => {
          if (!connected) {
            reject(new Error(`WebSocket recorder closed before STOMP CONNECTED from ${websocketUrl}`));
          }
        };

        recorderWindow.__flunavStompRecorder = {
          events,
          stop: () => socket.close(),
        };
      }),
    { topics, wsUrl },
  );
};

export const getRecordedSocketEvents = async (page: Page): Promise<RecordedSocketEvent[]> =>
  page.evaluate(() => {
    const recorderWindow = window as RecorderWindow;
    return recorderWindow.__flunavStompRecorder?.events ?? [];
  });

export const stopStompRecorder = async (page: Page) => {
  await page.evaluate(() => {
    const recorderWindow = window as RecorderWindow;
    recorderWindow.__flunavStompRecorder?.stop();
    delete recorderWindow.__flunavStompRecorder;
  });
};
