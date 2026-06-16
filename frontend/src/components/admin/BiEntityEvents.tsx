import { FormEvent, Fragment, useMemo, useState } from "react";
import type { AxiosError } from "axios";
import toast from "react-hot-toast";
import { EntityEventRecord, EntityEventType } from "../../api-client";
import { useApi } from "../../hooks/useApi";

const inputClass = "w-full p-2 rounded border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 outline-none focus:ring-2 focus:ring-blue-500";
const entityTypes = [EntityEventType.Item, EntityEventType.Location, EntityEventType.Conveyor];

export const BiEntityEvents = () => {
  const { analyticsApi } = useApi();
  const [entityType, setEntityType] = useState<EntityEventType>(EntityEventType.Item);
  const [entityId, setEntityId] = useState("");
  const [limit, setLimit] = useState(200);
  const [events, setEvents] = useState<EntityEventRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [searched, setSearched] = useState(false);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());

  const normalizedLimit = useMemo(() => Math.max(1, Math.min(limit || 200, 1000)), [limit]);

  const search = async (event: FormEvent) => {
    event.preventDefault();
    const normalizedEntityId = entityId.trim();
    if (!normalizedEntityId) {
      toast.error("Entity id is required");
      return;
    }

    setLoading(true);
    setSearched(true);
    setExpandedIds(new Set());
    try {
      const response = await analyticsApi.getEntityEvents(entityType, normalizedEntityId, normalizedLimit);
      setEvents(response.data);
    } catch (error) {
      console.error("Failed to load entity events", error);
      const axiosError = error as AxiosError<{ message?: string }>;
      toast.error(axiosError.response?.data?.message || "Failed to load entity events");
    } finally {
      setLoading(false);
    }
  };

  const toggleExpanded = (eventId?: string) => {
    if (!eventId) return;
    setExpandedIds(current => {
      const next = new Set(current);
      if (next.has(eventId)) {
        next.delete(eventId);
      } else {
        next.add(eventId);
      }
      return next;
    });
  };

  return (
    <section className="bg-white dark:bg-gray-800 p-6 rounded-lg shadow border border-gray-200 dark:border-gray-700">
      <form onSubmit={search} className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between mb-4 border-b border-gray-200 dark:border-gray-700 pb-4">
        <div className="grid w-full grid-cols-1 gap-3 md:grid-cols-[180px_minmax(240px,1fr)_120px]">
          <label className="space-y-1 text-sm font-semibold">
            <span>Entity Type</span>
            <select value={entityType} onChange={event => setEntityType(event.target.value as EntityEventType)} className={inputClass}>
              {entityTypes.map(type => <option key={type} value={type}>{formatEntityType(type)}</option>)}
            </select>
          </label>
          <label className="space-y-1 text-sm font-semibold">
            <span>Entity ID</span>
            <input value={entityId} onChange={event => setEntityId(event.target.value)} className={inputClass} />
          </label>
          <label className="space-y-1 text-sm font-semibold">
            <span>Limit</span>
            <input
              type="number"
              min={1}
              max={1000}
              value={limit}
              onChange={event => setLimit(Number(event.target.value))}
              className={inputClass}
            />
          </label>
        </div>
        <button type="submit" disabled={loading} className="px-3 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded font-semibold text-sm disabled:opacity-50">
          {loading ? "Searching..." : "Search"}
        </button>
      </form>

      <div className="overflow-x-auto">
        <table className="w-full table-fixed text-sm min-w-[1180px]">
          <thead className="text-xs text-gray-500 uppercase bg-gray-50 dark:bg-gray-700/50">
            <tr>
              <th className="px-3 py-3 text-left w-[180px]">Received</th>
              <th className="px-3 py-3 text-left w-[180px]">Processed</th>
              <th className="px-3 py-3 text-left w-[220px]">Event Type</th>
              <th className="px-3 py-3 text-left w-[180px]">Entity ID</th>
              <th className="px-3 py-3 text-left">Payload</th>
              <th className="px-3 py-3 text-left w-[120px]">Raw</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
            {events.map(event => {
              const expanded = Boolean(event.eventId && expandedIds.has(event.eventId));
              const rowKey = event.eventId || `${event.eventType}-${event.timestampReceived}`;
              return (
                <Fragment key={rowKey}>
                  <tr>
                    <td className="p-3 align-top text-gray-700 dark:text-gray-200">{formatTimestamp(event.timestampReceived)}</td>
                    <td className="p-3 align-top text-gray-700 dark:text-gray-200">{formatTimestamp(event.timestampProcessed)}</td>
                    <td className="p-3 align-top">
                      <div className="font-semibold text-gray-900 dark:text-white">{formatEventType(event.eventType)}</div>
                      <div className="mt-1 truncate font-mono text-xs text-gray-500">{event.eventType}</div>
                    </td>
                    <td className="p-3 align-top font-mono text-xs text-gray-700 dark:text-gray-200">{event.entityId}</td>
                    <td className="p-3 align-top">
                      <PayloadSummary payload={event.payload} />
                    </td>
                    <td className="p-3 align-top">
                      <button type="button" onClick={() => toggleExpanded(event.eventId)} className="px-3 py-1.5 bg-gray-100 dark:bg-gray-700 rounded font-semibold text-sm">
                        {expanded ? "Hide" : "View"}
                      </button>
                    </td>
                  </tr>
                  {expanded && (
                    <tr>
                      <td colSpan={6} className="bg-gray-50 dark:bg-gray-900/60 p-4">
                        <pre className="max-h-96 overflow-auto whitespace-pre-wrap break-words rounded border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-950 p-4 text-xs text-gray-800 dark:text-gray-100">
                          {JSON.stringify(event.payload ?? {}, null, 2)}
                        </pre>
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
            {!events.length && <EmptyRow loading={loading} searched={searched} />}
          </tbody>
        </table>
      </div>
    </section>
  );
};

const EmptyRow = ({ loading, searched }: { loading: boolean; searched: boolean }) => (
  <tr>
    <td colSpan={6} className="px-4 py-8 text-center text-gray-500 italic">
      {loading ? "Loading events..." : searched ? "No events found." : "Search for an entity to load events."}
    </td>
  </tr>
);

const PayloadSummary = ({ payload }: { payload?: { [key: string]: unknown } }) => {
  const fields = summarizePayload(payload);
  if (!fields.length) {
    return <span className="text-gray-500 italic">No payload fields</span>;
  }

  return (
    <div className="flex flex-wrap gap-2">
      {fields.map(([key, value]) => (
        <span key={key} className="inline-flex max-w-[260px] items-center gap-1 rounded border border-gray-200 bg-gray-50 px-2 py-1 text-xs dark:border-gray-700 dark:bg-gray-900">
          <span className="font-semibold text-gray-600 dark:text-gray-300">{key}:</span>
          <span className="truncate text-gray-900 dark:text-gray-100">{formatPayloadValue(value)}</span>
        </span>
      ))}
    </div>
  );
};

const summarizePayload = (payload?: { [key: string]: unknown }) => {
  if (!payload) return [];
  const preferredKeys = [
    "itemId",
    "locationId",
    "connectionId",
    "chuteId",
    "sourceLocationId",
    "targetLocationId",
    "name",
    "destination",
    "position",
    "speed",
    "length",
    "capacity",
    "active",
  ];
  const preferred = preferredKeys
    .filter(key => payload[key] !== undefined)
    .map(key => [key, payload[key]] as [string, unknown]);

  if (preferred.length) {
    return preferred.slice(0, 6);
  }

  return Object.entries(payload)
    .filter(([key, value]) => !["eventId", "eventType", "timestamp", "senderId"].includes(key) && isSummaryValue(value))
    .slice(0, 6);
};

const isSummaryValue = (value: unknown) =>
  typeof value === "string" || typeof value === "number" || typeof value === "boolean" || value == null;

const formatPayloadValue = (value: unknown) => {
  if (value == null) return "null";
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") return String(value);
  return JSON.stringify(value);
};

const formatTimestamp = (value?: string) => {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString();
};

const formatEntityType = (value: EntityEventType) => value.charAt(0) + value.slice(1).toLowerCase();

const formatEventType = (value?: string) => {
  if (!value) return "";
  return value
    .toLowerCase()
    .split("_")
    .filter(Boolean)
    .map(part => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
};
