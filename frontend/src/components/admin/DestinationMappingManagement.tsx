import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { AxiosError } from "axios";
import toast from "react-hot-toast";
import {
  DestinationExitMappingRecord,
  DestinationMappingRecord,
  DisplayRuleDataTypeEnum,
  DisplayRuleOperatorEnum,
} from "../../api-client";
import { useApi } from "../../hooks/useApi";

type PropertyRow = DestinationMappingRecord & { _localId: string; destinationsText: string };
type ExitRow = DestinationExitMappingRecord & { _localId: string; exitsText: string };

const dataTypes = Object.values(DisplayRuleDataTypeEnum);
const operatorLabels: Record<DisplayRuleOperatorEnum, string> = {
  [DisplayRuleOperatorEnum.Equal]: "=",
  [DisplayRuleOperatorEnum.Greater]: ">",
  [DisplayRuleOperatorEnum.Lesser]: "<",
};

const operatorsForType = (dataType?: DisplayRuleDataTypeEnum) =>
  dataType === DisplayRuleDataTypeEnum.Number || dataType === DisplayRuleDataTypeEnum.Datetime
    ? [DisplayRuleOperatorEnum.Equal, DisplayRuleOperatorEnum.Greater, DisplayRuleOperatorEnum.Lesser]
    : [DisplayRuleOperatorEnum.Equal];

const normalizeDataType = (value?: string): DisplayRuleDataTypeEnum => {
  const normalized = value?.trim().toUpperCase();
  return normalized && dataTypes.includes(normalized as DisplayRuleDataTypeEnum)
    ? normalized as DisplayRuleDataTypeEnum
    : DisplayRuleDataTypeEnum.String;
};

const normalizeOperator = (value?: string): DisplayRuleOperatorEnum => {
  const normalized = value?.trim().replace(/-/g, "_").toUpperCase();
  if (normalized === "GREATER" || normalized === "GREATER_THAN" || normalized === "GT") {
    return DisplayRuleOperatorEnum.Greater;
  }
  if (normalized === "LESSER" || normalized === "LESS" || normalized === "LESS_THAN" || normalized === "LT") {
    return DisplayRuleOperatorEnum.Lesser;
  }
  return DisplayRuleOperatorEnum.Equal;
};

const localId = (prefix: string) => `${prefix}_${crypto.randomUUID?.() || Date.now()}`;
const toArrayText = (values?: string[]) => JSON.stringify(values ?? []);

const parseStringArray = (value: string, fieldName: string) => {
  const parsed: unknown = JSON.parse(value);
  if (!Array.isArray(parsed) || parsed.length === 0 || parsed.some(item => typeof item !== "string" || !item.trim())) {
    throw new Error(`${fieldName} must be a nonempty JSON array of nonblank strings`);
  }
  return [...new Set(parsed.map(item => item.trim()))];
};

const toInputDateTime = (value?: string) => {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 16);
};

const fromInputDateTime = (value: string) => value ? new Date(value).toISOString() : undefined;

const parseCsv = (text: string) => {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = "";
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    const next = text[index + 1];
    if (char === '"' && quoted && next === '"') {
      cell += '"';
      index += 1;
    } else if (char === '"') {
      quoted = !quoted;
    } else if (char === "," && !quoted) {
      row.push(cell);
      cell = "";
    } else if ((char === "\n" || char === "\r") && !quoted) {
      if (char === "\r" && next === "\n") index += 1;
      row.push(cell);
      if (row.some(value => value.trim())) rows.push(row);
      row = [];
      cell = "";
    } else {
      cell += char;
    }
  }
  row.push(cell);
  if (row.some(value => value.trim())) rows.push(row);
  return rows;
};

const normalizeDate = (value: string) => {
  const date = new Date(value.trim());
  return Number.isNaN(date.getTime()) ? value.trim() : date.toISOString();
};

const newPropertyRow = (): PropertyRow => {
  const now = new Date();
  return {
    _localId: localId("property"),
    fieldName: "",
    dataType: DisplayRuleDataTypeEnum.String,
    operator: DisplayRuleOperatorEnum.Equal,
    value: "",
    destinations: [],
    destinationsText: "[]",
    validFrom: now.toISOString(),
    validTo: new Date(now.getTime() + 86_400_000).toISOString(),
  };
};

