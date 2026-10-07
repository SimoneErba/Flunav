import { createServer } from "node:http";
import { auth, configure, runs, sessions } from "@trigger.dev/sdk";

configure({
  secretKey: process.env.TRIGGER_SECRET_KEY,
  baseURL: process.env.TRIGGER_API_URL,
});

const port = Number(process.env.PORT ?? 8090);
const backendUrl = (process.env.FLUNAV_BACKEND_URL ?? "http://backend:8080").replace(/\/$/, "");
const triggerPublicUrl = (process.env.TRIGGER_PUBLIC_URL ?? "http://localhost:8030").replace(/\/$/, "");
const serviceToken = process.env.FLUNAV_SERVICE_TOKEN ?? process.env.FLUNAV_ASSISTANT_SERVICE_TOKEN ?? "";
const agentId = "flunav-investigation-agent";

createServer(async (request, response) => {
  try {
    const url = new URL(request.url ?? "/", "http://assistant-gateway");
    if (request.headers["x-forwarded-proto"] === "https" && !triggerPublicUrl.startsWith("https://")) {
      return json(response, 503, { error: "TRIGGER_PUBLIC_URL must use HTTPS when Flunav is served over HTTPS" });
    }
    if (request.method === "GET" && url.pathname === "/health") return health(response);
    const user = await authenticate(request);
    if (request.method === "POST" && url.pathname === "/sessions/start") {
      const body = validateSessionBody(await readJson(request));
      const existing = await ownedSession(body.chatId, user.username, body.simulationId);
      if (existing) return json(response, 200, { publicAccessToken: await sessionToken(body.chatId), chatId: body.chatId });
      const created = await sessions.start({
        type: "chat.agent",
        externalId: body.chatId,
        taskIdentifier: agentId,
        tags: [`assistant-user:${user.username}`, `assistant-scope:${body.simulationId ?? "live"}`],
        metadata: { username: user.username, simulationId: body.simulationId ?? null },
        triggerConfig: {
          idleTimeoutInSeconds: 30,
          tags: [`chat:${body.chatId}`],
          basePayload: {
            messages: [],
            trigger: "preload",
            chatId: body.chatId,
            metadata: body.clientData,
          },
        },
      });
      return json(response, 201, { publicAccessToken: created.publicAccessToken, chatId: body.chatId });
    }
    if (request.method === "POST" && url.pathname === "/sessions/token") {
      const body = validateSessionBody(await readJson(request));
      const session = await ownedSession(body.chatId, user.username, body.simulationId);
      if (!session) return json(response, 404, { error: "Session not found" });
      return json(response, 200, { publicAccessToken: await sessionToken(body.chatId), chatId: body.chatId });
    }
    if (request.method === "POST" && url.pathname === "/sessions/stop") {
      const body = validateSessionBody(await readJson(request));
      const session = await ownedSession(body.chatId, user.username, body.simulationId);
      if (!session) return json(response, 404, { error: "Session not found" });
      await sessions.open(session.id).in.send({ kind: "stop", message: "Stopped by the authenticated user" });
      return json(response, 202, { stopped: true });
    }
    if (request.method === "POST" && url.pathname === "/sessions/reset") {
      const body = validateSessionBody(await readJson(request));
      const session = await ownedSession(body.chatId, user.username, body.simulationId);
      let cancelled = false;
      let closed = false;
      if (session) {
        const runId = session.currentRunId ?? session.runId;
        if (runId) {
          cancelled = await runs.cancel(runId).then(() => true).catch(() => false);
        }
        await sessions.open(session.id).in.send({ kind: "stop", message: "Reset by the authenticated user" }).catch(() => undefined);
        closed = await sessions.close(session.id, { reason: "Reset by the authenticated user" }).then(() => true).catch(() => false);
      }
      return json(response, 202, { reset: true, cancelled, closed });
    }
    return json(response, 404, { error: "Not found" });
  } catch (error) {
    const requestedStatus = Number(error?.status);
    const status = requestedStatus >= 400 && requestedStatus < 500 ? requestedStatus : 500;
    return json(response, status, { error: status === 500 ? "Assistant gateway failure" : error.message });
  }
}).listen(port);

