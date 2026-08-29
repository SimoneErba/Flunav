import type { Evidence, VisualWidget } from "./investigation.js";
import { recipesForStrategy, validateWidgetRecipe, type WidgetKind, type WidgetRecipe } from "./widget-catalog.js";
import { templateForStrategy } from "./answer-templates.js";
import type { InvestigationScope, InvestigationStrategy } from "./investigation.js";

type RecordValue = Record<string, unknown>;

export type MetricCardData = {
  metrics: Array<{ id: string; label: string; value: string | number; unit?: string; tone?: "neutral" | "good" | "warning" | "critical" }>;
};

export type CartesianChartData = {
  variant: "bar" | "line";
  xLabel?: string;
  yLabel?: string;
  series: Array<{ id: string; label: string; color: string }>;
  points: Array<{ x: string; values: Record<string, number> }>;
};

export type TopologyGraphData = {
  nodes: Array<{ id: string; label: string; type?: string; active?: boolean; occupancy?: number; capacity?: number }>;
  edges: Array<{ id: string; sourceId: string; targetId: string; label?: string; active?: boolean; occupancy?: number; capacity?: number }>;
};

export type AlarmTimelineData = {
  events: Array<{ id: string; alarmId?: string; componentId?: string; severity?: string; typology?: string; eventType?: string; timestamp: string; stopsConveyor?: boolean }>;
};

export type ItemJourneyData = {
  itemId?: string;
  name?: string;
  positionId?: string;
  positionType?: string;
  selectedExitId?: string;
  destinations: string[];
  steps: Array<{ id: string; label: string; current?: boolean; timestamp?: string; eventType?: string }>;
};

export type TraversalTimeData = {
  metrics: Array<{ label: string; value: number; unit?: string }>;
  points: Array<{ x: string; value: number }>;
};

export type EvidenceTableData = {
  rows: Array<{ operationId: string; operationKind: string; status: "retrieved" | "failed"; records: number; detail: string }>;
};

export type TypedWidgetData =
  | MetricCardData
  | CartesianChartData
  | TopologyGraphData
  | AlarmTimelineData
  | ItemJourneyData
  | TraversalTimeData
  | EvidenceTableData;

export function composeWidgets(strategy: InvestigationStrategy, scope: InvestigationScope, evidence: Evidence[]): VisualWidget[] {
  const availableEvidence = evidence.filter(entry => !entry.error);
  const byKind = new Map(availableEvidence.map(entry => [entry.operationKind, entry]));
  const byId = new Map(evidence.map(entry => [entry.operationId, entry]));
  const widgets: VisualWidget[] = [];
  for (const recipe of recipesForStrategy(strategy)) {
    if (!validateWidgetRecipe(recipe)) continue;
    const matchedEvidenceIds = evidenceIdsForRecipe(recipe, byKind, byId);
    if (!matchedEvidenceIds.length) continue;
    const data = dataForRecipe(recipe.kind, matchedEvidenceIds.map(id => byId.get(id)).filter((entry): entry is Evidence => Boolean(entry)), evidence);
    if (!data) continue;
    widgets.push({
      id: recipe.id,
      kind: recipe.kind,
      title: recipe.title,
      layout: recipe.layout,
      evidenceIds: matchedEvidenceIds,
      data,
    });
  }
  if (!widgets.some(widget => widget.kind === "evidence-table")) {
    widgets.push({
      id: "evidence",
      kind: "evidence-table",
      title: "Evidence",
      layout: { area: "secondary", span: 4 },
      evidenceIds: evidence.map(entry => entry.operationId),
      data: evidenceTable(evidence),
    });
  }
  const simulationId = scope.simulationId;
  if (simulationId) {
    return widgets.map(widget => widget.kind === "metric-card"
      ? { ...widget, data: addSimulationMetric(widget.data, simulationId) }
      : widget);
  }
  return widgets;
}

