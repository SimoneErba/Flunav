import { metadata, task } from "@trigger.dev/sdk";

type MetadataJson = Parameters<typeof metadata.set>[1];

type Payload = {
  question: string;
  username: string;
  simulationId?: string | null;
  chatId: string;
};

type InvestigationKind =
  | "system_summary"
  | "topology"
  | "alarms"
  | "alarm_investigation"
  | "item"
  | "item_positions"
  | "component"
  | "conveyor_flow"
  | "destination";

type Plan = {
  kind: InvestigationKind;
  entityId?: string;
  from?: string;
  to?: string;
  bucketSeconds?: 60 | 300 | 900 | 3600;
};

type Evidence = {
  operation: string;
  path: string;
  response: unknown;
};

const operationPaths = {
  systemSummary: () => "/system/summary",
  topology: () => "/topology",
  alarms: () => "/alarms",
  alarmInvestigation: (id: string) => `/alarms/${encodeURIComponent(id)}/investigation`,
  alarmAffectedItems: (id: string) => `/alarms/${encodeURIComponent(id)}/affected-items`,
  alarmRerouteOptions: (id: string) => `/alarms/${encodeURIComponent(id)}/reroute-options`,
  itemSummary: (id: string) => `/items/${encodeURIComponent(id)}/summary`,
  itemEvents: (id: string) => `/items/${encodeURIComponent(id)}/events`,
  itemPositions: () => "/items/positions",
  componentSummary: (id: string) => `/components/${encodeURIComponent(id)}/summary`,
  conveyorFlow: () => "/conveyors/flow",
  destinationSummary: (id: string) => `/destinations/${encodeURIComponent(id)}/summary`,
} as const;

export const flumenInvestigation = task({
  id: "flumen-investigation",
  run: async (payload: Payload) => {
    validatePayload(payload);
    await publishStatus("classifying", 10);
    const plan = await classify(payload.question);

    await publishStatus("collecting-evidence", 30, { plan });
    const evidence = await collectEvidence(plan, payload.simulationId);

    await publishStatus("composing", 75, {
      operations: evidence.map((entry) => entry.operation),
    });
    const answer = await complete([
      {
        role: "system",
        content: `You are Flumen's read-only conveyor investigation assistant.
Use only the supplied semantic API evidence and cite operation names in square brackets.
Separate observations and operational impact from possible causes.
Alarm typology is reported evidence, never proof of root cause.
For alarm investigations, state reroute feasibility, exact paths when returned, upstream eligibility,
committed items, and any unavailable alternative. Recommendations are advisory only.
Never claim a reroute was applied, invent throughput, expose secrets, or prescribe unsafe recovery actions.
If evidence is empty, say exactly what could not be established.`,
      },
      {
        role: "user",
        content: `Question: ${payload.question}\nPlan: ${JSON.stringify(plan)}\nEvidence: ${JSON.stringify(evidence)}`,
      },
    ], "large");

    await publishStatus("completed", 100);
    return {
      message: answer,
      plan,
      evidence,
      artifact: {
        protocolVersion: 1,
        recipes: widgetRecipes(plan.kind, evidence),
      },
    };
  },
});

async function classify(question: string): Promise<Plan> {
  const raw = await complete([
    {
      role: "system",
      content: `Classify a Flumen conveyor question. Return JSON only:
{"kind":"system_summary|topology|alarms|alarm_investigation|item|item_positions|component|conveyor_flow|destination","entityId":"optional exact ID","from":"optional ISO instant","to":"optional ISO instant","bucketSeconds":60}
Use alarm_investigation for root cause, impact, recovery, affected-item, or reroute questions.
Use item/component/destination only when the question identifies that entity. Do not invent IDs.
Allowed bucketSeconds: 60, 300, 900, 3600.`,
    },
    { role: "user", content: question },
  ], "small");

  try {
    const start = raw.indexOf("{");
    const end = raw.lastIndexOf("}");
    if (start < 0 || end < start) return { kind: "system_summary" };
    return validatePlan(JSON.parse(raw.slice(start, end + 1)));
  } catch {
    return { kind: "system_summary" };
  }
}