const newExitRow = (): ExitRow => ({
  _localId: localId("exit"),
  destination: "",
  exits: [],
  exitsText: "[]",
});

export const DestinationMappingManagement = () => {
  const { destinationMappingApi, destinationExitMappingApi, locationApi } = useApi();
  const propertyFileRef = useRef<HTMLInputElement | null>(null);
  const exitFileRef = useRef<HTMLInputElement | null>(null);
  const [propertyRows, setPropertyRows] = useState<PropertyRow[]>([]);
  const [exitRows, setExitRows] = useState<ExitRow[]>([]);
  const [locationIds, setLocationIds] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [savingProperties, setSavingProperties] = useState(false);
  const [savingExits, setSavingExits] = useState(false);

  const logicalDestinations = useMemo(
    () => [...new Set(exitRows.map(row => row.destination?.trim()).filter((value): value is string => Boolean(value)))],
    [exitRows],
  );

  const fetchMappings = useCallback(async () => {
    setLoading(true);
    try {
      const [propertyResponse, exitResponse, locationResponse] = await Promise.all([
        destinationMappingApi.getDestinationMappings(),
        destinationExitMappingApi.getMappings(),
        locationApi.getAllLocations(),
      ]);
      setPropertyRows(propertyResponse.data.map(mapping => ({
        ...mapping,
        _localId: localId("property"),
        dataType: normalizeDataType(mapping.dataType),
        operator: normalizeOperator(mapping.operator),
        destinationsText: toArrayText(mapping.destinations),
      })));
      setExitRows(exitResponse.data.map(mapping => ({
        ...mapping,
        _localId: localId("exit"),
        exitsText: toArrayText(mapping.exits),
      })));
      setLocationIds(locationResponse.data.map(location => location.id).filter((id): id is string => Boolean(id)));
    } catch (error) {
      console.error("Failed to load mappings", error);
      toast.error("Failed to load mappings");
    } finally {
      setLoading(false);
    }
  }, [destinationExitMappingApi, destinationMappingApi, locationApi]);

  useEffect(() => {
    fetchMappings();
  }, [fetchMappings]);

  const saveProperties = async () => {
    setSavingProperties(true);
    try {
      const payload = propertyRows.map(row => ({
        fieldName: row.fieldName?.trim(),
        dataType: normalizeDataType(row.dataType),
        operator: normalizeOperator(row.operator),
        value: row.value?.trim(),
        destinations: parseStringArray(row.destinationsText, "Destinations"),
        validFrom: row.validFrom,
        validTo: row.validTo,
      }));
      await destinationMappingApi.updateDestinationMappings(payload);
      toast.success("Property mappings saved");
      await fetchMappings();
    } catch (error) {
      const axiosError = error as AxiosError<{ message?: string }>;
      toast.error(axiosError.response?.data?.message || (error as Error).message || "Failed to save property mappings");
    } finally {
      setSavingProperties(false);
    }
  };

  const saveExits = async () => {
    setSavingExits(true);
    try {
      const payload = exitRows.map(row => ({
        destination: row.destination?.trim(),
        exits: parseStringArray(row.exitsText, "Exits"),
      }));
      await destinationExitMappingApi.updateMappings(payload);
      toast.success("Destination exit mappings saved");
      await fetchMappings();
    } catch (error) {
      const axiosError = error as AxiosError<{ message?: string }>;
      toast.error(axiosError.response?.data?.message || (error as Error).message || "Failed to save exit mappings");
    } finally {
      setSavingExits(false);
    }
  };

  const importProperties = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => {
      const [header, ...rows] = parseCsv(String(reader.result || ""));
      const expected = ["fieldName", "dataType", "operator", "value", "destinations", "validFrom", "validTo"];
      if (!header || expected.some((column, index) => header[index]?.trim() !== column)) {
        toast.error(`CSV header must be ${expected.join(",")}`);
        return;
      }
      try {
        setPropertyRows(rows.map(cells => ({
          _localId: localId("property"),
          fieldName: cells[0]?.trim(),
          dataType: normalizeDataType(cells[1]),
          operator: normalizeOperator(cells[2]),
          value: cells[3]?.trim(),
          destinations: parseStringArray(cells[4] || "", "Destinations"),
          destinationsText: cells[4]?.trim() || "[]",
          validFrom: normalizeDate(cells[5] || ""),
          validTo: normalizeDate(cells[6] || ""),
        })));
        toast.success("Property CSV imported. Review and save to persist.");
      } catch (error) {
        toast.error((error as Error).message);
      }
    };
    reader.readAsText(file);
  };

  const importExits = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => {
      const [header, ...rows] = parseCsv(String(reader.result || ""));
      if (!header || header[0]?.trim() !== "destination" || header[1]?.trim() !== "exits") {
        toast.error("CSV header must be destination,exits");
        return;
      }
      try {
        setExitRows(rows.map(cells => ({
          _localId: localId("exit"),
          destination: cells[0]?.trim(),
          exits: parseStringArray(cells[1] || "", "Exits"),
          exitsText: cells[1]?.trim() || "[]",
        })));
        toast.success("Exit CSV imported. Review and save to persist.");
      } catch (error) {
        toast.error((error as Error).message);
      }
    };
    reader.readAsText(file);
  };

  return (
    <div className="space-y-6">
      <datalist id="logical-destinations">
        {logicalDestinations.map(destination => <option key={destination} value={JSON.stringify([destination])} />)}
      </datalist>
      <datalist id="graph-locations">
        {locationIds.map(id => <option key={id} value={JSON.stringify([id])} />)}
      </datalist>

      <MappingSection
        title="Property To Destinations"
        loading={loading}
        saving={savingProperties}
        onAdd={() => setPropertyRows(rows => [...rows, newPropertyRow()])}
        onReload={fetchMappings}
        onImport={() => propertyFileRef.current?.click()}
        onSave={saveProperties}
        fileRef={propertyFileRef}
        onFile={importProperties}
      >
        <div className="overflow-x-auto">
          <table className="w-full table-fixed text-sm min-w-[1400px]">
            <thead className="text-xs text-gray-500 uppercase bg-gray-50 dark:bg-gray-700/50">
              <tr>{["Field", "Type", "Op", "Value", "Destinations JSON", "Valid From", "Valid To", "Actions"].map(label => <th key={label} className="px-3 py-3 text-left">{label}</th>)}</tr>
            </thead>
            <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
              {propertyRows.map(row => (
                <tr key={row._localId}>
                  <td className="p-3"><Cell value={row.fieldName} onChange={value => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, fieldName: value } : item))} /></td>
                  <td className="p-3"><select value={row.dataType} onChange={event => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, dataType: normalizeDataType(event.target.value), operator: DisplayRuleOperatorEnum.Equal } : item))} className={inputClass}>{dataTypes.map(type => <option key={type}>{type}</option>)}</select></td>
                  <td className="p-3"><select value={row.operator} onChange={event => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, operator: normalizeOperator(event.target.value) } : item))} className={inputClass}>{operatorsForType(row.dataType).map(operator => <option key={operator} value={operator}>{operatorLabels[operator]}</option>)}</select></td>
                  <td className="p-3"><Cell value={String(row.value || "")} onChange={value => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, value } : item))} /></td>
                  <td className="p-3"><Cell value={row.destinationsText} onChange={destinationsText => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, destinationsText } : item))} list="logical-destinations" /></td>
                  <td className="p-3"><DateCell value={row.validFrom} onChange={validFrom => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, validFrom } : item))} /></td>
                  <td className="p-3"><DateCell value={row.validTo} onChange={validTo => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, validTo } : item))} /></td>
                  <td className="p-3"><DeleteButton onClick={() => setPropertyRows(rows => rows.filter(item => item._localId !== row._localId))} /></td>
                </tr>
              ))}
              {!propertyRows.length && <EmptyRow columns={8} loading={loading} />}
            </tbody>
          </table>
        </div>
      </MappingSection>

      <MappingSection
        title="Destinations To Exits"
        loading={loading}
        saving={savingExits}
        onAdd={() => setExitRows(rows => [...rows, newExitRow()])}
        onReload={fetchMappings}
        onImport={() => exitFileRef.current?.click()}
        onSave={saveExits}
        fileRef={exitFileRef}
        onFile={importExits}
      >
        <div className="overflow-x-auto">
          <table className="w-full table-fixed text-sm min-w-[760px]">
            <thead className="text-xs text-gray-500 uppercase bg-gray-50 dark:bg-gray-700/50">
              <tr><th className="p-3 text-left">Destination</th><th className="p-3 text-left">Exits JSON</th><th className="p-3 text-left">Actions</th></tr>
            </thead>
            <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
              {exitRows.map(row => (
                <tr key={row._localId}>
                  <td className="p-3"><Cell value={row.destination} onChange={destination => setExitRows(rows => rows.map(item => item._localId === row._localId ? { ...item, destination } : item))} /></td>
                  <td className="p-3"><Cell value={row.exitsText} onChange={exitsText => setExitRows(rows => rows.map(item => item._localId === row._localId ? { ...item, exitsText } : item))} list="graph-locations" /></td>
                  <td className="p-3"><DeleteButton onClick={() => setExitRows(rows => rows.filter(item => item._localId !== row._localId))} /></td>
                </tr>
              ))}
              {!exitRows.length && <EmptyRow columns={3} loading={loading} />}
            </tbody>
          </table>
        </div>
      </MappingSection>
    </div>
  );
};