export function findingsFor(strategy: InvestigationStrategy, scope: InvestigationScope, evidence: Evidence[]): string[] {
  const template = templateForStrategy(strategy, scope.simulationId);
  const data = evidence.filter(entry => !entry.error).map(entry => unwrapData(entry.data));
  const findings = template.findingHints.flatMap(hint => findHint(hint, data));
  const failures = evidence.filter(entry => entry.error).map(entry => `${entry.operationId} failed: ${entry.error}`);
  return [...new Set([...findings, ...failures])].slice(0, 8);
}

function evidenceIdsForRecipe(recipe: WidgetRecipe, byKind: Map<string, Evidence>, byId: Map<string, Evidence>): string[] {
  if (recipe.kind === "evidence-table") return [...byId.keys()];
  return recipe.operationKinds
    .map(kind => byKind.get(kind)?.operationId)
    .filter((id): id is string => Boolean(id));
}

function dataForRecipe(kind: WidgetKind, matched: Evidence[], allEvidence: Evidence[]): TypedWidgetData | undefined {
  switch (kind) {
    case "metric-card":
      return metricCard(matched);
    case "cartesian-chart":
      return cartesianChart(matched);
    case "topology-graph":
      return topologyGraph(matched);
    case "alarm-timeline":
      return alarmTimeline(matched);
    case "item-journey":
      return itemJourney(matched);
    case "traversal-time":
      return traversalTime(matched);
    case "evidence-table":
      return evidenceTable(allEvidence);
  }
}

function metricCard(evidence: Evidence[]): MetricCardData | undefined {
  const data = evidence.map(entry => unwrapData(entry.data));
  const metrics: MetricCardData["metrics"] = [];
  const summary = firstRecord(data, "activeItems", "activeConveyors");
  if (summary) {
    pushMetric(metrics, "active-items", "Active items", summary.activeItems);
    pushMetric(metrics, "active-conveyors", "Active conveyors", summary.activeConveyors);
    pushMetric(metrics, "active-alarms", "Active alarms", summary.activeAlarms, undefined, number(summary.activeAlarms) > 0 ? "warning" : "good");
    pushMetric(metrics, "stopping-alarms", "Stopping alarms", summary.stoppingAlarms, undefined, number(summary.stoppingAlarms) > 0 ? "critical" : "neutral");
    pushMetric(metrics, "entered", "Entered", record(summary.comparison).entered);
    pushMetric(metrics, "exited", "Exited", record(summary.comparison).exited);
  }
  const component = firstRecord(data, "componentId", "componentType");
  if (component) {
    pushMetric(metrics, "occupancy", "Occupancy", component.occupancy);
    pushMetric(metrics, "capacity", "Capacity", component.capacity);
    pushMetric(metrics, "speed", "Speed", component.speed);
    pushMetric(metrics, "active", "Active", component.active === true ? "Yes" : "No", undefined, component.active === true ? "good" : "critical");
  }
  const destination = firstRecord(data, "destinationId", "assignedItems");
  if (destination) {
    pushMetric(metrics, "assigned", "Assigned items", destination.assignedItems);
    pushMetric(metrics, "candidates", "Candidate items", destination.candidateItems);
  }
  const item = firstRecord(data, "itemId", "positionId");
  if (item) {
    pushMetric(metrics, "position", "Position", item.positionId);
    pushMetric(metrics, "exit", "Selected exit", item.selectedExitId);
    pushMetric(metrics, "path", "Path steps", array(item.path).length);
  }
  return metrics.length ? { metrics: metrics.slice(0, 8) } : undefined;
}

