import { z } from "zod";
import { isOperationAllowed, templateForStrategy } from "./answer-templates.js";
import { composeWidgets, findingsFor, type TypedWidgetData } from "./widget-shaping.js";
import type { WidgetKind } from "./widget-catalog.js";

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
  | "conveyors.flow" | "destinations.summary" | "simulations.list" | "system.snapshot"
  | "anomalies.list" | "anomaly-incidents.list";

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

export type AssistantRuntimeDiagnostic = {
  ok: boolean;
  status?: number;
  url: string;
  error?: string;
};

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
  kind: WidgetKind;
  title: string;
  layout: { area: "kpi" | "primary" | "secondary"; span?: 1 | 2 | 3 | 4 };
  evidenceIds: string[];
  data: TypedWidgetData;
};

export type VisualAnswerDocument = {
  id: string;
  title: string;
  question: string;
  verdict: string;
  generatedAt: string;
  simulationId?: string;
  strategy: InvestigationStrategy;
  dataMode: "live" | "simulation" | "historical" | "mixed";
  timeRange?: { from?: string; to?: string; selectedTimestamp?: string };
  retainedContextIds: string[];
  context: { entityIds: string[]; selectedTimestamp?: string };
  findings: string[];
  evidence: Array<{ operationId: string; operationKind: SemanticOperationKind; records: number; error?: string; generatedAt?: string }>;
  widgets: VisualWidget[];
};

export type InvestigationScope = { username: string; simulationId?: string };

const identifier = /^[A-Za-z0-9_.:-]{1,160}$/;
const operationPathPatterns: Record<SemanticOperationKind, RegExp[]> = {
  "system.summary": [/^\/system\/summary$/],
  "topology.get": [/^\/topology$/],
  "alarms.list": [/^\/alarms$/],
  "alarms.investigation": [/^\/alarms\/[^/]{1,220}\/investigation$/],
  "items.summary": [/^\/items\/[^/]{1,220}\/summary$/],
  "items.events": [/^\/items\/[^/]{1,220}\/events$/],
  "items.positions": [/^\/items\/positions$/],
  "components.summary": [/^\/components\/[^/]{1,220}\/summary$/],
  "conveyors.flow": [/^\/conveyors\/flow$/],
  "destinations.summary": [/^\/destinations\/[^/]{1,220}\/summary$/],
  "simulations.list": [/^\/simulations$/],
  "system.snapshot": [/^\/system\/snapshot$/],
  "anomalies.list": [/^\/anomalies$/],
  "anomaly-incidents.list": [/^\/anomaly-incidents$/],
};

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
        { id: "anomaly-findings", kind: "anomalies.list", path: "/anomalies" },
        { id: "anomaly-incidents", kind: "anomaly-incidents.list", path: "/anomaly-incidents" },
        { id: "alarm-investigation", kind: "alarms.investigation", path: `/alarms/${encodeURIComponent(id)}/investigation`, params: { bucketSeconds: 60 } },
        { id: "alarms", kind: "alarms.list", path: "/alarms" },
      ] : [...common, ...snapshot,
        { id: "anomaly-findings", kind: "anomalies.list", path: "/anomalies" },
        { id: "anomaly-incidents", kind: "anomaly-incidents.list", path: "/anomaly-incidents" },
        { id: "alarms", kind: "alarms.list", path: "/alarms" }];
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
        { id: "anomaly-findings", kind: "anomalies.list", path: "/anomalies" },
        { id: "anomaly-incidents", kind: "anomaly-incidents.list", path: "/anomaly-incidents" },
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

