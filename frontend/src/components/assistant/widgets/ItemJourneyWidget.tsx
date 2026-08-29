import type { VisualWidget } from '../types';
import { EmptyWidget, WidgetFrame } from './WidgetFrame';
import { formatTime, isItemJourneyData } from './utils';

export const ItemJourneyWidget = ({ widget }: { widget: VisualWidget }) => {
  if (!isItemJourneyData(widget.data) || !widget.data.steps.length) {
    return <EmptyWidget title={widget.title} message="No item journey was returned." />;
  }
  return (
    <WidgetFrame title={widget.title}>
      <div className="mb-3 grid gap-1 text-xs text-gray-500 dark:text-gray-400 sm:grid-cols-2">
        <div className="truncate">Item: <span className="font-medium text-gray-800 dark:text-gray-100">{widget.data.name ?? widget.data.itemId ?? 'Unknown'}</span></div>
        <div className="truncate">Position: <span className="font-medium text-gray-800 dark:text-gray-100">{widget.data.positionId ?? 'Unknown'}</span></div>
        {widget.data.selectedExitId && <div className="truncate">Exit: <span className="font-medium text-gray-800 dark:text-gray-100">{widget.data.selectedExitId}</span></div>}
        {widget.data.destinations.length > 0 && <div className="truncate">Candidates: <span className="font-medium text-gray-800 dark:text-gray-100">{widget.data.destinations.join(', ')}</span></div>}
      </div>
      <ol className="space-y-2">
        {widget.data.steps.slice(0, 24).map((step, index) => (
          <li key={step.id} className="flex gap-3 text-sm">
            <span className={`mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-xs font-semibold ${step.current ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300'}`}>{index + 1}</span>
            <div className="min-w-0">
              <div className="truncate font-medium" title={step.label}>{step.label}</div>
              {(step.eventType || step.timestamp) && <div className="text-xs text-gray-500 dark:text-gray-400">{step.eventType ?? 'Event'}{step.timestamp ? ` · ${formatTime(step.timestamp)}` : ''}</div>}
            </div>
          </li>
        ))}
      </ol>
    </WidgetFrame>
  );
};