function cartesianChart(evidence: Evidence[]): CartesianChartData | undefined {
  const data = evidence.map(entry => unwrapData(entry.data));
  const flow = firstArray(data, "conveyorId");
  if (flow.length) {
    return {
      variant: "bar",
      xLabel: "Conveyor",
      yLabel: "Items",
      series: [
        { id: "occupancy", label: "Occupancy", color: "#2563eb" },
        { id: "capacity", label: "Capacity", color: "#64748b" },
      ],
      points: flow.map(row => ({
        x: String(row.conveyorId ?? row.id ?? "unknown"),
        values: { occupancy: number(row.occupancy), capacity: number(row.capacity) },
      })).slice(0, 20),
    };
  }
  const summary = firstRecord(data, "throughput");
  const throughput = array(summary?.throughput).map(record).filter(row => typeof row.timestamp === "string");
  if (throughput.length) {
    return {
      variant: "line",
      xLabel: "Time",
      yLabel: "Items",
      series: [
        { id: "itemsEntered", label: "Entered", color: "#2563eb" },
        { id: "itemsExited", label: "Exited", color: "#16a34a" },
        { id: "itemsCurrent", label: "Current", color: "#f59e0b" },
      ],
      points: throughput.map(row => ({
        x: compactTime(String(row.timestamp)),
        values: {
          itemsEntered: number(row.itemsEntered),
          itemsExited: number(row.itemsExited),
          itemsCurrent: number(row.itemsCurrent),
        },
      })),
    };
  }
  return undefined;
}

function topologyGraph(evidence: Evidence[]): TopologyGraphData | undefined {
  const graph = evidence.map(entry => unwrapData(entry.data)).find(value => record(value).locations || record(value).conveyors);
  const value = record(graph);
  const nodes = array(value.locations).map(record)
    .filter(node => typeof node.id === "string")
    .map(node => ({ id: String(node.id), label: String(node.name ?? node.id), type: string(node.type), active: boolean(node.active), capacity: optionalNumber(node.capacity) }));
  const edges = array(value.conveyors).map(record)
    .filter(edge => typeof edge.id === "string" && typeof edge.sourceId === "string" && typeof edge.targetId === "string")
    .map(edge => ({
      id: String(edge.id),
      sourceId: String(edge.sourceId),
      targetId: String(edge.targetId),
      label: String(edge.name ?? edge.id),
      active: boolean(edge.active),
      occupancy: optionalNumber(edge.occupancy),
      capacity: optionalNumber(edge.capacity),
    }));
  return nodes.length || edges.length ? { nodes, edges } : undefined;
}

function alarmTimeline(evidence: Evidence[]): AlarmTimelineData | undefined {
  const records = evidence.flatMap(entry => {
    const data = unwrapData(entry.data);
    return Array.isArray(data) ? data : array(record(data).history);
  }).map(record).filter(row => typeof row.timestamp === "string" || typeof row.timestampReceived === "string");
  const events = records.map((row, index) => ({
    id: String(row.eventId ?? `${row.alarmId ?? "alarm"}-${index}`),
    alarmId: string(row.alarmId),
    componentId: string(row.conveyorId ?? row.componentId),
    severity: string(row.severity),
    typology: string(row.typology),
    eventType: string(row.eventType),
    timestamp: String(row.timestamp ?? row.timestampReceived),
    stopsConveyor: boolean(row.stopsConveyor),
  }));
  return events.length ? { events: events.slice(0, 24) } : undefined;
}

function itemJourney(evidence: Evidence[]): ItemJourneyData | undefined {
  const data = evidence.map(entry => unwrapData(entry.data));
  const summary = firstRecord(data, "itemId");
  const events = evidence.flatMap(entry => array(unwrapData(entry.data))).map(record);
  const path = array(summary?.path).filter((value): value is string => typeof value === "string");
  const eventSteps = events
    .filter(row => typeof row.timestampReceived === "string" || typeof row.timestamp === "string")
    .map((row, index) => ({
      id: String(row.eventId ?? index),
      label: String(row.eventType ?? row.entityId ?? "Event"),
      timestamp: string(row.timestampReceived ?? row.timestamp),
      eventType: string(row.eventType),
    }));
  const pathSteps = path.map((step, index) => ({
    id: `${step}-${index}`,
    label: step,
    current: step === summary?.positionId,
  }));
  const steps = eventSteps.length ? eventSteps : pathSteps;
  if (!summary && !steps.length) return undefined;
  return {
    itemId: string(summary?.itemId),
    name: string(summary?.name),
    positionId: string(summary?.positionId),
    positionType: string(summary?.positionType),
    selectedExitId: string(summary?.selectedExitId),
    destinations: array(summary?.destinations).filter((value): value is string => typeof value === "string"),
    steps,
  };
}