const inputClass = "w-full p-2 rounded border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 outline-none focus:ring-2 focus:ring-blue-500";

const Cell = ({ value, onChange, list }: { value?: string; onChange: (value: string) => void; list?: string }) => (
  <input list={list} value={value || ""} onChange={event => onChange(event.target.value)} className={inputClass} />
);

const DateCell = ({ value, onChange }: { value?: string; onChange: (value?: string) => void }) => (
  <input type="datetime-local" value={toInputDateTime(value)} onChange={event => onChange(fromInputDateTime(event.target.value))} className={`${inputClass} dark:[color-scheme:dark]`} />
);

const DeleteButton = ({ onClick }: { onClick: () => void }) => (
  <button onClick={onClick} className="px-2 py-1 text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20 rounded">Delete</button>
);

const EmptyRow = ({ columns, loading }: { columns: number; loading: boolean }) => (
  <tr><td colSpan={columns} className="px-4 py-8 text-center text-gray-500 italic">{loading ? "Loading mappings..." : "No mappings configured."}</td></tr>
);

const MappingSection = ({
  title,
  loading,
  saving,
  onAdd,
  onReload,
  onImport,
  onSave,
  fileRef,
  onFile,
  children,
}: {
  title: string;
  loading: boolean;
  saving: boolean;
  onAdd: () => void;
  onReload: () => void;
  onImport: () => void;
  onSave: () => void;
  fileRef: React.RefObject<HTMLInputElement>;
  onFile: (file: File) => void;
  children: React.ReactNode;
}) => (
  <section className="bg-white dark:bg-gray-800 p-6 rounded-lg shadow border border-gray-200 dark:border-gray-700">
    <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between mb-4 border-b border-gray-200 dark:border-gray-700 pb-4">
      <h2 className="text-lg font-bold">{title}</h2>
      <div className="flex flex-wrap gap-2">
        <button onClick={onAdd} className="px-3 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded font-semibold text-sm">Add Row</button>
        <button onClick={onReload} disabled={loading} className="px-3 py-2 bg-gray-100 dark:bg-gray-700 rounded font-semibold text-sm disabled:opacity-50">Reload</button>
        <button onClick={onImport} className="px-3 py-2 bg-gray-100 dark:bg-gray-700 rounded font-semibold text-sm">Import CSV</button>
        <button onClick={onSave} disabled={saving} className="px-3 py-2 bg-green-600 hover:bg-green-700 text-white rounded font-semibold text-sm disabled:opacity-50">{saving ? "Saving..." : "Save"}</button>
        <input ref={fileRef} type="file" accept=".csv,text/csv" className="hidden" onChange={event => {
          const file = event.target.files?.[0];
          if (file) onFile(file);
          event.target.value = "";
        }} />
      </div>
    </div>
    {children}
  </section>
);
