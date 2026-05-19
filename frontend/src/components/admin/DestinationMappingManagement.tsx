import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import toast from "react-hot-toast";
import { DestinationMappingRecord, DisplayRuleDataTypeEnum, DisplayRuleOperatorEnum } from "../../api-client";
import { useApi } from "../../hooks/useApi";
import type { AxiosError } from "axios";

type EditableDestinationMapping = DestinationMappingRecord & { _localId: string };

const dataTypes = Object.values(DisplayRuleDataTypeEnum);
const operatorLabels: Record<DisplayRuleOperatorEnum, string> = {
  [DisplayRuleOperatorEnum.Equal]: "=",
  [DisplayRuleOperatorEnum.Greater]: ">",
  [DisplayRuleOperatorEnum.Lesser]: "<",
};

const operatorsForType = (dataType?: DisplayRuleDataTypeEnum) => {
  if (dataType === DisplayRuleDataTypeEnum.Number || dataType === DisplayRuleDataTypeEnum.Datetime) {
    return [DisplayRuleOperatorEnum.Equal, DisplayRuleOperatorEnum.Greater, DisplayRuleOperatorEnum.Lesser];
  }
  return [DisplayRuleOperatorEnum.Equal];
};

const normalizeDataType = (value?: string): DisplayRuleDataTypeEnum => {
  const normalized = value?.trim().toUpperCase();
  if (normalized && dataTypes.includes(normalized as DisplayRuleDataTypeEnum)) {
    return normalized as DisplayRuleDataTypeEnum;
  }
  return DisplayRuleDataTypeEnum.String;
};

const normalizeOperator = (value?: string): DisplayRuleOperatorEnum => {
  const normalized = value?.trim().replace(/-/g, "_").toUpperCase();
  if (normalized === "GREATER") {
    return DisplayRuleOperatorEnum.Greater;
  }
  if (normalized === "GREATER_THAN" || normalized === "GT") {
    return DisplayRuleOperatorEnum.Greater;
  }
  if (normalized === "LESSER" || normalized === "LESS") {
    return DisplayRuleOperatorEnum.Lesser;
  }
  if (normalized === "LESS_THAN" || normalized === "LESSER_THAN" || normalized === "LT") {
    return DisplayRuleOperatorEnum.Lesser;
  }
  return DisplayRuleOperatorEnum.Equal;
};

const normalizeRow = (mapping: DestinationMappingRecord, localId: string): EditableDestinationMapping => {
  const dataType = normalizeDataType(mapping.dataType);
  const operator = normalizeOperator(mapping.operator);
  const availableOperators = operatorsForType(dataType);

  return {
    ...mapping,
    _localId: localId,
    dataType,
    operator: availableOperators.includes(operator) ? operator : DisplayRuleOperatorEnum.Equal,
  };
};

const toInputDateTime = (value?: string) => {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 16);
};

const fromInputDateTime = (value?: string) => {
  if (!value) return undefined;
  return new Date(value).toISOString();
};

const newRow = (): EditableDestinationMapping => {
  const now = new Date();
  const tomorrow = new Date(now.getTime() + 24 * 60 * 60 * 1000);
  return {
    _localId: `mapping_${crypto.randomUUID?.() || Date.now()}`,
    fieldName: "",
    dataType: DisplayRuleDataTypeEnum.String,
    operator: DisplayRuleOperatorEnum.Equal,
    value: "",
    destination: "",
    validFrom: now.toISOString(),
    validTo: tomorrow.toISOString(),
  };
};

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
      if (row.some(value => value.trim() !== "")) rows.push(row);
      row = [];
      cell = "";
    } else {
      cell += char;
    }
  }

  row.push(cell);
  if (row.some(value => value.trim() !== "")) rows.push(row);
  return rows;
};

const normalizeImportedDate = (value: string) => {
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  const date = new Date(trimmed);
  if (Number.isNaN(date.getTime())) return trimmed;
  return date.toISOString();
};

