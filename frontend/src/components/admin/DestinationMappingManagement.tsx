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

type PropertyRow = DestinationMappingRecord & { _localId: string };
type ExitRow = DestinationExitMappingRecord & { _localId: string };

const dataTypes = Object.values(DisplayRuleDataTypeEnum);
const operatorLabels: Record<DisplayRuleOperatorEnum, string> = {
  [DisplayRuleOperatorEnum.Equal]: "=",
  [DisplayRuleOperatorEnum.Greater]: ">",
  [DisplayRuleOperatorEnum.GreaterOrEqual]: ">=",
  [DisplayRuleOperatorEnum.Lesser]: "<",
  [DisplayRuleOperatorEnum.LesserOrEqual]: "<=",
};

const operatorsForType = (dataType?: DisplayRuleDataTypeEnum) =>
  dataType === DisplayRuleDataTypeEnum.Number || dataType === DisplayRuleDataTypeEnum.Datetime
    ? [DisplayRuleOperatorEnum.Equal, DisplayRuleOperatorEnum.Greater, DisplayRuleOperatorEnum.GreaterOrEqual,
      DisplayRuleOperatorEnum.Lesser, DisplayRuleOperatorEnum.LesserOrEqual]
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
  if (normalized === "GREATER_OR_EQUAL" || normalized === "GREATER_THAN_OR_EQUAL" || normalized === "GTE") {
    return DisplayRuleOperatorEnum.GreaterOrEqual;
  }
  if (normalized === "LESSER" || normalized === "LESS" || normalized === "LESS_THAN" || normalized === "LT") {
    return DisplayRuleOperatorEnum.Lesser;
  }
  if (normalized === "LESSER_OR_EQUAL" || normalized === "LESS_THAN_OR_EQUAL" || normalized === "LTE") {
    return DisplayRuleOperatorEnum.LesserOrEqual;
  }
  return DisplayRuleOperatorEnum.Equal;
};

const localId = (prefix: string) => `${prefix}_${crypto.randomUUID?.() || Date.now()}`;
const normalizeStringList = (values: string[] | undefined, fieldName: string) => {
  if (!values?.length || values.some(value => !value.trim())) {
    throw new Error(`${fieldName} must contain at least one nonblank value`);
  }
  return [...new Set(values.map(value => value.trim()))];
};

