import type { VisualWidget } from '../types';
import { EmptyWidget, WidgetFrame } from './WidgetFrame';
import { isMetricCardData } from './utils';

const toneClass = {
  neutral: 'border-gray-200 bg-gray-50 text-gray-900 dark:border-gray-800 dark:bg-gray-950 dark:text-gray-100',
  good: 'border-emerald-200 bg-emerald-50 text-emerald-900 dark:border-emerald-900 dark:bg-emerald-950/40 dark:text-emerald-100',
  warning: 'border-amber-200 bg-amber-50 text-amber-900 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-100',
  critical: 'border-red-200 bg-red-50 text-red-900 dark:border-red-900 dark:bg-red-950/40 dark:text-red-100',
};

export const MetricCardWidget = ({ widget }: { widget: VisualWidget }) => {
  if (!isMetricCardData(widget.data) || !widget.data.metrics.length) {
    return <EmptyWidget title={widget.title} message="No metric values were returned." />;
  }
  return (
    <WidgetFrame title={widget.title}>
      <div className="grid grid-cols-2 gap-2">
        {widget.data.metrics.slice(0, 8).map(metric => (
          <div key={metric.id} className={`rounded-md border p-3 ${toneClass[metric.tone ?? 'neutral']}`}>
            <div className="truncate text-xs opacity-70" title={metric.label}>{metric.label}</div>
            <div className="mt-1 flex min-w-0 items-baseline gap-1">
              <span className="truncate text-xl font-bold" title={String(metric.value)}>{metric.value}</span>
              {metric.unit && <span className="text-xs opacity-70">{metric.unit}</span>}
            </div>
          </div>
        ))}
      </div>
    </WidgetFrame>
  );
};
