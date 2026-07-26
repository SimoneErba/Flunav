import { z } from "zod";

export const chatClientDataSchema = z.object({
  selections: z.object({
    selectedComponentId: z.string().max(160).optional(),
    selectedItemId: z.string().max(160).optional(),
    selectedDestinationId: z.string().max(160).optional(),
    selectedAlarmId: z.string().max(160).optional(),
    selectedTimestamp: z.string().datetime().optional(),
  }).optional(),
  inheritedContext: z.object({
    entityIds: z.array(z.string().max(160)).max(12).optional(),
    selectedTimestamp: z.string().datetime().optional(),
  }).optional(),
});

export type ChatClientData = z.infer<typeof chatClientDataSchema>;
export type InvestigationStrategy = "system_status" | "alarm_investigation" | "item_trace" | "component_investigation" | "destination_performance" | "historical_comparison" | "simulation_analysis";
export type SemanticOperationKind =
  | "system.summary" | "topology.get" | "alarms.list" | "alarms.investigation"
  | "items.summary" | "items.events" | "items.positions" | "components.summary"
  | "conveyors.flow" | "destinations.summary" | "simulations.list" | "system.snapshot";

export interface SemanticOperation {
  id: string;
  kind: SemanticOperationKind;
  path: string;
  params?: Record<string, string | number>;
}

export interface Evidence {
  operationId: string;
  operationKind: SemanticOperationKind;
  data: unknown;
  error?: string;
}

export type AgentProgress = {
  status: "resolving-context" | "classifying-strategy" | "planning-evidence" | "running-tool" | "assembling" | "complete" | "failed";
  label: string;
  percentage: number;
  completedOperations: number;
  failedOperations: number;
  strategy?: InvestigationStrategy;
};

export type VisualWidget = {
  id: string;
  kind: "metric-card" | "cartesian-chart" | "topology-graph" | "alarm-timeline" | "item-journey" | "traversal-time" | "evidence-table";
  title: string;
  layout: { area: "kpi" | "primary" | "secondary"; span?: 1 | 2 | 3 | 4 };
  evidenceIds: string[];
  data: unknown;
};

export type VisualAnswerDocument = {
  id: string;
  title: string;
  question: string;
  verdict: string;
  generatedAt: string;
  simulationId?: string;
  strategy: InvestigationStrategy;
  context: { entityIds: string[]; selectedTimestamp?: string };
  evidence: Array<{ operationId: string; operationKind: SemanticOperationKind; error?: string }>;
  widgets: VisualWidget[];
};

export type InvestigationScope = { username: string; simulationId?: string };

const identifier = /^[A-Za-z0-9_.:-]{1,160}$/;

export function resolveContext(question: string, data: ChatClientData): { entityIds: string[]; selectedTimestamp?: string } {
  const selections = data.selections ?? {};
  const direct = [selections.selectedAlarmId, selections.selectedItemId, selections.selectedComponentId, selections.selectedDestinationId]
    .filter((value): value is string => Boolean(value && identifier.test(value)));
  const mentioned = question.match(/\b[A-Za-z][A-Za-z0-9_.:-]{1,159}\b/g) ?? [];
  const retained = data.inheritedContext?.entityIds?.filter(value => identifier.test(value)) ?? [];
  return {
    entityIds: [...new Set([...direct, ...mentioned.filter(value => /[-_.:]/.test(value)), ...retained])].slice(0, 12),
    selectedTimestamp: selections.selectedTimestamp ?? data.inheritedContext?.selectedTimestamp,
  };
}

