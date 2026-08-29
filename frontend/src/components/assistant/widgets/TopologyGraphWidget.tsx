import type { VisualWidget } from '../types';
import { EmptyWidget, WidgetFrame } from './WidgetFrame';
import { isTopologyGraphData } from './utils';

export const TopologyGraphWidget = ({ widget }: { widget: VisualWidget }) => {
  if (!isTopologyGraphData(widget.data) || (!widget.data.nodes.length && !widget.data.edges.length)) {
    return <EmptyWidget title={widget.title} message="No topology nodes were returned." />;
  }
  const positioned = widget.data.nodes.slice(0, 32).map((node, index) => ({
    ...node,
    x: 48 + (index % 6) * 120,
    y: 42 + Math.floor(index / 6) * 78,
  }));
  const lookup = new Map(positioned.map(node => [node.id, node]));
  const height = Math.max(220, 88 + Math.ceil(positioned.length / 6) * 78);
  return (
    <WidgetFrame title={widget.title}>
      <svg viewBox={`0 0 720 ${height}`} className="h-72 w-full min-w-0 text-gray-700 dark:text-gray-200" role="img" aria-label="Conveyor topology">
        {widget.data.edges.map(edge => {
          const from = lookup.get(edge.sourceId);
          const to = lookup.get(edge.targetId);
          if (!from || !to) return null;
          return (
            <g key={edge.id}>
              <line x1={from.x} y1={from.y} x2={to.x} y2={to.y} stroke={edge.active === false ? '#dc2626' : '#2563eb'} strokeWidth="3" strokeLinecap="round" />
              {typeof edge.occupancy === 'number' && <text x={(from.x + to.x) / 2} y={(from.y + to.y) / 2 - 6} textAnchor="middle" className="fill-current text-[10px]">{edge.occupancy}/{edge.capacity ?? '-'}</text>}
            </g>
          );
        })}
        {positioned.map(node => (
          <g key={node.id}>
            <circle cx={node.x} cy={node.y} r="16" fill={node.active === false ? '#7f1d1d' : '#111827'} stroke={node.type === 'CHUTE' ? '#16a34a' : '#60a5fa'} strokeWidth="2" />
            <text x={node.x} y={node.y + 31} textAnchor="middle" className="fill-current text-[10px]">
              {node.label.length > 14 ? `${node.label.slice(0, 12)}...` : node.label}
            </text>
          </g>
        ))}
      </svg>
    </WidgetFrame>
  );
};
