import type { VisualAnswerDocument, VisualWidget } from './types';
import { AlarmTimelineWidget } from './widgets/AlarmTimelineWidget';
import { CartesianChartWidget } from './widgets/CartesianChartWidget';
import { EvidenceTableWidget } from './widgets/EvidenceTableWidget';
import { ItemJourneyWidget } from './widgets/ItemJourneyWidget';
import { MetricCardWidget } from './widgets/MetricCardWidget';
import { TopologyGraphWidget } from './widgets/TopologyGraphWidget';
import { TraversalTimeWidget } from './widgets/TraversalTimeWidget';

export type { VisualAnswerDocument } from './types';

export const InvestigationWidgets = ({ answer }: { answer: VisualAnswerDocument | undefined }) => {
  if (!answer) return null;
  const kpis = answer.widgets.filter(widget => (widget.layout?.area ?? (widget.kind === 'metric-card' ? 'kpi' : 'primary')) === 'kpi');
  const primary = answer.widgets.filter(widget => (widget.layout?.area ?? 'primary') === 'primary');
  const secondary = answer.widgets.filter(widget => (widget.layout?.area ?? (widget.kind === 'evidence-table' ? 'secondary' : 'primary')) === 'secondary');
  return (
    <section className="mt-3 space-y-4" aria-label="Visual investigation dashboard">
      <header className="rounded-lg border border-slate-200 bg-slate-50 p-4 dark:border-slate-800 dark:bg-slate-950/50">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="min-w-0 text-base font-semibold">{answer.title}</h2>
          <div className="flex flex-wrap gap-2 text-xs font-semibold">
            <span className="rounded bg-blue-100 px-2 py-1 text-blue-800 dark:bg-blue-950 dark:text-blue-200">{answer.simulationId ? `Simulation ${answer.simulationId}` : 'Live scope'}</span>
            {answer.dataMode && <span className="rounded bg-slate-200 px-2 py-1 text-slate-700 dark:bg-slate-800 dark:text-slate-200">{answer.dataMode}</span>}
          </div>
        </div>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-300">{answer.verdict}</p>
        {answer.findings?.length ? (
          <div className="mt-3 flex flex-wrap gap-2">
            {answer.findings.slice(0, 6).map(finding => (
              <span key={finding} className="rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-700 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-200">{finding}</span>
            ))}
          </div>
        ) : null}
      </header>
      {kpis.length > 0 && <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">{kpis.map(renderWidget)}</div>}
      {primary.length > 0 && <div className="grid gap-3 sm:grid-cols-2">{primary.map(renderWidget)}</div>}
      {secondary.length > 0 && <div className="grid gap-3 sm:grid-cols-2">{secondary.map(renderWidget)}</div>}
    </section>
  );
};

function renderWidget(widget: VisualWidget) {
  switch (widget.kind) {
    case 'metric-card':
      return <MetricCardWidget key={widget.id} widget={widget} />;
    case 'cartesian-chart':
      return <CartesianChartWidget key={widget.id} widget={widget} />;
    case 'topology-graph':
      return <TopologyGraphWidget key={widget.id} widget={widget} />;
    case 'alarm-timeline':
      return <AlarmTimelineWidget key={widget.id} widget={widget} />;
    case 'item-journey':
      return <ItemJourneyWidget key={widget.id} widget={widget} />;
    case 'traversal-time':
      return <TraversalTimeWidget key={widget.id} widget={widget} />;
    case 'evidence-table':
      return <EvidenceTableWidget key={widget.id} widget={widget} />;
  }
}
