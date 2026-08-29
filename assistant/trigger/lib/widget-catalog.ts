import type { InvestigationStrategy, SemanticOperationKind } from "./investigation.js";

export type WidgetKind =
  | "metric-card"
  | "cartesian-chart"
  | "topology-graph"
  | "alarm-timeline"
  | "item-journey"
  | "traversal-time"
  | "evidence-table";

export type VisualWidgetArea = "kpi" | "primary" | "secondary";

export type WidgetRecipe = {
  id: string;
  kind: WidgetKind;
  title: string;
  layout: { area: VisualWidgetArea; span?: 1 | 2 | 3 | 4 };
  operationKinds: SemanticOperationKind[];
  strategies: InvestigationStrategy[];
};

export const widgetCatalog: Record<WidgetKind, { label: string; operationKinds: SemanticOperationKind[] }> = {
  "metric-card": {
    label: "Metric card",
    operationKinds: ["system.summary", "components.summary", "destinations.summary", "items.summary"],
  },
  "cartesian-chart": {
    label: "Cartesian chart",
    operationKinds: ["conveyors.flow", "system.summary", "alarms.investigation"],
  },
  "topology-graph": {
    label: "Topology graph",
    operationKinds: ["topology.get", "system.snapshot"],
  },
  "alarm-timeline": {
    label: "Alarm timeline",
    operationKinds: ["alarms.list", "alarms.investigation"],
  },
  "item-journey": {
    label: "Item journey",
    operationKinds: ["items.summary", "items.events", "items.positions"],
  },
  "traversal-time": {
    label: "Traversal time",
    operationKinds: ["system.summary", "items.summary", "items.events", "conveyors.flow"],
  },
  "evidence-table": {
    label: "Evidence table",
    operationKinds: [
      "system.summary",
      "topology.get",
      "alarms.list",
      "alarms.investigation",
      "items.summary",
      "items.events",
      "items.positions",
      "components.summary",
      "conveyors.flow",
      "destinations.summary",
      "simulations.list",
      "system.snapshot",
    ],
  },
};

export const widgetRecipes: WidgetRecipe[] = [
  recipe("system-kpis", "metric-card", "System state", "kpi", ["system.summary"], ["system_status", "historical_comparison", "simulation_analysis"], 4),
  recipe("component-kpis", "metric-card", "Component state", "kpi", ["components.summary"], ["component_investigation"], 2),
  recipe("destination-kpis", "metric-card", "Destination state", "kpi", ["destinations.summary"], ["destination_performance"], 2),
  recipe("item-kpis", "metric-card", "Item state", "kpi", ["items.summary"], ["item_trace"], 2),
  recipe("flow", "cartesian-chart", "Conveyor flow", "primary", ["conveyors.flow", "system.summary"], ["system_status", "component_investigation", "historical_comparison", "alarm_investigation"], 2),
  recipe("topology", "topology-graph", "Topology", "primary", ["topology.get", "system.snapshot"], ["system_status", "alarm_investigation", "component_investigation", "destination_performance", "historical_comparison", "simulation_analysis"], 2),
  recipe("alarms", "alarm-timeline", "Alarm timeline", "secondary", ["alarms.investigation", "alarms.list"], ["alarm_investigation", "system_status", "historical_comparison"], 2),
  recipe("journey", "item-journey", "Item journey", "primary", ["items.summary", "items.events", "items.positions"], ["item_trace"], 2),
  recipe("traversal", "traversal-time", "Traversal metrics", "secondary", ["system.summary", "items.summary", "items.events", "conveyors.flow"], ["item_trace", "component_investigation", "system_status"], 2),
  recipe("evidence", "evidence-table", "Evidence", "secondary", widgetCatalog["evidence-table"].operationKinds, ["system_status", "alarm_investigation", "item_trace", "component_investigation", "destination_performance", "historical_comparison", "simulation_analysis"], 4),
];

export function recipesForStrategy(strategy: InvestigationStrategy): WidgetRecipe[] {
  return widgetRecipes.filter(recipe => recipe.strategies.includes(strategy));
}

export function validateWidgetRecipe(recipe: WidgetRecipe): boolean {
  const allowed = new Set(widgetCatalog[recipe.kind].operationKinds);
  return recipe.operationKinds.every(kind => allowed.has(kind));
}

function recipe(
  id: string,
  kind: WidgetKind,
  title: string,
  area: VisualWidgetArea,
  operationKinds: SemanticOperationKind[],
  strategies: InvestigationStrategy[],
  span?: 1 | 2 | 3 | 4,
): WidgetRecipe {
  return { id, kind, title, layout: { area, span }, operationKinds, strategies };
}
