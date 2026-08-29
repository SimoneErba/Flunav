import type { VisualWidget } from '../types';
import { EmptyWidget, WidgetFrame } from './WidgetFrame';
import { formatTime, isAlarmTimelineData } from './utils';

const severityClass = (severity?: string) => {
  const value = severity?.toLowerCase() ?? '';
  if (value.includes('critical') || value.includes('high')) return 'border-red-500';
  if (value.includes('warn') || value.includes('medium')) return 'border-amber-500';
  return 'border-blue-500';
};

export const AlarmTimelineWidget = ({ widget }: { widget: VisualWidget }) => {
  if (!isAlarmTimelineData(widget.data) || !widget.data.events.length) {
    return <EmptyWidget title={widget.title} message="No alarm events were returned." />;
  }
  return (
    <WidgetFrame title={widget.title}>
      <ol className="max-h-72 space-y-2 overflow-y-auto pr-1">
        {widget.data.events.map(event => (
          <li key={event.id} className={`border-l-2 pl-3 text-sm ${severityClass(event.severity)}`}>
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium">{event.typology ?? event.alarmId ?? 'Alarm'}</span>
              {event.stopsConveyor && <span className="rounded bg-red-100 px-1.5 py-0.5 text-[11px] font-semibold text-red-800 dark:bg-red-950 dark:text-red-200">Stops conveyor</span>}
            </div>
            <div className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
              {event.eventType ?? 'Reported'} {event.componentId ? `on ${event.componentId}` : ''} · {formatTime(event.timestamp)}
            </div>
          </li>
        ))}
      </ol>
    </WidgetFrame>
  );
};