export function classifyStrategy(question: string, context: ReturnType<typeof resolveContext>): InvestigationStrategy {
  const lower = question.toLowerCase();
  if (/compare|previous|baseline|earlier|before/.test(lower)) return "historical_comparison";
  if (/simulation|what-if|what if|forecast/.test(lower)) return "simulation_analysis";
  if (/alarm|incident|fault|root cause|anomal|reroute|bottleneck/.test(lower) || context.entityIds.some(id => /^alarm|^inc/i.test(id))) return "alarm_investigation";
  if (/item|parcel|journey|trace|path|position/.test(lower)) return "item_trace";
  if (/destination|exit/.test(lower)) return "destination_performance";
  if (/conveyor|component|location|queue|capacity|utilization/.test(lower)) return "component_investigation";
  return "system_status";
}

export function planOperations(strategy: InvestigationStrategy, context: ReturnType<typeof resolveContext>): SemanticOperation[] {
  const id = context.entityIds[0];
  const common: SemanticOperation[] = [
    { id: "system-summary", kind: "system.summary", path: "/system/summary" },
    { id: "topology", kind: "topology.get", path: "/topology" },
  ];
  const snapshot = context.selectedTimestamp
    ? [{ id: "system-snapshot", kind: "system.snapshot" as const, path: "/system/snapshot", params: { at: context.selectedTimestamp } }]
    : [];
  switch (strategy) {
    case "alarm_investigation":
      return id ? [
        ...common,
        ...snapshot,
        { id: "alarm-investigation", kind: "alarms.investigation", path: `/alarms/${encodeURIComponent(id)}/investigation`, params: { bucketSeconds: 60 } },
        { id: "alarms", kind: "alarms.list", path: "/alarms" },
      ] : [...common, ...snapshot, { id: "alarms", kind: "alarms.list", path: "/alarms" }];
    case "item_trace":
      return id ? [
        ...common,
        ...snapshot,
        { id: "item-summary", kind: "items.summary", path: `/items/${encodeURIComponent(id)}/summary` },
        { id: "item-events", kind: "items.events", path: `/items/${encodeURIComponent(id)}/events` },
      ] : [...snapshot, { id: "item-positions", kind: "items.positions", path: "/items/positions" }];
    case "component_investigation":
      return id ? [
        ...common,
        ...snapshot,
        { id: "component-summary", kind: "components.summary", path: `/components/${encodeURIComponent(id)}/summary` },
        { id: "conveyor-flow", kind: "conveyors.flow", path: "/conveyors/flow" },
      ] : [...common, ...snapshot, { id: "conveyor-flow", kind: "conveyors.flow", path: "/conveyors/flow" }];
    case "destination_performance":
      return id ? [
        ...common,
        ...snapshot,
        { id: "destination-summary", kind: "destinations.summary", path: `/destinations/${encodeURIComponent(id)}/summary` },
      ] : [...common, ...snapshot];
    case "historical_comparison":
      return [...common, ...snapshot, { id: "alarms", kind: "alarms.list", path: "/alarms" }, { id: "conveyor-flow", kind: "conveyors.flow", path: "/conveyors/flow" }];
    case "simulation_analysis":
      return [{ id: "simulations", kind: "simulations.list", path: "/simulations" }, ...common, ...snapshot];
    default:
      return [...common, ...snapshot, { id: "alarms", kind: "alarms.list", path: "/alarms" }, { id: "conveyor-flow", kind: "conveyors.flow", path: "/conveyors/flow" }];
  }
}

export async function runOperation(operation: SemanticOperation, scope: InvestigationScope): Promise<Evidence> {
  const baseUrl = required("FLUMEN_BACKEND_URL").replace(/\/$/, "");
  const url = new URL(`/api/analytics/investigation${operation.path}`, baseUrl);
  for (const [key, value] of Object.entries(operation.params ?? {})) url.searchParams.set(key, String(value));
  const headers: Record<string, string> = { "X-Flumen-Service-Token": required("FLUMEN_SERVICE_TOKEN") };
  if (scope.simulationId) headers["X-Simulation-ID"] = scope.simulationId;
  try {
    const response = await fetch(url, { headers, signal: AbortSignal.timeout(20_000) });
    const body = await response.json().catch(() => null);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return { operationId: operation.id, operationKind: operation.kind, data: body };
  } catch (error) {
    return { operationId: operation.id, operationKind: operation.kind, data: null, error: error instanceof Error ? error.message : "semantic operation failed" };
  }
}