const parseCsvStringArray = (value: string, fieldName: string) => {
  const parsed: unknown = JSON.parse(value);
  if (!Array.isArray(parsed) || parsed.some(item => typeof item !== "string")) {
    throw new Error(`${fieldName} must be a JSON array of strings`);
  }
  return normalizeStringList(parsed, fieldName);
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

const csvCell = (value: unknown) => {
  const text = value == null ? "" : String(value);
  return `"${text.replace(/"/g, '""')}"`;
};

const downloadCsv = (filename: string, rows: unknown[][]) => {
  const blob = new Blob([rows.map(row => row.map(csvCell).join(",")).join("\n")], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
};

const newPropertyRow = (): PropertyRow => {
  const now = new Date();
  return {
    _localId: localId("property"),
    fieldName: "",
    dataType: DisplayRuleDataTypeEnum.String,
    operator: DisplayRuleOperatorEnum.Equal,
    value: "",
    destinations: [""],
    validFrom: now.toISOString(),
    validTo: new Date(now.getTime() + 86_400_000).toISOString(),
  };
};

const newExitRow = (): ExitRow => ({
  _localId: localId("exit"),
  destination: "",
  exits: [""],
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
  const [propertyDraft, setPropertyDraft] = useState<PropertyRow | null>(null);
  const [exitDraft, setExitDraft] = useState<ExitRow | null>(null);

  const logicalDestinations = useMemo(
    () => [...new Set(exitRows.map(row => row.destination?.trim()).filter((value): value is string => Boolean(value)))],
    [exitRows],
  );

  const fetchMappings = useCallback(async () => {
    setLoading(true);
    try {
      const [propertyResponse, exitResponse, locationResponse] = await Promise.all([
        destinationMappingApi.getDestinationMappings(),
        destinationExitMappingApi.getDestinationExitMappings(),
        locationApi.getAllLocations(),
      ]);
      setPropertyRows(propertyResponse.data.map(mapping => ({
        ...mapping,
        _localId: localId("property"),
        dataType: normalizeDataType(mapping.dataType),
        operator: normalizeOperator(mapping.operator),
        secondOperator: mapping.secondOperator ? normalizeOperator(mapping.secondOperator) : undefined,
      })));
      setExitRows(exitResponse.data.map(mapping => ({
        ...mapping,
        _localId: localId("exit"),
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
        secondOperator: row.secondOperator,
        secondValue: row.secondValue?.trim() || undefined,
        destinations: normalizeStringList(row.destinations, "Destinations"),
        validFrom: row.validFrom,
        rushAt: row.rushAt || undefined,
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
        exits: normalizeStringList(row.exits, "Exits"),
      }));
      await destinationExitMappingApi.updateDestinationExitMappings(payload);
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
      const legacyHeader = ["fieldName", "dataType", "operator", "value", "secondOperator", "secondValue", "destinations", "validFrom", "validTo"];
      const rushHeader = [...legacyHeader.slice(0, 8), "rushAt", "validTo"];
      const columns = header?.map(column => column.trim()) ?? [];
      const legacy = columns.length === legacyHeader.length && legacyHeader.every((column, index) => columns[index] === column);
      const withRush = columns.length === rushHeader.length && rushHeader.every((column, index) => columns[index] === column);
      if (!legacy && !withRush) {
        toast.error(`CSV header must be ${rushHeader.join(",")} (legacy nine-column files are also accepted)`);
        return;
      }
      try {
        setPropertyRows(rows.map(cells => ({
          _localId: localId("property"),
          fieldName: cells[0]?.trim(),
          dataType: normalizeDataType(cells[1]),
          operator: normalizeOperator(cells[2]),
          value: cells[3]?.trim(),
          secondOperator: cells[4]?.trim() ? normalizeOperator(cells[4]) : undefined,
          secondValue: cells[5]?.trim() || undefined,
          destinations: parseCsvStringArray(cells[6] || "", "Destinations"),
          validFrom: normalizeDate(cells[7] || ""),
          rushAt: withRush && cells[8]?.trim() ? normalizeDate(cells[8]) : undefined,
          validTo: normalizeDate(cells[withRush ? 9 : 8] || ""),
        })));
        toast.success("Property CSV imported. Review and save to persist.");
      } catch (error) {
        toast.error((error as Error).message);
      }
    };
    reader.readAsText(file);
  };

  const exportProperties = () => downloadCsv("destination-mappings.csv", [
    ["fieldName", "dataType", "operator", "value", "secondOperator", "secondValue", "destinations", "validFrom", "rushAt", "validTo"],
    ...propertyRows.map(row => [
      row.fieldName, row.dataType, row.operator, row.value, row.secondOperator, row.secondValue,
      JSON.stringify(row.destinations ?? []), row.validFrom, row.rushAt, row.validTo,
    ]),
  ]);

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
          exits: parseCsvStringArray(cells[1] || "", "Exits"),
        })));
        toast.success("Exit CSV imported. Review and save to persist.");
      } catch (error) {
        toast.error((error as Error).message);
      }
    };
    reader.readAsText(file);
  };

  const addProperty = () => {
    if (!propertyDraft) return;
    try {
      if (!propertyDraft.fieldName?.trim() || !propertyDraft.value?.trim()) {
        throw new Error("Field and value are required");
      }
      const destinations = normalizeStringList(propertyDraft.destinations, "Destinations");
      setPropertyRows(rows => [...rows, { ...propertyDraft, destinations }]);
      setPropertyDraft(null);
    } catch (error) {
      toast.error((error as Error).message);
    }
  };

  const addExit = () => {
    if (!exitDraft) return;
    try {
      if (!exitDraft.destination?.trim()) throw new Error("Destination is required");
      const exits = normalizeStringList(exitDraft.exits, "Exits");
      setExitRows(rows => [...rows, { ...exitDraft, exits }]);
      setExitDraft(null);
    } catch (error) {
      toast.error((error as Error).message);
    }
  };

  return (
    <div className="space-y-6">
      <datalist id="property-destination-suggestions">
        {logicalDestinations.map(destination => <option key={`logical-${destination}`} value={destination} />)}
        {locationIds.map(id => <option key={`location-${id}`} value={id} />)}
      </datalist>
      <datalist id="graph-locations">
        {locationIds.map(id => <option key={id} value={id} />)}
      </datalist>

      {propertyDraft && <AddPropertyModal draft={propertyDraft} onChange={setPropertyDraft} onCancel={() => setPropertyDraft(null)} onSave={addProperty} />}
      {exitDraft && <AddExitModal draft={exitDraft} onChange={setExitDraft} onCancel={() => setExitDraft(null)} onSave={addExit} />}

      <MappingSection
        title="Property To Destinations"
        loading={loading}
        saving={savingProperties}
        onAdd={() => setPropertyDraft(newPropertyRow())}
        onReload={fetchMappings}
        onImport={() => propertyFileRef.current?.click()}
        onExport={exportProperties}
        onSave={saveProperties}
        fileRef={propertyFileRef}
        onFile={importProperties}
      >
        <p className="mb-3 text-sm text-gray-600 dark:text-gray-300">Destinations can be logical names configured in Destinations To Exits or direct graph location/chute IDs.</p>
        <div className="overflow-x-auto">
          <table className="w-full table-fixed text-sm min-w-[1800px]">
            <thead className="text-xs text-gray-500 uppercase bg-gray-50 dark:bg-gray-700/50">
              <tr>{["Field", "Type", "Op", "Value", "Second Op", "Second Value", "Destinations", "Valid From", "Rush At", "Valid To", "Actions"].map(label => <th key={label} className="px-3 py-3 text-left">{label}</th>)}</tr>
            </thead>
            <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
              {propertyRows.map(row => (
                <tr key={row._localId}>
                  <td className="p-3"><Cell value={row.fieldName} onChange={value => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, fieldName: value } : item))} /></td>
                  <td className="p-3"><select value={row.dataType} onChange={event => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, dataType: normalizeDataType(event.target.value), operator: DisplayRuleOperatorEnum.Equal } : item))} className={inputClass}>{dataTypes.map(type => <option key={type}>{type}</option>)}</select></td>
                  <td className="p-3"><select value={row.operator} onChange={event => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, operator: normalizeOperator(event.target.value) } : item))} className={inputClass}>{operatorsForType(row.dataType).map(operator => <option key={operator} value={operator}>{operatorLabels[operator]}</option>)}</select></td>
                  <td className="p-3"><Cell value={String(row.value || "")} onChange={value => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, value } : item))} /></td>
                  <td className="p-3"><select disabled={row.dataType !== DisplayRuleDataTypeEnum.Number && row.dataType !== DisplayRuleDataTypeEnum.Datetime} value={row.secondOperator || ""} onChange={event => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, secondOperator: event.target.value ? normalizeOperator(event.target.value) : undefined, secondValue: event.target.value ? item.secondValue || "0" : undefined } : item))} className={inputClass}><option value="">None</option>{operatorsForType(row.dataType).filter(operator => operator !== DisplayRuleOperatorEnum.Equal).map(operator => <option key={operator} value={operator}>{operatorLabels[operator]}</option>)}</select></td>
                  <td className="p-3"><Cell value={row.secondValue} onChange={secondValue => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, secondValue } : item))} /></td>
                  <td className="p-3"><StringListEditor label="Destinations" values={row.destinations ?? []} onChange={destinations => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, destinations } : item))} list="property-destination-suggestions" addLabel="Add destination" /></td>
                  <td className="p-3"><DateCell value={row.validFrom} onChange={validFrom => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, validFrom } : item))} /></td>
                  <td className="p-3"><DateCell value={row.rushAt} onChange={rushAt => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, rushAt } : item))} /></td>
                  <td className="p-3"><DateCell value={row.validTo} onChange={validTo => setPropertyRows(rows => rows.map(item => item._localId === row._localId ? { ...item, validTo } : item))} /></td>
                  <td className="p-3"><DeleteButton onClick={() => setPropertyRows(rows => rows.filter(item => item._localId !== row._localId))} /></td>
                </tr>
              ))}
              {!propertyRows.length && <EmptyRow columns={11} loading={loading} />}
            </tbody>
          </table>
        </div>
      </MappingSection>

      <MappingSection
        title="Destinations To Exits"
        loading={loading}
        saving={savingExits}
        onAdd={() => setExitDraft(newExitRow())}
        onReload={fetchMappings}
        onImport={() => exitFileRef.current?.click()}
        onSave={saveExits}
        fileRef={exitFileRef}
        onFile={importExits}
      >
        <div className="overflow-x-auto">
          <table className="w-full table-fixed text-sm min-w-[760px]">
            <thead className="text-xs text-gray-500 uppercase bg-gray-50 dark:bg-gray-700/50">
              <tr><th className="p-3 text-left">Destination</th><th className="p-3 text-left">Exits</th><th className="p-3 text-left">Actions</th></tr>
            </thead>
            <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
              {exitRows.map(row => (
                <tr key={row._localId}>
                  <td className="p-3"><Cell value={row.destination} onChange={destination => setExitRows(rows => rows.map(item => item._localId === row._localId ? { ...item, destination } : item))} /></td>
                  <td className="p-3"><StringListEditor label="Exits" values={row.exits ?? []} onChange={exits => setExitRows(rows => rows.map(item => item._localId === row._localId ? { ...item, exits } : item))} list="graph-locations" addLabel="Add exit" /></td>
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

const StringListEditor = ({
  label,
  values,
  onChange,
  list,
  addLabel,
}: {
  label: string;
  values: string[];
  onChange: (values: string[]) => void;
  list?: string;
  addLabel: string;
}) => {
  const rows = values.length ? values : [""];
  const updateValue = (index: number, value: string) => onChange(rows.map((item, itemIndex) => itemIndex === index ? value : item));

  return <div className="space-y-2">
    {rows.map((value, index) => (
      <div key={index} className="flex gap-2">
        <input aria-label={`${label} ${index + 1}`} list={list} value={value} onChange={event => updateValue(index, event.target.value)} className={inputClass} />
        <button type="button" aria-label={`Remove ${label.toLowerCase()} ${index + 1}`} onClick={() => onChange(rows.filter((_, itemIndex) => itemIndex !== index))} className="rounded px-2 py-1 text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20">Remove</button>
      </div>
    ))}
    <button type="button" onClick={() => onChange([...values, ""])} className="rounded px-2 py-1 text-sm font-semibold text-blue-600 hover:bg-blue-50 dark:hover:bg-blue-900/20">+ {addLabel}</button>
  </div>;
};

const DateCell = ({ value, onChange }: { value?: string; onChange: (value?: string) => void }) => (
  <input type="datetime-local" value={toInputDateTime(value)} onChange={event => onChange(fromInputDateTime(event.target.value))} className={`${inputClass} dark:[color-scheme:dark]`} />
);

const DeleteButton = ({ onClick }: { onClick: () => void }) => (
  <button onClick={onClick} className="px-2 py-1 text-red-600 hover:bg-red-50 dark:hover:bg-red-900/20 rounded">Delete</button>
);

const EmptyRow = ({ columns, loading }: { columns: number; loading: boolean }) => (
  <tr><td colSpan={columns} className="px-4 py-8 text-center text-gray-500 italic">{loading ? "Loading mappings..." : "No mappings configured."}</td></tr>
);

const AddPropertyModal = ({ draft, onChange, onCancel, onSave }: {
  draft: PropertyRow;
  onChange: (draft: PropertyRow) => void;
  onCancel: () => void;
  onSave: () => void;
}) => {
  const update = (changes: Partial<PropertyRow>) => onChange({ ...draft, ...changes });
  const supportsRange = draft.dataType === DisplayRuleDataTypeEnum.Number || draft.dataType === DisplayRuleDataTypeEnum.Datetime;

  return <MappingModal title="Add Property Mapping" onCancel={onCancel} onSave={onSave}>
    <div className="grid gap-4 sm:grid-cols-2">
      <ModalField label="Field"><input autoFocus value={draft.fieldName || ""} onChange={event => update({ fieldName: event.target.value })} className={inputClass} /></ModalField>
      <ModalField label="Type"><select value={draft.dataType} onChange={event => update({ dataType: normalizeDataType(event.target.value), operator: DisplayRuleOperatorEnum.Equal, secondOperator: undefined, secondValue: undefined })} className={inputClass}>{dataTypes.map(type => <option key={type}>{type}</option>)}</select></ModalField>
      <ModalField label="Operator"><select value={draft.operator} onChange={event => update({ operator: normalizeOperator(event.target.value) })} className={inputClass}>{operatorsForType(draft.dataType).map(operator => <option key={operator} value={operator}>{operatorLabels[operator]}</option>)}</select></ModalField>
      <ModalField label="Value"><input value={draft.value || ""} onChange={event => update({ value: event.target.value })} className={inputClass} /></ModalField>
      <ModalField label="Second operator"><select disabled={!supportsRange} value={draft.secondOperator || ""} onChange={event => update({ secondOperator: event.target.value ? normalizeOperator(event.target.value) : undefined, secondValue: event.target.value ? draft.secondValue || "0" : undefined })} className={inputClass}><option value="">None</option>{operatorsForType(draft.dataType).filter(operator => operator !== DisplayRuleOperatorEnum.Equal).map(operator => <option key={operator} value={operator}>{operatorLabels[operator]}</option>)}</select></ModalField>
      <ModalField label="Second value"><input disabled={!draft.secondOperator} value={draft.secondValue || ""} onChange={event => update({ secondValue: event.target.value })} className={inputClass} /></ModalField>
      <div className="sm:col-span-2">
        <div className="mb-1 text-sm font-medium">Destinations</div>
        <StringListEditor label="Destinations" values={draft.destinations ?? []} onChange={destinations => update({ destinations })} list="property-destination-suggestions" addLabel="Add destination" />
        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">Use a logical destination configured below or a direct graph location/chute ID.</p>
      </div>
      <ModalField label="Valid from"><DateCell value={draft.validFrom} onChange={validFrom => update({ validFrom })} /></ModalField>
      <ModalField label="Rush at (optional)"><DateCell value={draft.rushAt} onChange={rushAt => update({ rushAt })} /></ModalField>
      <ModalField label="Valid to"><DateCell value={draft.validTo} onChange={validTo => update({ validTo })} /></ModalField>
    </div>
  </MappingModal>;
};

const AddExitModal = ({ draft, onChange, onCancel, onSave }: {
  draft: ExitRow;
  onChange: (draft: ExitRow) => void;
  onCancel: () => void;
  onSave: () => void;
}) => <MappingModal title="Add Destination Exit Mapping" onCancel={onCancel} onSave={onSave}>
  <div className="space-y-4">
    <ModalField label="Destination"><input autoFocus value={draft.destination || ""} onChange={event => onChange({ ...draft, destination: event.target.value })} className={inputClass} /></ModalField>
    <div>
      <div className="mb-1 text-sm font-medium">Exits</div>
      <StringListEditor label="Exits" values={draft.exits ?? []} onChange={exits => onChange({ ...draft, exits })} list="graph-locations" addLabel="Add exit" />
    </div>
  </div>
</MappingModal>;

const MappingModal = ({ title, onCancel, onSave, children }: { title: string; onCancel: () => void; onSave: () => void; children: React.ReactNode }) => (
  <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" role="dialog" aria-modal="true" aria-labelledby="add-mapping-title">
    <div className="max-h-[90vh] w-full max-w-3xl overflow-y-auto rounded-lg bg-white p-6 shadow-xl dark:bg-gray-800">
      <h3 id="add-mapping-title" className="text-lg font-bold">{title}</h3>
      <div className="mt-4">{children}</div>
      <div className="mt-6 flex justify-end gap-2">
        <button onClick={onCancel} className="rounded bg-gray-100 px-3 py-2 text-sm font-semibold dark:bg-gray-700">Cancel</button>
        <button onClick={onSave} className="rounded bg-blue-600 px-3 py-2 text-sm font-semibold text-white hover:bg-blue-700">Add</button>
      </div>
    </div>
  </div>
);

const ModalField = ({ label, className, children }: { label: string; className?: string; children: React.ReactNode }) => (
  <label className={`block text-sm font-medium ${className || ""}`}><span className="mb-1 block">{label}</span>{children}</label>
);

const MappingSection = ({
  title,
  loading,
  saving,
  onAdd,
  onReload,
  onImport,
  onExport,
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
  onExport?: () => void;
  onSave: () => void;
  fileRef: React.RefObject<HTMLInputElement>;
  onFile: (file: File) => void;
  children: React.ReactNode;
}) => (
  <section className="bg-white dark:bg-gray-800 p-6 rounded-lg shadow border border-gray-200 dark:border-gray-700">
    <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between mb-4 border-b border-gray-200 dark:border-gray-700 pb-4">
      <h2 className="text-lg font-bold">{title}</h2>
      <div className="flex flex-wrap gap-2">
        <button onClick={onAdd} className="px-3 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded font-semibold text-sm">Add Mapping</button>
        <button onClick={onReload} disabled={loading} className="px-3 py-2 bg-gray-100 dark:bg-gray-700 rounded font-semibold text-sm disabled:opacity-50">Reload</button>
        <button onClick={onImport} disabled={loading} className="px-3 py-2 bg-gray-100 dark:bg-gray-700 rounded font-semibold text-sm disabled:opacity-50">Import CSV</button>
        {onExport && <button onClick={onExport} disabled={loading} className="px-3 py-2 bg-gray-100 dark:bg-gray-700 rounded font-semibold text-sm disabled:opacity-50">Export CSV</button>}
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
