export type WidgetKind =
  | 'metric-card'
  | 'cartesian-chart'
  | 'topology-graph'
  | 'alarm-timeline'
  | 'item-journey'
  | 'traversal-time'
  | 'evidence-table';

export type MetricCardData = {
  metrics: Array<{ id: string; label: string; value: string | number; unit?: string; tone?: 'neutral' | 'good' | 'warning' | 'critical' }>;
};

export type CartesianChartData = {
  variant: 'bar' | 'line';
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
  rows: Array<{ operationId: string; operationKind: string; status: 'retrieved' | 'failed'; records: number; detail: string }>;
};

export type VisualWidget = {
  id: string;
  kind: WidgetKind;
  title: string;
  layout?: { area: 'kpi' | 'primary' | 'secondary'; span?: 1 | 2 | 3 | 4 };
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
  strategy: string;
  dataMode?: 'live' | 'simulation' | 'historical' | 'mixed';
  timeRange?: { from?: string; to?: string; selectedTimestamp?: string };
  retainedContextIds?: string[];
  context: { entityIds: string[]; selectedTimestamp?: string };
  findings?: string[];
  evidence: Array<{ operationId: string; operationKind: string; records?: number; error?: string; generatedAt?: string }>;
  widgets: VisualWidget[];
};