export function composeVisualAnswer(question: string, strategy: InvestigationStrategy, context: ReturnType<typeof resolveContext>, scope: InvestigationScope, evidence: Evidence[]): VisualAnswerDocument {
  const available = new Set(evidence.filter(entry => !entry.error).map(entry => entry.operationId));
  const widget = (id: string, kind: VisualWidget["kind"], title: string, evidenceIds: string[], area: VisualWidget["layout"]["area"], data: unknown, span?: 1 | 2 | 3 | 4): VisualWidget | undefined => {
    const bound = evidenceIds.filter(entry => available.has(entry));
    return bound.length ? { id, kind, title, evidenceIds: bound, layout: { area, span }, data } : undefined;
  };
  const byId = Object.fromEntries(evidence.map(entry => [entry.operationId, unwrapData(entry.data)]));
  const widgets = [
    widget("system-kpis", "metric-card", "System state", ["system-summary"], "kpi", byId["system-summary"]),
    widget("flow", "cartesian-chart", "Conveyor flow", ["conveyor-flow", "system-summary"], "primary", { flow: byId["conveyor-flow"], summary: byId["system-summary"] }, 2),
    widget("topology", "topology-graph", "Topology", ["topology"], "primary", byId.topology, 2),
    widget("alarms", "alarm-timeline", "Alarm timeline", ["alarm-investigation", "alarms"], "secondary", byId["alarm-investigation"] ?? byId.alarms, 2),
    widget("journey", "item-journey", "Item journey", ["item-summary", "item-events"], "primary", { summary: byId["item-summary"], events: byId["item-events"] }, 2),
    widget("traversal", "traversal-time", "Traversal metrics", ["system-summary", "item-summary"], "secondary", { summary: byId["system-summary"], item: byId["item-summary"] }, 2),
    widget("evidence", "evidence-table", "Evidence", evidence.map(entry => entry.operationId), "secondary", evidence, 4),
  ].filter((value): value is VisualWidget => Boolean(value));
  const succeeded = evidence.filter(entry => !entry.error).length;
  return {
    id: `visual-answer-${Date.now()}`,
    title: titleFor(strategy),
    question,
    verdict: succeeded ? `Based on ${succeeded} read-only Flumen semantic operation${succeeded === 1 ? "" : "s"}.` : "No semantic evidence could be retrieved for this scope.",
    generatedAt: new Date().toISOString(),
    simulationId: scope.simulationId,
    strategy,
    context,
    evidence: evidence.map(entry => ({ operationId: entry.operationId, operationKind: entry.operationKind, error: entry.error })),
    widgets,
  };
}

export function evidencePrompt(answer: VisualAnswerDocument, evidence: Evidence[]): string {
  return `You are Flumen's read-only conveyor investigation assistant. Answer in two concise paragraphs. Use only this evidence. Separate observed facts from possible causes, do not invent values or claim a change was applied. Cite operation ids in brackets.\nQuestion: ${answer.question}\nStrategy: ${answer.strategy}\nEvidence: ${JSON.stringify(evidence)}`;
}

function unwrapData(value: unknown): unknown {
  return value && typeof value === "object" && "data" in value ? (value as { data: unknown }).data : value;
}

function titleFor(strategy: InvestigationStrategy): string {
  return ({ system_status: "System investigation", alarm_investigation: "Alarm investigation", item_trace: "Item journey", component_investigation: "Component investigation", destination_performance: "Destination performance", historical_comparison: "Historical comparison", simulation_analysis: "Simulation analysis" })[strategy];
}

function required(name: string): string {
  const value = process.env[name];
  if (!value?.trim()) throw new Error(`${name} is required`);
  return value;
}