export function validateOperations(strategy: InvestigationStrategy, operations: SemanticOperation[]): SemanticOperation[] {
  const seen = new Set<string>();
  return operations
    .filter(operation => isOperationAllowed(strategy, operation.kind) && isKnownOperation(operation))
    .filter(operation => {
      const key = `${operation.kind}:${operation.path}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
}

export function isKnownOperation(operation: SemanticOperation): boolean {
  return operationPathPatterns[operation.kind]?.some(pattern => pattern.test(operation.path)) ?? false;
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

export async function validateSemanticAccess(scope: InvestigationScope): Promise<AssistantRuntimeDiagnostic> {
  const baseUrl = required("FLUMEN_BACKEND_URL").replace(/\/$/, "");
  const url = new URL("/api/analytics/investigation/system/summary", baseUrl);
  const headers: Record<string, string> = { "X-Flumen-Service-Token": required("FLUMEN_SERVICE_TOKEN") };
  if (scope.simulationId) headers["X-Simulation-ID"] = scope.simulationId;
  try {
    const response = await fetch(url, { headers, signal: AbortSignal.timeout(5_000) });
    await response.arrayBuffer().catch(() => undefined);
    return {
      ok: response.ok,
      status: response.status,
      url: url.toString(),
      error: response.ok ? undefined : diagnosticMessage(response.status),
    };
  } catch (error) {
    return {
      ok: false,
      url: url.toString(),
      error: error instanceof Error ? error.message : "semantic diagnostic failed",
    };
  }
}

export function composeVisualAnswer(question: string, strategy: InvestigationStrategy, context: ReturnType<typeof resolveContext>, scope: InvestigationScope, evidence: Evidence[]): VisualAnswerDocument {
  const template = templateForStrategy(strategy, scope.simulationId);
  const widgets = composeWidgets(strategy, scope, evidence);
  const findings = findingsFor(strategy, scope, evidence);
  const succeeded = evidence.filter(entry => !entry.error).length;
  return {
    id: `visual-answer-${Date.now()}`,
    title: template.title,
    question,
    verdict: succeeded ? `Based on ${succeeded} validated read-only Flumen semantic operation${succeeded === 1 ? "" : "s"}.` : "No semantic evidence could be retrieved for this scope.",
    generatedAt: new Date().toISOString(),
    simulationId: scope.simulationId,
    strategy,
    dataMode: template.dataMode,
    timeRange: context.selectedTimestamp ? { selectedTimestamp: context.selectedTimestamp } : undefined,
    retainedContextIds: context.entityIds,
    context,
    findings,
    evidence: evidence.map(entry => ({
      operationId: entry.operationId,
      operationKind: entry.operationKind,
      records: countRecords(unwrapData(entry.data)),
      error: entry.error,
      generatedAt: generatedAt(entry.data),
    })),
    widgets,
  };
}

export function evidencePrompt(answer: VisualAnswerDocument, evidence: Evidence[]): string {
  return `You are Flumen's read-only conveyor investigation assistant. Answer in two concise paragraphs. Use only this evidence. Separate observed facts from possible causes, do not invent values or claim a change was applied. Cite operation ids in brackets.\nQuestion: ${answer.question}\nStrategy: ${answer.strategy}\nEvidence: ${JSON.stringify(evidence)}`;
}

function unwrapData(value: unknown): unknown {
  return value && typeof value === "object" && "data" in value ? (value as { data: unknown }).data : value;
}

function countRecords(value: unknown): number {
  if (Array.isArray(value)) return value.length;
  if (!value || typeof value !== "object") return 0;
  const nested = Object.values(value).find(entry => Array.isArray(entry));
  return Array.isArray(nested) ? nested.length : 1;
}

function generatedAt(value: unknown): string | undefined {
  const meta = value && typeof value === "object" && "meta" in value ? (value as { meta?: { generatedAt?: unknown } }).meta : undefined;
  return typeof meta?.generatedAt === "string" ? meta.generatedAt : undefined;
}

function required(name: string): string {
  const value = process.env[name];
  if (!value?.trim()) throw new Error(`${name} is required`);
  return value;
}

function diagnosticMessage(status: number): string {
  if (status === 401 || status === 403) {
    return "Semantic endpoint rejected the assistant service token. Align APP_ASSISTANT_SERVICE_TOKEN on the backend with FLUMEN_SERVICE_TOKEN or FLUMEN_ASSISTANT_SERVICE_TOKEN in the assistant worker.";
  }
  return `Semantic endpoint returned HTTP ${status}`;
}
