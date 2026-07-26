import { Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

type RecordValue = Record<string, unknown>;

export type VisualAnswerDocument = {
  id: string;
  title: string;
  question: string;
  verdict: string;
  generatedAt: string;
  simulationId?: string;
  strategy: string;
  evidence: Array<{ operationId: string; operationKind: string; error?: string }>;
  widgets: Array<{
    id: string;
    kind: 'metric-card' | 'cartesian-chart' | 'topology-graph' | 'alarm-timeline' | 'item-journey' | 'traversal-time' | 'evidence-table';
    title: string;
    evidenceIds: string[];
    data: unknown;
  }>;
};

export const InvestigationWidgets = ({ answer }: { answer: VisualAnswerDocument | undefined }) => {
  if (!answer) return null;
  const kpis = answer.widgets.filter(widget => widget.kind === 'metric-card');
  const visual = answer.widgets.filter(widget => widget.kind !== 'metric-card' && widget.kind !== 'evidence-table');
  const evidence = answer.widgets.filter(widget => widget.kind === 'evidence-table');
  return (
    <section className="mt-3 space-y-4" aria-label="Visual investigation dashboard">
      <header className="rounded-xl border border-slate-200 bg-slate-50 p-4 dark:border-slate-800 dark:bg-slate-950/50">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="font-semibold">{answer.title}</h2>
          <span className="rounded-full bg-blue-100 px-2 py-1 text-xs font-semibold text-blue-800 dark:bg-blue-950 dark:text-blue-200">{answer.simulationId ? `Simulation ${answer.simulationId}` : 'Live scope'}</span>
        </div>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-300">{answer.verdict}</p>
      </header>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">{kpis.map(renderWidget)}</div>
      <div className="grid gap-3 lg:grid-cols-2">{visual.map(renderWidget)}</div>
      <div className="grid gap-3">{evidence.map(renderWidget)}</div>
    </section>
  );
};

function renderWidget(widget: VisualAnswerDocument['widgets'][number]) {
  switch (widget.kind) {
    case 'metric-card': return <MetricCards key={widget.id} widget={widget} />;
    case 'cartesian-chart': return <FlowChart key={widget.id} widget={widget} />;
    case 'topology-graph': return <Topology key={widget.id} widget={widget} />;
    case 'alarm-timeline': return <AlarmTimeline key={widget.id} widget={widget} />;
    case 'item-journey': return <ItemJourney key={widget.id} widget={widget} />;
    case 'traversal-time': return <TraversalMetrics key={widget.id} widget={widget} />;
    case 'evidence-table': return <EvidenceTable key={widget.id} widget={widget} />;
  }
}

const Frame = ({ title, children }: { title: string; children: React.ReactNode }) => (
  <section className="min-w-0 rounded-xl border border-gray-200 bg-white p-4 shadow-sm dark:border-gray-800 dark:bg-gray-900">
    <h3 className="mb-3 text-sm font-semibold">{title}</h3>{children}
  </section>
);

function MetricCards({ widget }: { widget: VisualAnswerDocument['widgets'][number] }) {
  const values = record(widget.data);
  const metrics = Object.entries(values).filter(([, value]) => typeof value === 'number' || typeof value === 'string').slice(0, 6);
  return <Frame title={widget.title}><div className="grid gap-2">{metrics.map(([key, value]) => <div key={key}><div className="text-xs text-gray-500">{humanize(key)}</div><div className="text-2xl font-bold">{String(value)}</div></div>)}</div></Frame>;
}

function FlowChart({ widget }: { widget: VisualAnswerDocument['widgets'][number] }) {
  const data = record(widget.data);
  const flow = array(data.flow).map(record).filter(value => typeof value.conveyorId === 'string').map(value => ({
    id: String(value.conveyorId), occupancy: number(value.occupancy), capacity: number(value.capacity),
  }));
  if (!flow.length) return <Frame title={widget.title}><p className="text-sm text-gray-500">No flow buckets were returned.</p></Frame>;
  return <Frame title={widget.title}><div className="h-56"><ResponsiveContainer width="100%" height="100%"><BarChart data={flow}><CartesianGrid strokeDasharray="3 3" opacity={0.2} /><XAxis dataKey="id" hide /><YAxis /><Tooltip /><Bar dataKey="occupancy" fill="#2563eb" name="Occupancy" /><Bar dataKey="capacity" fill="#94a3b8" name="Capacity" /></BarChart></ResponsiveContainer></div></Frame>;
}

function Topology({ widget }: { widget: VisualAnswerDocument['widgets'][number] }) {
  const data = record(widget.data);
  const nodes = array(data.locations).map(record).filter(node => typeof node.id === 'string');
  const edges = array(data.conveyors).map(record).filter(edge => typeof edge.sourceId === 'string' && typeof edge.targetId === 'string');
  const positioned = nodes.map((node, index) => ({ id: String(node.id), x: 45 + (index % 5) * 140, y: 40 + Math.floor(index / 5) * 85 }));
  const lookup = new Map(positioned.map(node => [node.id, node]));
  return <Frame title={widget.title}><svg viewBox="0 0 760 280" className="h-64 w-full" role="img" aria-label="Conveyor topology">{edges.map((edge, index) => { const from = lookup.get(String(edge.sourceId)); const to = lookup.get(String(edge.targetId)); return from && to ? <line key={index} x1={from.x} y1={from.y} x2={to.x} y2={to.y} stroke={edge.active === false ? '#dc2626' : '#2563eb'} strokeWidth="3" /> : null; })}{positioned.map(node => <g key={node.id}><circle cx={node.x} cy={node.y} r="16" fill="#111827" stroke="#60a5fa" strokeWidth="2" /><text x={node.x} y={node.y + 30} textAnchor="middle" className="fill-current text-[10px]">{node.id}</text></g>)}</svg></Frame>;
}

function AlarmTimeline({ widget }: { widget: VisualAnswerDocument['widgets'][number] }) {
  const data = widget.data;
  const events = array(Array.isArray(data) ? data : record(data).history).map(record).filter(event => typeof event.timestamp === 'string');
  return <Frame title={widget.title}><ol className="space-y-2">{events.slice(0, 12).map((event, index) => <li key={`${event.alarmId ?? index}-${event.timestamp}`} className="border-l-2 border-amber-500 pl-3 text-sm"><div className="font-medium">{String(event.typology ?? event.alarmId ?? 'Alarm')}</div><div className="text-xs text-gray-500">{String(event.eventType ?? 'REPORTED')} · {formatTime(String(event.timestamp))}</div></li>)}</ol></Frame>;
}

function ItemJourney({ widget }: { widget: VisualAnswerDocument['widgets'][number] }) {
  const data = record(widget.data); const summary = record(data.summary); const itemId = String(summary.itemId ?? 'Item');
  const path = array(summary.path).filter((value): value is string => typeof value === 'string');
  return <Frame title={widget.title}><div className="text-xs text-gray-500">{itemId}</div><div className="mt-3 flex flex-wrap items-center gap-2">{path.map((step, index) => <span className="contents" key={`${step}-${index}`}><span className="rounded-full bg-blue-100 px-2 py-1 text-xs text-blue-800 dark:bg-blue-950 dark:text-blue-200">{step}</span>{index < path.length - 1 && <span>→</span>}</span>)}</div></Frame>;
}

function TraversalMetrics({ widget }: { widget: VisualAnswerDocument['widgets'][number] }) {
  const data = record(widget.data); const summary = record(data.summary); const item = record(data.item);
  const samples = [{ label: 'Active items', value: number(summary.activeItems) }, { label: 'Path steps', value: array(item.path).length }];
  return <Frame title={widget.title}><div className="h-40"><ResponsiveContainer width="100%" height="100%"><LineChart data={samples}><CartesianGrid strokeDasharray="3 3" opacity={0.2} /><XAxis dataKey="label" /><YAxis /><Tooltip /><Line dataKey="value" stroke="#2563eb" strokeWidth={2} /></LineChart></ResponsiveContainer></div></Frame>;
}

function EvidenceTable({ widget }: { widget: VisualAnswerDocument['widgets'][number] }) {
  const rows = array(widget.data).map(record).map(entry => ({ operation: String(entry.operationKind ?? entry.operationId), status: entry.error ? 'Failed' : 'Retrieved', detail: entry.error ? String(entry.error) : 'Read-only semantic evidence' }));
  return <Frame title={widget.title}><div className="overflow-x-auto"><table className="w-full text-left text-xs"><thead><tr className="text-gray-500"><th className="p-2">Operation</th><th className="p-2">Status</th><th className="p-2">Detail</th></tr></thead><tbody>{rows.map((row, index) => <tr key={index} className="border-t border-gray-100 dark:border-gray-800"><td className="p-2">{row.operation}</td><td className="p-2">{row.status}</td><td className="p-2">{row.detail}</td></tr>)}</tbody></table></div></Frame>;
}

const record = (value: unknown): RecordValue => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as RecordValue : {};
const array = (value: unknown): unknown[] => Array.isArray(value) ? value : [];
const number = (value: unknown): number => typeof value === 'number' && Number.isFinite(value) ? value : 0;
const humanize = (value: string) => value.replace(/([A-Z])/g, ' $1').replace(/^./, character => character.toUpperCase());
const formatTime = (value: string) => Number.isNaN(new Date(value).getTime()) ? value : new Date(value).toLocaleString();