function validatePlan(candidate: unknown): Plan {
  if (!candidate || typeof candidate !== "object" || Array.isArray(candidate)) {
    return { kind: "system_summary" };
  }
  const value = candidate as Record<string, unknown>;
  const kinds: InvestigationKind[] = [
    "system_summary", "topology", "alarms", "alarm_investigation", "item",
    "item_positions", "component", "conveyor_flow", "destination",
  ];
  const kind = kinds.includes(value.kind as InvestigationKind)
    ? value.kind as InvestigationKind
    : "system_summary";
  const entityId = typeof value.entityId === "string" && /^[A-Za-z0-9_.:-]{1,160}$/.test(value.entityId)
    ? value.entityId
    : undefined;
  const from = validInstant(value.from);
  const to = validInstant(value.to);
  const allowedBuckets = [60, 300, 900, 3600] as const;
  const bucketSeconds = allowedBuckets.find((bucket) => bucket === value.bucketSeconds) ?? 60;
  return { kind, entityId, from, to, bucketSeconds };
}

async function collectEvidence(plan: Plan, simulationId?: string | null): Promise<Evidence[]> {
  const windowParams = {
    ...(plan.from ? { from: plan.from } : {}),
    ...(plan.to ? { to: plan.to } : {}),
  };
  switch (plan.kind) {
    case "system_summary":
      return Promise.all([
        evidence("system.summary", operationPaths.systemSummary(), simulationId),
        evidence("alarms.list", operationPaths.alarms(), simulationId, windowParams),
        evidence("conveyors.flow", operationPaths.conveyorFlow(), simulationId),
      ]);
    case "topology":
      return Promise.all([
        evidence("topology.get", operationPaths.topology(), simulationId),
        evidence("conveyors.flow", operationPaths.conveyorFlow(), simulationId),
      ]);
    case "alarms":
      return [await evidence("alarms.list", operationPaths.alarms(), simulationId, windowParams)];
    case "alarm_investigation": {
      const alarmId = plan.entityId ?? await discoverAlarmId(simulationId, windowParams);
      if (!alarmId) {
        return [await evidence("alarms.list", operationPaths.alarms(), simulationId, windowParams)];
      }
      const investigationParams = {
        ...windowParams,
        bucketSeconds: plan.bucketSeconds ?? 60,
      };
      return Promise.all([
        evidence("alarms.investigation", operationPaths.alarmInvestigation(alarmId), simulationId, investigationParams),
        evidence("alarms.affected-items", operationPaths.alarmAffectedItems(alarmId), simulationId),
        evidence("alarms.reroute-options", operationPaths.alarmRerouteOptions(alarmId), simulationId),
        evidence("topology.get", operationPaths.topology(), simulationId),
      ]);
    }
    case "item":
      return plan.entityId ? Promise.all([
        evidence("items.summary", operationPaths.itemSummary(plan.entityId), simulationId),
        evidence("items.events", operationPaths.itemEvents(plan.entityId), simulationId),
      ]) : [await evidence("items.positions", operationPaths.itemPositions(), simulationId)];
    case "item_positions":
      return [await evidence("items.positions", operationPaths.itemPositions(), simulationId)];
    case "component":
      return plan.entityId
        ? [await evidence("components.summary", operationPaths.componentSummary(plan.entityId), simulationId)]
        : [await evidence("conveyors.flow", operationPaths.conveyorFlow(), simulationId)];
    case "conveyor_flow":
      return [await evidence("conveyors.flow", operationPaths.conveyorFlow(), simulationId)];
    case "destination":
      return plan.entityId
        ? [await evidence("destinations.summary", operationPaths.destinationSummary(plan.entityId), simulationId)]
        : [await evidence("system.summary", operationPaths.systemSummary(), simulationId)];
  }
}

async function discoverAlarmId(simulationId: string | null | undefined, params: Record<string, unknown>) {
  const alarmEvidence = await evidence("alarms.list", operationPaths.alarms(), simulationId, params);
  const response = alarmEvidence.response as { data?: unknown };
  if (!Array.isArray(response?.data)) return undefined;
  const records = response.data as Array<Record<string, unknown>>;
  const openById = new Map<string, Record<string, unknown>>();
  for (const record of records) {
    const id = typeof record.alarmId === "string" ? record.alarmId : undefined;
    if (!id) continue;
    if (record.eventType === "CLEARED") openById.delete(id);
    else openById.set(id, record);
  }
  const candidates = openById.size ? [...openById.values()] : records;
  return candidates
    .sort((left, right) => String(right.timestamp ?? "").localeCompare(String(left.timestamp ?? "")))
    .find((record) => typeof record.alarmId === "string")?.alarmId as string | undefined;
}

