import type {
  AlarmTimelineData,
  CartesianChartData,
  EvidenceTableData,
  ItemJourneyData,
  MetricCardData,
  TopologyGraphData,
  TraversalTimeData,
} from '../types';

export type RecordValue = Record<string, unknown>;

export const record = (value: unknown): RecordValue => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as RecordValue : {};
export const array = (value: unknown): unknown[] => Array.isArray(value) ? value : [];
export const number = (value: unknown): number => typeof value === 'number' && Number.isFinite(value) ? value : 0;
export const humanize = (value: string) => value.replace(/([A-Z])/g, ' $1').replace(/^./, character => character.toUpperCase());
export const formatTime = (value: string) => Number.isNaN(new Date(value).getTime()) ? value : new Date(value).toLocaleString();

export function isMetricCardData(value: unknown): value is MetricCardData {
  return array(record(value).metrics).every(metric => typeof record(metric).label === 'string' && ['string', 'number'].includes(typeof record(metric).value));
}

export function isCartesianChartData(value: unknown): value is CartesianChartData {
  const data = record(value);
  return (data.variant === 'bar' || data.variant === 'line') && array(data.series).length > 0 && array(data.points).length > 0;
}

export function isTopologyGraphData(value: unknown): value is TopologyGraphData {
  const data = record(value);
  return Array.isArray(data.nodes) && Array.isArray(data.edges);
}

export function isAlarmTimelineData(value: unknown): value is AlarmTimelineData {
  return Array.isArray(record(value).events);
}

export function isItemJourneyData(value: unknown): value is ItemJourneyData {
  return Array.isArray(record(value).steps);
}

export function isTraversalTimeData(value: unknown): value is TraversalTimeData {
  const data = record(value);
  return Array.isArray(data.metrics) && Array.isArray(data.points);
}

export function isEvidenceTableData(value: unknown): value is EvidenceTableData {
  return Array.isArray(record(value).rows);
}
