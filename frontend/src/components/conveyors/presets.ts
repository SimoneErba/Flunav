import type { ConveyorPreset } from '../../api-client';
import type { ConveyorFailureConfiguration } from '../../api/multiSimulation';

export const presetSummary = (preset: ConveyorPreset) =>
  `${preset.type} · ${preset.speed} m/s · ${preset.length} m · ${preset.minDistance} m spacing · capacity ${preset.capacity ?? 'unbounded'} · ${preset.properties?.failuresPerHour ?? 0} failures/h`;

/** Translate stored component defaults into the experiment's explicit failure model. */
export const conveyorFailureDefaults = (conveyors: { id?: string; properties?: Record<string, unknown> }[]): ConveyorFailureConfiguration[] =>
  conveyors.flatMap(conveyor => {
    const rate = conveyor.properties?.failuresPerHour;
    const repair = conveyor.properties?.repairDurationSeconds;
    return conveyor.id && typeof rate === 'number' && Number.isFinite(rate) && rate > 0
      ? [{ conveyorId: conveyor.id, failuresPerHour: rate, repairDurationSeconds: typeof repair === 'number' && Number.isInteger(repair) && repair > 0 ? repair : null }]
      : [];
  });