async function evidence(
  operation: string,
  path: string,
  simulationId?: string | null,
  params: Record<string, unknown> = {},
): Promise<Evidence> {
  const response = await flumenGet(path, simulationId, params);
  return { operation, path, response };
}

async function flumenGet(
  path: string,
  simulationId?: string | null,
  params: Record<string, unknown> = {},
) {
  const baseUrl = required("FLUMEN_BACKEND_URL").replace(/\/$/, "");
  const url = new URL(`${baseUrl}/api/analytics/investigation${path}`);
  for (const [name, value] of Object.entries(params)) {
    if (value !== undefined) url.searchParams.set(name, String(value));
  }
  const headers: Record<string, string> = {
    "X-Flumen-Service-Token": required("FLUMEN_SERVICE_TOKEN"),
  };
  if (simulationId) headers["X-Simulation-ID"] = simulationId;
  const response = await fetch(url, { headers, signal: AbortSignal.timeout(20_000) });
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(`Flumen semantic operation failed with HTTP ${response.status}`);
  }
  return body;
}

function widgetRecipes(kind: InvestigationKind, evidence: Evidence[]) {
  const available = new Set(evidence.map((entry) => entry.operation));
  return [
    ...(available.has("system.summary") ? [{ type: "metric-cards", source: "system.summary" }] : []),
    ...(available.has("topology.get") ? [{ type: "topology-graph", source: "topology.get" }] : []),
    ...(available.has("alarms.list") || available.has("alarms.investigation")
      ? [{ type: "alarm-timeline", source: available.has("alarms.investigation") ? "alarms.investigation" : "alarms.list" }]
      : []),
    ...(available.has("items.summary") ? [{ type: "item-journey", source: "items.summary" }] : []),
    ...(available.has("alarms.investigation") ? [
      { type: "cartesian-chart", source: "alarms.investigation", selector: "data.throughput" },
      { type: "traversal-time", source: "alarms.investigation" },
    ] : []),
    { type: "evidence-table", source: "*", investigationKind: kind },
  ];
}

async function publishStatus(status: string, progress: number, details?: Record<string, unknown>) {
  metadata.set("status", status).set("progress", progress);
  if (details) metadata.set("details", details as MetadataJson);
  await metadata.flush();
}

async function complete(messages: Array<{ role: string; content: string }>, size: "small" | "large") {
  const groqModel = size === "small"
    ? process.env.GROQ_SMALL_MODEL ?? "openai/gpt-oss-20b"
    : process.env.GROQ_LARGE_MODEL ?? "openai/gpt-oss-120b";
  try {
    return await providerCompletion(
      "https://api.groq.com/openai/v1/chat/completions",
      required("GROQ_API_KEY"),
      groqModel,
      messages,
    );
  } catch (error) {
    if (!isFallbackEligible(error)) throw error;
    return providerCompletion(
      "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
      required("GEMINI_API_KEY"),
      process.env.GEMINI_FALLBACK_MODEL ?? "gemini-2.5-flash",
      messages,
    );
  }
}

async function providerCompletion(
  url: string,
  apiKey: string,
  model: string,
  messages: Array<{ role: string; content: string }>,
) {
  const response = await fetch(url, {
    method: "POST",
    headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
    body: JSON.stringify({ model, messages, temperature: 0.1 }),
    signal: AbortSignal.timeout(45_000),
  });
  if (!response.ok) {
    const failure = new Error(`Provider HTTP ${response.status}`) as Error & { status?: number };
    failure.status = response.status;
    throw failure;
  }
  const body = await response.json() as { choices?: Array<{ message?: { content?: string } }> };
  return body.choices?.[0]?.message?.content ?? "No answer was produced.";
}

function isFallbackEligible(error: unknown) {
  if (error instanceof DOMException && error.name === "TimeoutError") return true;
  const status = (error as { status?: number })?.status;
  return status === 429 || (typeof status === "number" && status >= 500)
    || error instanceof TypeError;
}

function validInstant(value: unknown) {
  return typeof value === "string" && !Number.isNaN(Date.parse(value)) ? new Date(value).toISOString() : undefined;
}

function validatePayload(payload: Payload) {
  if (!payload || typeof payload.question !== "string" || !payload.question.trim() || payload.question.length > 8000) {
    throw new Error("Invalid investigation question");
  }
  if (typeof payload.username !== "string" || !payload.username || typeof payload.chatId !== "string" || !payload.chatId) {
    throw new Error("Invalid investigation identity");
  }
}

function required(name: string) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}
