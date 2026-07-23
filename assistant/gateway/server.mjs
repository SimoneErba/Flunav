import { createServer } from "node:http";
import { auth, configure, runs, tasks } from "@trigger.dev/sdk";

configure({
  secretKey: process.env.TRIGGER_SECRET_KEY,
  baseURL: process.env.TRIGGER_API_URL,
});

const sessions = new Map();
const port = Number(process.env.PORT ?? 8090);
const backendUrl = (process.env.FLUMEN_BACKEND_URL ?? "http://backend:8080").replace(/\/$/, "");
const triggerPublicUrl = (process.env.TRIGGER_PUBLIC_URL ?? "http://localhost:8030").replace(/\/$/, "");

createServer(async (request, response) => {
  try {
    const requestUrl = new URL(request.url ?? "/", "http://assistant-gateway");
    if (request.headers["x-forwarded-proto"] === "https" && !triggerPublicUrl.startsWith("https://")) {
      return json(response, 503, {
        error: "TRIGGER_PUBLIC_URL must use HTTPS when Flumen is served over HTTPS",
      });
    }
    if (request.method === "GET" && requestUrl.pathname === "/health") {
      return health(response);
    }
    const user = await authenticate(request);
    if (request.method === "POST" && requestUrl.pathname === "/chat") {
      const body = validateChatBody(await readJson(request));
      const scope = body.simulationId ?? "live";
      const key = `${user.username}:${scope}:${body.chatId}`;
      const handle = await tasks.trigger("flumen-investigation", {
        question: body.message,
        username: user.username,
        simulationId: body.simulationId,
        chatId: body.chatId,
      }, {
        tags: [`user:${user.username}`, `scope:${scope}`, `chat:${body.chatId}`],
        metadata: { username: user.username, simulationId: body.simulationId ?? null, chatId: body.chatId },
        publicTokenOptions: { expirationTime: "15m" },
      });
      sessions.set(key, { runId: handle.id, username: user.username, scope });
      return json(response, 202, {
        message: "Investigation started. Evidence and status will stream from the scoped run.",
        runId: handle.id,
        publicAccessToken: handle.publicAccessToken,
      });
    }
    if (request.method === "POST" && requestUrl.pathname === "/token") {
      const body = validateRunBody(await readJson(request));
      const scope = body.simulationId ?? "live";
      const session = sessions.get(`${user.username}:${scope}:${body.chatId}`);
      const run = await retrieveOwnedRun(body.runId, user.username, scope, body.chatId);
      if (!run || (session && session.runId !== body.runId)) {
        return json(response, 404, { error: "Session not found" });
      }
      const token = await auth.createPublicToken({
        scopes: { read: { runs: [run.id] } },
        expirationTime: "15m",
      });
      return json(response, 200, { publicAccessToken: token });
    }
    if (request.method === "GET" && requestUrl.pathname.startsWith("/runs/")) {
      const runId = decodeURIComponent(requestUrl.pathname.slice("/runs/".length));
      const scope = validateScope(requestUrl.searchParams.get("simulationId"));
      const chatId = validateIdentifier(requestUrl.searchParams.get("chatId"), "chatId");
      const run = await retrieveOwnedRun(runId, user.username, scope, chatId);
      if (!run) return json(response, 404, { error: "Run not found" });
      return json(response, 200, {
        id: run.id,
        status: run.status,
        output: run.output,
        metadata: run.metadata,
      });
    }
    return json(response, 404, { error: "Not found" });
  } catch (error) {
    const requestedStatus = Number(error?.status);
    const status = requestedStatus >= 400 && requestedStatus < 500 ? requestedStatus : 500;
    const message = status === 500 ? "Assistant gateway failure" : error.message;
    return json(response, status, { error: message });
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
    const backendResponse = await fetch(`${backendUrl}/api/auth/me`, {
      signal: AbortSignal.timeout(2000),
    });
    if (backendResponse.status >= 500) throw new Error("Backend unavailable");
    return json(response, 200, { status: "ok", backend: "reachable", triggerPublicUrl });
  } catch {
    return json(response, 503, { status: "unavailable", backend: "unreachable" });
  }
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

function json(response, status, body) {
  response.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
  response.end(JSON.stringify(body));
}

async function retrieveOwnedRun(runId, username, scope, chatId) {
  if (typeof runId !== "string" || !runId.startsWith("run_")) return null;
  const run = await runs.retrieve(runId);
  const runMetadata = run.metadata && typeof run.metadata === "object" ? run.metadata : {};
  if (runMetadata.username !== username) return null;
  if (scope !== undefined && (runMetadata.simulationId ?? "live") !== scope) return null;
  if (chatId !== undefined && runMetadata.chatId !== chatId) return null;
  return run;
}

function validateChatBody(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw Object.assign(new Error("Invalid request body"), { status: 400 });
  }
  const chatId = validateIdentifier(body.chatId, "chatId");
  const simulationId = validateScope(body.simulationId);
  if (typeof body.message !== "string" || !body.message.trim() || body.message.length > 8000) {
    throw Object.assign(new Error("message must contain between 1 and 8000 characters"), { status: 400 });
  }
  return { chatId, simulationId, message: body.message.trim() };
}

function validateRunBody(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw Object.assign(new Error("Invalid request body"), { status: 400 });
  }
  return {
    runId: validateIdentifier(body.runId, "runId"),
    chatId: validateIdentifier(body.chatId, "chatId"),
    simulationId: validateScope(body.simulationId),
  };
}

function validateScope(value) {
  if (value === null || value === undefined || value === "" || value === "live") return undefined;
  return validateIdentifier(value, "simulationId");
}

function validateIdentifier(value, name) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(value)) {
    throw Object.assign(new Error(`${name} is invalid`), { status: 400 });
  }
  return value;
}
