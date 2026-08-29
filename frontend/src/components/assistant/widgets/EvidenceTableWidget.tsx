import type { VisualWidget } from '../types';
import { EmptyWidget, WidgetFrame } from './WidgetFrame';
import { array, isEvidenceTableData, record } from './utils';

export const EvidenceTableWidget = ({ widget }: { widget: VisualWidget }) => {
  const rows = isEvidenceTableData(widget.data)
    ? widget.data.rows
    : array(widget.data).map(record).map(entry => ({
      operationId: String(entry.operationId ?? ''),
      operationKind: String(entry.operationKind ?? ''),
      status: entry.error ? 'failed' as const : 'retrieved' as const,
      records: 0,
      detail: entry.error ? String(entry.error) : 'Read-only semantic evidence',
    }));
  if (!rows.length) return <EmptyWidget title={widget.title} message="No evidence operations were recorded." />;
  return (
    <WidgetFrame title={widget.title}>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[520px] text-left text-xs">
          <thead>
            <tr className="text-gray-500 dark:text-gray-400">
              <th className="p-2 font-semibold">Operation</th>
              <th className="p-2 font-semibold">Status</th>
              <th className="p-2 font-semibold">Rows</th>
              <th className="p-2 font-semibold">Detail</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(row => (
              <tr key={row.operationId} className="border-t border-gray-100 dark:border-gray-800">
                <td className="p-2 font-medium">{row.operationKind || row.operationId}</td>
                <td className="p-2">
                  <span className={`rounded px-1.5 py-0.5 font-semibold ${row.status === 'failed' ? 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-200' : 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-200'}`}>
                    {row.status === 'failed' ? 'Failed' : 'Retrieved'}
                  </span>
                </td>
                <td className="p-2 tabular-nums">{row.records}</td>
                <td className="p-2 text-gray-500 dark:text-gray-400">{row.detail}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </WidgetFrame>
  );
};