async function authenticate(request) {
  const authorization = request.headers.authorization;
  if (!authorization?.startsWith("Bearer ")) throw Object.assign(new Error("Unauthorized"), { status: 401 });
  const response = await fetch(`${backendUrl}/api/auth/me`, { headers: { Authorization: authorization } });
  if (!response.ok) throw Object.assign(new Error("Unauthorized"), { status: 401 });
  return response.json();
}

async function health(response) {
  try {
    const backendResponse = await fetch(`${backendUrl}/api/health`, { signal: AbortSignal.timeout(2000) });
    if (backendResponse.status >= 500) throw new Error("Backend unavailable");
    const semantic = await semanticDiagnostic();
    return json(response, semantic.ok ? 200 : 503, { status: semantic.ok ? "ok" : "unavailable", triggerPublicUrl, agentId, semantic });
  } catch (error) {
    return json(response, 503, { status: "unavailable", semantic: { ok: false, error: error instanceof Error ? error.message : "Assistant health check failed" } });
  }
}

async function semanticDiagnostic() {
  if (!serviceToken.trim()) {
    return { ok: false, error: "FLUNAV_SERVICE_TOKEN or FLUNAV_ASSISTANT_SERVICE_TOKEN is required for assistant semantic access" };
  }
  try {
    const semanticResponse = await fetch(`${backendUrl}/api/analytics/investigation/system/summary`, {
      headers: { "X-Flunav-Service-Token": serviceToken },
      signal: AbortSignal.timeout(3000),
    });
    await semanticResponse.arrayBuffer().catch(() => undefined);
    if (semanticResponse.ok) return { ok: true, status: semanticResponse.status };
    if (semanticResponse.status === 401 || semanticResponse.status === 403) {
      return {
        ok: false,
        status: semanticResponse.status,
        error: "Semantic endpoint rejected the assistant service token. Align APP_ASSISTANT_SERVICE_TOKEN on the backend with FLUNAV_SERVICE_TOKEN or FLUNAV_ASSISTANT_SERVICE_TOKEN.",
      };
    }
    return { ok: false, status: semanticResponse.status, error: `Semantic endpoint returned HTTP ${semanticResponse.status}` };
  } catch (error) {
    return { ok: false, error: error instanceof Error ? error.message : "Semantic diagnostic failed" };
  }
}

async function ownedSession(chatId, username, simulationId) {
  try {
    const session = await sessions.retrieve(chatId);
    const metadata = session.metadata && typeof session.metadata === "object" ? session.metadata : {};
    if (metadata.username !== username || (metadata.simulationId ?? null) !== (simulationId ?? null)) return null;
    return session;
  } catch {
    return null;
  }
}

async function sessionToken(sessionId) {
  return auth.createPublicToken({
    scopes: { read: { sessions: sessionId }, write: { sessions: sessionId } },
    expirationTime: "15m",
  });
}

async function readJson(request) {
  let body = "";
  for await (const chunk of request) {
    body += chunk;
    if (body.length > 64 * 1024) throw Object.assign(new Error("Payload too large"), { status: 413 });
  }
  try {
    return JSON.parse(body || "{}");
  } catch {
    throw Object.assign(new Error("Invalid JSON"), { status: 400 });
  }
}

function validateSessionBody(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) throw Object.assign(new Error("Invalid request body"), { status: 400 });
  const chatId = validateIdentifier(body.chatId, "chatId");
  const simulationId = validateScope(body.simulationId);
  return { chatId, simulationId, clientData: sanitizeClientData(body.clientData) };
}

function sanitizeClientData(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const source = value;
  const selected = source.selections && typeof source.selections === "object" && !Array.isArray(source.selections) ? source.selections : {};
  const selections = Object.fromEntries(Object.entries(selected)
    .filter(([key, entry]) => ["selectedComponentId", "selectedItemId", "selectedDestinationId", "selectedAlarmId", "selectedTimestamp"].includes(key)
      && typeof entry === "string" && entry.length <= 160));
  return Object.keys(selections).length ? { selections } : {};
}

function validateScope(value) {
  if (value === null || value === undefined || value === "" || value === "live") return undefined;
  return validateIdentifier(value, "simulationId");
}

function validateIdentifier(value, name) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(value)) throw Object.assign(new Error(`${name} is invalid`), { status: 400 });
  return value;
}

function json(response, status, body) {
  response.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
  response.end(JSON.stringify(body));
}
