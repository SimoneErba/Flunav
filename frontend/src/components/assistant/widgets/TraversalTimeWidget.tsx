import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { VisualWidget } from '../types';
import { EmptyWidget, WidgetFrame } from './WidgetFrame';
import { isTraversalTimeData } from './utils';

export const TraversalTimeWidget = ({ widget }: { widget: VisualWidget }) => {
  if (!isTraversalTimeData(widget.data) || (!widget.data.metrics.length && !widget.data.points.length)) {
    return <EmptyWidget title={widget.title} message="No traversal metrics were returned." />;
  }
  return (
    <WidgetFrame title={widget.title}>
      {widget.data.metrics.length > 0 && (
        <div className="mb-3 grid grid-cols-2 gap-2">
          {widget.data.metrics.slice(0, 4).map(metric => (
            <div key={metric.label} className="rounded-md bg-gray-50 p-2 dark:bg-gray-950">
              <div className="truncate text-xs text-gray-500 dark:text-gray-400">{metric.label}</div>
              <div className="text-lg font-semibold">{metric.value}{metric.unit ? ` ${metric.unit}` : ''}</div>
            </div>
          ))}
        </div>
      )}
      {widget.data.points.length > 0 && (
        <div className="h-44">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={widget.data.points}>
              <CartesianGrid strokeDasharray="3 3" opacity={0.2} />
              <XAxis dataKey="x" hide />
              <YAxis tick={{ fontSize: 11 }} />
              <Tooltip />
              <Line type="monotone" dataKey="value" stroke="#2563eb" strokeWidth={2} dot={false} name="Occupancy" />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </WidgetFrame>
  );
};
