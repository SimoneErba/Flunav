import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

export const MetricCards = ({ metrics }: {
  metrics: Array<{ label: string; value: string | number; tone?: 'normal' | 'warning' }>;
}) => (
  <div className="grid gap-3 sm:grid-cols-3">
    {metrics.map(metric => (
      <div key={metric.label} className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm dark:border-gray-800 dark:bg-gray-900">
        <div className="text-xs font-semibold uppercase tracking-wide text-gray-500">{metric.label}</div>
        <div className={`mt-2 text-3xl font-bold ${metric.tone === 'warning' ? 'text-red-600' : ''}`}>{metric.value}</div>
      </div>
    ))}
  </div>
);

export const CartesianTimeChart = ({ title, data, series }: {
  title: string;
  data: Array<Record<string, string | number>>;
  series: Array<{ key: string; label: string; color: string }>;
}) => (
  <WidgetFrame title={title}>
    <div className="h-64">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={data}>
          <CartesianGrid strokeDasharray="3 3" opacity={0.25} />
          <XAxis dataKey="timestamp" minTickGap={32} />
          <YAxis />
          <Tooltip />
          {series.map(item => <Line key={item.key} dataKey={item.key} name={item.label} stroke={item.color} dot={false} />)}
        </LineChart>
      </ResponsiveContainer>
    </div>
  </WidgetFrame>
);

export const TopologyGraphWidget = ({ nodes, edges }: {
  nodes: Array<{ id: string; x?: number; y?: number }>;
  edges: Array<{ id: string; sourceId: string; targetId: string; active: boolean }>;
}) => {
  const positioned = nodes.map((node, index) => ({
    ...node,
    x: node.x ?? 45 + (index % 6) * 115,
    y: node.y ?? 45 + Math.floor(index / 6) * 90,
  }));
  const byId = new Map(positioned.map(node => [node.id, node]));
  return (
    <WidgetFrame title="Topology">
      <svg viewBox="0 0 720 300" className="h-72 w-full" role="img" aria-label="Conveyor topology">
        {edges.map(edge => {
          const source = byId.get(edge.sourceId);
          const target = byId.get(edge.targetId);
          return source && target ? (
            <line key={edge.id} x1={source.x} y1={source.y} x2={target.x} y2={target.y}
              stroke={edge.active ? '#2563eb' : '#dc2626'} strokeWidth="3" />
          ) : null;
        })}
        {positioned.map(node => (
          <g key={node.id}>
            <circle cx={node.x} cy={node.y} r="16" fill="#111827" stroke="#60a5fa" strokeWidth="3" />
            <text x={node.x} y={(node.y ?? 0) + 30} textAnchor="middle" className="fill-current text-[10px]">{node.id}</text>
          </g>
        ))}
      </svg>
    </WidgetFrame>
  );
};

export const AlarmTimelineWidget = ({ alarms }: {
  alarms: Array<{ alarmId: string; typology: string; severity: string; timestamp: string; eventType: string }>;
}) => (
  <WidgetFrame title="Alarm timeline">
    <ol className="space-y-3">
      {alarms.map(alarm => (
        <li key={`${alarm.alarmId}-${alarm.eventType}-${alarm.timestamp}`} className="border-l-2 border-amber-500 pl-4">
          <div className="font-semibold">{alarm.typology} <span className="text-xs text-gray-500">{alarm.severity}</span></div>
          <div className="text-xs text-gray-500">{alarm.eventType} · {new Date(alarm.timestamp).toLocaleString()}</div>
        </li>
      ))}
    </ol>
  </WidgetFrame>
);

export const ItemJourneyWidget = ({ itemId, path }: { itemId: string; path: string[] }) => (
  <WidgetFrame title={`Item journey · ${itemId}`}>
    <div className="flex flex-wrap items-center gap-2">
      {path.map((position, index) => (
        <span key={`${position}-${index}`} className="contents">
          <span className="rounded-full bg-blue-100 px-3 py-1 text-sm text-blue-800 dark:bg-blue-950 dark:text-blue-200">{position}</span>
          {index < path.length - 1 && <span aria-hidden>→</span>}
        </span>
      ))}
    </div>
  </WidgetFrame>
);

export const TraversalTimeWidget = ({ samples }: { samples: Array<{ label: string; milliseconds: number }> }) => (
  <WidgetFrame title="Traversal time">
    <div className="space-y-3">
      {samples.map(sample => {
        const max = Math.max(...samples.map(value => value.milliseconds), 1);
        return (
          <div key={sample.label}>
            <div className="mb-1 flex justify-between text-xs"><span>{sample.label}</span><span>{sample.milliseconds} ms</span></div>
            <div className="h-2 rounded bg-gray-100 dark:bg-gray-800">
              <div className="h-2 rounded bg-blue-600" style={{ width: `${sample.milliseconds / max * 100}%` }} />
            </div>
          </div>
        );
      })}
    </div>
  </WidgetFrame>
);

export const EvidenceTableWidget = ({ columns, rows }: {
  columns: Array<{ key: string; label: string }>;
  rows: Array<Record<string, unknown>>;
}) => (
  <WidgetFrame title="Evidence">
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead><tr>{columns.map(column => <th key={column.key} className="px-3 py-2">{column.label}</th>)}</tr></thead>
        <tbody>{rows.map((row, index) => (
          <tr key={index} className="border-t border-gray-100 dark:border-gray-800">
            {columns.map(column => <td key={column.key} className="px-3 py-2">{String(row[column.key] ?? '—')}</td>)}
          </tr>
        ))}</tbody>
      </table>
    </div>
  </WidgetFrame>
);

const WidgetFrame = ({ title, children }: { title: string; children: React.ReactNode }) => (
  <section className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm dark:border-gray-800 dark:bg-gray-900">
    <h3 className="mb-4 text-lg font-semibold">{title}</h3>
    {children}
  </section>
);