export const DestinationMappingManagement = () => {
  const { destinationMappingApi, locationApi } = useApi();
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [rows, setRows] = useState<EditableDestinationMapping[]>([]);
  const [locationIds, setLocationIds] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const destinationOptions = useMemo(() => locationIds.map(id => <option key={id} value={id} />), [locationIds]);

  const fetchMappings = useCallback(async () => {
    setLoading(true);
    try {
      const [mappingResponse, locationResponse] = await Promise.all([
        destinationMappingApi.getDestinationMappings(),
        locationApi.getAllLocations(),
      ]);
      setRows(mappingResponse.data.map((mapping, index) => normalizeRow(
        mapping,
        `mapping_${index}_${crypto.randomUUID?.() || Date.now()}`,
      )));
      setLocationIds(locationResponse.data.map(location => location.id).filter((id): id is string => Boolean(id)));
    } catch (error) {
      console.error("Failed to load destination mappings", error);
      toast.error("Failed to load destination mappings");
    } finally {
      setLoading(false);
    }
  }, [destinationMappingApi, locationApi]);

  useEffect(() => {
    fetchMappings();
  }, [fetchMappings]);

  const updateRow = (localId: string, patch: Partial<DestinationMappingRecord>) => {
    setRows(current => current.map(row => {
      if (row._localId !== localId) return row;
      const next = {
        ...row,
        ...patch,
        dataType: patch.dataType !== undefined ? normalizeDataType(patch.dataType) : row.dataType,
        operator: patch.operator !== undefined ? normalizeOperator(patch.operator) : row.operator,
      };
      const availableOperators = operatorsForType(next.dataType);
      if (next.operator && !availableOperators.includes(next.operator)) {
        next.operator = DisplayRuleOperatorEnum.Equal;
      }
      return next;
    }));
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      const payload = rows.map(row => ({
        fieldName: row.fieldName?.trim(),
        dataType: normalizeDataType(row.dataType),
        operator: normalizeOperator(row.operator),
        value: row.value?.trim(),
        destination: row.destination?.trim(),
        validFrom: row.validFrom,
        validTo: row.validTo,
      }));
      const response = await destinationMappingApi.updateDestinationMappings(payload);
      setRows(response.data.map((mapping, index) => normalizeRow(
        mapping,
        `mapping_${index}_${crypto.randomUUID?.() || Date.now()}`,
      )));
      toast.success("Destination mappings saved");
    } catch (error) {
      const axiosError = error as AxiosError<{ message?: string }>;
      toast.error(axiosError.response?.data?.message || "Failed to save destination mappings");
    } finally {
      setSaving(false);
    }
  };

  const handleImport = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => {
      const text = String(reader.result || "");
      const parsed = parseCsv(text);
      const [header, ...dataRows] = parsed;
      const expected = ["fieldName", "dataType", "operator", "value", "destination", "validFrom", "validTo"];
      if (!header || expected.some((column, index) => header[index]?.trim() !== column)) {
        toast.error("CSV header must be fieldName,dataType,operator,value,destination,validFrom,validTo");
        return;
      }

      setRows(dataRows.map((cells, index) => normalizeRow({
        fieldName: cells[0]?.trim() || "",
        dataType: normalizeDataType(cells[1]),
        operator: normalizeOperator(cells[2]),
        value: cells[3]?.trim() || "",
        destination: cells[4]?.trim() || "",
        validFrom: normalizeImportedDate(cells[5] || ""),
        validTo: normalizeImportedDate(cells[6] || ""),
      }, `import_${index}_${crypto.randomUUID?.() || Date.now()}`)));
      toast.success("CSV imported. Review and save to persist.");
    };
    reader.readAsText(file);
  };

  return (
    <div className="bg-white dark:bg-gray-800 p-6 rounded-lg shadow border border-gray-200 dark:border-gray-700">
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between mb-4 border-b border-gray-200 dark:border-gray-700 pb-4">
        <h2 className="text-lg font-bold">Destination Mappings</h2>
        <div className="flex flex-wrap gap-2">
          <button onClick={() => setRows(current => [...current, newRow()])} className="px-3 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded font-semibold text-sm transition-colors">
            Add Row
          </button>
          <button onClick={fetchMappings} disabled={loading} className="px-3 py-2 bg-gray-100 hover:bg-gray-200 dark:bg-gray-700 dark:hover:bg-gray-600 rounded font-semibold text-sm transition-colors disabled:opacity-50">
            Reload
          </button>
          <button onClick={() => fileInputRef.current?.click()} className="px-3 py-2 bg-gray-100 hover:bg-gray-200 dark:bg-gray-700 dark:hover:bg-gray-600 rounded font-semibold text-sm transition-colors">
            Import CSV
          </button>
          <button onClick={handleSave} disabled={saving} className="px-3 py-2 bg-green-600 hover:bg-green-700 text-white rounded font-semibold text-sm transition-colors disabled:opacity-50">
            {saving ? "Saving..." : "Save"}
          </button>
          <input
            ref={fileInputRef}
            type="file"
            accept=".csv,text/csv"
            className="hidden"
            onChange={event => {
              const file = event.target.files?.[0];
              if (file) handleImport(file);
              event.target.value = "";
            }}
          />
        </div>
      </div>

      <datalist id="destination-mapping-locations">{destinationOptions}</datalist>

      <div className="overflow-x-auto">
        <table className="w-full table-fixed text-sm text-left min-w-[1320px]">
          <colgroup>
            <col className="w-[220px]" />
            <col className="w-[150px]" />
            <col className="w-[100px]" />
            <col className="w-[170px]" />
            <col className="w-[220px]" />
            <col className="w-[190px]" />
            <col className="w-[190px]" />
            <col className="w-[80px]" />
          </colgroup>
          <thead className="text-xs text-gray-500 uppercase bg-gray-50 dark:bg-gray-700/50">
            <tr>
              <th className="px-3 py-3">Field</th>
              <th className="px-3 py-3">Type</th>
              <th className="px-3 py-3">Op</th>
              <th className="px-3 py-3">Value</th>
              <th className="px-3 py-3">Destination</th>
              <th className="px-3 py-3">Valid From</th>
              <th className="px-3 py-3">Valid To</th>
              <th className="px-3 py-3 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
            {rows.map(row => (
              <tr key={row._localId} className="hover:bg-gray-50 dark:hover:bg-gray-700/30">
                <td className="px-3 py-3">
                  <input value={row.fieldName || ""} onChange={event => updateRow(row._localId, { fieldName: event.target.value })} className="w-full p-2 rounded border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 outline-none focus:ring-2 focus:ring-blue-500" />
                </td>
                <td className="px-3 py-3">
                  <select value={row.dataType || DisplayRuleDataTypeEnum.String} onChange={event => updateRow(row._localId, { dataType: event.target.value as DisplayRuleDataTypeEnum })} className="w-full p-2 rounded border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 outline-none focus:ring-2 focus:ring-blue-500">
                    {dataTypes.map(type => <option key={type} value={type}>{type}</option>)}
                  </select>
                </td>
                <td className="px-3 py-3">
                  <select value={row.operator || DisplayRuleOperatorEnum.Equal} onChange={event => updateRow(row._localId, { operator: event.target.value as DisplayRuleOperatorEnum })} className="w-full p-2 rounded border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 outline-none focus:ring-2 focus:ring-blue-500">
                    {operatorsForType(row.dataType).map(operator => <option key={operator} value={operator}>{operatorLabels[operator]}</option>)}
                  </select>
                </td>
                <td className="px-3 py-3">
                  <input value={String(row.value || "")} onChange={event => updateRow(row._localId, { value: event.target.value })} className="w-full p-2 rounded border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 outline-none focus:ring-2 focus:ring-blue-500" />
                </td>
                <td className="px-3 py-3">
                  <input list="destination-mapping-locations" value={row.destination || ""} onChange={event => updateRow(row._localId, { destination: event.target.value })} className="w-full p-2 rounded border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 outline-none focus:ring-2 focus:ring-blue-500" />
                </td>
                <td className="px-3 py-3">
                  <input type="datetime-local" value={toInputDateTime(row.validFrom)} onChange={event => updateRow(row._localId, { validFrom: fromInputDateTime(event.target.value) })} className="w-full p-2 rounded border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 outline-none focus:ring-2 focus:ring-blue-500 dark:[color-scheme:dark]" />
                </td>
                <td className="px-3 py-3">
                  <input type="datetime-local" value={toInputDateTime(row.validTo)} onChange={event => updateRow(row._localId, { validTo: fromInputDateTime(event.target.value) })} className="w-full p-2 rounded border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 outline-none focus:ring-2 focus:ring-blue-500 dark:[color-scheme:dark]" />
                </td>
                <td className="px-3 py-3 text-right">
                  <button onClick={() => setRows(current => current.filter(item => item._localId !== row._localId))} className="px-2 py-1 text-red-600 hover:text-red-700 hover:bg-red-50 dark:hover:bg-red-900/20 rounded transition-colors">
                    Delete
                  </button>
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={8} className="px-4 py-8 text-center text-gray-500 italic">
                  {loading ? "Loading destination mappings..." : "No destination mappings configured."}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};