function traversalTime(evidence: Evidence[]): TraversalTimeData | undefined {
  const data = evidence.map(entry => unwrapData(entry.data));
  const summary = firstRecord(data, "activeItems");
  const item = firstRecord(data, "itemId");
  const flow = firstArray(data, "conveyorId");
  const metrics = [
    { label: "Active items", value: number(summary?.activeItems) },
    { label: "Path steps", value: array(item?.path).length },
    { label: "Occupied conveyors", value: flow.filter(row => number(row.occupancy) > 0).length },
    { label: "Active conveyors", value: flow.filter(row => row.active === true).length },
  ].filter(metric => metric.value > 0);
  const points = flow.map(row => ({ x: String(row.conveyorId ?? "Conveyor"), value: number(row.occupancy) }));
  return metrics.length || points.length ? { metrics, points: points.slice(0, 20) } : undefined;
}

function evidenceTable(evidence: Evidence[]): EvidenceTableData {
  return {
    rows: evidence.map(entry => ({
      operationId: entry.operationId,
      operationKind: entry.operationKind,
      status: entry.error ? "failed" : "retrieved",
      records: countRecords(unwrapData(entry.data)),
      detail: entry.error ?? "Read-only semantic evidence",
    })),
  };
}

function addSimulationMetric(data: TypedWidgetData, simulationId: string): TypedWidgetData {
  const metrics = array(record(data).metrics);
  return metrics.length
    ? { metrics: [{ id: "simulation", label: "Simulation", value: simulationId }, ...metrics.map(metric => record(metric)).map(metric => ({ id: String(metric.id), label: String(metric.label), value: typeof metric.value === "number" ? metric.value : String(metric.value), unit: string(metric.unit), tone: tone(metric.tone) }))] }
    : data;
}

function findHint(hint: string, data: unknown[]): string[] {
  return data.flatMap(value => {
    const candidate = record(value);
    if (!(hint in candidate)) return [];
    const found = candidate[hint];
    if (Array.isArray(found)) return [`${humanize(hint)}: ${found.length}`];
    if (found && typeof found === "object") return [`${humanize(hint)} available`];
    if (found === undefined || found === null || found === "") return [];
    return [`${humanize(hint)}: ${String(found)}`];
  });
}

function unwrapData(value: unknown): unknown {
  return value && typeof value === "object" && "data" in value ? (value as { data: unknown }).data : value;
}

function firstRecord(values: unknown[], ...keys: string[]): RecordValue | undefined {
  return values.map(record).find(value => keys.every(key => key in value));
}

function firstArray(values: unknown[], key: string): RecordValue[] {
  const arrayValue = values.find(value => Array.isArray(value) && array(value).some(entry => key in record(entry)));
  return array(arrayValue).map(record);
}

function countRecords(value: unknown): number {
  if (Array.isArray(value)) return value.length;
  const object = record(value);
  const nestedCount = Object.values(object).find(entry => Array.isArray(entry));
  return Array.isArray(nestedCount) ? nestedCount.length : Object.keys(object).length ? 1 : 0;
}

function pushMetric(metrics: MetricCardData["metrics"], id: string, label: string, value: unknown, unit?: string, tone?: MetricCardData["metrics"][number]["tone"]) {
  if (value === undefined || value === null || value === "") return;
  metrics.push({ id, label, value: typeof value === "number" ? value : String(value), unit, tone });
}

function record(value: unknown): RecordValue {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as RecordValue : {};
}

function array(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function number(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

function optionalNumber(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function boolean(value: unknown): boolean | undefined {
  return typeof value === "boolean" ? value : undefined;
}

function string(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value : undefined;
}

function tone(value: unknown): MetricCardData["metrics"][number]["tone"] | undefined {
  return value === "neutral" || value === "good" || value === "warning" || value === "critical" ? value : undefined;
}

function compactTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleTimeString("en-US", { hour: "2-digit", minute: "2-digit" });
}

function humanize(value: string): string {
  return value.replace(/([A-Z])/g, " $1").replace(/^./, character => character.toUpperCase());
}
