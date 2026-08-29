import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { VisualWidget } from '../types';
import { EmptyWidget, WidgetFrame } from './WidgetFrame';
import { isCartesianChartData } from './utils';

type ChartPoint = { x: string } & Record<string, number | string>;

export const CartesianChartWidget = ({ widget }: { widget: VisualWidget }) => {
  if (!isCartesianChartData(widget.data)) {
    return <EmptyWidget title={widget.title} message="No chart series were returned." />;
  }
  const data: ChartPoint[] = widget.data.points.map(point => ({ x: point.x, ...point.values }));
  const chart = widget.data.variant === 'line'
    ? (
      <LineChart data={data}>
        <CartesianGrid strokeDasharray="3 3" opacity={0.2} />
        <XAxis dataKey="x" tick={{ fontSize: 11 }} />
        <YAxis tick={{ fontSize: 11 }} />
        <Tooltip />
        <Legend />
        {widget.data.series.map(series => <Line key={series.id} type="monotone" dataKey={series.id} stroke={series.color} strokeWidth={2} dot={false} name={series.label} />)}
      </LineChart>
    )
    : (
      <BarChart data={data}>
        <CartesianGrid strokeDasharray="3 3" opacity={0.2} />
        <XAxis dataKey="x" tick={{ fontSize: 11 }} interval={0} angle={-25} textAnchor="end" height={56} />
        <YAxis tick={{ fontSize: 11 }} />
        <Tooltip />
        <Legend />
        {widget.data.series.map(series => <Bar key={series.id} dataKey={series.id} fill={series.color} name={series.label} />)}
      </BarChart>
    );
  return (
    <WidgetFrame title={widget.title}>
      <div className="h-64 min-w-0">
        <ResponsiveContainer width="100%" height="100%">{chart}</ResponsiveContainer>
      </div>
    </WidgetFrame>
  );
};
