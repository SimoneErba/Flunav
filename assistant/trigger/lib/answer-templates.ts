import type { InvestigationStrategy, SemanticOperationKind } from "./investigation.js";

export type AnswerTemplate = {
  id: InvestigationStrategy;
  title: string;
  dataMode: "live" | "simulation" | "historical" | "mixed";
  requiredOperations: SemanticOperationKind[];
  optionalOperations: SemanticOperationKind[];
  findingHints: string[];
};

export const answerTemplates: Record<InvestigationStrategy, AnswerTemplate> = {
  system_status: {
    id: "system_status",
    title: "System investigation",
    dataMode: "live",
    requiredOperations: ["system.summary", "topology.get"],
    optionalOperations: ["alarms.list", "conveyors.flow", "system.snapshot"],
    findingHints: ["activeItems", "activeAlarms", "activeConveyors", "throughput"],
  },
  alarm_investigation: {
    id: "alarm_investigation",
    title: "Alarm investigation",
    dataMode: "mixed",
    requiredOperations: ["alarms.investigation"],
    optionalOperations: ["system.summary", "topology.get", "alarms.list", "system.snapshot", "anomalies.list", "anomaly-incidents.list"],
    findingHints: ["alarmId", "affectedItems", "stoppingAlarms", "findingId", "probableRootComponentId", "confidence", "conclusion"],
  },
  item_trace: {
    id: "item_trace",
    title: "Item journey",
    dataMode: "mixed",
    requiredOperations: ["items.summary"],
    optionalOperations: ["items.events", "items.positions", "system.summary", "topology.get", "system.snapshot"],
    findingHints: ["itemId", "positionId", "selectedExitId", "path"],
  },
  component_investigation: {
    id: "component_investigation",
    title: "Component investigation",
    dataMode: "live",
    requiredOperations: ["components.summary"],
    optionalOperations: ["system.summary", "topology.get", "conveyors.flow", "system.snapshot", "anomalies.list", "anomaly-incidents.list"],
    findingHints: ["componentId", "occupancy", "capacity", "activeAlarms"],
  },
  destination_performance: {
    id: "destination_performance",
    title: "Destination performance",
    dataMode: "live",
    requiredOperations: ["destinations.summary"],
    optionalOperations: ["system.summary", "topology.get", "system.snapshot"],
    findingHints: ["destinationId", "assignedItems", "candidateItems"],
  },
  historical_comparison: {
    id: "historical_comparison",
    title: "Historical comparison",
    dataMode: "historical",
    requiredOperations: ["system.summary"],
    optionalOperations: ["topology.get", "system.snapshot", "alarms.list", "conveyors.flow"],
    findingHints: ["comparison", "baselineEntered", "baselineExited", "throughput"],
  },
  simulation_analysis: {
    id: "simulation_analysis",
    title: "Simulation analysis",
    dataMode: "simulation",
    requiredOperations: ["simulations.list"],
    optionalOperations: ["system.summary", "topology.get", "system.snapshot"],
    findingHints: ["simulationId", "status", "buildProgress", "lastProcessedTimestamp"],
  },
};

export function templateForStrategy(strategy: InvestigationStrategy, simulationId?: string): AnswerTemplate {
  const template = answerTemplates[strategy];
  return simulationId ? { ...template, dataMode: "simulation" } : template;
}

export function isOperationAllowed(strategy: InvestigationStrategy, kind: SemanticOperationKind): boolean {
  const template = answerTemplates[strategy];
  return template.requiredOperations.includes(kind) || template.optionalOperations.includes(kind);
}
